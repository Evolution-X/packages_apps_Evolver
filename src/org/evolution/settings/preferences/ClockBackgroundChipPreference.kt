/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.settings.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ClockBackgroundChipPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null

    private val pm: PackageManager = context.packageManager
    private val sysUiRes: Resources? = try {
        pm.getResourcesForApplication(SYSTEMUI_PACKAGE)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val chipDrawables: Array<String> =
        context.resources.getStringArray(R.array.statusbar_clock_chip_drawables)

    private val chipLabels: Array<String> = run {
        val labels = context.resources.getStringArray(R.array.statusbar_clock_chip_labels)
        if (labels.size == chipDrawables.size) labels else chipDrawables
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        super.onDetached()
    }

    override fun onClick() {
        dialog?.dismiss()

        val selectedIndex = getCurrentValue().let { if (it in chipDrawables.indices) it else 0 }
        val time = pickerClockText(context)
        val options = chipLabels.indices.map { index ->
            val chip = getChipDrawable(index)
            val gradient = if (index == CHIP_STYLE_CUSTOM_GRADIENT) readGradient() else null
            val textColor = gradient?.textColor ?: chipTextColor(index)
            PickerOption(labelForIndex(index).orEmpty()) {
                ClockChipPreview(time, chip, textColor, gradient)
            }
        }

        dialog = context.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            onClosed = { dialog = null },
        ) { position ->
            if (position != selectedIndex) {
                if (!callChangeListener(position)) return@showPickerDialog false
                Settings.System.putIntForUser(
                    context.contentResolver,
                    SETTING_KEY,
                    position,
                    UserHandle.USER_CURRENT
                )
                summary = labelForIndex(position).orEmpty()
            }
            true
        }
    }

    private fun getCurrentValue(): Int = readInt(SETTING_KEY, 0)

    private fun readInt(key: String, def: Int): Int =
        Settings.System.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    /** Current custom gradient settings, resolved the way SystemUI's chip drawable does. */
    private fun readGradient(): GradientChip {
        val primary = context.getColor(com.android.internal.R.color.materialColorPrimary)
        val secondary = context.getColor(com.android.internal.R.color.materialColorSecondary)
        var start = primary
        var end = secondary
        if (readInt(KEY_GRADIENT_COLOR_MODE, COLOR_MODE_AUTO) == COLOR_MODE_CUSTOM) {
            val customStart = readInt(KEY_GRADIENT_START_COLOR, DEFAULT_GRADIENT_START_COLOR)
            val customEnd = readInt(KEY_GRADIENT_END_COLOR, DEFAULT_GRADIENT_END_COLOR)
            if (customStart != 0) start = customStart
            if (customEnd != 0) end = customEnd
        }
        val mask = readInt(KEY_GRADIENT_MASK_TEXT, 0) == GRADIENT_TEXT_STYLE_MASK
        return GradientChip(
            start = Color(start),
            end = Color(end),
            angle = readInt(KEY_GRADIENT_ANGLE, 0).toFloat(),
            mask = mask,
            textColor = if (mask) Color.White else contrastingTextColor(start, end),
        )
    }

    /** Mirrors SystemUI's chip text colour choice: white or black, whichever contrasts better. */
    private fun contrastingTextColor(start: Int, end: Int): Color {
        val mid = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(start, end, 0.5f), 255)
        val white = ColorUtils.calculateContrast(Color.White.toArgb(), mid)
        val black = ColorUtils.calculateContrast(Color.Black.toArgb(), mid)
        return if (white >= black) Color.White else Color.Black
    }

    private fun updateSummary() {
        val label = labelForIndex(getCurrentValue()).orEmpty()
        summary = label
    }

    private fun labelForIndex(index: Int): String? {
        if (index < 0 || index >= chipLabels.size) return null
        return chipLabels[index]
    }

    private fun getChipDrawable(styleIndex: Int): Drawable? {
        val res = sysUiRes ?: return null
        if (styleIndex <= 0 || styleIndex >= chipDrawables.size) return null
        if (styleIndex == CHIP_STYLE_CUSTOM_GRADIENT) return null

        val name = chipDrawables[styleIndex]
        val id = res.getIdentifier(name, "drawable", SYSTEMUI_PACKAGE)
        if (id == 0) return null

        return try {
            res.getDrawable(id, context.theme)
        } catch (_: Throwable) {
            null
        }
    }

    /** Null keeps the row's own content colour. */
    private fun chipTextColor(styleIndex: Int): Color? = when (styleIndex) {
        0, 2, 8 -> null
        in LIGHT_CHIP_STYLES -> CHIP_TEXT_DARK
        else -> Color.White
    }

    companion object {
        private const val SETTING_KEY = "statusbar_clock_chip"
        private const val KEY_GRADIENT_COLOR_MODE = "statusbar_clock_chip_gradient_color_mode"
        private const val KEY_GRADIENT_START_COLOR = "statusbar_clock_chip_gradient_start_color"
        private const val KEY_GRADIENT_END_COLOR = "statusbar_clock_chip_gradient_end_color"
        private const val KEY_GRADIENT_ANGLE = "statusbar_clock_chip_gradient_angle"
        private const val KEY_GRADIENT_MASK_TEXT = "statusbar_clock_chip_gradient_mask_text"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // Keep in sync with SystemUI's HomeStatusBarViewBinder and StatusBar.java.
        private const val CHIP_STYLE_CUSTOM_GRADIENT = 13
        private const val COLOR_MODE_AUTO = 0
        private const val COLOR_MODE_CUSTOM = 1
        private const val GRADIENT_TEXT_STYLE_MASK = 1
        private const val DEFAULT_GRADIENT_START_COLOR = 0xFFFF6B6B.toInt()
        private const val DEFAULT_GRADIENT_END_COLOR = 0xFF4ECDC4.toInt()

        // Chip styles with a light/near-white center that need dark clock text.
        // Keep in sync with SystemUI's HomeStatusBarViewBinder.
        private val LIGHT_CHIP_STYLES = setOf(5)
        private val CHIP_TEXT_DARK = Color(0xFF1B1B1F)
    }
}

