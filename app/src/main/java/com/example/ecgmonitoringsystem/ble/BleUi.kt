package com.example.ecgmonitoringsystem.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional BLE client facade you can wire later.
 * Critically: THIS FILE DOES NOT declare BleUiState (to avoid redeclaration).
 */
interface BleClient {
    val state: StateFlow<BleUiState>
    suspend fun connect(address: String? = null)
    suspend fun disconnect()
}

// Minimal fake client to unblock UI wiring; swap with real implementation later.
class FakeBleClient : BleClient {
    private val _state = MutableStateFlow<BleUiState>(BleUiState.Disconnected)
    override val state: StateFlow<BleUiState> = _state

    override suspend fun connect(address: String?) {
        _state.value = BleUiState.Connecting
        // Simulate success; inject a real device name when available
        _state.value = BleUiState.Connected(deviceName = null)
    }

    override suspend fun disconnect() {
        _state.value = BleUiState.Disconnected
    }
}
