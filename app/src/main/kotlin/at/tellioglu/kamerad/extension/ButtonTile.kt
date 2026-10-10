package at.tellioglu.kamerad.extension

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import at.tellioglu.kamerad.R
import at.tellioglu.kamerad.gopro.CameraState
import at.tellioglu.kamerad.gopro.GoProController
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Content of a [ButtonTileDataType]: a large title and a small hint line on a coloured background.
 * @param icon graphic drawn in front of the title
 * @param subtitleBattery camera battery level appended to the subtitle as "· 87%" plus a small battery icon
 */
data class TileModel(
    val title: String,
    val subtitle: String,
    val background: Color,
    val icon: TileIcon? = null,
    val subtitleBattery: Int? = null,
    /** Shrinks the title (below the size that fits the tile), e.g. for a longer text. */
    val titleScale: Float = 1f,
) {
    companion object {
        /** Camera connected and ready; a GoPro-like blue that keeps white text readable. */
        val READY_BLUE = Color(0xFF0A84D6)
        val ACTIVE_RED = Color(0xFFD7261E)
        val UNAVAILABLE = Color(0xFF3A3A3A)

        /** Waiting for the confirming second tap, see [TapConfirmation]. */
        val CONFIRM = Color(0xFFE07800)

        /** Model for states in which the camera can't be used, or null if connected. */
        fun unavailable(state: CameraState): TileModel? = when (state) {
            CameraState.NotPaired -> TileModel("No camera", "Pair in Kamerad app", UNAVAILABLE)
            CameraState.NoPermission -> TileModel("No permission", "Open Kamerad app", UNAVAILABLE)
            // Connecting, or the camera is starting up
            CameraState.Standby, CameraState.Searching -> {
                val empty = GoProController.probablyEmpty.value
                if (empty != null) {
                    // Gone while the battery was low: it has probably run out
                    TileModel(
                        "Empty?",
                        "Battery was ${empty.percent}%",
                        UNAVAILABLE,
                        icon = TileIcon.Battery(empty.percent),
                        titleScale = 0.7f,
                    )
                } else {
                    TileModel("Waiting…", "for camera", UNAVAILABLE)
                }
            }
            // The camera has answered and is starting up: nothing to do but wait
            CameraState.Starting -> TileModel("Starting…", "camera responding", UNAVAILABLE)
            is CameraState.Off, is CameraState.Connected -> null
        }
    }
}

/** Battery level shown in the previews while a page is edited. */
const val SAMPLE_BATTERY = 85

sealed interface TileIcon {
    /** Toggle switch, knob on the right when [on]. */
    data class Switch(val on: Boolean) : TileIcon

    /** Battery filled to [percent] (empty outline if unknown). */
    data class Battery(val percent: Int?) : TileIcon
}

/**
 * Graphical, tappable in-ride field without the Karoo header, rendered from the camera state.
 * The camera is kept connected while the field is on screen.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class ButtonTileDataType(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance = GlanceRemoteViews()

    /** Tap on the field (see [TileTapReceiver]), or null if the field isn't tappable. */
    open fun clickAction(context: Context): Action? = null

    /** Largest title in multiples of the font size, used to size the title to the field. */
    abstract val titleWidthEm: Float

    /**
     * Called every second (for timers) and on every change.
     * @param pending action waiting for a confirming second tap or being carried out
     * @param compact the field is narrow (half width or less); leave out secondary information
     */
    abstract fun model(state: CameraState, pending: TapConfirmation.Pending?, compact: Boolean): TileModel

    /** Sample content shown while the Karoo page is edited: tiles don't connect to the camera there. */
    abstract fun previewModel(compact: Boolean): TileModel

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        // Hide the Karoo's standard stream container, which otherwise covers the lower part of the view
        emitter.onNext(ShowCustomStreamState("", null))
        // The subtitle line and padding take 30 dp; same title width for all tiles so their titles match in size
        val titleSize = fitFontSize(
            context,
            config,
            reservedHeightDp = 30f,
            widthEm = titleWidthEm,
            horizontalPaddingDp = 8f,
            // Glance text lines are taller than the font size (font padding)
            lineHeight = 1.35f,
        )
        Log.d("Kamerad", "$typeId field $config -> ${titleSize}sp")
        // Keep the camera connected while the field is on screen (not in page-editing preview)
        if (!config.preview) GoProController.acquire()
        // In the page editor a tap selects the field for editing: the tile must not take it
        val onClick = if (config.preview) null else clickAction(context)
        // Half-width fields (30 of 60 grid columns) or narrower
        val compact = config.gridSize.first <= 30
        val logoSize = if (compact) COMPACT_LOGO_SIZE_DP else LOGO_SIZE_DP
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(1000)
            }
        }
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(GoProController.state, TapConfirmation.pending, GoProController.probablyEmpty, ticker) { state, pending, _, _ ->
                if (config.preview) previewModel(compact) else model(state, pending, compact)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { model ->
                    val result = glance.compose(context, DpSize.Unspecified) { ButtonTile(model, titleSize, titleWidthEm, logoSize, onClick) }
                    emitter.updateView(result.remoteViews)
                    // The host drops view updates arriving less than ~1 s apart
                    delay(1000)
                }
        }
        emitter.setCancellable {
            job.cancel()
            if (!config.preview) GoProController.release()
        }
    }
}

