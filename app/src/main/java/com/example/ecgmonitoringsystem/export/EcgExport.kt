package com.example.ecgmonitoringsystem.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import com.example.ecgmonitoringsystem.ui.widgets.renderEcgBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object EcgExport {

    // ---------- CSV ----------
    suspend fun saveCsv(
        resolver: ContentResolver,
        dst: Uri,
        frames: List<EcgFrame>
    ) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(dst)?.bufferedWriter().use { out ->
            out ?: return@withContext
            // Keep the CSV generic: works with minimal EcgFrame (seq, fs, samples)
            out.appendLine("seq,fs,index,mV")
            frames.forEach { f ->
                f.samples.forEachIndexed { i, s ->
                    val mv = s.toFloat() / 1000f // your pipeline encodes mV*1000 in Short
                    out.appendLine("${f.seq},${f.fs},$i,$mv")
                }
            }
        }
    }

    // ---------- PNG (current view) ----------
    suspend fun savePng(
        resolver: ContentResolver,
        dst: Uri,
        samplesMv: FloatArray,
        fs: Int,
        seconds: Int,
        gainMmPerMv: Float
    ) = withContext(Dispatchers.Default) {
        val bmp = renderEcgBitmap(
            widthPx = 2400,
            heightPx = 800,
            samplesMv = samplesMv,
            fs = fs,
            seconds = seconds,
            gainMmPerMv = gainMmPerMv
        )
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(dst)?.use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
        }
    }

    // ---------- PDF (1 page) ----------
    suspend fun savePdf(
        resolver: ContentResolver,
        dst: Uri,
        samplesMv: FloatArray,
        fs: Int,
        seconds: Int,
        gainMmPerMv: Float
    ) = withContext(Dispatchers.Default) {
        val w = 2400; val h = 800
        val bmp = renderEcgBitmap(w, h, samplesMv, fs, seconds, gainMmPerMv)

        withContext(Dispatchers.IO) {
            val doc = PdfDocument()
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)

            resolver.openOutputStream(dst)?.use { os -> doc.writeTo(os) }
            doc.close()
        }
    }

    // ---------- Share ----------
    fun share(context: Context, uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "Share ECG"))
    }
}
