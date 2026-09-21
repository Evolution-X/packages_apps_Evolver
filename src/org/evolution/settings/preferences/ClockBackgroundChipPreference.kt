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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.settings.R

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
            val textColor = chipTextColor(index)
            PickerOption(labelForIndex(index).orEmpty()) {
                ClockChipPreview(time, chip, textColor)
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

    private fun getCurrentValue(): Int {
        return Settings.System.getIntForUser(
            context.contentResolver,
            SETTING_KEY,
            0,
            UserHandle.USER_CURRENT
        )
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
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"

        // Chip styles with a light/near-white center that need dark clock text.
        // Keep in sync with SystemUI's HomeStatusBarViewBinder.
        private val LIGHT_CHIP_STYLES = setOf(5)
        private val CHIP_TEXT_DARK = Color(0xFF1B1B1F)
    }
}

/** The clock as it looks on a status bar chip: [chip] drawn behind the time. */
@Composable
private fun ClockChipPreview(time: String, chip: Drawable?, textColor: Color?) {
    Text(
        text = time,
        style = MaterialTheme.typography.titleMedium,
        color = textColor ?: LocalContentColor.current,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .width(84.dp)
            .drawBehind {
                chip?.let { drawable ->
                    drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    drawIntoCanvas { drawable.draw(it.nativeCanvas) }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
