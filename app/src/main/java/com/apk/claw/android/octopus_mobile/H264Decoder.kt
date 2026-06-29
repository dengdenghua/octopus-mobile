package com.apk.claw.android.octopus_mobile

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 解码器 —— 收母体推来的 Annex-B 帧,用 MediaCodec 解码并直接渲染到 [Surface].
 *
 * 用法:surface 就绪后 [start]，每收到一帧调 [feed]，界面销毁时 [stop]。
 * 首个关键帧里解析出 SPS/PPS 作为 csd 配置解码器,然后逐帧喂入。
 * 解码+渲染在独立线程,不阻塞 WebSocket 读循环。
 */
class H264Decoder(width: Int = 0, height: Int = 0) {
    private val tag = "H264Decoder"
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val queue = LinkedBlockingQueue<Pair<ByteArray, Boolean>>(120)
    private var worker: Thread? = null
    @Volatile private var running = false
    private var configured = false

    /** 初始默认分辨率，SPS 解析成功后更新为实际值 */
    @Volatile private var videoWidth: Int = if (width > 0) width else 1280
    @Volatile private var videoHeight: Int = if (height > 0) height else 720

    fun start(surface: Surface) {
        this.surface = surface
        running = true
        configured = false
        worker = Thread({ loop() }, "h264-decoder").apply { start() }
    }

    fun stop() {
        running = false
        val w = worker
        worker = null
        w?.interrupt()
        // 先等解码线程退出，再释放 codec —— 否则 worker 可能正卡在 dequeue/release
        // 里被并发 stop()/release()，MediaCodec 非线程安全会触发 native 崩溃。
        runCatching { w?.join(500) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configured = false
        queue.clear()
    }

    /** 收到一帧 Annex-B 数据（含起始码）。isKey=关键帧。 */
    fun feed(data: ByteArray, isKey: Boolean) {
        if (!running) return
        if (!configured && !isKey) return          // 配置前只认关键帧
        if (!queue.offer(data to isKey)) {          // 队列满（解码跟不上）→ 丢最旧
            queue.poll()
            queue.offer(data to isKey)
        }
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        while (running) {
            val item = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            val (data, isKey) = item

            if (!configured) {
                if (!isKey) continue
                val csd = extractSpsPps(data) ?: continue
                // 从 SPS 中解析实际分辨率，替代硬编码值
                val parsedRes = parseSpsResolution(csd.first)
                if (parsedRes != null) {
                    videoWidth = parsedRes.first
                    videoHeight = parsedRes.second
                }
                try {
                    val fmt = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                    fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                    val c = MediaCodec.createDecoderByType("video/avc")
                    c.configure(fmt, surface, null, 0)
                    c.start()
                    codec = c
                    configured = true
                    Log.i(tag, "decoder configured ${videoWidth}x${videoHeight} (sps=${csd.first.size}B pps=${csd.second.size}B)")
                } catch (e: Exception) {
                    Log.w(tag, "configure failed: ${e.message}")
                    continue
                }
            }

            val c = codec ?: continue
            try {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = c.getInputBuffer(inIdx)
                    ib?.clear(); ib?.put(data)
                    ptsUs += 1_000_000L / 15
                    val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    c.queueInputBuffer(inIdx, 0, data.size, ptsUs, flags)
                }
                var oIdx = c.dequeueOutputBuffer(info, 0)
                while (oIdx >= 0) {
                    c.releaseOutputBuffer(oIdx, true)   // render=true → 直接画到 Surface
                    oIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                Log.w(tag, "decode step failed: ${e.message}")
            }
        }
    }

    /**
     * 从 Annex-B 关键帧里抽出 SPS(NAL 7) 与 PPS(NAL 8)，各自带 4 字节起始码返回(给 csd 用)。
     */
    private fun extractSpsPps(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val nals = splitNals(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for ((start, end) in nals) {
            val type = data[start].toInt() and 0x1F
            val payload = data.copyOfRange(start, end)
            val withSc = ByteArray(4 + payload.size)
            withSc[0] = 0; withSc[1] = 0; withSc[2] = 0; withSc[3] = 1
            System.arraycopy(payload, 0, withSc, 4, payload.size)
            when (type) {
                7 -> sps = withSc
                8 -> pps = withSc
            }
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    /**
     * 从 SPS NAL 单元解析视频分辨率（width, height）。
     * SPS 格式参考 ITU-T H.264 7.3.2.1。
     * 返回 null 表示解析失败，调用方将使用默认分辨率。
     */
    private fun parseSpsResolution(spsWithSc: ByteArray): Pair<Int, Int>? {
        return try {
            // 跳过 4 字节起始码 + 1 字节 NAL 头
            val sps = spsWithSc.copyOfRange(5, spsWithSc.size)
            val reader = BitReader(sps)
            reader.readBits(8)  // profile_idc
            reader.readBits(8)  // constraint flags
            reader.readBits(8)  // level_idc
            reader.readUe()     // seq_parameter_set_id

            val profileIdc = sps[0].toInt() and 0xFF
            if (profileIdc in 100..110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                val chromaFormat = reader.readUe()
                if (chromaFormat == 3) reader.readBits(1)  // separate_colour_plane_flag
                reader.readUe()  // bit_depth_luma_minus8
                reader.readUe()  // bit_depth_chroma_minus8
                reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent == 1) {
                    val count = if (chromaFormat != 3) 8 else 12
                    for (i in 0 until count) {
                        if (reader.readBits(1) == 1) skipScalingList(reader, if (i < 6) 16 else 64)
                    }
                }
            }

            reader.readUe()  // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.readBits(1)  // delta_pic_order_always_zero_flag
                    reader.readSe()     // offset_for_non_ref_pic
                    reader.readSe()     // offset_for_top_to_bottom_field
                    val numRefFramesInPicOrderCntCycle = reader.readUe()
                    for (i in 0 until numRefFramesInPicOrderCntCycle) reader.readSe()
                }
            }
            reader.readUe()  // max_num_ref_frames
            reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) reader.readBits(1)  // mb_adaptive_frame_field_flag

            reader.readBits(1)  // direct_8x8_inference_flag
            val frameCroppingFlag = reader.readBits(1)
            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (frameCroppingFlag == 1) {
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
            val height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * 2
            // 合理性校验:SPS 异常或解析错位可能算出负数/畸大尺寸,此时返回 null,
            // 让上层退回默认尺寸 + csd-0 由 MediaCodec 自行推导,避免用坏值 configure。
            if (width !in 1..8192 || height !in 1..8192) {
                Log.w(tag, "SPS resolution out of range: ${width}x${height}, ignoring")
                null
            } else {
                Pair(width, height)
            }
        } catch (e: Exception) {
            Log.w(tag, "SPS resolution parse failed: ${e.message}")
            null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    /** 返回每个 NAL 的 [payloadStart, payloadEnd)（不含起始码）。支持 3/4 字节起始码。 */
    private fun splitNals(d: ByteArray): List<Pair<Int, Int>> {
        // 先找出所有起始码：scStart=起始码首字节(含 4 字节的前导 0)，payloadStart=NAL 头字节
        val scStart = ArrayList<Int>()
        val payloadStart = ArrayList<Int>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                scStart.add(if (i > 0 && d[i - 1].toInt() == 0) i - 1 else i)
                payloadStart.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val res = ArrayList<Pair<Int, Int>>()
        for (k in payloadStart.indices) {
            val s = payloadStart[k]
            val e = if (k + 1 < scStart.size) scStart[k + 1] else d.size
            if (e > s) res.add(s to e)
        }
        return res
    }
}
