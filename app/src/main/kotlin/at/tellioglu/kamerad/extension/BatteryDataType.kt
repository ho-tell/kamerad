package at.tellioglu.kamerad.extension

import at.tellioglu.kamerad.gopro.CameraState

/** Camera battery level: a battery icon filled to the level plus the percentage. Not tappable. */
class BatteryDataType(extension: String) : ButtonTileDataType(extension, TYPE_ID) {
    // Battery icon plus "100%"
    override val titleWidthEm = 4.6f

    override fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel = TileModel.unavailable(state)
        ?: when (state) {
            is CameraState.Off -> TileModel("--%", "Camera off", TileModel.UNAVAILABLE, icon = TileIcon.Battery(null))
            is CameraState.Connected -> TileModel(
                state.batteryPercent?.let { "$it%" } ?: "--%",
                "Camera battery",
                TileModel.READY_BLUE,
                icon = TileIcon.Battery(state.batteryPercent),
            )
            else -> error("unreachable")
        }

    companion object {
        const val TYPE_ID = "battery"
    }
}
