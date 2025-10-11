package com.example.ecgmonitoringsystem.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.DataReceivedCallback
import no.nordicsemi.android.ble.data.Data
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import com.example.ecgmonitoringsystem.domain.model.EcgFrameML

class CardioScopeBleManager(
    context: Context,
    private val countsPerMv: Float,
    private val serviceUuid: UUID,
    private val notifyCharUuid: UUID
) : BleManager(context) {

    private var notifyChar: BluetoothGattCharacteristic? = null

    private val framesChannel = Channel<EcgFrameML>(Channel.BUFFERED)
    val framesFlow = framesChannel.receiveAsFlow()

    override fun getGattCallback(): BleManager.BleManagerGattCallback =
        object : BleManager.BleManagerGattCallback() {

            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val svc = gatt.getService(serviceUuid) ?: return false
                notifyChar = svc.getCharacteristic(notifyCharUuid)
                val props = notifyChar?.properties ?: 0
                return (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            }

            override fun initialize() {
                requestMtu(247).enqueue()
                requestConnectionPriority(
                    android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH
                ).enqueue()
                setNotificationCallback(notifyChar).with(onPacket)
                enableNotifications(notifyChar).enqueue()
            }

            override fun onServicesInvalidated() {
                notifyChar = null
            }
        }

    private val onPacket = DataReceivedCallback { _, data: Data ->
        val bytes = data.value ?: return@DataReceivedCallback
        parseAndOffer(bytes)
    }

    // Expected payload: [u32 seq][u16 fs][u16 n] + int16 L0[n], L1[n], L2[n] (LE)
    private fun parseAndOffer(bytes: ByteArray) {
        if (bytes.size < 8) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val seq = (bb.int.toLong() and 0xFFFFFFFFL)
        val fs  = (bb.short.toInt() and 0xFFFF)
        val n   = (bb.short.toInt() and 0xFFFF)
        if (fs <= 0 || n <= 0) return
        if (bb.remaining() < 3 * n * 2) return

        val l0 = ShortArray(n) { bb.short }
        val l1 = ShortArray(n) { bb.short }
        val l2 = ShortArray(n) { bb.short }

        framesChannel.trySend(
            EcgFrameML(seq = seq, fs = fs, countsPerMv = countsPerMv, leads = arrayOf(l0, l1, l2))
        )
    }
}
