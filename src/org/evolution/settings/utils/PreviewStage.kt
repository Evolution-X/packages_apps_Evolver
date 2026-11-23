/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.settings.R

/**
 * The rounded box the pinned live previews are drawn in. It owns the corner radius, so every
 * preview stage is clipped the same way; the height comes from the preview_stage_height_*
 * dimens and the padding around it from PreviewStageHost.
 *
 * With app:previewBackdrop="wallpaper" the lock screen wallpaper is drawn behind the content,
 * dimmed by preview_stage_scrim. The stage's own background shows until the wallpaper is
 * loaded, and stays if there is none.
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

        val a = context.obtainStyledAttributes(attrs, R.styleable.PreviewStage)
        val backdrop = try {
            a.getInt(R.styleable.PreviewStage_previewBackdrop, BACKDROP_NONE)
        } finally {
            a.recycle()
        }
        if (backdrop == BACKDROP_WALLPAPER) {
            // Added here, so they sit under whatever the layout adds afterwards.
            addView(LockscreenWallpaperView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply {
                setBackgroundColor(context.getColor(R.color.preview_stage_scrim))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private companion object {
        const val BACKDROP_NONE = 0
        const val BACKDROP_WALLPAPER = 1
    }
}
