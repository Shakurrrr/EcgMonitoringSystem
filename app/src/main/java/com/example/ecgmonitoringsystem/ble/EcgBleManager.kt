package com.example.ecgmonitoringsystem.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Temporary BLE stub so the project builds & Demo Mode runs without Nordic BLE.
 * Swap back to the real manager when we resume hardware/BLE integration.
 */
class EcgBleManager(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val _ecgFrames = Channel<ByteArray>(Channel.BUFFERED)
    val ecgFramesFlow = _ecgFrames.receiveAsFlow()

    private val _hr = Channel<Pair<Int, Int>>(Channel.BUFFERED)
    val hrFlow = _hr.receiveAsFlow()

    fun connect(@Suppress("UNUSED_PARAMETER") device: BluetoothDevice): ConnectRequest = ConnectRequest()

    fun startStream(
        @Suppress("UNUSED_PARAMETER") start: Boolean,
        @Suppress("UNUSED_PARAMETER") notch50: Boolean
    ) { /* no-op */ }

    class ConnectRequest {
        fun retry(@Suppress("UNUSED_PARAMETER") times: Int,
                  @Suppress("UNUSED_PARAMETER") delayMs: Int) = this
        fun enqueue() { /* no-op */ }
    }
}
