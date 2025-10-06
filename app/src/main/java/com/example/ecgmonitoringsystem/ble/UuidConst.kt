package com.example.ecgmonitoringsystem.ble

import java.util.UUID

object UuidConst {
    val SERVICE_ECG: UUID       = UUID.fromString("0000ECG0-0000-1000-8000-00805F9B34FB")
    val CHAR_ECG_STREAM: UUID   = UUID.fromString("0000ECG1-0000-1000-8000-00805F9B34FB")
    val CHAR_HR: UUID           = UUID.fromString("0000ECG2-0000-1000-8000-00805F9B34FB")
    val CHAR_CTRL: UUID         = UUID.fromString("0000ECG3-0000-1000-8000-00805F9B34FB")
}
