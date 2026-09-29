package at.tellioglu.kamerad.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Two-tap confirmation for tile actions that are annoying if triggered by accident on a bumpy road
 * (stopping a recording, switching the camera off).
 */
object TapConfirmation {
    enum class Kind { STOP_RECORDING, SWITCH_OFF }

    /** [kind] is waiting for its second tap, or has been [confirmed] and is being carried out. */
    data class Pending(val kind: Kind, val confirmed: Boolean)

    private val WINDOW = 4.seconds

    /** How long a confirmed action is shown as in progress at most (the camera state usually changes sooner). */
    private val IN_PROGRESS = 10.seconds

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The action waiting for its confirming second tap or being carried out, if any. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeout: Job? = null

    /**
     * Returns true on the confirming second tap. The first tap only asks for confirmation
     * (returns false), which expires after [WINDOW].
     */
    @Synchronized
    fun confirm(kind: Kind): Boolean {
        val current = _pending.value
        if (current?.kind == kind && current.confirmed) return false // already being carried out
        val confirmed = current?.kind == kind
        val next = Pending(kind, confirmed)
        _pending.value = next
        timeout?.cancel()
        timeout = scope.launch {
            delay(if (confirmed) IN_PROGRESS else WINDOW)
            _pending.compareAndSet(next, null)
        }
        return confirmed
    }

    @Synchronized
    fun clear() {
        timeout?.cancel()
        _pending.value = null
    }
}
