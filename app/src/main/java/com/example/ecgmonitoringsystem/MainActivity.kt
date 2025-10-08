package com.example.ecgmonitoringsystem

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.ecgmonitoringsystem.domain.model.EcgAnnotator
import com.example.ecgmonitoringsystem.ui.MainViewModel
import kotlin.math.min

// ---------- Drawing helpers ----------
data class EcgMarkers(val qrs: IntArray, val p: IntArray, val t: IntArray)

/** Lightweight ECG painter with paper grid (25 mm/s, 10 mm/mV). */
@Composable
private fun EcgCanvas(
    samplesMv: FloatArray,
    fs: Int,
    seconds: Int = 10,
    markers: EcgMarkers? = null
) {
    val n = min(samplesMv.size, fs * seconds)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        if (n < 2) return@Canvas
        val start = samplesMv.size - n

        // Paper grid: 1 mm small squares, 5 mm heavy lines
        val pxPerMm = size.width / (25f * seconds)
        val pxPerMv = 10f * pxPerMm
        val small = pxPerMm

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

        // Waveform
        val midY = size.height / 2f
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

        // Feature ticks (optional)
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
        markers?.let {
            drawTicks(it.qrs, Color.Red)             // QRS
            drawTicks(it.p, Color(0xFF1565C0))       // P
            drawTicks(it.t, Color(0xFF2E7D32))       // T
        }

        // Caption (Compose-safe)
        drawIntoCanvas { c ->
            val p = android.graphics.Paint().apply {
                color = android.graphics.Color.DKGRAY
                textSize = 28f
                isAntiAlias = true
            }
            c.nativeCanvas.drawText("25 mm/s   10 mm/mV", 8f, size.height - 10f, p)
        }
    }
}

// ---------- Activity ----------
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

                    // Start demo by default so users see motion immediately
                    LaunchedEffect(Unit) {
                        vm.enableDemo(true)
                        vm.startStream(true, true)
                    }

                    Column(
                        Modifier
                            // <— KEY fix: safe padding for status bar / notch
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .fillMaxSize()
                    ) {
                        // Top controls
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { quickConnect() },
                                enabled = !demo,
                                modifier = Modifier.weight(1f)
                            ) { Text("Connect BLE") }

                            Button(
                                onClick = { vm.startStream(true, true) },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (demo) "Start Demo" else "Start") }

                            Button(
                                onClick = { vm.startStream(false, true) },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (demo) "Stop Demo" else "Stop") }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AssistChip(
                                onClick = { vm.enableDemo(!demo) },
                                label = { Text(if (demo) "Demo Mode: ON" else "Demo Mode: OFF") }
                            )
                            OutlinedTextField(
                                value = refHrText,
                                onValueChange = { refHrText = it.filter(Char::isDigit) },
                                label = { Text("Ref HR") },
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                            val refHr = refHrText.toIntOrNull()
                            val delta = if (refHr != null && hr > 0) hr - refHr else null
                            Text(
                                "HR: $hr bpm   SQI: $sqi" +
                                        (delta?.let { "   ΔHR: $it bpm" } ?: ""),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Rolling buffers for plotting (mV)
                        var ringRaw by remember { mutableStateOf(FloatArray(0)) }
                        var ringFilt by remember { mutableStateOf(FloatArray(0)) }
                        var fsRemember by remember { mutableStateOf(360) }
                        var markers by remember { mutableStateOf<EcgMarkers?>(null) }

                        // Simple IIR HP/LP filter state
                        var hpfPrevX by remember { mutableStateOf(0f) }
                        var hpfPrevY by remember { mutableStateOf(0f) }
                        var lpfPrevY by remember { mutableStateOf(0f) }

                        // Ingest frames and update plot buffers
                        LaunchedEffect(frame) {
                            val fr = frame ?: return@LaunchedEffect
                            fsRemember = fr.fs

                            // Append raw (convert short -> mV)
                            val incoming = FloatArray(fr.n) { fr.samples[it] / 1000f }
                            val newRaw = FloatArray(ringRaw.size + incoming.size).also {
                                if (ringRaw.isNotEmpty())
                                    System.arraycopy(ringRaw, 0, it, 0, ringRaw.size)
                                System.arraycopy(incoming, 0, it, ringRaw.size, incoming.size)
                            }
                            val maxKeep = fr.fs * 10
                            ringRaw = if (newRaw.size <= maxKeep) newRaw
                            else newRaw.copyOfRange(newRaw.size - maxKeep, newRaw.size)

                            // Filter (≈0.5–40 Hz)
                            val dt = 1f / fr.fs
                            val hpfAlpha = (0.5f / (0.5f + dt))
                            val lpfAlpha = (dt / (dt + (1f / (2f * Math.PI.toFloat() * 40f))))

                            val target = FloatArray(ringFilt.size + incoming.size).also {
                                if (ringFilt.isNotEmpty())
                                    System.arraycopy(ringFilt, 0, it, 0, ringFilt.size)
                            }

                            val startIdx = ringRaw.size - incoming.size
                            for (i in startIdx until ringRaw.size) {
                                val x = ringRaw[i]
                                val yh = hpfAlpha * (hpfPrevY + x - hpfPrevX)
                                hpfPrevX = x
                                hpfPrevY = yh
                                val yl = lpfPrevY + lpfAlpha * (yh - lpfPrevY)
                                lpfPrevY = yl
                                target[(i - startIdx) + ringFilt.size] = yl
                            }
                            ringFilt = if (target.size <= maxKeep) target
                            else target.copyOfRange(target.size - maxKeep, target.size)

                            // Feature detection on last 3 s (short units)
                            val win = min(ringFilt.size, fsRemember * 3)
                            val startWin = ringFilt.size - win
                            val winShort = ShortArray(win) {
                                (ringFilt[startWin + it] * 1000f).toInt().toShort()
                            }
                            val feat = EcgAnnotator.detect(winShort, fsRemember)

                            // Thin + map to 10 s slice
                            val refr = (0.22f * fsRemember).toInt().coerceAtLeast(1)
                            fun thin(idx: IntArray): IntArray {
                                if (idx.isEmpty()) return idx
                                val out = ArrayList<Int>()
                                var last = -1_000_000
                                for (i in idx) if (i - last >= refr) { out.add(i); last = i }
                                return out.toIntArray()
                            }
                            val sliceN = min(ringFilt.size, fsRemember * 10)
                            val sliceStart = ringFilt.size - sliceN
                            fun mapToSlice(rel: IntArray): IntArray {
                                val out = ArrayList<Int>(rel.size)
                                for (i in rel) {
                                    val g = startWin + i
                                    val s = g - sliceStart
                                    if (s in 0 until sliceN) out.add(s)
                                }
                                return thin(out.toIntArray())
                            }
                            markers = EcgMarkers(
                                qrs = mapToSlice(feat.qrsIdx),
                                p   = mapToSlice(feat.pIdx),
                                t   = mapToSlice(feat.tIdx)
                            )
                        }

                        EcgCanvas(
                            samplesMv = ringFilt,
                            fs = fsRemember,
                            seconds = 10,
                            markers = markers
                        )

                        Text(
                            "SEQ: ${frame?.seq ?: -1}",
                            style = MaterialTheme.typography.bodySmall
                        )
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
