package com.example.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.app.ble.BleManager
import com.example.app.ble.ConnectionState
import com.example.app.ble.EmgPacket
import com.example.app.ble.ImuPacket
import com.example.app.ble.SensorPacketListener
import com.example.app.data.local.RecordingStatus
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RecordingUiState(
    val isRecording: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

/** Application-owned writer: UI disposal cannot cancel the final database/file commit. */
class RecordingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ready: kotlinx.coroutines.Deferred<Unit>,
    private val repository: RecordingRepository,
) : SensorPacketListener {
    private data class Packet(val value: Any, val time: Long, val elapsed: Long)
    private class Active(val session: RecordingRepository.Session, val started: Long) {
        val packets = Channel<Packet>(256)
        val done = CompletableDeferred<Unit>()
        var accepting = true
        var reason: String? = null
        var end = System.currentTimeMillis()
        var elapsed = 0L
    }

    private val commands = Mutex()
    private val packetLock = Any()
    private var active: Active? = null
    @Volatile private var foreground = false
    private val _state = MutableStateFlow(RecordingUiState())
    val state = _state.asStateFlow()
    private val ble by lazy { BleManager.get(context) }

    fun foregroundChanged(value: Boolean) {
        foreground = value
        if (!value) stop("App left the foreground; recording saved on this device")
    }

    fun start(exercise: String, arm: String) {
        scope.launch {
            commands.withLock {
                if (_state.value.busy || active != null) return@withLock
                _state.value = RecordingUiState(busy = true)
                try {
                    ready.await()
                    check(foreground) { "Open the recording screen to start a session" }
                    check(ble.servicesReady.value) { "Connect your wearable before recording" }
                    check(SupabaseProvider.configured) { "Sign in before recording" }
                    val userId = AccountRepository().userId ?: error("Sign in before recording")
                    val started = SystemClock.elapsedRealtime()
                    val session = withContext(Dispatchers.IO) { repository.start(userId, exercise, arm) }
                    val recording = Active(session, started)
                    synchronized(packetLock) { active = recording }
                    ble.recordingListener = this@RecordingManager
                    _state.value = RecordingUiState(isRecording = true)
                    scope.launch(Dispatchers.IO) { write(recording) }
                    // Lifecycle may have changed while Room/file creation suspended.
                    if (!foreground || !ble.startStreaming()) {
                        closeInput("Sensor stream could not start; local recording retained")
                    }
                    scope.launch {
                        ble.connectionState.collect { connection ->
                            if (connection == ConnectionState.DISCONNECTED) {
                                closeInput("Wearable disconnected; recording saved on this device")
                            }
                        }
                    }.also { watcher -> recording.done.invokeOnCompletion { watcher.cancel() } }
                } catch (e: Exception) {
                    Log.e("Recording", "Could not start local recording", e)
                    if (synchronized(packetLock) { active != null }) {
                        closeInput(e.message ?: "Could not start sensor stream")
                    } else _state.value = RecordingUiState(error = e.message ?: "Could not start recording")
                }
            }
        }
    }

    fun stop(reason: String? = null) {
        scope.launch {
            commands.withLock {
                val recording = synchronized(packetLock) { active } ?: return@withLock
                _state.value = _state.value.copy(busy = true)
                closeInput(reason)
                stopSensor()
                recording.done.await()
            }
        }
    }

    override fun onEmg(packet: EmgPacket) { if (packet.samples.isNotEmpty()) offer(packet) }
    override fun onImu(packet: ImuPacket) { if (packet.samples.isNotEmpty()) offer(packet) }

    private fun offer(packet: Any) {
        synchronized(packetLock) {
            val recording = active ?: return
            if (!recording.accepting) return
            if (recording.packets.trySend(Packet(packet, System.currentTimeMillis(),
                    SystemClock.elapsedRealtime() - recording.started)).isFailure) {
                // Bound memory and stop explicitly if disk cannot keep up; never silently drop samples.
                closeInput("Local storage could not keep up with the sensor; recording interrupted")
            }
        }
    }

    private fun closeInput(reason: String?) {
        synchronized(packetLock) {
            val recording = active ?: return
            if (!recording.accepting) return
            recording.accepting = false
            recording.reason = reason
            recording.end = System.currentTimeMillis()
            recording.elapsed = SystemClock.elapsedRealtime() - recording.started
            recording.packets.close()
            scope.launch { stopSensor() }
        }
    }

    private fun stopSensor() {
        try { ble.stopStreaming() } catch (e: Exception) {
            Log.e("Recording", "Could not stop sensor stream", e)
        }
    }

    private suspend fun write(recording: Active) {
        var writeFailed = false
        try {
            for (packet in recording.packets) {
                when (val value = packet.value) {
                    is EmgPacket -> recording.session.writer.append(value, packet.time, packet.elapsed)
                    is ImuPacket -> recording.session.writer.append(value, packet.time, packet.elapsed)
                }
            }
        } catch (e: Exception) {
            writeFailed = true
            closeInput("Sensor data could not be written: ${e.message}")
            Log.e("Recording", "Local sensor write failed", e)
        }
        withContext(Dispatchers.Main) { stopSensor() }
        var finalState: RecordingUiState
        try {
            if (writeFailed) repository.recoverFailedWrite(recording.session)
            else repository.finish(recording.session, recording.end, recording.elapsed, recording.reason)
            val saved = repository.dao.get(recording.session.recording.id)
            finalState = if (saved?.recordingStatus == RecordingStatus.CORRUPTED) {
                RecordingUiState(error = saved.recordingError ?: "Local recording needs attention")
            } else RecordingUiState(info = "Recording saved on this device. Upload will run when connected.",
                error = saved?.recordingError ?: recording.reason)
        } catch (e: Exception) {
            Log.e("Recording", "Local recording will be recovered on next startup", e)
            finalState = RecordingUiState(error = "Could not finish saving. Recording will be recovered on next startup.")
        }
        withContext(Dispatchers.Main) {
            synchronized(packetLock) { if (active === recording) active = null }
            ble.recordingListener = null
            _state.value = finalState
            recording.done.complete(Unit)
        }
    }
}
