package at.tellioglu.kamerad.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionSendBroadcast
import at.tellioglu.kamerad.gopro.CameraState
import at.tellioglu.kamerad.gopro.GoProController

/**
 * Receives taps on the tiles.
 *
 * Each tile gets its own intent action: with Glance's `actionRunCallback` the tiles' pending
 * intents were equal apart from their extras, so a tap on one tile could run the other's action.
 */
class TileTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = GoProController.state.value as? CameraState.Connected
        when (intent.action) {
            ACTION_TOGGLE_RECORDING ->
                // Stopping needs a second tap; starting doesn't
                if (connected?.recording == true) {
                    if (TapConfirmation.confirm(TapConfirmation.Kind.STOP_RECORDING)) GoProController.toggleRecording(announce = false)
                } else {
                    TapConfirmation.clear()
                    GoProController.toggleRecording(announce = false)
                }
            ACTION_TOGGLE_POWER ->
                // Switching off needs a second tap; switching on doesn't
                if (connected != null) {
                    if (TapConfirmation.confirm(TapConfirmation.Kind.SWITCH_OFF)) GoProController.togglePower(announce = false)
                } else {
                    TapConfirmation.clear()
                    GoProController.togglePower(announce = false)
                }
        }
    }

    companion object {
        const val ACTION_TOGGLE_RECORDING = "at.tellioglu.kamerad.TOGGLE_RECORDING"
        const val ACTION_TOGGLE_POWER = "at.tellioglu.kamerad.TOGGLE_POWER"

        fun tapAction(context: Context, action: String): Action =
            actionSendBroadcast(Intent(context, TileTapReceiver::class.java).setAction(action))
    }
}
