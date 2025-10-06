package com.example.ecgmonitoringsystem

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ecgmonitoringsystem.ui.MainViewModel
import com.example.ecgmonitoringsystem.domain.model.EcgAnnotator
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.*

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePerms()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val frame by vm.frame.collectAsState()
                    val hr by vm.hr.collectAsState()
                    val sqi by vm.sqi.collectAsState()
                    val demo by vm.demoMode.collectAsState()
                    var refHrText by remember { mutableStateOf("") }

                    Column(
                        Modifier
                            .statusBarsPadding()
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { quickConnect() }, modifier = Modifier.weight(1f), enabled = !demo) {
                                Text("Connect BLE")
                            }
                            Button(onClick = { vm.startStream(true, true) }, modifier = Modifier.weight(1f)) {
                                Text(if (demo) "Start Demo" else "Start")
                            }
                            Button(onClick = { vm.startStream(false, true) }, modifier = Modifier.weight(1f)) {
                                Text(if (demo) "Stop Demo" else "Stop")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistChip(
                                onClick = { vm.enableDemo(!demo) },
                                label = { Text(if (demo) "Demo Mode: ON" else "Demo Mode: OFF") }
                            )
                            OutlinedTextField(
                                value = refHrText,
                                onValueChange = { refHrText = it.filter { c -> c.isDigit() } },
                                label = { Text("Ref HR") },
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                            val refHr = refHrText.toIntOrNull()
                            val delta = if (refHr != null && hr > 0) hr - refHr else null
                            Text(
                                "HR: $hr bpm   SQI: $sqi" + (if (delta != null) "   ΔHR: ${delta} bpm" else ""),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        var chartRef by remember { mutableStateOf<LineChart?>(null) }
                        AndroidView(
                            factory = { ctx ->
                                LineChart(ctx).apply {
                                    description = Description().apply { text = "" }
                                    setTouchEnabled(false)
                                    setPinchZoom(false)

                                    // === live-stream essentials ===
                                    setAutoScaleMinMaxEnabled(true)
                                    axisLeft.isEnabled = true
                                    axisRight.isEnabled = false
                                    xAxis.setDrawLabels(false)
                                    legend.isEnabled = true

                                    data = LineData().also { it.setDrawValues(false) }
                                    // 0: ECG line, 1: QRS, 2: P, 3: T
                                    chartRef = this
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )

                        LaunchedEffect(frame) {
                            val fr = frame ?: return@LaunchedEffect
                            val chart = chartRef ?: return@LaunchedEffect

                            fun ensureSet(idx: Int, label: String, circle: Boolean): LineDataSet {
                                val ds = chart.data.getDataSetByIndex(idx) as? LineDataSet
                                if (ds != null) return ds
                                return LineDataSet(mutableListOf(), label).apply {
                                    lineWidth = if (idx == 0) 1.4f else 0f
                                    setDrawCircles(circle)
                                    setDrawValues(false)
                                    circleRadius = 3.5f
                                    chart.data.addDataSet(this)
                                }
                            }
                            val setEcg = ensureSet(0, "ECG", false)
                            val setQRS = ensureSet(1, "QRS", true)
                            val setP   = ensureSet(2, "P", true)
                            val setT   = ensureSet(3, "T", true)

                            val baseX = (System.currentTimeMillis() / 1000f)
                            val entriesEcg = ArrayList<Entry>(fr.n)
                            fr.samples.forEachIndexed { i, s ->
                                val x = baseX + i / fr.fs.toFloat()
                                entriesEcg.add(Entry(x, s.toFloat()))
                            }
                            entriesEcg.forEach { chart.data.addEntry(it, 0) }

                            val feat = EcgAnnotator.detect(fr.samples, fr.fs)
                            fun idxToX(idx: Int) = baseX + idx / fr.fs.toFloat()
                            setQRS.values = feat.qrsIdx.map { Entry(idxToX(it), fr.samples[it].toFloat()) }
                            setP.values   = feat.pIdx.map   { Entry(idxToX(it), fr.samples[it].toFloat()) }
                            setT.values   = feat.tIdx.map   { Entry(idxToX(it), fr.samples[it].toFloat()) }

                            chart.data.notifyDataChanged()
                            chart.notifyDataSetChanged()
                            chart.setVisibleXRangeMaximum(10f)
                            chart.moveViewToX(baseX)
                            chart.invalidate() // <— force redraw
                        }
                    }
                }
            }
        }
    }

    private fun requestBlePerms() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }.toTypedArray()
        permissionsLauncher.launch(perms)
    }

    private fun quickConnect() {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        val device: android.bluetooth.BluetoothDevice? = adapter.bondedDevices?.firstOrNull {
            val n = it.name ?: return@firstOrNull false
            n.startsWith("ECG-AD8232") || n.startsWith("ECG")
        }
        device?.let { vm.connect(it) }
    }
}
