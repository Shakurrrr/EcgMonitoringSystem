package com.example.ecgmonitoringsystem.data

import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class DemoEcgSource(
    private val scope: CoroutineScope,
    private val fs: Int = 360,
    private val nPerPacket: Int = 60
) {
    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame = _frame.asStateFlow()

    private val _hr = MutableStateFlow(72)
    val hr = _hr.asStateFlow()

    private val _sqi = MutableStateFlow(95)
    val sqi = _sqi.asStateFlow()

    private var job: Job? = null
    private var seq: Int = 0
    private var t: Int = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val dt = 1.0 / fs
            val baseHr = 72.0
            var rr = (60.0 / baseHr)
            var phase = 0.0
            val qrsAmp = 1800.0
            val noise = Random(42)

            while (isActive) {
                val samples = ShortArray(nPerPacket)
                repeat(nPerPacket) { i ->
                    // Baseline + small respiration drift
                    val drift = 150.0 * sin(2.0 * PI * 0.25 * (t * dt))
                    // P + T gentle waves
                    val p = 120.0 * sin(2.0 * PI * 5.0 * (t * dt))
                    val tw = 220.0 * sin(2.0 * PI * 2.0 * (t * dt))
                    var v = drift + p + tw

                    // QRS spike every rr seconds
                    phase += dt
                    if (phase >= rr) {
                        // narrow QRS pulse
                        v += qrsAmp
                        phase = 0.0
                        // jitter HR a bit
                        rr = (60.0 / baseHr) + noise.nextDouble(-0.05, 0.05)
                        _hr.value = (60.0 / rr).toInt()
                    }

                    // Add light noise
                    v += noise.nextDouble(-20.0, 20.0)

                    // Scale into int16
                    val s = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    samples[i] = s.toShort()
                    t++
                }
                _frame.value = EcgFrame(seq = seq++, fs = fs, n = nPerPacket, flags = 0, samples = samples)
                delay(((nPerPacket.toDouble() / fs) * 1000.0).toLong())
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
