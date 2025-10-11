package com.example.ecgmonitoringsystem.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitoringsystem.ble.BleUiState
import com.example.ecgmonitoringsystem.ble.CardioScopeBleManager
import com.example.ecgmonitoringsystem.data.DemoEcgSource
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Demo path ----
    private val demo = DemoEcgSource(viewModelScope)

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame: StateFlow<EcgFrame?> = _frame.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode = _demoMode.asStateFlow()

    private val _gainMmPerMv = MutableStateFlow(20f)
    val gainMmPerMv = _gainMmPerMv.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _windowSeconds = MutableStateFlow(10)
    val windowSeconds = _windowSeconds.asStateFlow()

    private val _countsPerMv = MutableStateFlow(200f) // update after calibration if needed
    val countsPerMv = _countsPerMv.asStateFlow()

    // ---- BLE state ----
    private val _bleState = MutableStateFlow<BleUiState>(BleUiState.Disconnected)
    val bleState = _bleState.asStateFlow()

    private var ble: CardioScopeBleManager? = null

    // Live 3-lead traces in mV
    private val _traceLead0 = MutableStateFlow(FloatArray(0))
    val traceLead0 = _traceLead0.asStateFlow()
    private val _traceLead1 = MutableStateFlow(FloatArray(0))
    val traceLead1 = _traceLead1.asStateFlow()
    private val _traceLead2 = MutableStateFlow(FloatArray(0))
    val traceLead2 = _traceLead2.asStateFlow()

    private val _leadIndex = MutableStateFlow(0)
    val leadIndex = _leadIndex.asStateFlow()

    fun selectLead(i: Int) { _leadIndex.value = i.coerceIn(0, 2) }

    // ---- Demo control ----
    fun startDemo(fs: Int = 360) {
        if (_demoMode.value) return
        _demoMode.value = true
        demo.start(fs)
        viewModelScope.launch {
            demo.frame.collect { fr -> _frame.value = fr }
        }
    }

    fun stopDemo() {
        if (!_demoMode.value) return
        _demoMode.value = false
        demo.stop()
        _frame.value = null
    }

    fun incGain(step: Float = 2f) { _gainMmPerMv.value = (_gainMmPerMv.value + step).coerceIn(5f, 40f) }
    fun decGain(step: Float = 2f) { _gainMmPerMv.value = (_gainMmPerMv.value - step).coerceIn(5f, 40f) }
    fun startRecording() { _isRecording.value = true }
    fun stopRecording() { _isRecording.value = false }

    // ---- BLE wiring from Activity ----
    fun attachBleManager(manager: CardioScopeBleManager) {
        if (ble != null) return
        ble = manager
        viewModelScope.launch {
            manager.framesFlow.collect { fr ->
                // Each frame has 3 leads (short counts). Convert to mV and append into 10s rings.
                val fs = fr.fs
                val need = fs * _windowSeconds.value
                fun toMv(src: ShortArray) = FloatArray(src.size) { i -> src[i] / fr.countsPerMv }
                updateRing(_traceLead0, toMv(fr.leads[0]), need)
                updateRing(_traceLead1, toMv(fr.leads[1]), need)
                updateRing(_traceLead2, toMv(fr.leads[2]), need)
                _frame.value = null // stop demo feed when live data arrives
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        val mgr = ble ?: return
        _bleState.value = BleUiState.Connecting
        mgr.connect(device)
            .useAutoConnect(false)
            .retry(3, 100)
            .timeout(15000)
            .done { _bleState.value = BleUiState.Connected(device.name ?: device.address) }
            .fail { _, status -> _bleState.value = BleUiState.Error("Connect failed ($status)") }
            .enqueue()
    }

    fun disconnect() {
        ble?.disconnect()?.enqueue()
        _bleState.value = BleUiState.Disconnected
    }

    private fun updateRing(dst: MutableStateFlow<FloatArray>, incoming: FloatArray, need: Int) {
        val prev = dst.value
        val out = if (prev.isEmpty()) {
            if (incoming.size <= need) incoming else incoming.copyOfRange(incoming.size - need, incoming.size)
        } else {
            val merged = FloatArray(min(prev.size + incoming.size, need))
            val keep = merged.size - incoming.size
            if (keep > 0) System.arraycopy(prev, prev.size - keep, merged, 0, keep)
            System.arraycopy(incoming, 0, merged, keep, incoming.size)
            merged
        }
        dst.value = out
    }

    override fun onCleared() {
        demo.stop()
        ble?.disconnect()?.enqueue()
        super.onCleared()
    }
}
