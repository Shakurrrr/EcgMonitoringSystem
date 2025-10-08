package com.example.ecgmonitoringsystem.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitoringsystem.ble.BleUiState
import com.example.ecgmonitoringsystem.data.DemoEcgSource
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val demo = DemoEcgSource(viewModelScope)

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame: StateFlow<EcgFrame?> = _frame.asStateFlow()

    private val _bleState = MutableStateFlow<BleUiState>(BleUiState.Disconnected)
    val bleState: StateFlow<BleUiState> = _bleState

    // UI controls
    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode

    private val _gainMmPerMv = MutableStateFlow(20f)
    val gainMmPerMv: StateFlow<Float> = _gainMmPerMv

    private val _speedMmPerSec = MutableStateFlow(25f)
    val speedMmPerSec: StateFlow<Float> = _speedMmPerSec

    private val _countsPerMv = MutableStateFlow(200f)
    val countsPerMv: StateFlow<Float> = _countsPerMv

    // Recording
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _recordedFrames = MutableStateFlow<List<EcgFrame>>(emptyList())
    val recordedFrames: StateFlow<List<EcgFrame>> = _recordedFrames

    // ===== NEW: rolling 10-second window in mV (continuous strip) =====
    private val _windowSeconds = MutableStateFlow(10)
    val windowSeconds: StateFlow<Int> = _windowSeconds

    private val _traceMv = MutableStateFlow(FloatArray(0))
    val traceMv: StateFlow<FloatArray> = _traceMv

    private val _traceFs = MutableStateFlow(360)
    val traceFs: StateFlow<Int> = _traceFs

    init {
        viewModelScope.launch {
            demo.frame.collect { fr ->
                _frame.value = fr
                if (fr != null) {
                    // update trace Fs if changed
                    if (_traceFs.value != fr.fs) _traceFs.value = fr.fs

                    // append new samples in mV
                    val cps = _countsPerMv.value
                    val incoming = FloatArray(fr.n) { i -> fr.samples[i].toFloat() / cps }

                    val maxLen = _windowSeconds.value * fr.fs
                    val old = _traceMv.value
                    val merged = if (old.isEmpty()) incoming
                    else FloatArray((old.size + incoming.size).coerceAtMost(maxLen)).also { out ->
                        val takeOld = (out.size - incoming.size).coerceAtLeast(0)
                        // keep tail of old
                        if (takeOld > 0) {
                            System.arraycopy(old, old.size - takeOld, out, 0, takeOld)
                            System.arraycopy(incoming, 0, out, takeOld, incoming.size)
                        } else {
                            // incoming fully replaces window (when first filling)
                            System.arraycopy(incoming, incoming.size - out.size, out, 0, out.size)
                        }
                    }
                    _traceMv.value = merged

                    if (_isRecording.value) {
                        _recordedFrames.value = _recordedFrames.value + fr
                    }
                }
            }
        }
    }

    // BLE
    fun connect(address: String? = null) = viewModelScope.launch {
        if (_bleState.value is BleUiState.Connected || _bleState.value is BleUiState.Connecting) return@launch
        _bleState.value = BleUiState.Connecting
        try {
            _bleState.value = BleUiState.Connected(null)
        } catch (t: Throwable) {
            _bleState.value = BleUiState.Error(t.message)
        }
    }
    fun disconnect() = viewModelScope.launch { _bleState.value = BleUiState.Disconnected }

    // Demo
    fun startDemo(fs: Int = 360) { if (!_demoMode.value) { _demoMode.value = true; demo.start(fs) } }
    fun stopDemo() { if (_demoMode.value) { _demoMode.value = false; demo.stop() } }

    // Controls
    fun incGain(step: Float = 2f) { _gainMmPerMv.value = (_gainMmPerMv.value + step).coerceIn(5f, 60f) }
    fun decGain(step: Float = 2f) { _gainMmPerMv.value = (_gainMmPerMv.value - step).coerceIn(5f, 60f) }
    fun setWindowSeconds(s: Int) { _windowSeconds.value = s.coerceIn(5, 30) }
    fun setCountsPerMv(v: Float) { _countsPerMv.value = v.coerceAtLeast(1f) }

    // Recording
    fun startRecording() { _isRecording.value = true; _recordedFrames.value = emptyList() }
    fun stopRecording() { _isRecording.value = false }
    fun clearRecording() { _recordedFrames.value = emptyList() }

    override fun onCleared() { demo.stop(); super.onCleared() }
}
