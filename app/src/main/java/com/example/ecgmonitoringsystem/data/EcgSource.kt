package com.example.ecgmonitoringsystem.data

import com.example.ecgmonitoringsystem.domain.model.EcgFrameML
import kotlinx.coroutines.flow.Flow

interface EcgSource {
    val frames: Flow<EcgFrameML>
    fun start()
    fun stop()
}
