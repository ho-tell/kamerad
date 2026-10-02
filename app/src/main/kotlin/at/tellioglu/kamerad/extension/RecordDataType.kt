package at.tellioglu.kamerad.extension

import android.content.Context
import android.os.SystemClock
import androidx.glance.action.Action
import at.tellioglu.kamerad.gopro.CameraState

/** Shows the camera state; tap starts or stops recording (switching the camera on if needed). */
class RecordDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    override fun clickAction(context: Context): Action = TileTapReceiver.tapAction(context, TileTapReceiver.ACTION_TOGGLE_RECORDING)

    // Title is at most "■ 0:00:00"
    override val titleWidthEm = 4.6f

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state)
        ?: when (state) {
            is CameraState.Off -> TileModel(
                "● REC",
                if (state.canWake) "Camera off · tap to record" else "Camera off · use its button",
                TileModel.UNAVAILABLE,
            )
            is CameraState.Connected -> {
                // No room for the battery level in narrow fields
                val battery = state.batteryPercent?.takeUnless { compact }
                when {
                    // Until the camera reports that recording stopped
                    state.recording && pending?.kind == TapConfirmation.Kind.STOP_RECORDING && pending.confirmed ->
                        TileModel("Stopping…", "", TileModel.CONFIRM)
                    state.recording && pending?.kind == TapConfirmation.Kind.STOP_RECORDING ->
                        TileModel("STOP?", "Tap again to stop", TileModel.CONFIRM)
                    state.recording -> TileModel(
                        "■ " + formatDuration(state.recordingSince),
                        "Tap to stop",
                        TileModel.ACTIVE_RED,
                        subtitleBattery = battery,
                    )
                    state.busy -> TileModel("● REC", "Busy…", TileModel.READY_BLUE, subtitleBattery = battery)
                    else -> TileModel("● REC", "Tap to record", TileModel.READY_BLUE, subtitleBattery = battery)
                }
            }
            else -> error("unreachable")
        }

    override fun previewModel(compact: Boolean) =
        TileModel("● REC", "Tap to record", TileModel.READY_BLUE, subtitleBattery = SAMPLE_BATTERY.takeUnless { compact })

    private fun formatDuration(since: Long?): String {
        val seconds = since?.let { (SystemClock.elapsedRealtime() - it) / 1000 }?.coerceAtLeast(0) ?: 0
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        const val TYPE_ID = "record"
    }
}
