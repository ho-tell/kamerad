package at.tellioglu.kamerad.gopro

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PairedCamera(val address: String, val name: String)

sealed interface CameraState {
    /** No camera has been paired in the Kamerad app yet. */
    data object NotPaired : CameraState

    /** Bluetooth permission has not been granted yet (open the Kamerad app). */
    data object NoPermission : CameraState

    /** Paired, but nobody needs the camera right now, so it's not connected. */
    data object Standby : CameraState

    /** Trying to connect; the camera may be off or out of range. */
    data object Searching : CameraState

    /** The camera has answered (Bluetooth link is up) and is starting up; not ready for commands yet. */
    data object Starting : CameraState

    /**
     * Switched off from Kamerad; not connecting, as that would wake the camera up again.
     * @param canWake the camera wakes up when Kamerad connects; if false (e.g. HERO8) it must be
     *   switched on with its own button
     */
    data class Off(val canWake: Boolean) : CameraState

    data class Connected(
        val recording: Boolean = false,
        /** [SystemClock.elapsedRealtime] when the current recording started. */
        val recordingSince: Long? = null,
        val batteryPercent: Int? = null,
        val busy: Boolean = false,
    ) : CameraState
}

/**
 * Owns the BLE connection to the paired GoPro.
 *
 * The camera is only connected (and kept awake) while someone holds a lease via [acquire]:
 * a visible data field, the open app, or a pending button press. After the last lease is
 * released the connection lingers for [LINGER] so page switches don't reconnect.
 */
object GoProController {
    private const val TAG = "Kamerad"
    private const val PREFS = "gopro"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_SWITCHED_OFF = "switched_off"
    private const val KEY_MODEL = "model"
    private const val KEY_EMPTY_PERCENT = "empty_percent"
    private const val KEY_EMPTY_AT = "empty_at"

    /** A camera that vanishes while its battery was at or below this level has probably run out of battery. */
    private const val EMPTY_BATTERY_THRESHOLD = 10
    private val LINGER = 2.minutes
    private val KEEP_ALIVE_INTERVAL = 3.seconds
    private val READY_TIMEOUT = 20.seconds
    private val DIRECT_CONNECT_TIMEOUT = 8.seconds
    private val BACKGROUND_CONNECT_WINDOW = 30.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val _pairedCamera = MutableStateFlow<PairedCamera?>(null)
    val pairedCamera: StateFlow<PairedCamera?> = _pairedCamera.asStateFlow()

    /** Model name reported by the paired camera, e.g. "HERO8 Black" (remembered, as a sleeping camera can't be asked). */
    /**
     * The camera vanished from Bluetooth (not switched off by Kamerad) while its battery was low: it has
     * probably run out of battery. The camera can't tell this, so it is a guess from the last battery level.
     */
    data class ProbablyEmpty(val percent: Int, val atMillis: Long)

    private val _probablyEmpty = MutableStateFlow<ProbablyEmpty?>(null)
    val probablyEmpty: StateFlow<ProbablyEmpty?> = _probablyEmpty.asStateFlow()

    @Volatile
    private var lastBattery: Int? = null

    @Volatile
    private var sleepRequestedAt = 0L

    @Volatile
    private var sessionReady = false

    private val _cameraModel = MutableStateFlow<String?>(null)
    val cameraModel: StateFlow<String?> = _cameraModel.asStateFlow()

    private val _state = MutableStateFlow<CameraState>(CameraState.NotPaired)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** User-facing notices (e.g. for in-ride alerts). */
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    /** A short [title] carries the message; [detail] is an optional short second line. */
    data class Notice(val title: String, val detail: String? = null, val isError: Boolean)

    /** Switched off from Kamerad: don't connect until switched on again. */
    private val switchedOff = MutableStateFlow(false)

    private val users = MutableStateFlow(0)
    private val commandResponses = MutableSharedFlow<TlvResponse>(extraBufferCapacity = 8)
    private val commandMutex = Mutex()
    private val actionRunning = AtomicBoolean(false)

