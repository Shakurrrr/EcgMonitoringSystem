package com.example.ecgmonitoringsystem.domain.model

data class EcgFrame(
    val seq: Int,
    val fs: Int,
    val n: Int,
    val flags: Int,
    val samples: ShortArray
)

fun parseEcgPacket(bytes: ByteArray): EcgFrame {
    fun u16(i: Int) = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8))
    var p = 0
    val seq = u16(p); p += 2
    val fs  = u16(p); p += 2
    val n   = u16(p); p += 2
    val flags = bytes[p].toInt() and 0xFF; p += 1
    val samples = ShortArray(n)
    for (i in 0 until n) {
        val lo = bytes[p++].toInt() and 0xFF
        val hi = bytes[p++].toInt()
        samples[i] = ((hi shl 8) or lo).toShort()
    }
    return EcgFrame(seq, fs, n, flags, samples)
}
