/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.graphics.Typeface

/** What a font looks like, for the filter chips on the font tab. */
enum class FontCategory { SANS, SERIF, DISPLAY, SCRIPT, MONO }

/**
 * One card of the font and emoji picker: a bundled or installed style, or the custom font. The
 * [typeface] is what the card and the preview are drawn in, null when it could not be loaded. A
 * font without a [category] only shows under All.
 */
data class StyleOption(
    val key: String,
    val label: String,
    val typeface: Typeface?,
    val category: FontCategory? = null,
    val download: DownloadInfo? = null
)

/** An online style: its download [sizeBytes], and whether the file is on the device yet. */
data class DownloadInfo(val sizeBytes: Long, val downloaded: Boolean)
