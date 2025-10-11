@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ecgmonitoringsystem

import android.bluetooth.BluetoothDevice
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
import com.example.ecgmonitoringsystem.ble.CardioScopeBleManager
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
import java.util.UUID

// Nordic scanner
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

class MainActivity : ComponentActivity() {

    // ---------- SAFE UUIDS (replace the constants with your ESP32 UUIDs later) ----------
    private companion object {
        private const val DEFAULT_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e" // Nordic UART service (for testing)
        private const val DEFAULT_NOTIFY_UUID  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // Nordic UART RX notify
    }
    private fun safeUuid(s: String): UUID =
        try { UUID.fromString(s) } catch (_: Exception) {
            UUID.fromString(DEFAULT_SERVICE_UUID) // fallback so app never crashes
        }
    private val serviceUuid by lazy { safeUuid(DEFAULT_SERVICE_UUID) }
    private val notifyUuid  by lazy { safeUuid(DEFAULT_NOTIFY_UUID) }

    // ---------- Export launchers ----------
    private lateinit var createCsv: ActivityResultLauncher<String>
    private lateinit var createPng: ActivityResultLauncher<String>
    private lateinit var createPdf: ActivityResultLauncher<String>

    // ---------- Export snapshot ----------
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

    // ---------- BLE manager + Scanner UI ----------
    private lateinit var bleMgr: CardioScopeBleManager

