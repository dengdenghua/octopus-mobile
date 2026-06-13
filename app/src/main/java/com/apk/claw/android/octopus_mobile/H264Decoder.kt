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
class H264Decoder(private val width: Int, private val height: Int) {
    private val tag = "H264Decoder"
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val queue = LinkedBlockingQueue<Pair<ByteArray, Boolean>>(120)
    private var worker: Thread? = null
    @Volatile private var running = false
    private var configured = false

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
                try {
                    val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                    fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                    val c = MediaCodec.createDecoderByType("video/avc")
                    c.configure(fmt, surface, null, 0)
                    c.start()
                    codec = c
                    configured = true
                    Log.i(tag, "decoder configured ${width}x$height (sps=${csd.first.size}B pps=${csd.second.size}B)")
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
