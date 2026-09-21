/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import android.graphics.Color as GraphicsColor
import androidx.compose.ui.graphics.Color

fun Color.toArgb(): Int {
    return GraphicsColor.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}

fun Color.toHexString(): String {
    return String.format("%06X", 0xFFFFFF and toArgb())
}
