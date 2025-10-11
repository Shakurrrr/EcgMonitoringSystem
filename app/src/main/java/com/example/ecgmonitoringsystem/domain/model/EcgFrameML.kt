package com.example.ecgmonitoringsystem.domain.model

/** Multi-lead ECG frame */
data class EcgFrameML(
    val seq: Long,
    val fs: Int,
    val countsPerMv: Float,            // ADC counts per mV (per device/gain)
    val leads: Array<ShortArray>,      // leads[L][i], all equal length
    val hr: Int? = null,
    val sqi: Int? = null,
) {
    val n: Int get() = leads.firstOrNull()?.size ?: 0
    val leadCount: Int get() = leads.size
}
