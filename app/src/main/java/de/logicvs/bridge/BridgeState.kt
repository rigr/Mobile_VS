package de.logicvs.bridge

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

object BridgeState {
    data class MidiDeviceInfo(val id: Int, val name: String, val inputs: Int, val outputs: Int)
    val devices = AtomicReference<List<MidiDeviceInfo>>(emptyList())
    val status = AtomicReference("Server wird gestartet")
    val received = ConcurrentLinkedQueue<String>()
    @Volatile var selectedDeviceId: Int? = null
    @Volatile var selectedInputPort: Int = 0
    @Volatile var selectedOutputPort: Int = 0
}