@Composable
private fun ButtonTile(model: TileModel, titleSize: Float, titleWidthEm: Float, logoSize: Float, onClick: Action?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(model.background))
            .let { if (onClick != null) it.clickable(onClick) else it },
    ) {
        // Small Kamerad logo in the upper right corner, identifying the tile
        Box(modifier = GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopEnd) {
            Image(
                provider = ImageProvider(R.drawable.ic_kamerad),
                contentDescription = null,
                modifier = GlanceModifier.size(logoSize.dp),
            )
        }
        Box(modifier = GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // titleSize fits titleWidthEm; shrink longer texts such as "Waiting…" to fit as well
                // (bold characters are about 0.6 em wide; icons count as part of titleWidthEm)
                // The icon in front of the title takes room too (in multiples of the font size)
                val iconEm = when (model.icon) {
                    is TileIcon.Switch -> 1.65f
                    is TileIcon.Battery -> 1.5f
                    null -> 0f
                }
                val fontSize = titleSize * minOf(1f, titleWidthEm / (model.title.length * 0.6f + iconEm)) * model.titleScale
                Row(verticalAlignment = Alignment.CenterVertically) {
                    model.icon?.let { icon ->
                        // The text box has more room above the digits than below, so centring the boxes
                        // leaves the icon too high. Top padding moves the centred icon down by half its
                        // height: 0.2 em puts it on the visual centre of the title (measured on the Karoo).
                        Box(modifier = GlanceModifier.padding(top = (fontSize * 0.2f).dp)) {
                            when (icon) {
                                is TileIcon.Switch -> ToggleSwitch(icon.on, heightDp = fontSize * 0.75f)
                                is TileIcon.Battery -> BatteryIcon(icon.percent, heightDp = fontSize * 0.6f, background = model.background)
                            }
                        }
                        Spacer(GlanceModifier.width((fontSize * 0.3f).dp))
                    }
                    Text(
                        text = model.title,
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.subtitle + (model.subtitleBattery?.let { " · $it%" } ?: ""),
                        style = TextStyle(
                            color = ColorProvider(Color(0xCCFFFFFF)),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                    )
                    model.subtitleBattery?.let { percent ->
                        Spacer(GlanceModifier.width(4.dp))
                        BatteryIcon(percent, heightDp = 11f, background = model.background)
                    }
                }
            }
        }
    }
}

private val SWITCH_ON_GREEN = Color(0xFF34C759)

private const val LOGO_SIZE_DP = 21f
private const val COMPACT_LOGO_SIZE_DP = 14f

/** A toggle switch graphic: white knob on the right of a green track (on) or on the left of a grey one (off). */
@Composable
private fun ToggleSwitch(on: Boolean, heightDp: Float) {
    val knob = heightDp * 0.8f
    Box(
        modifier = GlanceModifier
            .size((heightDp * 1.8f).dp, heightDp.dp)
            .cornerRadius((heightDp / 2).dp)
            .background(ColorProvider(if (on) SWITCH_ON_GREEN else Color(0x66FFFFFF)))
            .padding(((heightDp - knob) / 2).dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = GlanceModifier
                .size(knob.dp)
                .cornerRadius((knob / 2).dp)
                .background(ColorProvider(Color.White)),
        ) {}
    }
}

/** A battery graphic: white outline filled to [percent] (red when low), with a tip on the right. */
@Composable
private fun BatteryIcon(percent: Int?, heightDp: Float, background: Color) {
    val width = heightDp * 1.9f
    val stroke = maxOf(2f, heightDp * 0.1f)
    val gap = stroke
    val innerWidth = width - 2 * (stroke + gap)
    val fill = if (percent != null && percent <= 15) Color(0xFFFF3B30) else Color.White
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Body: white rounded box with a background-coloured inside, like an outline
        Box(
            modifier = GlanceModifier
                .size(width.dp, heightDp.dp)
                .cornerRadius((heightDp * 0.18f).dp)
                .background(ColorProvider(Color.White))
                .padding(stroke.dp),
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius((heightDp * 0.1f).dp)
                    .background(ColorProvider(background))
                    .padding(gap.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val level = (percent ?: 0).coerceIn(0, 100)
                if (level > 0) {
                    Box(
                        modifier = GlanceModifier
                            .size((innerWidth * level / 100f).coerceAtLeast(2f).dp, (heightDp - 2 * (stroke + gap)).dp)
                            .background(ColorProvider(fill)),
                    ) {}
                }
            }
        }
        // Tip
        Box(
            modifier = GlanceModifier
                .size((heightDp * 0.12f).dp, (heightDp * 0.4f).dp)
                .background(ColorProvider(Color.White)),
        ) {}
    }
}
