package com.example.ecgmonitoringsystem.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Feature markers for the current visible window (indexes are sample offsets). */
data class EcgMarkers(val qrs: IntArray, val p: IntArray, val t: IntArray)

/**
 * ECG paper + waveform.
 *
 * @param gainMmPerMv     paper gain (mm/mV). Clinical standard is 10 mm/mV.
 * @param amplitudeBoost  additional multiplicative boost (UI “±Amp” controls).
 */
@Composable
fun EcgCanvas(
    samplesMv: FloatArray,
    fs: Int,
    seconds: Int = 10,
    markers: EcgMarkers? = null,
    gainMmPerMv: Float = 10f,
    amplitudeBoost: Float = 1f,
    height: Dp = 240.dp,
    showMarkers: Boolean = true,
) {
    val n = min(samplesMv.size, fs * seconds)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (n < 2) return@Canvas

        val start = samplesMv.size - n

        // --- ECG paper (25 mm/s) ---
        val pxPerMm  = size.width / (25f * seconds)
        // apply the amplitude boost here
        val pxPerMv  = gainMmPerMv * pxPerMm * amplitudeBoost
        val small    = pxPerMm

        var xg = 0f; var k = 0
        while (xg <= size.width + 1f) {
            drawLine(
                color = if (k % 5 == 0) Color(0xFFE0BFC0) else Color(0xFFF3D9DA),
                start = Offset(xg, 0f),
                end = Offset(xg, size.height),
                strokeWidth = if (k % 5 == 0) 1.6f else 0.8f
            )
            xg += small; k++
        }
        var yg = 0f; k = 0
        while (yg <= size.height + 1f) {
            drawLine(
                color = if (k % 5 == 0) Color(0xFFE0BFC0) else Color(0xFFF3D9DA),
                start = Offset(0f, yg),
                end = Offset(size.width, yg),
                strokeWidth = if (k % 5 == 0) 1.6f else 0.8f
            )
            yg += small; k++
        }

        // --- Waveform ---
        val midY  = size.height / 2f
        val stepX = size.width / (n - 1).toFloat()

        var x = 0f
        var prev = samplesMv[start]
        for (i in (start + 1) until samplesMv.size) {
            val curr = samplesMv[i]
            val y1 = midY - prev * pxPerMv
            val y2 = midY - curr * pxPerMv
            drawLine(
                color = Color.Black,
                start = Offset(x, y1),
                end = Offset(x + stepX, y2),
                strokeWidth = 2f
            )
            x += stepX
            prev = curr
        }

        // --- Optional markers ---
        if (showMarkers && markers != null) {
            fun drawTicks(idxs: IntArray, color: Color) {
                val tickH = 6f * pxPerMm
                for (i in idxs) if (i in 0 until n) {
                    val xt = i * stepX
                    drawLine(
                        color = color,
                        start = Offset(xt, midY - tickH),
                        end = Offset(xt, midY + tickH),
                        strokeWidth = 2f
                    )
                }
            }
            drawTicks(markers.qrs, Color.Red)
            drawTicks(markers.p,   Color(0xFF1565C0))
            drawTicks(markers.t,   Color(0xFF2E7D32))
        }

        // --- Caption ---
        drawIntoCanvas { c ->
            val p = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                textSize = 28f
                isAntiAlias = true
            }
            c.nativeCanvas.drawText(
                "25 mm/s   ${gainMmPerMv.toInt()} mm/mV",
                8f,
                size.height - 10f,
                p
            )
        }
    }
}
