@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ecgmonitoringsystem

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ecgmonitoringsystem.ble.BleUiState
import com.example.ecgmonitoringsystem.domain.model.EcgAnnotator
import com.example.ecgmonitoringsystem.export.EcgExport
import com.example.ecgmonitoringsystem.export.EcgExport.Meta
import com.example.ecgmonitoringsystem.export.EcgExport.Summary
import com.example.ecgmonitoringsystem.ui.MainViewModel
import com.example.ecgmonitoringsystem.ui.widgets.EcgCanvas
import com.example.ecgmonitoringsystem.ui.widgets.EcgMarkers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class MainActivity : ComponentActivity() {

    // ---------- SAF launchers ----------
    private lateinit var createCsv: ActivityResultLauncher<String>
    private lateinit var createPng: ActivityResultLauncher<String>
    private lateinit var createPdf: ActivityResultLauncher<String>

    // ---------- “current visible strip” snapshot for exporters ----------
    private var currentVm: MainViewModel? = null
    private var _samplesMv: FloatArray = floatArrayOf()
    private var _fs: Int = 360
    private var _gain: Float = 20f
    private var _countsPerMv: Float = 200f
    private var _markers: EcgMarkers? = null
    private var _metrics: EcgAnnotator.Metrics? = null

    private fun currentSamplesMv() = _samplesMv
    private fun currentFs() = _fs
    private fun currentGain() = _gain
    private fun currentCountsPerMv() = _countsPerMv
    private fun currentMarkers() = _markers
    private fun currentMetrics() = _metrics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- CSV ----------
        createCsv = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val sum = Summary(
                        hrBpm = currentMetrics()?.hrBpm,
                        prMs  = currentMetrics()?.prMs,
                        qrsMs = currentMetrics()?.qrsMs,
                        qtMs  = currentMetrics()?.qtMs,
                        classification = EcgExport.classify(currentMetrics()?.hrBpm)
                    )
                    EcgExport.saveCsv(
                        cr = contentResolver,
                        uri = uri,
                        windowMv = currentSamplesMv(),
                        fs = currentFs(),
                        countsPerMv = currentCountsPerMv(),
                        summary = sum,
                        markers = currentMarkers(),
                        meta = Meta()
                    )
                    shareUri("text/csv", uri)
                }
            }
        }

        // ---------- PNG (image only; metrics printed in PDF instead) ----------
        createPng = registerForActivityResult(
            ActivityResultContracts.CreateDocument("image/png")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    savePngInline(
                        uri = uri,
                        samplesMv = currentSamplesMv(),
                        fs = currentFs(),
                        seconds = 10,
                        gainMmPerMv = currentGain()
                    )
                    shareUri("image/png", uri)
                }
            }
        }

        // ---------- PDF (header includes metrics) ----------
        createPdf = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val sum = Summary(
                        hrBpm = currentMetrics()?.hrBpm,
                        prMs  = currentMetrics()?.prMs,
                        qrsMs = currentMetrics()?.qrsMs,
                        qtMs  = currentMetrics()?.qtMs,
                        classification = EcgExport.classify(currentMetrics()?.hrBpm)
                    )
                    EcgExport.savePdf(
                        cr = contentResolver,
                        uri = uri,
                        windowMv = currentSamplesMv(),
                        fs = currentFs(),
                        seconds = 10,
                        gainMmPerMv = currentGain(),
                        summary = sum,
                        meta = Meta()
                    )
                    shareUri("application/pdf", uri)
                }
            }
        }

        setContent {
            MaterialTheme {
                MainScreen(
                    onExportCsv = { createCsv.launch("ECG_${System.currentTimeMillis()}.csv") },
                    onExportPng = { createPng.launch("ECG_${System.currentTimeMillis()}.png") },
                    onExportPdf = { createPdf.launch("ECG_${System.currentTimeMillis()}.pdf") }
                )
            }
        }
    }

    // =======================================================================
    // UI
    // =======================================================================

    @Composable
    private fun MainScreen(
        onExportCsv: () -> Unit,
        onExportPng: () -> Unit,
        onExportPdf: () -> Unit
    ) {
        val vm: MainViewModel = viewModel()
        currentVm = vm

        // ---- VM state ----
        val bleState by vm.bleState.collectAsStateWithLifecycle()
        val demo by vm.demoMode.collectAsStateWithLifecycle()
        val rec by vm.isRecording.collectAsStateWithLifecycle()
        val gainMmPerMv by vm.gainMmPerMv.collectAsStateWithLifecycle()

        // Prefer rolling window from VM if available
        val traceMv by vm.traceMv.collectAsStateWithLifecycle()
        val traceFs by vm.traceFs.collectAsStateWithLifecycle()
        val seconds by vm.windowSeconds.collectAsStateWithLifecycle()

        // Fallback path from frames → ring buffer if VM doesn’t expose trace*
        val frame by vm.frame.collectAsStateWithLifecycle()
        val countsPerMv by vm.countsPerMv.collectAsStateWithLifecycle()
        val fsFallback = frame?.fs ?: 360
        val need = fsFallback * seconds
        var ring by remember(fsFallback, seconds) { mutableStateOf(FloatArray(0)) }

        LaunchedEffect(frame, countsPerMv, seconds, traceMv) {
            if (traceMv.isEmpty()) {
                frame?.let { fr ->
                    val incoming = FloatArray(fr.n) { i -> fr.samples[i].toFloat() / countsPerMv }
                    ring = if (ring.isEmpty()) {
                        if (incoming.size <= need) incoming
                        else incoming.copyOfRange(incoming.size - need, incoming.size)
                    } else {
                        val out = FloatArray(min(ring.size + incoming.size, need))
                        val keep = out.size - incoming.size
                        if (keep > 0) System.arraycopy(ring, ring.size - keep, out, 0, keep)
                        System.arraycopy(incoming, 0, out, keep, incoming.size)
                        out
                    }
                }
            }
        }

        // Source for canvas
        val rawSamplesForCanvas: FloatArray
        val fsForCanvas: Int
        if (traceMv.isNotEmpty()) {
            rawSamplesForCanvas = traceMv
            fsForCanvas = traceFs
        } else {
            rawSamplesForCanvas = ring
            fsForCanvas = fsFallback
        }

        // DC-centering so strip sits on midline
        val samplesMvCentered = remember(rawSamplesForCanvas) {
            if (rawSamplesForCanvas.isEmpty()) rawSamplesForCanvas
            else {
                var mean = 0f
                for (v in rawSamplesForCanvas) mean += v
                mean /= rawSamplesForCanvas.size.toFloat()
                FloatArray(rawSamplesForCanvas.size) { i -> rawSamplesForCanvas[i] - mean }
            }
        }

        // Compute markers + metrics for the visible window
        val (markers, metrics) = remember(samplesMvCentered, fsForCanvas) {
            EcgAnnotator.annotateAndMeasure(samplesMvCentered, fsForCanvas)
        }

        // Snapshot for exporters (Activity fields)
        _samplesMv = samplesMvCentered
        _fs = fsForCanvas
        _gain = gainMmPerMv
        _countsPerMv = countsPerMv
        _markers = markers
        _metrics = metrics

        var showMarkers by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("ECG Monitoring System") }) },
            bottomBar = {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ---- BLE + Demo + Recording controls ----
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val connectLabel = when (bleState) {
                            BleUiState.Disconnected -> "Connect BLE"
                            BleUiState.Connecting   -> "Connecting…"
                            is BleUiState.Connected -> "Disconnect BLE"
                            is BleUiState.Error     -> "Retry Connect"
                        }
                        Button(
                            onClick = {
                                when (bleState) {
                                    BleUiState.Disconnected, is BleUiState.Error -> vm.connect()
                                    is BleUiState.Connected -> vm.disconnect()
                                    BleUiState.Connecting -> Unit
                                }
                            },
                            enabled = bleState !is BleUiState.Connecting,
                            modifier = Modifier.weight(1f)
                        ) { Text(connectLabel) }

                        Button(
                            onClick = { if (demo) vm.stopDemo() else vm.startDemo(360) },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (demo) "Stop Demo" else "Start Demo") }

                        Button(
                            onClick = { if (rec) vm.stopRecording() else vm.startRecording() },
                            modifier = Modifier.weight(1f),
                            enabled = demo || (bleState is BleUiState.Connected)
                        ) { Text(if (rec) "Stop Rec" else "Start Rec") }
                    }

                    // ---- Gain + toggles ----
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { showMarkers = !showMarkers },
                            label = { Text("Markers: " + if (showMarkers) "ON" else "OFF") }
                        )
                        AssistChip(
                            onClick = { /* future: filters/leads */ },
                            label = { Text("Gain: ${gainMmPerMv.toInt()} mm/mV") }
                        )
                        OutlinedButton(onClick = { vm.decGain() }) { Text("− Amp") }
                        OutlinedButton(onClick = { vm.incGain() }) { Text("+ Amp") }
                    }

                    // ---- Export buttons ----
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportCsv, enabled = samplesMvCentered.isNotEmpty()) { Text("Export CSV") }
                        OutlinedButton(onClick = onExportPng, enabled = samplesMvCentered.isNotEmpty()) { Text("Export PNG") }
                        OutlinedButton(onClick = onExportPdf, enabled = samplesMvCentered.isNotEmpty()) { Text("Export PDF") }
                    }
                }
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ---- ECG strip (legacy canvas) ----
                Card(Modifier.fillMaxWidth()) {
                    EcgCanvas(
                        samplesMv = samplesMvCentered,
                        fs = fsForCanvas,
                        seconds = seconds,
                        gainMmPerMv = gainMmPerMv,
                        amplitudeBoost = 1f,
                        height = 280.dp,
                        showMarkers = showMarkers,
                        markers = markers
                    )
                }

                // ---- Optional metrics readout ----
                metrics?.let {
                    Text(
                        "HR: ${it.hrBpm ?: "--"} bpm   " +
                                "PR: ${it.prMs ?: "--"} ms   " +
                                "QRS: ${it.qrsMs ?: "--"} ms   " +
                                "QT: ${it.qtMs ?: "--"} ms",
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    // =======================================================================
    // Helpers (replace missing EcgExport.share / savePng)
    // =======================================================================

    private fun shareUri(mime: String, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private suspend fun savePngInline(
        uri: Uri,
        samplesMv: FloatArray,
        fs: Int,
        seconds: Int,
        gainMmPerMv: Float
    ) = withContext(Dispatchers.IO) {
        // Render a moderate-resolution strip to bitmap
        val width = 2400    // ~ 25 mm/s * seconds @ decent px/mm
        val height = 700
        val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)

        // BG
        c.drawColor(Color.rgb(252, 242, 246))

        // Grid
        val pxPerMm = width / (25f * seconds)
        val major = Paint().apply { color = Color.rgb(224, 191, 192); strokeWidth = 2f }
        val minor = Paint().apply { color = Color.rgb(243, 217, 218); strokeWidth = 1f }
        var x = 0f; var k = 0
        while (x <= width) {
            c.drawLine(x, 0f, x, height.toFloat(), if (k % 5 == 0) major else minor)
            x += pxPerMm; k++
        }
        var y = 0f; k = 0
        while (y <= height) {
            c.drawLine(0f, y, width.toFloat(), y, if (k % 5 == 0) major else minor)
            y += pxPerMm; k++
        }

        // Wave
        if (samplesMv.isNotEmpty()) {
            val ampPx = gainMmPerMv * pxPerMm
            val midY = height / 2f
            val n = min(samplesMv.size, fs * seconds)
            val start = samplesMv.size - n
            val stepX = width / (n - 1f)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; strokeWidth = 3f
            }
            var xx = 0f
            var prev = samplesMv[start]
            var yPrev = midY - prev * ampPx
            for (i in (start + 1) until samplesMv.size) {
                val curr = samplesMv[i]
                val yNow = midY - curr * ampPx
                c.drawLine(xx, yPrev, xx + stepX, yNow, line)
                xx += stepX
                yPrev = yNow
            }

            // Caption
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 36f }
            c.drawText("25 mm/s   ${gainMmPerMv.toInt()} mm/mV", 16f, (height - 20).toFloat(), txt)
        }

        // Write to URI
        contentResolver.openOutputStream(uri)?.use { out ->
            bm.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bm.recycle()
    }
}