    private var showScanDialog by mutableStateOf(false)
    private val scanResults = mutableStateListOf<ScanResult>()
    private val scanner by lazy { BluetoothLeScannerCompat.getScanner() }
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val i = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (i >= 0) scanResults[i] = result else scanResults.add(result)
        }
    }
    private fun startScan() {
        scanResults.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCb)
        showScanDialog = true
        window.decorView.postDelayed({ stopScan() }, 5000)
    }
    private fun stopScan() {
        try { scanner.stopScan(scanCb) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CSV
        createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val sum = Summary(
                    hrBpm = currentMetrics()?.hrBpm,
                    prMs  = currentMetrics()?.prMs,
                    qrsMs = currentMetrics()?.qrsMs,
                    qtMs  = currentMetrics()?.qtMs,
                    classification = EcgExport.classify(currentMetrics()?.hrBpm)
                )
                EcgExport.saveCsv(contentResolver, uri, currentSamplesMv(), currentFs(), currentCountsPerMv(), sum, currentMarkers(), Meta())
                shareUri("text/csv", uri)
            }
        }
        // PNG
        createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri != null) lifecycleScope.launch {
                savePngInline(uri, currentSamplesMv(), currentFs(), 10, currentGain())
                shareUri("image/png", uri)
            }
        }
        // PDF
        createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val sum = Summary(
                    hrBpm = currentMetrics()?.hrBpm,
                    prMs  = currentMetrics()?.prMs,
                    qrsMs = currentMetrics()?.qrsMs,
                    qtMs  = currentMetrics()?.qtMs,
                    classification = EcgExport.classify(currentMetrics()?.hrBpm)
                )
                EcgExport.savePdf(contentResolver, uri, currentSamplesMv(), currentFs(), 10, currentGain(), sum, Meta())
                shareUri("application/pdf", uri)
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

    // ========================= UI =========================
    @Composable
    private fun MainScreen(
        onExportCsv: () -> Unit,
        onExportPng: () -> Unit,
        onExportPdf: () -> Unit
    ) {
        val vm: MainViewModel = viewModel()

        // Build & attach BLE manager once (uses safe UUIDs)
        LaunchedEffect(Unit) {
            if (!::bleMgr.isInitialized) {
                bleMgr = CardioScopeBleManager(
                    context = applicationContext,
                    countsPerMv = vm.countsPerMv.value,
                    serviceUuid = serviceUuid,
                    notifyCharUuid = notifyUuid
                )
                vm.attachBleManager(bleMgr)
            }
        }

        val bleState by vm.bleState.collectAsStateWithLifecycle()
        val demo by vm.demoMode.collectAsStateWithLifecycle()
        val rec by vm.isRecording.collectAsStateWithLifecycle()
        val gainMmPerMv by vm.gainMmPerMv.collectAsStateWithLifecycle()
        val seconds by vm.windowSeconds.collectAsStateWithLifecycle()

        // Demo fallback ring from frames
        val frame by vm.frame.collectAsStateWithLifecycle()
        val fsFallback = frame?.fs ?: 360
        val need = fsFallback * seconds
        var ring by remember(fsFallback, seconds) { mutableStateOf(FloatArray(0)) }
        LaunchedEffect(frame, seconds) {
            frame?.let { fr ->
                val incoming = FloatArray(fr.n) { i -> fr.samples[i].toFloat() / 200f }
                ring = if (ring.isEmpty()) {
                    if (incoming.size <= need) incoming else incoming.copyOfRange(incoming.size - need, incoming.size)
                } else {
                    val out = FloatArray(min(ring.size + incoming.size, need))
                    val keep = out.size - incoming.size
                    if (keep > 0) System.arraycopy(ring, ring.size - keep, out, 0, keep)
                    System.arraycopy(incoming, 0, out, keep, incoming.size)
                    out
                }
            }
        }

        // Live 3-lead
        val leadIdx by vm.leadIndex.collectAsStateWithLifecycle()
        val lead0 by vm.traceLead0.collectAsStateWithLifecycle()
        val lead1 by vm.traceLead1.collectAsStateWithLifecycle()
        val lead2 by vm.traceLead2.collectAsStateWithLifecycle()
        val live = when (leadIdx) { 0 -> lead0; 1 -> lead1; else -> lead2 }

        // Choose source: live > demo
        val source = if (live.isNotEmpty()) live else ring
        val fs = if (live.isNotEmpty()) fsFallback /* or expose fs from manager */ else fsFallback

        // DC-center
        val samplesMv = remember(source) {
            if (source.isEmpty()) source else {
                var mean = 0f; for (v in source) mean += v
                mean /= source.size
                FloatArray(source.size) { i -> source[i] - mean }
            }
        }

        // Markers + metrics
        val (markers, metrics) = remember(samplesMv, fs) {
            EcgAnnotator.annotateAndMeasure(samplesMv, fs)
        }

        // Snapshot for export
        _samplesMv = samplesMv
        _fs = fs
        _gain = gainMmPerMv
        _countsPerMv = 200f
        _markers = markers
        _metrics = metrics

        var showMarkers by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopAppBar(title = { Text(getString(R.string.app_name)) }) },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    BleUiState.Disconnected, is BleUiState.Error -> startScan()
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
                            enabled = samplesMv.isNotEmpty()
                        ) { Text(if (rec) "Stop Rec" else "Start Rec") }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { showMarkers = !showMarkers }, label = { Text("Markers: " + if (showMarkers) "ON" else "OFF") })
                        AssistChip(onClick = { }, label = { Text("Gain: ${gainMmPerMv.toInt()} mm/mV") })
                        OutlinedButton(onClick = { vm.decGain() }) { Text("− Amp") }
                        OutlinedButton(onClick = { vm.incGain() }) { Text("+ Amp") }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { vm.selectLead(0) }, label = { Text("Lead I") })
                        AssistChip(onClick = { vm.selectLead(1) }, label = { Text("Lead II") })
                        AssistChip(onClick = { vm.selectLead(2) }, label = { Text("Lead III") })
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportCsv, enabled = samplesMv.isNotEmpty()) { Text("Export CSV") }
                        OutlinedButton(onClick = onExportPng, enabled = samplesMv.isNotEmpty()) { Text("Export PNG") }
                        OutlinedButton(onClick = onExportPdf, enabled = samplesMv.isNotEmpty()) { Text("Export PDF") }
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    EcgCanvas(
                        samplesMv = samplesMv,
                        fs = fs,
                        seconds = seconds,
                        gainMmPerMv = gainMmPerMv,
                        amplitudeBoost = 1f,
                        height = 280.dp,
                        showMarkers = showMarkers,
                        markers = markers
                    )
                }
                metrics?.let {
                    Text(
                        "HR: ${it.hrBpm ?: "--"} bpm   PR: ${it.prMs ?: "--"} ms   QRS: ${it.qrsMs ?: "--"} ms   QT: ${it.qtMs ?: "--"} ms",
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }

        // Device picker dialog
        if (showScanDialog) {
            AlertDialog(
                onDismissRequest = { stopScan(); showScanDialog = false },
                title = { Text("Select device") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        if (scanResults.isEmpty()) {
                            Text("Scanning… ensure the device is advertising")
                        } else {
                            scanResults.forEach { r ->
                                val dev = r.device
                                val name = dev.name ?: "(unknown)"
                                TextButton(
                                    onClick = {
                                        stopScan(); showScanDialog = false
                                        vm.connectToDevice(dev)
                                    }
                                ) { Text("$name  •  ${dev.address}") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { stopScan(); showScanDialog = false }) { Text("Close") } }
            )
        }
    }

    // ---------- helpers ----------
    private fun shareUri(mime: String, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private suspend fun savePngInline(
        uri: Uri, samplesMv: FloatArray, fs: Int, seconds: Int, gainMmPerMv: Float
    ) = withContext(Dispatchers.IO) {
        val width = 2400
        val height = 700
        val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)

        c.drawColor(Color.rgb(252, 242, 246))

        val pxPerMm = width / (25f * seconds)
        val major = Paint().apply { color = Color.rgb(224, 191, 192); strokeWidth = 2f }
        val minor = Paint().apply { color = Color.rgb(243, 217, 218); strokeWidth = 1f }
        var x = 0f; var k = 0
        while (x <= width) { c.drawLine(x, 0f, x, height.toFloat(), if (k % 5 == 0) major else minor); x += pxPerMm; k++ }
        var y = 0f; k = 0
        while (y <= height) { c.drawLine(0f, y, width.toFloat(), y, if (k % 5 == 0) major else minor); y += pxPerMm; k++ }

        if (samplesMv.isNotEmpty()) {
            val ampPx = gainMmPerMv * pxPerMm
            val midY = height / 2f
            val n = min(samplesMv.size, fs * seconds)
            val start = samplesMv.size - n
            val stepX = width / (n - 1f)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f }
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
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 36f }
            c.drawText("25 mm/s   ${gainMmPerMv.toInt()} mm/mV", 16f, (height - 20).toFloat(), txt)
        }

        contentResolver.openOutputStream(uri)?.use { out -> bm.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bm.recycle()
    }
}
