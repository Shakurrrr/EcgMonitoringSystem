package com.example.ecgmonitoringsystem.domain.model

data class EcgFeatures(
    val qrsIdx: IntArray = intArrayOf(),
    val pIdx: IntArray = intArrayOf(),
    val tIdx: IntArray = intArrayOf()
)
