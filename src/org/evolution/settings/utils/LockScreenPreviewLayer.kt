/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.settings.R
import kotlin.math.max
import kotlin.math.min

/**
 * A lock screen at the size of this device's own screen, scaled down to the width of the layer
 * and cropped to its bottom edge. Its children are laid out at the real screen size, so an
 * effect drawn from its width and height (such as the charging animation, which rises from the
 * bottom edge and is sized as a share of the screen) plays here the way it does on the lock
 * screen. The lock screen wallpaper and a scrim are drawn behind them.
 */
class LockScreenPreviewLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val screenWidth: Int
    private val screenHeight: Int

    init {
        val bounds = context.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        screenWidth = min(bounds.width(), bounds.height())
        screenHeight = max(bounds.width(), bounds.height())
        clipChildren = true

        // Added here, so they sit under whatever the layout adds afterwards.
        addView(LockscreenWallpaperView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.preview_stage_scrim))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        val w = MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.EXACTLY)
        val h = MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(w, h)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = (right - left) / screenWidth.toFloat()
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                layout(0, 0, screenWidth, screenHeight)
                // Scale towards the bottom left corner and sit it on the bottom edge.
                pivotX = 0f
                pivotY = screenHeight.toFloat()
                scaleX = scale
                scaleY = scale
                translationY = (bottom - top - screenHeight).toFloat()
            }
        }
    }
}
