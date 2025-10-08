package com.example.ecgmonitoringsystem.data

import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight synthetic ECG generator that emits EcgFrame objects.
 * Samples are in *mV*×1000 shorts (1 mV -> 1000).
 */
class DemoEcgSource(
    private val scope: CoroutineScope
) {

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame = _frame.asStateFlow()

    private var running = false
    private var seq: Long = 0

    /**
     * Start emitting frames at ~180 ms cadence (like your previous demo).
     * fs defaults to 360 Hz.
     */
    fun start(fs: Int = 360) {
        if (running) return
        running = true

        scope.launch {
            val rnd = Random(System.currentTimeMillis())
            var t = 0.0
            var bpm = 72.0

            while (isActive && running) {

                // Slight HR variability every ~2 s
                if (seq % (fs * 2) == 0L) {
                    bpm = 70.0 + rnd.nextDouble(-3.0, 3.0)
                }

                val rr = 60.0 / bpm         // seconds per beat
                val n  = (0.18 * fs).toInt() // samples per frame (~180 ms)

                // Build one chunk in mV then convert to mV*1000 shorts
                val mv = DoubleArray(n)
                repeat(n) { i ->
                    val tt = t + i.toDouble() / fs

                    // Very simple synthetic beat: narrow QRS spikes + small p/t bumps
                    val phase = (tt % rr) / rr // 0..1 within the beat
                    val p  = 0.10; val pAmp  = 0.10; val pCtr  = 0.18
                    val qrs= 0.08; val rAmp  = 0.90; val rCtr  = 0.36
                    val tW = 0.18; val tAmp  = 0.22; val tCtr  = 0.62

                    fun g(x: Double, c: Double, w: Double) =
                        kotlin.math.exp(-((x - c) * (x - c)) / (2.0 * w * w))

                    var valMv =
                        pAmp * g(phase, pCtr, p) +
                                rAmp * g(phase, rCtr, qrs) +
                                tAmp * g(phase, tCtr, tW)

                    // tiny mains residue and baseline wander for realism
                    valMv += 0.01 * sin(2.0 * PI * 50.0 * tt)     // 50 Hz
                    valMv += 0.03 * sin(2.0 * PI * 0.30 * tt)     // baseline

                    // add very small white noise (~±3 µV)
                    valMv += rnd.nextDouble(from = -0.003, until = 0.003)

                    mv[i] = valMv
                }

                // Convert mV -> mV*1000 shorts (clamped)
                val shorts = ShortArray(n)
                for (i in 0 until n) {
                    val v = (mv[i] * 1000.0).toInt()
                    shorts[i] = when {
                        v > Short.MAX_VALUE -> Short.MAX_VALUE
                        v < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> v.toShort()
                    }
                }

                // Publish frame (NO 'n = ...' argument — n is derived)
                _frame.value = EcgFrame(
                    seq = seq,
                    fs = fs,
                    samples = shorts,
                    hr = bpm.toInt(),
                    sqi = 95,
                    flags = 0
                )
                seq++

                // advance time & cadence
                t += n.toDouble() / fs
                delay(180L)
            }
        }
    }

    fun stop() {
        running = false
    }
}
