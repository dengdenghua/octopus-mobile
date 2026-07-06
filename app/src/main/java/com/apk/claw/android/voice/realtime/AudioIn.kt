@file:Suppress("MagicNumber", "MaxLineLength", "ReturnCount")

package com.apk.claw.android.voice.realtime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.apk.claw.android.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 实时语音采集 —— 16k / 单声道 / PCM16,常驻线程读裸 PCM 回调给上层。
 *
 * 关键:AudioSource 用 [MediaRecorder.AudioSource.VOICE_COMMUNICATION],系统自带
 * AEC(回声消除)/AGC/NS —— 否则扬声器播的 AI 声音会被麦克风回采,server_vad 误判无限打断。
 * 与 [AudioOut] 的 USAGE_VOICE_COMMUNICATION 两端配对,系统才按单一 voice 会话做硬件回声消除。
 *
 * 采样率照 DashScope Qwen-Omni-Realtime 上行要求(PCM_16000HZ_MONO_16BIT)。
 * 骨架参考 pipecat-client-android-transports(BSD-2)与 klomash(MIT)的常驻线程模型。
 */
class AudioIn(private val onAudioCaptured: (ByteArray) -> Unit) {

    private companion object {
        const val TAG = "VoiceAudioIn"
        const val SAMPLE_RATE = 16000
        const val BUFFER_MS = 100
        const val BYTES_PER_FRAME = 2 // 16-bit mono
    }

    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    /** 调用方(UI 层)必须已确保 RECORD_AUDIO 授权后再调用。 */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val frameBytes = BYTES_PER_FRAME * SAMPLE_RATE * BUFFER_MS / 1000 // = 3200
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, frameBytes)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (e: IllegalArgumentException) {
            XLog.e(TAG, "AudioRecord 构造失败: ${e.message}")
            running.set(false)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            XLog.e(TAG, "AudioRecord 初始化失败(权限或被占用)")
            rec.release()
            running.set(false)
            return
        }
        rec.startRecording()
        worker = thread(name = "VoiceAudioIn") {
            val buf = ByteArray(frameBytes)
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                when {
                    n > 0 -> onAudioCaptured(buf.copyOf(n)) // 只回实际读到的字节
                    n < 0 -> { XLog.e(TAG, "AudioRecord.read 错误码 $n"); break }
                    else -> { /* n==0:无数据,继续 */ }
                }
            }
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    /** 非阻塞:置标志,常驻线程在下一帧(≤100ms)退出并自行释放 AudioRecord。 */
    fun stop() {
        running.set(false)
        worker = null
    }
}
