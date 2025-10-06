package com.example.ecgmonitoringsystem.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitoringsystem.ble.EcgBleManager
import com.example.ecgmonitoringsystem.data.DemoEcgSource
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import com.example.ecgmonitoringsystem.domain.model.parseEcgPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    // BLE path
    private val mgr = EcgBleManager(app)

    // Demo path
    private val demo = DemoEcgSource(viewModelScope)

    private val _frame = MutableStateFlow<EcgFrame?>(null)
    val frame = _frame.asStateFlow()

    private val _hr = MutableStateFlow(0)
    val hr = _hr.asStateFlow()
    private val _sqi = MutableStateFlow(0)
    val sqi = _sqi.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode = _demoMode.asStateFlow()

    fun enableDemo(on: Boolean) {
        _demoMode.value = on
        if (on) {
            demo.start()
            // Bridge demo flows to UI state
            viewModelScope.launch { demo.frame.collect { _frame.value = it } }
            viewModelScope.launch { demo.hr.collect { _hr.value = it } }
            viewModelScope.launch { demo.sqi.collect { _sqi.value = it } }
        } else {
            demo.stop()
        }
    }

    // BLE connect & stream
    fun connect(device: BluetoothDevice) {
        _demoMode.value = false
        mgr.connect(device).retry(3, 100).enqueue()
        viewModelScope.launch {
            mgr.ecgFramesFlow.collect { _frame.value = parseEcgPacket(it) }
        }
        viewModelScope.launch {
            mgr.hrFlow.collect { (h, s) -> _hr.value = h; _sqi.value = s }
        }
    }

    fun startStream(start: Boolean, notch50: Boolean) {
        if (!_demoMode.value) mgr.startStream(start, notch50)
        else if (!start) demo.stop() else demo.start()
    }
}
