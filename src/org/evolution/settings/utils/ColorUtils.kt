package org.evolution.settings.utils

import android.graphics.Color as GraphicsColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

fun Color.toArgb(): Int {
    return GraphicsColor.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}

fun Color.getContentColor(): Color {
    return if (luminance() > 0.5f) Color.Black else Color.White
}

fun String.parseColorHex(): Color? {
    return try {
        Color(GraphicsColor.parseColor(if (startsWith("#")) this else "#$this"))
    } catch (e: Exception) {
        null
    }
}

fun Color.toHexString(): String {
    return String.format("%06X", 0xFFFFFF and toArgb())
}
