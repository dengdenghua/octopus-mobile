@file:Suppress("MagicNumber")

package com.apk.claw.android.voice.realtime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 声音复刻录音器:录 [seconds] 秒 24k 单声道 pcm16(满足 enrollment ≥24kHz 单声道要求),
 * 打成 WAV 字节返回。用 AudioSource.MIC 取原始人声(不做 AEC),样本更干净。
 */
object VoiceEnrollRecorder {

    private const val TAG = "VoiceEnroll"
    private const val SAMPLE_RATE = 24000

    @SuppressLint("MissingPermission")
    suspend fun record(seconds: Int): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE),
            )
        } catch (e: IllegalArgumentException) {
            XLog.e(TAG, "AudioRecord 构造失败: ${e.message}")
            return@withContext null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@withContext null
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(SAMPLE_RATE)          // ~0.5s 一块
        val target = SAMPLE_RATE * 2 * seconds     // 目标字节数
        rec.startRecording()
        try {
            while (out.size() < target) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n) else if (n < 0) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val pcm = out.toByteArray()
        if (pcm.size < SAMPLE_RATE * 2 * 3) return@withContext null  // 不足 3s 视为失败
        wavHeader(pcm.size) + pcm
    }

    private fun wavHeader(pcmLen: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + pcmLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)                 // PCM
        bb.putShort(1)                 // mono
        bb.putInt(SAMPLE_RATE)
        bb.putInt(SAMPLE_RATE * 2)     // byte rate
        bb.putShort(2)                 // block align
        bb.putShort(16)                // bits
        bb.put("data".toByteArray())
        bb.putInt(pcmLen)
        return bb.array()
    }
}
