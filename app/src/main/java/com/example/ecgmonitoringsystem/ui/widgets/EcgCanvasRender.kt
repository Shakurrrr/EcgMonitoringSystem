package com.example.ecgmonitoringsystem.ui.widgets

import android.graphics.*
import kotlin.math.min

fun renderEcgBitmap(
    widthPx: Int,
    heightPx: Int,
    samplesMv: FloatArray,
    fs: Int,
    seconds: Int,
    gainMmPerMv: Float
): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)

    val n = min(samplesMv.size, fs * seconds)
    if (n < 2) return bmp

    val start = samplesMv.size - n
    val pxPerMm = widthPx / (25f * seconds)
    val pxPerMv = gainMmPerMv * pxPerMm

    val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224,191,192); strokeWidth = 2f }
    val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(243,217,218); strokeWidth = 1f }

    // grid
    run {
        var x = 0f; var k = 0
        while (x <= widthPx + 1) {
            c.drawLine(x, 0f, x, heightPx.toFloat(), if (k % 5 == 0) major else minor)
            x += pxPerMm; k++
        }
        var y = 0f; k = 0
        while (y <= heightPx + 1) {
            c.drawLine(0f, y, widthPx.toFloat(), y, if (k % 5 == 0) major else minor)
            y += pxPerMm; k++
        }
    }

    val midY = heightPx / 2f
    val stepX = widthPx / (n - 1).toFloat()
    val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 4f }

    var x = 0f
    var prev = samplesMv[start]
    for (i in (start + 1) until samplesMv.size) {
        val curr = samplesMv[i]
        val y1 = midY - prev * pxPerMv
        val y2 = midY - curr * pxPerMv
        c.drawLine(x, y1, x + stepX, y2, pen)
        x += stepX
        prev = curr
    }

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 36f }
    c.drawText("25 mm/s   ${gainMmPerMv.toInt()} mm/mV", 12f, heightPx - 16f, text)
    return bmp
}
