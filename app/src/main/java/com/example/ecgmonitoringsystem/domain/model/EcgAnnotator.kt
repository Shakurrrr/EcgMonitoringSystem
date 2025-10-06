package com.example.ecgmonitoringsystem.domain.model

data class EcgFeatures(
    val qrsIdx: IntArray,
    val pIdx: IntArray,
    val tIdx: IntArray
)

/**
 * Ultra-light detector good enough for MVP/demo:
 * - QRS: absolute threshold + refractory.
 * - P/T: local extrema in windows before/after QRS.
 * Tune THR_QRS and windows once you see real signals.
 */
object EcgAnnotator {
    fun detect(samples: ShortArray, fs: Int): EcgFeatures {
        val n = samples.size
        val abs = IntArray(n) { kotlin.math.abs(samples[it].toInt()) }

        // --- QRS detection ---
        // dynamic threshold: median(abs) * factor
        val sorted = abs.copyOf().apply { sort() }
        val median = sorted[n / 2].coerceAtLeast(1)
        val THR_QRS = (median * 3.0).toInt()  // start aggressive for demo
        val refractory = (0.22 * fs).toInt()  // 220 ms

        val qrs = ArrayList<Int>()
        var last = -refractory
        for (i in 1 until n - 1) {
            if (abs[i] > THR_QRS && i - last > refractory && abs[i] >= abs[i - 1] && abs[i] >= abs[i + 1]) {
                qrs.add(i); last = i
            }
        }

        // --- P/T rough pick ---
        val pList = ArrayList<Int>()
        val tList = ArrayList<Int>()
        val preWin = (0.12 * fs).toInt()   // 120 ms before QRS
        val postWin = (0.20 * fs).toInt()  // 200 ms after QRS

        for (r in qrs) {
            val pStart = (r - preWin).coerceAtLeast(1)
            val pEnd   = (r - (preWin / 2)).coerceAtLeast(2)
            val tStart = (r + (postWin / 2)).coerceAtMost(n - 2)
            val tEnd   = (r + postWin).coerceAtMost(n - 2)

            // P: local max abs in [pStart, pEnd)
            var pIdx = pStart
            for (i in pStart until pEnd) if (abs[i] > abs[pIdx]) pIdx = i
            pList.add(pIdx)

            // T: local max abs in [tStart, tEnd)
            var tIdx = tStart
            for (i in tStart until tEnd) if (abs[i] > abs[tIdx]) tIdx = i
            tList.add(tIdx)
        }

        return EcgFeatures(
            qrsIdx = qrs.toIntArray(),
            pIdx = pList.toIntArray(),
            tIdx = tList.toIntArray()
        )
    }
}
