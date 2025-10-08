package com.example.ecgmonitoringsystem.ble

// Single source of truth for BLE UI state
sealed interface BleUiState {
    object Disconnected : BleUiState
    object Connecting   : BleUiState
    data class Connected(val deviceName: String? = null) : BleUiState
    data class Error(val message: String? = null) : BleUiState
}
