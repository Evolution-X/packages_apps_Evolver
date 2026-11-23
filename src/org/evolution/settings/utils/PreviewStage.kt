/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.android.settings.R

/**
 * The rounded box the pinned live previews are drawn in. It owns the corner radius, so every
 * preview stage is clipped the same way; the height comes from the preview_stage_height_*
 * dimens and the padding around it from Widget.Evolver.PreviewStageHost.
 */
class PreviewStage @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        val radius = resources.getDimension(R.dimen.preview_stage_corner_radius)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }
}
