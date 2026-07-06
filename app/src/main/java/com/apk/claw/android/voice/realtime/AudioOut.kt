@file:Suppress("MagicNumber", "MaxLineLength", "LoopWithTooManyJumpStatements")

package com.apk.claw.android.voice.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.apk.claw.android.utils.XLog
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 实时语音播放 —— 24k / 单声道 / PCM16 MODE_STREAM,常驻线程消费队列写 AudioTrack。
 *
 * 采样率照 DashScope Qwen-Omni-Realtime 下行(PCM_24000HZ_MONO_16BIT,与采集 16k 不对称)。
 * 抖动缓冲:队列削峰,避免网络抖动导致断音。barge-in:[interrupt] 清软件队列 + pause/flush/play
 * 清掉硬件缓冲里已排的音频(否则用户打断后 AI 还会响一小段)。
 *
 * USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH:与 [AudioIn] 配对做硬件回声消除。
 * 用常驻线程 + LinkedBlockingQueue(不反复 release/重建 AudioTrack,避免卡顿竞态)。
 */
class AudioOut {

    private companion object {
        const val TAG = "VoiceAudioOut"
        const val SAMPLE_RATE = 24000
        const val BUFFER_MS = 100
        const val BYTES_PER_FRAME = 2
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val poison = ByteArray(0) // 毒丸:唤醒阻塞的 take() 以退出
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val jitter = BYTES_PER_FRAME * SAMPLE_RATE * BUFFER_MS / 1000 // = 4800
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, jitter)
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            XLog.e(TAG, "AudioTrack 初始化失败")
            t.release()
            running = false
            return
        }
        track = t
        t.play()
        worker = thread(name = "VoiceAudioOut") {
            while (running) {
                val item = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (!running || item === poison || item.isEmpty()) continue
                var p = 0
                while (p < item.size && running) {
                    val w = t.write(item, p, item.size - p)
                    if (w <= 0) break
                    p += w
                }
            }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    fun write(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    /** barge-in:丢弃未播的软件队列 + 硬件缓冲(pause→flush→play)。 */
    fun interrupt() {
        queue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        queue.offer(poison)
        worker = null
    }
}
