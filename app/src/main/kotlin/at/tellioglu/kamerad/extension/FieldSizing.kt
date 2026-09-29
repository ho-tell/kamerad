package at.tellioglu.kamerad.extension

import android.content.Context
import io.hammerhead.karooext.models.ViewConfig

/**
 * Largest font size (sp) for a single line of text that fits into the field.
 *
 * @param reservedHeightDp height taken by other content (header, subtitle, padding)
 * @param widthEm width of the text in multiples of the font size
 * @param lineHeight height of a text line in multiples of the font size (incl. font padding)
 */
fun fitFontSize(
    context: Context,
    config: ViewConfig,
    reservedHeightDp: Float,
    widthEm: Float,
    horizontalPaddingDp: Float,
    lineHeight: Float = 1.2f,
): Float {
    val metrics = context.resources.displayMetrics
    val fontScale = context.resources.configuration.fontScale
    val widthDp = config.viewSize.first / metrics.density
    val heightDp = config.viewSize.second / metrics.density
    // sp == dp at font scale 1
    val byHeight = (heightDp - reservedHeightDp) / lineHeight / fontScale
    val byWidth = (widthDp - horizontalPaddingDp) / widthEm / fontScale
    return minOf(config.textSize.toFloat(), byHeight, byWidth).coerceAtLeast(10f)
}
