package com.apk.claw.android.octopus_mobile

/**
 * 位读取器 —— 用于解析 H.264 SPS/PPS 等 Exp-Golomb 编码的位流。
 *
 * 支持：
 * - 按位读取 [readBits]
 * - 无符号 Exp-Golomb 解码 [readUe]
 * - 有符号 Exp-Golomb 解码 [readSe]
 */
internal class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    fun readBits(n: Int): Int {
        var result = 0
        for (i in 0 until n) {
            val byteIdx = bitPos / 8
            if (byteIdx >= data.size) return result
            val bitIdx = 7 - (bitPos % 8)
            result = (result shl 1) or ((data[byteIdx].toInt() shr bitIdx) and 1)
            bitPos++
        }
        return result
    }

    /** 无符号 Exp-Golomb 解码：ue(v) */
    fun readUe(): Int {
        var leadingZeros = 0
        while (readBits(1) == 0 && leadingZeros < 32) {
            leadingZeros++
        }
        if (leadingZeros == 0) return 0
        return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
    }

    /** 有符号 Exp-Golomb 解码：se(v) */
    fun readSe(): Int {
        val code = readUe()
        return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
    }
}
