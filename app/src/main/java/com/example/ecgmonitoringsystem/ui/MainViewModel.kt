package com.example.ecgmonitoringsystem.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitoringsystem.data.DemoEcgSource
import com.example.ecgmonitoringsystem.domain.model.EcgFrame
// If you later add BLE manager, keep it optional and off in demo
// import com.example.ecgmonitoringsystem.ble.EcgBleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // private val ble = EcgBleManager(app) // keep out of the way for demo
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
            viewModelScope.launch { demo.frame.collect { _frame.value = it } }
            viewModelScope.launch { demo.hr.collect { _hr.value = it } }
            viewModelScope.launch { demo.sqi.collect { _sqi.value = it } }
        } else {
            demo.stop()
        }
    }

    fun startStream(start: Boolean, notch50: Boolean) {
        if (_demoMode.value) {
            if (start) demo.start() else demo.stop()
            return
        }
        // BLE path would go here later
    }

    fun connect(device: BluetoothDevice) {
        _demoMode.value = false
        // ble.connect(device)...
    }
}
