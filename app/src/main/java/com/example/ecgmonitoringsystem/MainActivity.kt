package com.example.ecgmonitoringsystem

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ecgmonitoringsystem.ui.MainViewModel
import com.example.ecgmonitoringsystem.ui.widgets.EcgCanvas
import com.example.ecgmonitoringsystem.ui.widgets.EcgMarkers
import kotlin.math.min

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

                    // VM state
                    val frame by vm.frame.collectAsState()
                    val hr by vm.hr.collectAsState()
                    val sqi by vm.sqi.collectAsState()
                    val demo by vm.demoMode.collectAsState()

                    // UI state
                    var showMarkers by remember { mutableStateOf(false) }
                    var gainMmPerMv by remember { mutableStateOf(20f) } // default tall
                    var amplitudeBoost by remember { mutableStateOf(1f) } // ±Amp controls

                    // rolling buffers for display (mV)
                    var ringFilt by remember { mutableStateOf(FloatArray(0)) }
                    var fsRemember by remember { mutableStateOf(360) }
                    var markers by remember { mutableStateOf<EcgMarkers?>(null) }

                    // start demo so there’s a trace
                    LaunchedEffect(Unit) {
                        vm.enableDemo(true)
                        vm.startStream(true, true)
                    }

                    Column(
                        Modifier
                            .padding(WindowInsets.systemBars.asPaddingValues())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxSize()
                    ) {
                        // Top row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { quickConnect() },
                                modifier = Modifier.weight(1f),
                                enabled = !demo
                            ) { Text("Connect BLE") }

                            Button(
                                onClick = { vm.startStream(true, true) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Start Demo") }

                            Button(
                                onClick = { vm.startStream(false, true) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Stop Demo") }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Controls row (includes amplitude ±)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AssistChip(
                                onClick = { vm.enableDemo(!demo) },
                                label = { Text(if (demo) "Demo Mode: ON" else "Demo Mode: OFF") }
                            )
                            AssistChip(
                                onClick = { showMarkers = !showMarkers },
                                label = { Text(if (showMarkers) "Markers: ON" else "Markers: OFF") }
                            )
                            AssistChip(
                                onClick = { /* no-op: info only */ },
                                label = { Text("Gain: ${gainMmPerMv.toInt()} mm/mV") }
                            )
                            OutlinedButton(
                                onClick = {
                                    amplitudeBoost = (amplitudeBoost / 1.25f).coerceAtLeast(0.25f)
                                }
                            ) { Text("– Amp") }
                            OutlinedButton(
                                onClick = {
                                    amplitudeBoost = (amplitudeBoost * 1.25f).coerceAtMost(4f)
                                }
                            ) { Text("+ Amp") }
                        }

                        Spacer(Modifier.height(8.dp))

                        // HR/SQI line
                        Text("HR: $hr bpm   SQI: $sqi")

                        Spacer(Modifier.height(8.dp))

                        // ---- Buffer update from VM frame ----
                        LaunchedEffect(frame) {
                            val fr = frame ?: return@LaunchedEffect
                            fsRemember = fr.fs

                            // Convert the VM's short[] (in µV or mV scale) to mV floats as before
                            val maxKeep = fsRemember * 10
                            val newBuf = FloatArray((ringFilt.size + fr.samples.size).coerceAtLeast(0))
                            if (ringFilt.isNotEmpty()) {
                                System.arraycopy(ringFilt, 0, newBuf, 0, ringFilt.size)
                            }
                            // Your pipeline: here I just map short -> mV (assuming 1000 units per mV)
                            for (i in fr.samples.indices) {
                                newBuf[ringFilt.size + i] = fr.samples[i] / 1000f
                            }
                            ringFilt = if (newBuf.size <= maxKeep) newBuf
                            else newBuf.copyOfRange(newBuf.size - maxKeep, newBuf.size)

                            // If you are already producing markers in your VM, assign them here:
                            // markers = EcgMarkers(qrsIdx, pIdx, tIdx)
                        }

                        // ---- ECG canvas ----
                        EcgCanvas(
                            samplesMv = ringFilt,
                            fs = fsRemember,
                            seconds = 10,
                            markers = markers,
                            gainMmPerMv = gainMmPerMv,
                            amplitudeBoost = amplitudeBoost,
                            height = 300.dp,
                            showMarkers = showMarkers
                        )

                        Text("SEQ: ${frame?.seq ?: -1}", style = MaterialTheme.typography.bodySmall)
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
        val device = adapter.bondedDevices?.firstOrNull {
            val n = it.name ?: return@firstOrNull false
            n.startsWith("ECG-AD8232") || n.startsWith("ECG")
        }
        device?.let { vm.connect(it) }
    }
}
