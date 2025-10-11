package com.example.ecgmonitoringsystem.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun EcgCanvasMulti(
    leadsMv: Array<FloatArray>,   // [3][N]
    fs: Int,
    seconds: Int = 10,
    gainMmPerMv: Float = 10f,
    height: Dp = 300.dp,
    labels: Array<String> = arrayOf("Lead I","Lead II","Lead III")
) {
    val n = min(leadsMv.firstOrNull()?.size ?: 0, fs * seconds)
    if (n < 2) return

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val laneH = size.height / leadsMv.size
        val pxPerMm = size.width / (25f * seconds)
        val pxPerMv = gainMmPerMv * pxPerMm
        val stepX = size.width / (n - 1).toFloat()

        // Grid (full canvas)
        val major = Color(0xFFE0BFC0); val minor = Color(0xFFF3D9DA)
        var x = 0f; var k = 0
        while (x <= size.width + 1) {
            drawLine(if (k % 5 == 0) major else minor, Offset(x, 0f), Offset(x, size.height),
                if (k % 5 == 0) 1.6f else 0.8f); x += pxPerMm; k++
        }
        var y = 0f; k = 0
        while (y <= size.height + 1) {
            drawLine(if (k % 5 == 0) major else minor, Offset(0f, y), Offset(size.width, y),
                if (k % 5 == 0) 1.6f else 0.8f); y += pxPerMm; k++
        }

        // Each lead
        for (L in leadsMv.indices) {
            val samples = leadsMv[L]
            val start = samples.size - n
            val baseY = laneH * L
            val midY  = baseY + laneH / 2f

            var px = 0f
            var prev = samples[start]
            for (i in (start + 1) until samples.size) {
                val curr = samples[i]
                val y1 = midY - prev * pxPerMv
                val y2 = midY - curr * pxPerMv
                drawLine(Color.Black, Offset(px, y1), Offset(px + stepX, y2), 2f)
                px += stepX
                prev = curr
            }
            // (Optional) draw label on left
            // drawContext.canvas.nativeCanvas.drawText(labels[L], 8f, baseY + 18f, paint)
        }
    }
}