/** The custom gradient chip with the user's current colours, angle and text style. */
private class GradientChip(
    val start: Color,
    val end: Color,
    val angle: Float,
    val mask: Boolean,
    val textColor: Color,
)

/**
 * The clock as it looks on a status bar chip: [chip] (or the custom [gradient]) drawn behind the
 * time. A masked gradient punches the text out of the chip, like SystemUI's mask mode.
 */
@Composable
private fun ClockChipPreview(
    time: String,
    chip: Drawable?,
    textColor: Color?,
    gradient: GradientChip? = null,
) {
    val base = Modifier.width(84.dp)
    val chipModifier = when {
        gradient == null -> base.drawBehind {
            chip?.let { drawable ->
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
        }
        gradient.mask -> base
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawGradientChip(gradient)
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(size.toRect(), Paint().apply { blendMode = BlendMode.DstOut })
                    this@drawWithContent.drawContent()
                    canvas.restore()
                }
            }
        else -> base.drawBehind { drawGradientChip(gradient) }
    }
    Text(
        text = time,
        style = MaterialTheme.typography.titleMedium,
        color = textColor ?: LocalContentColor.current,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = chipModifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Pill with a linear gradient using the GradientDrawable angle convention (0° = left to right,
 * counter-clockwise), same as SystemUI's ClockChipGradientDrawable.
 */
private fun DrawScope.drawGradientChip(gradient: GradientChip) {
    val rad = Math.toRadians((((gradient.angle % 360f) + 360f) % 360f).toDouble())
    val dx = cos(rad).toFloat()
    val dy = -sin(rad).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minOf(size.width, size.height) / 2f
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(gradient.start, gradient.end),
            start = Offset(center.x - dx * half, center.y - dy * half),
            end = Offset(center.x + dx * half, center.y + dy * half),
        ),
        cornerRadius = CornerRadius(radius, radius),
    )
}
