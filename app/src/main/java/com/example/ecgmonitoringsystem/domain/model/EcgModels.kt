package com.example.ecgmonitoringsystem.domain.model

data class EcgFrame(
    val seq: Long,                // <— Long, consistent everywhere
    val fs: Int,
    val n: Int,
    val samples: ShortArray,      // raw units (1 mV = 1000)
    val flags: Int = 0            // default provided so callers may omit
)

data class EcgFeatures(
    val qrsIdx: IntArray = intArrayOf(),
    val pIdx: IntArray = intArrayOf(),
    val tIdx: IntArray = intArrayOf()
)
