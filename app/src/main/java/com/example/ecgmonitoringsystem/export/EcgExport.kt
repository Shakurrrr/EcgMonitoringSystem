package com.example.ecgmonitoringsystem.export

import android.content.ContentResolver
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.annotation.WorkerThread
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import com.example.ecgmonitoringsystem.ui.widgets.EcgMarkers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.min

object EcgExport {

    // ---------- Public DTOs -------------------------------------------------

    data class Summary(
        val hrBpm: Int?, val prMs: Int?, val qrsMs: Int?, val qtMs: Int?,
        val classification: String? = null
    )

    // Local-time ISO helper (with timezone offset)
    private fun isoNowLocal(): String {
        return try {
            val zdt = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
            zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
        } catch (_: Throwable) {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            df.timeZone = TimeZone.getDefault()
            df.format(Date())
        }
    }

    data class Meta(
        val startTimeIso: String = isoNowLocal(),
        val deviceName: String? = "Demo",
        val firmwareVersion: String? = null,
        val featureVersion: String? = "ecg-app 1.0.0",
        val participantId: String? = null,
        val participantIdentifier: String? = null,
        val insertedDateIso: String = isoNowLocal()
    )

    // ---------- JSON (Fitbit-like) -----------------------------------------

    @WorkerThread
    suspend fun saveJson(
        cr: ContentResolver,
        uri: Uri,
        frames: List<EcgFrame>?,      // optional; else use windowMv+fs
        windowMv: FloatArray?,
        fs: Int?,
        countsPerMv: Float,
        leadNumber: Int = 3,
        summary: Summary? = null,
        markers: EcgMarkers? = null,
        meta: Meta = Meta()
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()

        val (counts, mv, samplingHz) = if (!frames.isNullOrEmpty()) {
            val allCounts = flattenCounts(frames)
            val hz = frames.first().fs
            val mvArr = FloatArray(allCounts.size) { i -> allCounts[i] / countsPerMv }
            Triple(allCounts, mvArr, hz)
        } else {
            val hz = fs ?: 360
            val mvArr = windowMv ?: FloatArray(0)
            val countsArr = IntArray(mvArr.size) { i -> (mvArr[i] * countsPerMv).toInt() }
            Triple(countsArr, mvArr, hz)
        }

        payload.put("StartTime", meta.startTimeIso)
        payload.put("AverageHeartRate", summary?.hrBpm)
        payload.put("ResultClassification", summary?.classification)
        payload.put("SamplingFrequencyHz", samplingHz.toDouble())
        payload.put("ScalingFactorCountsPermV", countsPerMv.toDouble())
        payload.put("LeadNumber", leadNumber)
        payload.put("NumberOfWaveformSamples", counts.size)

        payload.put("WaveformSamplesCounts", JSONArray().apply { counts.forEach { put(it) } })
        payload.put("WaveformSamplesmV", JSONArray().apply { mv.forEach { put(it) } })

        payload.put("FeatureVersion", meta.featureVersion)
        payload.put("DeviceName", meta.deviceName)
        payload.put("FirmwareVersion", meta.firmwareVersion)
        payload.put("ParticipantID", meta.participantId)
        payload.put("ParticipantIdentifier", meta.participantIdentifier)
        payload.put("InsertedDate", meta.insertedDateIso)

        summary?.let {
            payload.put("Metrics", JSONObject().apply {
                put("HR_bpm", it.hrBpm)
                put("PR_ms", it.prMs)
                put("QRS_ms", it.qrsMs)
                put("QT_ms", it.qtMs)
            })
        }
        markers?.let {
            payload.put("Markers", JSONObject().apply {
                put("R", JSONArray().apply { it.qrs.forEach { x -> put(x) } })
                put("P", JSONArray().apply { it.p.forEach { x -> put(x) } })
                put("T", JSONArray().apply { it.t.forEach { x -> put(x) } })
            })
        }

        cr.openOutputStream(uri)?.bufferedWriter().use { out ->
            out?.write(payload.toString())
            out?.flush()
        }
    }

    // ---------- CSV (header + mV rows) -------------------------------------

    @WorkerThread
    suspend fun saveCsv(
        cr: ContentResolver,
        uri: Uri,
        windowMv: FloatArray,
        fs: Int,
        countsPerMv: Float,
        summary: Summary? = null,
        markers: EcgMarkers? = null,
        meta: Meta = Meta(),
    ) = withContext(Dispatchers.IO) {
        cr.openOutputStream(uri)?.bufferedWriter().use { out ->
            if (out == null) return@use

            // Basic metadata (each header in one cell; use quotes where commas may appear)
            out.appendLine("# ECG Export")
            out.appendLine("# StartTime=" + meta.startTimeIso)
            out.appendLine("# SamplingFrequencyHz=" + fs)
            out.appendLine("# ScalingFactorCountsPermV=" + countsPerMv)
            out.appendLine("# LeadNumber=3")
            out.appendLine("# AverageHeartRate=" + (summary?.hrBpm ?: "-"))
            out.appendLine("# ResultClassification=" + (summary?.classification ?: "-"))

            summary?.let {
                val line = "Metrics: HR=${it.hrBpm ?: "-"} bpm, PR=${it.prMs ?: "-"} ms, " +
                        "QRS=${it.qrsMs ?: "-"} ms, QT=${it.qtMs ?: "-"} ms"
                out.appendLine("\"# $line\"")  // quoted to keep commas inside one cell
            }

            // Markers as separate commented lines (no CSV commas)
            markers?.let {
                fun listToSpaceSeparated(ints: IntArray) =
                    ints.joinToString(separator = " ") { i -> i.toString() }
                out.appendLine("# Markers.R=" + listToSpaceSeparated(it.qrs))
                out.appendLine("# Markers.P=" + listToSpaceSeparated(it.p))
                out.appendLine("# Markers.T=" + listToSpaceSeparated(it.t))
            }

            // Column headers with time (ms) + amplitude (mV)
            out.appendLine("sample_index,time_ms,mV")

            // Data rows
            val dtMs = 1000.0 / fs
            for (i in windowMv.indices) {
                val tMs = i * dtMs
                out.append(i.toString()).append(',')
                    .append(String.format(Locale.US, "%.3f", tMs)).append(',')
                    .append(String.format(Locale.US, "%.6f", windowMv[i]))
                    .append('\n')
            }
            out.flush()
        }
    }