    @Volatile
    private var currentBle: GoProBle? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _pairedCamera.value = prefs.getString(KEY_ADDRESS, null)?.let { address ->
            PairedCamera(address, prefs.getString(KEY_NAME, null) ?: address)
        }
        switchedOff.value = prefs.getBoolean(KEY_SWITCHED_OFF, false)
        _cameraModel.value = prefs.getString(KEY_MODEL, null)
        if (prefs.contains(KEY_EMPTY_PERCENT)) {
            _probablyEmpty.value = ProbablyEmpty(prefs.getInt(KEY_EMPTY_PERCENT, 0), prefs.getLong(KEY_EMPTY_AT, 0))
        }
        scope.launch { manageConnection() }
    }

    fun acquire() = users.update { it + 1 }

    fun release() = users.update { maxOf(0, it - 1) }

    fun setPairedCamera(camera: PairedCamera?) {
        prefs.edit().apply {
            if (camera == null) {
                remove(KEY_ADDRESS)
                remove(KEY_NAME)
            } else {
                putString(KEY_ADDRESS, camera.address)
                putString(KEY_NAME, camera.name)
            }
            remove(KEY_MODEL)
            remove(KEY_EMPTY_PERCENT)
            remove(KEY_EMPTY_AT)
        }.apply()
        _cameraModel.value = null
        _probablyEmpty.value = null
        lastBattery = null
        setSwitchedOff(false)
        _pairedCamera.value = camera
    }

    private fun setSwitchedOff(off: Boolean) {
        prefs.edit().putBoolean(KEY_SWITCHED_OFF, off).apply()
        switchedOff.value = off
    }

    /**
     * Starts or stops recording, switching the camera on and connecting first if needed. Returns immediately.
     * @param announce emit a [Notice] on success too (not only on errors)
     */
    fun toggleRecording(announce: Boolean) = runAction("Toggling recording") { connected ->
        val start = !connected.recording
        setRecording(start)
        if (announce) _notices.emit(Notice(if (start) "Recording started" else "Recording stopped", isError = false))
    }

    /** Switches the camera on (by connecting to it) or off (sleep). Returns immediately. */
    fun togglePower(announce: Boolean) {
        if (switchedOff.value || _state.value !is CameraState.Connected) {
            switchOn(announce)
        } else {
            switchOff(announce)
        }
    }

    private fun switchOn(announce: Boolean) = runAction("Switching on") {
        if (announce) _notices.emit(Notice("Camera switched on", isError = false))
    }

    private fun switchOff(announce: Boolean) = runAction("Switching off", wake = false) {
        // The camera rejects "sleep" while it is recording: stop the recording first and let it finish saving
        if ((_state.value as? CameraState.Connected)?.recording == true) {
            Log.i(TAG, "Stopping the recording before switching off")
            setRecording(false)
            withTimeoutOrNull(8.seconds) { state.first { it is CameraState.Connected && !it.recording && !it.busy } }
        }
        sleepWithRetry()
        // Stop connecting, which would wake the camera again
        setSwitchedOff(true)
        if (announce) _notices.emit(Notice("Camera switched off", isError = false))
    }

    /**
     * Runs [action] once the camera is connected (one action at a time).
     * @param wake switch the camera on if it was switched off from Kamerad
     */
    private fun runAction(name: String, wake: Boolean = true, action: suspend (CameraState.Connected) -> Unit) {
        if (!actionRunning.compareAndSet(false, true)) return
        scope.launch {
            acquire()
            try {
                if (_pairedCamera.value == null) {
                    _notices.emit(Notice("No camera paired", "Pair it in the Kamerad app", isError = true))
                    return@launch
                }
                if (switchedOff.value) {
                    if (!wake) return@launch
                    setSwitchedOff(false)
                }
                val connected = try {
                    // Waking a switched-off camera takes a few seconds longer
                    withTimeout(30.seconds) { state.first { it is CameraState.Connected } } as CameraState.Connected
                } catch (e: TimeoutCancellationException) {
                    _notices.emit(Notice("Camera not found", "Is it switched on?", isError = true))
                    return@launch
                }
                action(connected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
                _notices.emit(Notice("Camera error", e.message, isError = true))
            } finally {
                release()
                actionRunning.set(false)
            }
        }
    }

    /** Puts the camera to sleep; it may still refuse for a moment while it finishes saving a recording. */
    private suspend fun sleepWithRetry() {
        sleepRequestedAt = SystemClock.elapsedRealtime()
        repeat(6) { attempt ->
            Log.i(TAG, "Sleep")
            try {
                sendCommand(GoProCommands.SLEEP_COMMAND, GoProCommands.SLEEP)
                return
            } catch (e: IllegalStateException) {
                // "command rejected": camera busy
                if (attempt == 5) throw e
                Log.i(TAG, "Camera not ready to sleep yet: ${e.message}")
                delay(1.seconds)
            }
        }
    }

    private suspend fun setRecording(on: Boolean) {
        Log.i(TAG, "Shutter ${if (on) "on" else "off"}")
        sendCommand(GoProCommands.shutter(on), GoProCommands.SET_SHUTTER)
        // Optimistic update; the camera's status push confirms it shortly after
        _state.update { current ->
            if (current is CameraState.Connected) {
                current.copy(recording = on, recordingSince = if (on) SystemClock.elapsedRealtime() else null)
            } else {
                current
            }
        }
    }

    /** Writes a command and waits for the camera's response to [commandId]. */
    private suspend fun sendCommand(command: ByteArray, commandId: Int): TlvResponse {
        val ble = currentBle ?: throw IllegalStateException("not connected")
        return commandMutex.withLock {
            coroutineScope {
                val response = async(start = CoroutineStart.UNDISPATCHED) {
                    commandResponses.first { it.id == commandId }
                }
                ble.write(GoProUuids.COMMAND, command)
                val result = withTimeout(5.seconds) { response.await() }
                if (result.status != 0) throw IllegalStateException("command rejected (status ${result.status})")
                result
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun manageConnection() {
        val wanted = users
            .map { it > 0 }
            .distinctUntilChanged()
            .transformLatest { active ->
                if (!active) delay(LINGER)
                emit(active)
            }
            .onStart { emit(false) }
            .distinctUntilChanged()
        combine(_pairedCamera, wanted, switchedOff) { camera, want, off -> Triple(camera, want, off) }.collectLatest { (camera, want, off) ->
            when {
                camera == null -> _state.value = CameraState.NotPaired
                off -> _state.value = CameraState.Off(canWake = canWakeOverBle(_cameraModel.value))
                !want -> _state.value = CameraState.Standby
                else -> connectLoop(camera)
            }
        }
    }

    /**
     * Keeps trying to connect while the camera is wanted. Android's background connection (autoConnect) is
     * patient but slow (10 to 30 s was normal), a direct connection is quick when the camera is advertising
     * but gives up. So the two alternate, starting with a direct attempt.
     */
    private suspend fun connectLoop(camera: PairedCamera) {
        var attempt = 0
        while (true) {
            if (!hasBluetoothPermission()) {
                _state.value = CameraState.NoPermission
                delay(5.seconds)
                continue
            }
            _state.value = CameraState.Searching
            val direct = attempt++ % 2 == 0
            val ble = GoProBle(appContext, camera.address, autoConnect = !direct)
            var cameraWasReady = false
            try {
                runSession(ble, direct, linkTimeout = if (direct) DIRECT_CONNECT_TIMEOUT else BACKGROUND_CONNECT_WINDOW)
                cameraWasReady = true
            } catch (e: CameraNotAnsweringException) {
                Log.i(TAG, "No answer from the camera (${if (direct) "direct" else "background"} attempt), trying again")
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Camera session ended: ${e.message}")
            } finally {
                currentBle = null
                ble.close()
            }
            if (sessionReady) {
                sessionReady = false
                noteConnectionLost()
            }
            // After a lost connection start over with a direct attempt
            if (cameraWasReady) attempt = 0
            _state.value = CameraState.Searching
            delay(5.seconds)
        }
    }

    /** A working connection ended; if the camera was not switched off by us and its battery was low, it is probably empty. */
    private fun noteConnectionLost() {
        val percent = lastBattery
        val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
        if (!switchedOffByUs && percent != null && percent <= EMPTY_BATTERY_THRESHOLD) {
            Log.i(TAG, "Camera vanished at $percent% battery: probably empty")
            setProbablyEmpty(ProbablyEmpty(percent, System.currentTimeMillis()))
        } else {
            Log.i(TAG, "Connection to the camera lost (last battery level: ${percent ?: "unknown"}%, switched off by us: $switchedOffByUs)")
        }
    }

    private fun setProbablyEmpty(value: ProbablyEmpty?) {
        if (_probablyEmpty.value == value) return
        _probablyEmpty.value = value
        prefs.edit().apply {
            if (value == null) {
                remove(KEY_EMPTY_PERCENT)
                remove(KEY_EMPTY_AT)
            } else {
                putInt(KEY_EMPTY_PERCENT, value.percent)
                putLong(KEY_EMPTY_AT, value.atMillis)
            }
        }.apply()
    }

    /** The camera didn't answer within the time allowed for this connection attempt. */
    private class CameraNotAnsweringException : Exception()

    private suspend fun runSession(ble: GoProBle, direct: Boolean, linkTimeout: Duration) = coroutineScope {
        val commandPackets = PacketAccumulator()
        val queryPackets = PacketAccumulator()
        launch(start = CoroutineStart.UNDISPATCHED) {
            ble.notifications.collect { (uuid, data) ->
                when (uuid) {
                    GoProUuids.COMMAND_RESPONSE ->
                        commandPackets.accept(data)?.let(TlvResponse::parse)?.let { commandResponses.emit(it) }
                    GoProUuids.QUERY_RESPONSE ->
                        queryPackets.accept(data)?.let(TlvResponse::parse)?.let(::onQueryResponse)
                }
            }
        }

        Log.i(TAG, "Connecting to camera (${if (direct) "direct" else "background"})")
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = "${(SystemClock.elapsedRealtime() - started) / 1000.0} s"
        val answered = ble.connect(linkTimeout, onLinkUp = {
            // The camera has answered; it may take a few more seconds until it is ready
            _state.value = CameraState.Starting
            Log.i(TAG, "Camera answered (link up) after ${elapsed()}")
        })
        if (!answered) throw CameraNotAnsweringException()
        Log.i(TAG, "Camera services discovered after ${elapsed()}")
        ble.enableNotifications(GoProUuids.COMMAND_RESPONSE)
        ble.enableNotifications(GoProUuids.SETTING_RESPONSE)
        ble.enableNotifications(GoProUuids.QUERY_RESPONSE)
        currentBle = ble
        // Stay in "Starting" until the camera accepts commands: right after waking up it still rejects them
        awaitCameraReady()
        _state.value = CameraState.Connected()
        sessionReady = true
        setProbablyEmpty(null)
        Log.i(TAG, "Camera ready after ${elapsed()}")
        ble.write(
            GoProUuids.QUERY,
            GoProCommands.registerStatusUpdates(
                GoProStatus.BUSY,
                GoProStatus.ENCODING,
                GoProStatus.VIDEO_DURATION,
                GoProStatus.BATTERY_PERCENT,
            ),
        )

        launch {
            while (true) {
                delay(KEEP_ALIVE_INTERVAL)
                ble.write(GoProUuids.SETTING, GoProCommands.KEEP_ALIVE)
            }
        }

        ble.disconnected.await()
        Log.i(TAG, "Camera disconnected")
        coroutineContext.cancelChildren()
    }

    /**
     * Waits until the camera accepts commands, by asking it for its model (e.g. "HERO8 Black", remembered).
     * A camera that has just woken up answers "not ready" for a moment; gives up after [READY_TIMEOUT]
     * and carries on, so a camera that never answers this command isn't blocked.
     */
    private suspend fun awaitCameraReady() {
        val started = SystemClock.elapsedRealtime()
        var attempts = 0
        while (true) {
            attempts++
            try {
                val model = parseModelName(sendCommand(GoProCommands.GET_HARDWARE_INFO_COMMAND, GoProCommands.GET_HARDWARE_INFO).payload)
                Log.i(TAG, "Camera model: $model (accepted commands after $attempts attempt(s))")
                if (model != null && model != _cameraModel.value) {
                    prefs.edit().putString(KEY_MODEL, model).apply()
                    _cameraModel.value = model
                }
                return
            } catch (e: TimeoutCancellationException) {
                // No answer within the command timeout: try again like any other failure
                Log.w(TAG, "Camera didn't answer (attempt $attempts)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Camera not ready yet (attempt $attempts): ${e.message}")
            }
            if (SystemClock.elapsedRealtime() - started > READY_TIMEOUT.inWholeMilliseconds) {
                Log.w(TAG, "Camera never accepted the model request; carrying on")
                return
            }
            delay(500)
        }
    }

    private fun onQueryResponse(response: TlvResponse) {
        if (response.status != 0) return
        if (response.id !in setOf(GoProCommands.QUERY_GET_STATUS, GoProCommands.QUERY_REGISTER_STATUS, GoProCommands.QUERY_STATUS_PUSH)) return
        val values = response.values()
        // The duration is pushed every second (and with junk values while idle), so don't log it
        values.filterKeys { it != GoProStatus.VIDEO_DURATION }.takeIf { it.isNotEmpty() }?.let { logged ->
            Log.d(TAG, "Status: " + logged.entries.joinToString { (id, value) -> "$id=${value.toUnsignedInt()}" })
        }
        values[GoProStatus.BATTERY_PERCENT]?.let { lastBattery = it.toUnsignedInt().toInt() }
        _state.update { current ->
            if (current !is CameraState.Connected) return@update current
            var next: CameraState.Connected = current
            values[GoProStatus.BUSY]?.let { next = next.copy(busy = it.toUnsignedInt() != 0L) }
            values[GoProStatus.BATTERY_PERCENT]?.let { next = next.copy(batteryPercent = it.toUnsignedInt().toInt()) }
            values[GoProStatus.ENCODING]?.let { encoding ->
                val recording = encoding.toUnsignedInt() != 0L
                next = next.copy(
                    recording = recording,
                    recordingSince = when {
                        !recording -> null
                        else -> next.recordingSince ?: SystemClock.elapsedRealtime()
                    },
                )
            }
            values[GoProStatus.VIDEO_DURATION]?.let { duration ->
                val seconds = duration.toUnsignedInt()
                // Ignore implausible values (seen while idle); a recording is shorter than a day
                if (next.recording && seconds < 24 * 3600) {
                    next = next.copy(recordingSince = SystemClock.elapsedRealtime() - seconds * 1000)
                }
            }
            next
        }
    }

    fun hasBluetoothPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }
}
