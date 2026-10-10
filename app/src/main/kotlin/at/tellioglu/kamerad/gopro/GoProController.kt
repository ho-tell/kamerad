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
    private const val KEY_LAST_BATTERY = "last_battery"
    private const val KEY_IDLE_MINUTES = "idle_minutes"
    private const val DEFAULT_IDLE_MINUTES = 5

    /** A camera that vanishes while its battery was at or below this level has probably run out of battery. */
    private const val EMPTY_BATTERY_THRESHOLD = 10
    private val LINGER = 2.minutes
    private val KEEP_ALIVE_INTERVAL = 3.seconds
    private val READY_TIMEOUT = 20.seconds
    private val DIRECT_CONNECT_TIMEOUT = 8.seconds
    private val RSSI_INTERVAL = 10.seconds
    private val ADVERTISING_LISTEN = 10.seconds
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
    private var connectLogged = false

    private var lastLoggedBattery: Int? = null
    private var lastReportedRecording: Boolean? = null

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

    /** Minutes without recording after which the camera is switched off; 0 = never. */
    private val _idleMinutes = MutableStateFlow(DEFAULT_IDLE_MINUTES)
    val idleMinutes: StateFlow<Int> = _idleMinutes.asStateFlow()

    /** Counts what the user did with the camera, so the idle timer starts over. */
    private val activity = MutableStateFlow(0)

    /** The camera recorded since it was last switched on: only then does the idle timer run. */
    @Volatile
    private var recordedSinceOn = false

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
        _idleMinutes.value = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
        if (prefs.contains(KEY_LAST_BATTERY)) lastBattery = prefs.getInt(KEY_LAST_BATTERY, 0)
        if (prefs.contains(KEY_EMPTY_PERCENT)) {
            _probablyEmpty.value = ProbablyEmpty(prefs.getInt(KEY_EMPTY_PERCENT, 0), prefs.getLong(KEY_EMPTY_AT, 0))
        }
        scope.launch { manageConnection() }
        scope.launch { watchIdle() }
    }

    fun setIdleMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_IDLE_MINUTES, minutes).apply()
        _idleMinutes.value = minutes
    }

    private enum class Activity { NOT_CONNECTED, RECORDING, BUSY, IDLE }

    /**
     * Switches the camera off when it has not recorded for [idleMinutes] while connected, so it is not left on
     * (and draining) after a recording. Waking it again is what pressing the shutter already does.
     */
    private suspend fun watchIdle() {
        val camera = _state
            .map { s ->
                when {
                    s !is CameraState.Connected -> Activity.NOT_CONNECTED
                    s.recording -> Activity.RECORDING
                    s.busy -> Activity.BUSY
                    else -> Activity.IDLE
                }
            }
            .distinctUntilChanged()
        combine(camera, _idleMinutes, activity) { c, minutes, _ -> c to minutes }.collectLatest { (c, minutes) ->
            if (c == Activity.RECORDING) recordedSinceOn = true
            if (c == Activity.IDLE && recordedSinceOn && minutes > 0) {
                delay(minutes.minutes)
                event("Not recording for $minutes min: switching the camera off")
                switchOff(announce = true, detail = "Not recording for $minutes min")
            }
        }
    }

    fun acquire() = users.update { it + 1 }

    fun release() = users.update { maxOf(0, it - 1) }

    fun setPairedCamera(camera: PairedCamera?) {
        // Kept in the history so an unexpected unpairing can be traced to what triggered it
        if (camera == null) {
            event("Camera forgotten, called from: " + Throwable().stackTrace.drop(1).take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
        } else {
            event("Paired with ${camera.name}")
        }
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
            remove(KEY_LAST_BATTERY)
        }.apply()
        _cameraModel.value = null
        _probablyEmpty.value = null
        lastBattery = null
        setSwitchedOff(false)
        _pairedCamera.value = camera
    }

    private fun setSwitchedOff(off: Boolean) {
        if (off) recordedSinceOn = false
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

    private fun switchOff(announce: Boolean, detail: String? = null) = runAction("Switching off", wake = false) {
        // The camera rejects "sleep" while it is recording: stop the recording first and let it finish saving
        if ((_state.value as? CameraState.Connected)?.recording == true) {
            event("Stopping the recording before switching off")
            setRecording(false)
            withTimeoutOrNull(8.seconds) { state.first { it is CameraState.Connected && !it.recording && !it.busy } }
        }
        sleepWithRetry()
        // Stop connecting, which would wake the camera again
        setSwitchedOff(true)
        if (announce) _notices.emit(Notice("Camera switched off", detail, isError = false))
    }

    /**
     * Runs [action] once the camera is connected (one action at a time).
     * @param wake switch the camera on if it was switched off from Kamerad
     */
    private fun runAction(name: String, wake: Boolean = true, action: suspend (CameraState.Connected) -> Unit) {
        if (!actionRunning.compareAndSet(false, true)) return
        activity.update { it + 1 }
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
                Log.w(TAG, "$name failed", e) // with the stack trace for logcat
                EventLog.add("$name failed: ${e.message}")
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
            event("Sleep")
            try {
                sendCommand(GoProCommands.SLEEP_COMMAND, GoProCommands.SLEEP)
                return
            } catch (e: IllegalStateException) {
                // "command rejected": camera busy
                if (attempt == 5) throw e
                event("Camera not ready to sleep yet: ${e.message}")
                delay(1.seconds)
            }
        }
    }

    private suspend fun setRecording(on: Boolean) {
        event("Shutter ${if (on) "on" else "off"}")
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
        // A session cancelled because the camera was no longer wanted didn't lose its connection
        sessionReady = false
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
                // Only the first miss is logged: hundreds of identical lines would push out what matters
                if (attempt == 1) event("No answer from the camera, trying again until it answers")
                if (attempt >= 3) noteUnreachable()
                if (attempt == 3 || attempt % 20 == 0) checkAdvertising(camera, firstCheck = attempt == 3)
                continue
            } catch (e: TimeoutCancellationException) {
                // A step of the setup got no answer (e.g. while the camera shows a notice): a failed attempt, not a cancellation
                event("Camera did not answer during setup (${e.message}), trying again")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("Camera session ended: ${e.message}")
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

    private var lastAdvertisingHeard: Boolean? = null

    /**
     * During an outage: is the camera still advertising? Heard: it is in range but doesn't accept the
     * connection; not heard: it is out of range or has stopped advertising. Logged when the answer changes.
     */
    private suspend fun checkAdvertising(camera: PairedCamera, firstCheck: Boolean) {
        val rssi = try {
            listenForCamera(appContext, camera.address, ADVERTISING_LISTEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            event("Could not listen for the camera: ${e.message}")
            return
        }
        val heard = rssi != null
        if (!firstCheck && heard == lastAdvertisingHeard) return
        lastAdvertisingHeard = heard
        event(
            if (rssi != null) "Camera is advertising ($rssi dBm) but does not accept the connection"
            else "Camera not heard advertising for ${ADVERTISING_LISTEN.inWholeSeconds} s: out of range or not advertising",
        )
    }

    private fun describeDisconnect(status: Int?): String = when (status) {
        null -> "closed by Kamerad"
        8 -> "status 8: link timed out, weak signal or out of range"
        19 -> "status 19: ended by the camera"
        22 -> "status 22: ended by the Karoo"
        62 -> "status 62: link could not be set up"
        133 -> "status 133: Bluetooth error"
        else -> "status $status"
    }

    /** Logs to logcat and to the event history that can be read later (see [EventLog]). */
    private fun event(message: String) {
        Log.i(TAG, message)
        EventLog.add(message)
    }

    /** A working connection ended; if the camera was not switched off by us and its battery was low, it is probably empty. */
    private fun noteConnectionLost() {
        val percent = lastBattery
        val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
        if (!switchedOffByUs && percent != null && percent <= EMPTY_BATTERY_THRESHOLD) {
            event("Camera vanished at $percent% battery: probably empty")
            setProbablyEmpty(ProbablyEmpty(percent, System.currentTimeMillis()))
        } else if (!switchedOffByUs && percent != null) {
            event("Camera shut down or lost at $percent% battery (more than $EMPTY_BATTERY_THRESHOLD% left): not a plain empty battery")
        } else {
            event("Connection to the camera lost (last battery level: ${percent ?: "unknown"}%, switched off by us: $switchedOffByUs)")
        }
    }

    /**
     * The camera does not answer although we want it: if its last known battery level was low and we did not
     * switch it off, it is probably empty (also when the connection was lost while the app was not running).
     */
    private fun noteUnreachable() {
        val percent = lastBattery ?: return
        val switchedOffByUs = SystemClock.elapsedRealtime() - sleepRequestedAt < 15_000 || switchedOff.value
        if (switchedOffByUs || percent > EMPTY_BATTERY_THRESHOLD || _probablyEmpty.value != null) return
        event("Camera not answering, last battery level was $percent%: probably empty")
        setProbablyEmpty(ProbablyEmpty(percent, System.currentTimeMillis()))
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

        // Only logged once per outage: the attempts repeat every few seconds and would fill the history
        if (!connectLogged) {
            connectLogged = true
            event("Connecting to camera")
        }
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = "${(SystemClock.elapsedRealtime() - started) / 1000.0} s"
        val answered = ble.connect(linkTimeout, onLinkUp = {
            // The camera has answered; it may take a few more seconds until it is ready
            _state.value = CameraState.Starting
            event("Camera answered (link up) after ${elapsed()}")
        })
        if (!answered) throw CameraNotAnsweringException()
        event("Camera services discovered after ${elapsed()}")
        ble.enableNotifications(GoProUuids.COMMAND_RESPONSE)
        ble.enableNotifications(GoProUuids.SETTING_RESPONSE)
        ble.enableNotifications(GoProUuids.QUERY_RESPONSE)
        currentBle = ble
        // Stay in "Starting" until the camera accepts commands: right after waking up it still rejects them
        awaitCameraReady()
        _state.value = CameraState.Connected()
        sessionReady = true
        connectLogged = false
        lastLoggedBattery = null
        lastReportedRecording = null
        setProbablyEmpty(null)
        event("Camera ready after ${elapsed()}")
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

        // The signal strength before a lost connection tells a weak link (distance, body in the way) from other causes
        var lastRssi: Int? = null
        var weakestRssi: Int? = null
        launch {
            while (true) {
                delay(RSSI_INTERVAL)
                try {
                    val rssi = ble.readRssi()
                    lastRssi = rssi
                    weakestRssi = minOf(weakestRssi ?: rssi, rssi)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // only a diagnostic: never end the session because of it
                }
            }
        }

        ble.disconnected.await()
        val signal = lastRssi?.let { ", signal before: $it dBm, weakest $weakestRssi dBm" } ?: ""
        event("Camera disconnected (${describeDisconnect(ble.disconnectStatus)}$signal)")
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
                event("Camera model: $model (accepted commands after $attempts attempt(s))")
                if (model != null && model != _cameraModel.value) {
                    prefs.edit().putString(KEY_MODEL, model).apply()
                    _cameraModel.value = model
                }
                return
            } catch (e: TimeoutCancellationException) {
                // No answer within the command timeout: try again like any other failure
                event("Camera didn't answer (attempt $attempts)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("Camera not ready yet (attempt $attempts): ${e.message}")
            }
            if (SystemClock.elapsedRealtime() - started > READY_TIMEOUT.inWholeMilliseconds) {
                event("Camera never accepted the model request; carrying on")
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
        values[GoProStatus.BATTERY_PERCENT]?.let {
            val percent = it.toUnsignedInt().toInt()
            if (lastBattery != percent) prefs.edit().putInt(KEY_LAST_BATTERY, percent).apply()
            lastBattery = percent
            // Every 10 %, and every single step when it gets low: the trail before a battery runs out
            val logged = lastLoggedBattery
            if (logged == null || percent != logged && (percent <= 20 || kotlin.math.abs(percent - logged) >= 10)) {
                lastLoggedBattery = percent
                event("Camera battery: $percent%")
            }
        }
        values[GoProStatus.ENCODING]?.let {
            val recording = it.toUnsignedInt() != 0L
            if (recording != lastReportedRecording) {
                lastReportedRecording = recording
                event("Camera reports: ${if (recording) "recording" else "not recording"}")
            }
        }
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
