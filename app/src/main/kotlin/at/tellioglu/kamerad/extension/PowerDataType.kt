package at.tellioglu.kamerad.extension

import android.content.Context
import androidx.glance.action.Action
import at.tellioglu.kamerad.gopro.CameraState

/** Shows whether the camera is on; tap switches it on or off. */
class PowerDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    override fun clickAction(context: Context): Action = TileTapReceiver.tapAction(context, TileTapReceiver.ACTION_TOGGLE_POWER)

    // Switch graphic plus "OFF?"
    override val titleWidthEm = 4.6f

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state)
        ?: when (state) {
            is CameraState.Off ->
                if (state.canWake) {
                    TileModel("OFF", "Tap to switch on", TileModel.UNAVAILABLE, icon = TileIcon.Switch(on = false))
                } else {
                    // e.g. HERO8: doesn't wake up over Bluetooth; a tap reconnects once it's switched on
                    TileModel("OFF", if (compact) "Use camera button" else "Use camera button, then tap", TileModel.UNAVAILABLE, icon = TileIcon.Switch(on = false))
                }
            is CameraState.Connected -> when {
                // Until the camera is off
                pending?.kind == TapConfirmation.Kind.SWITCH_OFF && pending.confirmed ->
                    TileModel("Switching off…", "", TileModel.CONFIRM)
                pending?.kind == TapConfirmation.Kind.SWITCH_OFF ->
                    // Switching off while recording stops the recording first
                    TileModel(
                        "OFF?",
                        if (state.recording) "Also stops recording" else "Tap again to switch off",
                        TileModel.CONFIRM,
                        icon = TileIcon.Switch(on = true),
                    )
                else -> {
                    // No room for the battery level in narrow fields
                    val battery = state.batteryPercent?.takeUnless { compact }
                    TileModel(
                        "ON",
                        "Tap to switch off",
                        // Blue like the other tiles; the switch knob shows "on" in green
                        TileModel.READY_BLUE,
                        icon = TileIcon.Switch(on = true),
                        subtitleBattery = battery,
                    )
                }
            }
            else -> error("unreachable")
        }

    override fun previewModel(compact: Boolean) = TileModel(
        "ON",
        "Tap to switch off",
        TileModel.READY_BLUE,
        icon = TileIcon.Switch(on = true),
        subtitleBattery = SAMPLE_BATTERY.takeUnless { compact },
    )

    companion object {
        const val TYPE_ID = "power"
    }
}
