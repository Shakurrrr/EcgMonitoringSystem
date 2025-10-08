@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ecgmonitoringsystem

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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ecgmonitoringsystem.ble.BleUiState
import com.example.ecgmonitoringsystem.export.EcgExport
import com.example.ecgmonitoringsystem.ui.MainViewModel
import com.example.ecgmonitoringsystem.ui.widgets.EcgCanvas
import kotlinx.coroutines.launch
import kotlin.math.min

class MainActivity : ComponentActivity() {

    // Export launchers
    private lateinit var createCsv: ActivityResultLauncher<String>
    private lateinit var createPng: ActivityResultLauncher<String>
    private lateinit var createPdf: ActivityResultLauncher<String>

    // Helpers for exporters
    private var currentVm: MainViewModel? = null
    private var _samplesMv: FloatArray = floatArrayOf()
    private var _fs: Int = 360
    private var _gain: Float = 20f

    private fun currentSamplesMv() = _samplesMv
    private fun currentFs() = _fs
    private fun currentGain() = _gain

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SAF launchers
        createCsv = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            if (uri != null) {
                val vm = currentVm ?: return@registerForActivityResult
                lifecycleScope.launch {
                    EcgExport.saveCsv(contentResolver, uri, vm.recordedFrames.value)
                    EcgExport.share(this@MainActivity, uri, "text/csv")
                }
            }
        }
        createPng = registerForActivityResult(
            ActivityResultContracts.CreateDocument("image/png")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    EcgExport.savePng(
                        contentResolver, uri,
                        currentSamplesMv(), currentFs(), 10, currentGain()
                    )
                    EcgExport.share(this@MainActivity, uri, "image/png")
                }
            }
        }
        createPdf = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    EcgExport.savePdf(
                        contentResolver, uri,
                        currentSamplesMv(), currentFs(), 10, currentGain()
                    )
                    EcgExport.share(this@MainActivity, uri, "application/pdf")
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

        // Prefer VM’s rolling window if available
        val traceMv by vm.traceMv.collectAsStateWithLifecycle()          // FloatArray (may be empty if not implemented)
        val traceFs by vm.traceFs.collectAsStateWithLifecycle()          // Int
        val seconds by vm.windowSeconds.collectAsStateWithLifecycle()     // Int

        // Fallback inputs (frame path) if traceMv is empty
        val frame by vm.frame.collectAsStateWithLifecycle()
        val countsPerMv by vm.countsPerMv.collectAsStateWithLifecycle()

        // ---------- Build a rolling window if VM doesn't supply one ----------
        val fsFallback = frame?.fs ?: 360
        val need = fsFallback * seconds

        // Local ring buffer (only used if traceMv.isEmpty())
        var ring by remember(fsFallback, seconds) { mutableStateOf(FloatArray(0)) }

        LaunchedEffect(frame, countsPerMv, seconds) {
            if (traceMv.isEmpty()) { // only maintain ring when VM doesn't provide rolling buffer
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

        // Choose source: VM trace if present, else ring
        val rawSamplesForCanvas: FloatArray
        val fsForCanvas: Int
        if (traceMv.isNotEmpty()) {
            rawSamplesForCanvas = traceMv
            fsForCanvas = traceFs
        } else {
            rawSamplesForCanvas = ring
            fsForCanvas = fsFallback
        }

        // ---------- DC-centering so the waveform sits on the midline ----------
        val samplesMvCentered = remember(rawSamplesForCanvas) {
            if (rawSamplesForCanvas.isEmpty()) rawSamplesForCanvas
            else {
                var mean = 0f
                for (v in rawSamplesForCanvas) mean += v
                mean /= rawSamplesForCanvas.size.toFloat()
                FloatArray(rawSamplesForCanvas.size) { i -> rawSamplesForCanvas[i] - mean }
            }
        }

        // Expose current data to exporters
        _samplesMv = samplesMvCentered
        _fs = fsForCanvas
        _gain = gainMmPerMv

        var showMarkers by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("ECG Monitoring System") }) },
            bottomBar = {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // BLE + Demo + Recording controls
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

                    // Gain / toggles
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { showMarkers = !showMarkers },
                            label = { Text("Markers: " + if (showMarkers) "ON" else "OFF") }
                        )
                        AssistChip(
                            onClick = { /* optional change marker type */ },
                            label = { Text("Gain: ${gainMmPerMv.toInt()} mm/mV") }
                        )
                        OutlinedButton(onClick = { vm.decGain() }) { Text("− Amp") }
                        OutlinedButton(onClick = { vm.incGain() }) { Text("+ Amp") }
                    }

                    // Export buttons
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportCsv, enabled = rec.not()) { Text("Export CSV") }
                        OutlinedButton(onClick = onExportPng, enabled = samplesMvCentered.isNotEmpty()) { Text("Export PNG") }
                        OutlinedButton(onClick = onExportPdf, enabled = samplesMvCentered.isNotEmpty()) { Text("Export PDF") }
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ECG strip (uses your legacy canvas)
                Card(Modifier.fillMaxWidth()) {
                    EcgCanvas(
                        samplesMv = samplesMvCentered, // centered rolling window
                        fs = fsForCanvas,
                        seconds = seconds,
                        gainMmPerMv = gainMmPerMv,
                        amplitudeBoost = 1f,
                        height = 280.dp,
                        showMarkers = showMarkers,
                        markers = null
                    )
                }
            }
        }
    }
}
