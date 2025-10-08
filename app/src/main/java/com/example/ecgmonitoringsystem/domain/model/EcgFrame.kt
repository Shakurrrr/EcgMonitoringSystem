package com.example.ecgmonitoringsystem.domain.model

/**
 * A single chunk ("frame") of ECG samples.
 *
 * Conventions used in this project:
 * - Time base is uniform at [fs] Hz (samples per second).
 * - Samples are stored as SHORT integers in "millivolts * 1000".
 *   i.e. 1.000 mV -> 1000 (short), -0.350 mV -> -350 (short).
 * - Use [toFloatMv] to get a FloatArray in mV for drawing/processing.
 *
 * @param seq    Monotonic sequence number for ordering/diagnostics.
 * @param fs     Sampling rate in Hz for this frame.
 * @param samples ECG samples in mV * 1000 (ShortArray).
 * @param hr     Optional heart-rate reported with this frame (bpm).
 * @param sqi    Optional signal quality index (0..100).
 * @param flags  Optional bit flags (see [Flags]).
 */
data class EcgFrame(
    val seq: Long,
    val fs: Int,
    val samples: ShortArray,
    val hr: Int? = null,
    val sqi: Int? = null,
    val flags: Int = 0
) {
    /** Number of samples in this frame. */
    val n: Int get() = samples.size

    /** Convert this frame's samples into a FloatArray in **mV**. */
    fun toFloatMv(): FloatArray =
        FloatArray(samples.size) { i -> samples[i] / 1000f }

    /**
     * Create a copy with samples provided in **mV**, converting to mV*1000 shorts.
     */
    fun copyWithSamplesMv(mv: FloatArray): EcgFrame =
        copy(samples = mvToShorts(mv))

    /**
     * Append another frame's samples (same fs) and return a new merged frame.
     * Intended for offline concatenation (e.g., before export).
     */
    fun concat(other: EcgFrame): EcgFrame {
        require(this.fs == other.fs) { "Sampling rates differ: ${this.fs} vs ${other.fs}" }
        val out = ShortArray(this.samples.size + other.samples.size)
        System.arraycopy(this.samples, 0, out, 0, this.samples.size)
        System.arraycopy(other.samples, 0, out, this.samples.size, other.samples.size)
        return copy(samples = out, seq = other.seq)
    }

    companion object {
        /** Utility: convert **mV** floats to mV*1000 shorts (clamped to Short range). */
        fun mvToShorts(mv: FloatArray): ShortArray =
            ShortArray(mv.size) { i ->
                val v = (mv[i] * 1000f).toInt()
                v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

        /** Build a frame from **mV** floats. */
        fun fromMv(
            seq: Long,
            fs: Int,
            mv: FloatArray,
            hr: Int? = null,
            sqi: Int? = null,
            flags: Int = 0
        ): EcgFrame = EcgFrame(seq, fs, mvToShorts(mv), hr, sqi, flags)
    }

    /**
     * Common flag bits for [flags].
     */
    object Flags {
        const val NONE        = 0
        const val DEMO        = 1 shl 0  // Frame produced by demo/simulator
        const val FILTERED    = 1 shl 1  // Samples have been filtered
        const val SATURATED   = 1 shl 2  // Hardware saturation detected
        const val ARTIFACT    = 1 shl 3  // Significant motion/noise
    }
}
