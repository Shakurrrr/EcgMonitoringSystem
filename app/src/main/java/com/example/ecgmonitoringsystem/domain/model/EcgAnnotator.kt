package com.example.ecgmonitoringsystem.domain.model

import com.example.ecgmonitoringsystem.ui.widgets.EcgMarkers
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EcgAnnotator {

    data class Metrics(
        val hrBpm: Int?,
        val prMs: Int?,   // onset P -> onset QRS
        val qrsMs: Int?,  // Q start -> S end
        val qtMs: Int?    // Q start -> T end (approx)
    )

    /**
     * Very simple detector for demo/exports. For clinical-grade, swap for Pan-Tompkins etc.
     * Input must be DC-centered mV of a single-lead rolling window (fs * seconds).
     */
    fun annotateAndMeasure(samplesMv: FloatArray, fs: Int): Pair<EcgMarkers?, Metrics> {
        if (samplesMv.size < fs * 2) return null to Metrics(null, null, null, null)

        val n = samplesMv.size
        val maxV = samplesMv.maxOrNull() ?: 0f
        val minV = samplesMv.minOrNull() ?: 0f
        val thr = 0.35f * (maxV - minV)              // adaptive amplitude threshold
        val refr = (0.200f * fs).toInt()             // 200 ms refractory

        // ---- R-peaks (very simple) ----
        val rIdx = ArrayList<Int>()
        var last = -refr
        for (i in 1 until n - 1) {
            val v = samplesMv[i]
            if (v > thr && v >= samplesMv[i - 1] && v >= samplesMv[i + 1]) {
                if (i - last >= refr) {
                    rIdx += i; last = i
                }
            }
        }

        val qIdx = ArrayList<Int>()
        val sIdx = ArrayList<Int>()
        val pIdx = ArrayList<Int>()
        val tIdx = ArrayList<Int>()

        // ---- Q,S,P,T around each R (coarse, good enough for demo/exports) ----
        for (r in rIdx) {
            // Q: min in [-60, -10] ms window relative to R
            val q0 = max(0, r - (0.060f * fs).toInt())
            val q1 = max(0, r - (0.010f * fs).toInt())
            var qMin = r; var qVal = Float.POSITIVE_INFINITY
            for (k in q0 until q1) if (samplesMv[k] < qVal) { qVal = samplesMv[k]; qMin = k }
            qIdx += qMin

            // S: min in [+10, +60] ms
            val s0 = min(n - 1, r + (0.010f * fs).toInt())
            val s1 = min(n - 1, r + (0.060f * fs).toInt())
            var sMin = r; var sVal = Float.POSITIVE_INFINITY
            for (k in s0..s1) if (samplesMv[k] < sVal) { sVal = samplesMv[k]; sMin = k }
            sIdx += sMin

            // P: max in [-220, -90] ms
            val p0 = max(0, r - (0.220f * fs).toInt())
            val p1 = max(0, r - (0.090f * fs).toInt())
            var pMax = p0; var pVal = Float.NEGATIVE_INFINITY
            for (k in p0..p1) if (samplesMv[k] > pVal) { pVal = samplesMv[k]; pMax = k }
            pIdx += pMax

            // T: max in [+180, +500] ms
            val t0 = min(n - 1, r + (0.180f * fs).toInt())
            val t1 = min(n - 1, r + (0.500f * fs).toInt())
            var tMax = t0; var tVal = Float.NEGATIVE_INFINITY
            for (k in t0..t1) if (samplesMv[k] > tVal) { tVal = samplesMv[k]; tMax = k }
            tIdx += tMax
        }

        // Metrics (averaged across visible beats)
        val rrList = rIdx.zipWithNext { a, b -> (b - a) }
        val hr = if (rrList.isNotEmpty()) (60f * fs / rrList.average()).toInt() else null

        val pr = if (pIdx.isNotEmpty() && qIdx.isNotEmpty()) {
            val pairs = min(pIdx.size, qIdx.size)
            val ms = (0 until pairs).map { ((qIdx[it] - pIdx[it]) * 1000f / fs).toInt() }
            ms.average().toInt()
        } else null

        val qrs = if (qIdx.isNotEmpty() && sIdx.isNotEmpty()) {
            val pairs = min(qIdx.size, sIdx.size)
            val ms = (0 until pairs).map { ((sIdx[it] - qIdx[it]) * 1000f / fs).toInt() }
            ms.average().toInt()
        } else null

        val qt = if (qIdx.isNotEmpty() && tIdx.isNotEmpty()) {
            val pairs = min(qIdx.size, tIdx.size)
            val ms = (0 until pairs).map { ((tIdx[it] - qIdx[it]) * 1000f / fs).toInt() }
            ms.average().toInt()
        } else null

        val markers = EcgMarkers(
            qrs = rIdx.toIntArray(),
            p   = pIdx.toIntArray(),
            t   = tIdx.toIntArray()
        )
        return markers to Metrics(hr, pr, qrs, qt)
    }
}
