/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.settings.R

class QSTileIconShapePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null

    private val entries: Array<String> =
        context.resources.getStringArray(R.array.qs_tile_icon_shape_entries)

    private val entryValues: Array<String> =
        context.resources.getStringArray(R.array.qs_tile_icon_shape_values)

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

        val selectedIndex = indexOfValue(getCurrentShapeKey()).coerceAtLeast(0)
        val options = entryValues.mapIndexed { index, key ->
            PickerOption(entries[index]) { TileShapeIcon(QSTileIconShapes.pathForKey(key)) }
        }

        dialog = context.showPickerDialog(title, options, selectedIndex) { position ->
            val shapeKey = entryValues[position]
            if (position != selectedIndex) {
                if (!callChangeListener(shapeKey)) return@showPickerDialog false
                Settings.System.putString(context.contentResolver, SETTING_KEY, shapeKey)
                summary = entries[position]
            }
            true
        }
    }

    private fun getCurrentShapeKey(): String {
        val raw = Settings.System.getString(
            context.contentResolver,
            SETTING_KEY
        )
        return if (raw != null && QSTileIconShapes.isKnownKey(raw)) raw else QSTileIconShapes.DEFAULT_KEY
    }

    private fun indexOfValue(key: String): Int =
        entryValues.indexOfFirst { it == key }

    private fun updateSummary() {
        val idx = indexOfValue(getCurrentShapeKey())
        summary = if (idx >= 0) entries[idx] else entries.firstOrNull().orEmpty()
    }

    companion object {
        private const val SETTING_KEY = "qs_tile_icon_shape"
    }
}

/** Tile shape from a 100x100 SVG path, drawn in the current content colour. */
@Composable
private fun TileShapeIcon(pathData: String) {
    val path = remember(pathData) {
        runCatching { PathParser().parsePathString(pathData).toPath() }.getOrElse {
            PathParser()
                .parsePathString(QSTileIconShapes.pathForKey(QSTileIconShapes.DEFAULT_KEY))
                .toPath()
        }
    }
    val color = LocalContentColor.current
    Canvas(Modifier.size(32.dp)) {
        val factor = size.minDimension / 100f
        scale(factor, factor, pivot = Offset.Zero) { drawPath(path, color) }
    }
}