    // ---------- PDF (wrapped header, classification + avg HR) ---------------

    @WorkerThread
    suspend fun savePdf(
        cr: ContentResolver,
        uri: Uri,
        windowMv: FloatArray,
        fs: Int,
        seconds: Int,
        gainMmPerMv: Float,
        summary: Summary? = null,
        meta: Meta = Meta()
    ) = withContext(Dispatchers.IO) {
        val pageWidth = 1240
        val pageHeight = 1754
        val margin = 48

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        fun drawHeaderLine(text: String, y: Float, baseSize: Float = 32f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = baseSize }
            val avail = (pageWidth - margin * 2).toFloat()
            var ts = baseSize
            while (p.measureText(text) > avail && ts > 18f) { ts -= 1f; p.textSize = ts }
            c.drawText(text, margin.toFloat(), y, p)
        }

        // Title (local time)
        drawHeaderLine("ECG Export — ${meta.startTimeIso}", (margin + 32).toFloat())

        val hrStr  = "HR=${summary?.hrBpm ?: "--"} bpm"
        val prStr  = "PR=${summary?.prMs ?: "--"} ms"
        val qrsStr = "QRS=${summary?.qrsMs ?: "--"} ms"
        val qtStr  = "QT=${summary?.qtMs ?: "--"} ms"
        val gainStr = "Gain=${gainMmPerMv.toInt()} mm/mV"
        val speedStr = "Speed=25 mm/s"
        val clsStr = "Result=${summary?.classification ?: "-"}"

        drawHeaderLine("$hrStr   $prStr   $qrsStr   $qtStr", (margin + 80).toFloat())
        drawHeaderLine("$gainStr   $speedStr   $clsStr", (margin + 120).toFloat())

        val top = margin + 160
        val left = margin
        val right = pageWidth - margin
        val bottom = pageHeight - margin
        val w = right - left
        val h = min(420, bottom - top)

        val pxPerMm = w / (25f * seconds)
        val major = Paint().apply { color = Color.rgb(224,191,192); strokeWidth = 2f }
        val minor = Paint().apply { color = Color.rgb(243,217,218); strokeWidth = 1f }
        var x = 0f; var k = 0
        while (left + x <= right) {
            c.drawLine(left + x, top.toFloat(), left + x, (top + h).toFloat(), if (k % 5 == 0) major else minor)
            x += pxPerMm; k++
        }
        var y = 0f; k = 0
        while (top + y <= top + h) {
            c.drawLine(left.toFloat(), top + y, right.toFloat(), top + y, if (k % 5 == 0) major else minor)
            y += pxPerMm; k++
        }

        if (windowMv.isNotEmpty()) {
            val ampPx = gainMmPerMv * pxPerMm
            val midY = top + h / 2f
            val stepX = w / (windowMv.size - 1f)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f }
            var xx = left.toFloat()
            var prevY = midY - windowMv[0] * ampPx
            for (i in 1 until windowMv.size) {
                val yy = midY - windowMv[i] * ampPx
                c.drawLine(xx, prevY, xx + stepX, yy, line)
                xx += stepX
                prevY = yy
            }
        }

        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 32f }
        c.drawText("25 mm/s   ${gainMmPerMv.toInt()} mm/mV",
            left.toFloat(), (top + h + 40).toFloat(), footer)

        doc.finishPage(page)
        cr.openOutputStream(uri)?.use { out -> doc.writeTo(out) }
        doc.close()
    }

    // ---------- Utilities ---------------------------------------------------

    private fun flattenCounts(frames: List<EcgFrame>): IntArray {
        var total = 0
        frames.forEach { total += it.n }
        val out = IntArray(total)
        var pos = 0
        frames.forEach { fr ->
            for (i in 0 until fr.n) out[pos++] = fr.samples[i].toInt()
        }
        return out
    }

    fun classify(hr: Int?): String {
        if (hr == null) return "Inconclusive"
        return when {
            hr < 50 -> "Inconclusive: Low heart rate"
            hr > 120 -> "Inconclusive: High heart rate"
            else -> "Normal sinus (demo)"
        }
    }
}
