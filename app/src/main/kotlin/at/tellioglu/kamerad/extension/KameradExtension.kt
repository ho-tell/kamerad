package at.tellioglu.kamerad.extension

import android.util.Log
import at.tellioglu.kamerad.BuildConfig
import at.tellioglu.kamerad.R
import at.tellioglu.kamerad.gopro.GoProController
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KameradExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private var serviceJob: Job? = null

    override val types by lazy {
        listOf(RecordDataType(extension), BatteryDataType(extension), PowerDataType(extension))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            if (connected) karooSystem.dispatch(RequestBluetooth(EXTENSION_ID))
        }
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            GoProController.notices.collect { notice ->
                karooSystem.dispatch(
                    InRideAlert(
                        id = "kamerad-notice",
                        icon = R.drawable.ic_kamerad,
                        // The title is what's readable at a glance, so it carries the message
                        title = notice.title,
                        detail = notice.detail,
                        autoDismissMs = if (notice.isError) 8_000 else 3_000,
                        backgroundColor = if (notice.isError) R.color.alert_error_background else R.color.alert_background,
                        textColor = R.color.alert_text,
                    ),
                )
            }
        }
    }

    override fun onBonusAction(actionId: String) {
        when (KameradAction.fromActionId(actionId)) {
            KameradAction.TOGGLE_RECORDING -> GoProController.toggleRecording(announce = true)
            null -> Log.w("Kamerad", "Unknown bonus action $actionId")
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.dispatch(ReleaseBluetooth(EXTENSION_ID))
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "kamerad"
    }
}
