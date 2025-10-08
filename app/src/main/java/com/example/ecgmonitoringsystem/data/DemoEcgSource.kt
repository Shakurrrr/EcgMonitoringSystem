
package com.example.ecgmonitoringsystem.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import com.example.ecgmonitoringsystem.domain.model.EcgFrame

/**
 * Demo ECG that renders a realistic P–QRS–T morphology in mV.
 * - fs: 360 Hz
 * - 10 mm/mV, 25 mm/s target look (rendered in your canvas)
 */
class DemoEcgSource(private val scope: CoroutineScope) {

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame = _frame.asStateFlow()

    private val _hr = MutableStateFlow(72)
    val hr = _hr.asStateFlow()

    private val _sqi = MutableStateFlow(95)
    val sqi = _sqi.asStateFlow()

    private var running = false
    private var seq = 0L

    fun start() {
        if (running) return
        running = true
        scope.launch {
            val fs = 360 // samples/sec
            val rnd = Random(System.currentTimeMillis())
            var t = 0.0
            var bpm = 72.0

            // Small HR variability (breathing-like)
            var drift = 0.0
            val driftHz = 0.2   // slow drift of HR

            while (isActive && running) {
                // update heart rate a little every frame
                drift += 1.0 / fs * 180.0
                val wobble = 2.0 * sin(2.0 * PI * driftHz * t) // ±2 bpm wobble
                bpm = (72.0 + wobble).coerceIn(55.0, 100.0)
                _hr.value = bpm.toInt()

                val rr = 60.0 / bpm // seconds/beat

                // one outgoing frame ~180 ms
                val n = (0.18 * fs).toInt().coerceAtLeast(1)
                val shorts = ShortArray(n)

                repeat(n) { i ->
                    val tt = t + i / fs.toDouble()
                    val phase = ((tt % rr) / rr) // 0..1

                    // ---- morphology (mV) ----
                    // classic values (tweak if you like)
                    // centers are fractions of the RR
                    val pAmp = 0.15 ; val pCtr = 0.18 ; val pW = 0.040
                    val qAmp = -0.06; val qCtr = 0.34 ; val qW = 0.012
                    val rAmp = 1.10 ; val rCtr = 0.36 ; val rW = 0.010
                    val sAmp = -0.25; val sCtr = 0.39 ; val sW = 0.014
                    val tAmp = 0.30 ; val tCtr = 0.65 ; val tW = 0.090

                    fun g(x: Double, c: Double, w: Double): Double {
                        val d = (x - c) / w
                        return exp(-0.5 * d * d)
                    }

                    var mv =
                        pAmp * g(phase, pCtr, pW) +
                                qAmp * g(phase, qCtr, qW) +
                                rAmp * g(phase, rCtr, rW) +
                                sAmp * g(phase, sCtr, sW) +
                                tAmp * g(phase, tCtr, tW)

                    // ST is naturally flat here because nothing between S and T
                    // Add tiny baseline wander (resp) and mains flicker for realism
                    mv += 0.02 * sin(2.0 * PI * 0.30 * tt)    // baseline (0.3 Hz)
                    mv += 0.005 * sin(2.0 * PI * 50.0 * tt)   // mains residue (very small)
                    mv += rnd.nextDouble(-0.003, 0.003)       // white noise ~±3 µV

                    // to "short": 1 mV -> 1000 units (you already divide by 1000f)
                    shorts[i] = (mv * 1000.0).toInt().toShort()
                }

                _frame.value = EcgFrame(
                    seq = seq++,
                    fs = fs,
                    n = shorts.size,
                    samples = shorts,
                )

                delay(180L)
                t += n / fs.toDouble()
            }
        }
    }

    fun stop() { running = false }
}
