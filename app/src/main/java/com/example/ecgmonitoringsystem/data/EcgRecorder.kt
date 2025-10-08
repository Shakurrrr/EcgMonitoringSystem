package com.example.ecgmonitoringsystem.data

import com.example.ecgmonitoringsystem.domain.model.EcgFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

class EcgRecorder {
    private val _frames = MutableStateFlow<List<EcgFrame>>(emptyList())
    val frames: StateFlow<List<EcgFrame>> = _frames

    @Volatile private var active = false
    private val buffer = CopyOnWriteArrayList<EcgFrame>()

    fun start() { active = true; buffer.clear(); _frames.value = emptyList() }
    fun stop()  { active = false; _frames.value = buffer.toList() }

    /** Call this for every new frame you already pipe to the screen. */
    fun onFrame(frame: EcgFrame) {
        if (active) buffer += frame
    }
}
