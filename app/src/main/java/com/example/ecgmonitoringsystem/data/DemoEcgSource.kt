package com.example.ecgmonitoringsystem.data

import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Physiologic ECG demo (P–QRS–T) generator.
 * IMPORTANT: Accept a plain CoroutineScope so it can run from viewModelScope.
 */
class DemoEcgSource(private val scope: CoroutineScope) {

    private var job: Job? = null
    private var running = false

    // For mV conversion when needed
    val countsPerMv: Float = 200f

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame: StateFlow<EcgFrame?> = _frame

    fun start(fs: Int = 360, bpm: Int = 72) {
        stop()
        running = true
        job = scope.launch(Dispatchers.Default) {
            val rr = 60.0 / bpm
            val dt = 1.0 / fs
            val frameLen = max(60, fs / 5)   // ~200 ms
            var t = 0.0
            var seq = 0L

            while (running) {
                val samples = ShortArray(frameLen)
                repeat(frameLen) { i ->
                    val vMv = pqrst((t % rr) / rr)
                    val noisy = vMv + 0.02 * (Random.nextDouble() - 0.5)
                    val counts = (noisy * countsPerMv)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    samples[i] = counts.toShort()
                    t += dt
                }

                _frame.value = EcgFrame(
                    seq = seq++,
                    fs = fs,
                    samples = samples,
                    hr = bpm,
                    sqi = 95,
                    flags = 0
                )

                val delayMs = (1000.0 * frameLen / fs).toLong()
                delay(max(1L, delayMs))
            }
        }
    }

    fun stop() { running = false; job?.cancel(); job = null }

    // --- simple PQRST model ---
    private fun pqrst(phase: Double): Double {
        val p = gaussian(phase, 0.18, 0.05, +0.15)
        val q = gaussian(phase, 0.44, 0.015, -0.07)
        val r = gaussian(phase, 0.46, 0.010, +1.10)
        val s = gaussian(phase, 0.49, 0.015, -0.15)
        val t = gaussian(phase, 0.72, 0.10, +0.35)
        return p + q + r + s + t
    }

    private fun gaussian(x: Double, c: Double, w: Double, a: Double): Double {
        val d = x - c
        return a * exp(-0.5 * (d / w) * (d / w))
    }
}
