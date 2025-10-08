package com.example.ecgmonitoringsystem.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EcgAnnotator {

    /**
     * Minimal QRS(P/T) estimator.
     * - Input: short units (1 mV = 1000)
     * - Output indices relative to the provided array
     */
    fun detect(x: ShortArray, fs: Int, flags: Int = 0): EcgFeatures {
        if (x.isEmpty() || fs <= 0) return EcgFeatures()

        // 1) Convert to float (mV)
        val f = FloatArray(x.size) { x[it] / 1000f }

        // 2) Center & scale by MAD-like
        val mean = f.average().toFloat()
        for (i in f.indices) f[i] -= mean
        val absMean = f.sumOf { abs(it).toDouble() }.toFloat() / f.size
        val scale = if (absMean > 1e-6f) (1f / (absMean * 1.253f)) else 1f
        for (i in f.indices) f[i] *= scale

        // 3) Derivative + rectify + smooth (simple envelope)
        val der = FloatArray(f.size)
        for (i in 1 until f.size) der[i] = f[i] - f[i - 1]
        for (i in der.indices) der[i] = abs(der[i])

        val w = max(3, fs / 40)         // ~25ms
        val env = movingAvg(der, w)

        // 4) Adaptive threshold + refractory to get R-peaks
        val thr = adaptiveThreshold(env, win = fs / 2) // ~0.5 s window
        val rIdx = pickPeaks(env, thr, refr = (0.22f * fs).toInt().coerceAtLeast(1))

        // For demo: P/T as offsets around R (very rough)
        val pList = ArrayList<Int>()
        val tList = ArrayList<Int>()
        for (r in rIdx) {
            val p = r - (0.16f * fs).toInt()
            val t = r + (0.20f * fs).toInt()
            if (p in f.indices) pList.add(p)
            if (t in f.indices) tList.add(t)
        }

        return EcgFeatures(
            qrsIdx = rIdx,
            pIdx = pList.toIntArray(),
            tIdx = tList.toIntArray()
        )
    }

    private fun movingAvg(src: FloatArray, w: Int): FloatArray {
        if (w <= 1) return src.copyOf()
        val dst = FloatArray(src.size)
        var sum = 0f
        var k = 0
        for (i in src.indices) {
            sum += src[i]
            k++
            if (k > w) {
                sum -= src[i - w]
                k = w
            }
            dst[i] = sum / k
        }
        return dst
    }

    private fun adaptiveThreshold(env: FloatArray, win: Int): FloatArray {
        val dst = FloatArray(env.size)
        val w = max(4, win)
        var run = 0f
        var k = 0
        for (i in env.indices) {
            run += env[i]
            k++
            if (k > w) {
                run -= env[i - w]
                k = w
            }
            dst[i] = (run / k) * 1.5f // 1.5× local mean
        }
        return dst
    }

    private fun pickPeaks(env: FloatArray, thr: FloatArray, refr: Int): IntArray {
        val out = ArrayList<Int>()
        var last = -1_000_000
        var i = 1
        while (i < env.size - 1) {
            if (env[i] > thr[i] && env[i] >= env[i - 1] && env[i] >= env[i + 1]) {
                if (i - last >= refr) {
                    out.add(i)
                    last = i
                }
            }
            i++
        }
        return out.toIntArray()
    }
}
