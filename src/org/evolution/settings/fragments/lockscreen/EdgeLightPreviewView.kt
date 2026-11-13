/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: 2026 The Lunaris Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.android.internal.graphics.EdgeLightRenderer
import com.android.settings.R
import com.android.settingslib.Utils

class EdgeLightPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val renderer = EdgeLightRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { readSettingsAndStart() }
    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(refresh)
            mainHandler.post(refresh)
        }
    }
    private var registered = false
    private var pulseAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null
    private var progress = 0f
    private var strokeWidth = 8
    private var pulseCount = 3
    private var speed = 100
    private var length = 35
    private var size = 30
    private var density = 50
    private var direction = 0
    private var spread = 0f
    private var intensity = 0f
    private var style = "default"
    private var effect = "none"
    private var rainbow = false
    private var color = Color.WHITE
    private val cornerRadii = FloatArray(8) {
        (resources.getDimension(R.dimen.edge_light_preview_shell_corner_radius) -
            resources.getDimension(R.dimen.edge_light_preview_shell_content_inset)).coerceAtLeast(0f)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            WATCHED_KEYS.forEach {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL,
                )
            }
            registered = true
        }
        mainHandler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(refresh)
        if (registered) context.contentResolver.unregisterContentObserver(observer)
        registered = false
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        mainHandler.removeCallbacks(refresh)
        if (isVisible && isAttachedToWindow) mainHandler.post(refresh)
        else stopAnimations()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        mainHandler.removeCallbacks(refresh)
        if (visibility == View.VISIBLE && isAttachedToWindow) mainHandler.post(refresh)
        else stopAnimations()
    }

    private fun readSettingsAndStart() {
        stopAnimations()
        if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE) return
        val resolver = context.contentResolver
        fun int(key: String, default: Int) =
            Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)
        fun string(key: String, default: String) =
            Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: default
        strokeWidth = int("edge_light_stroke_width", 8).coerceIn(2, 32)
        pulseCount = int("edge_light_pulse_count", 3).coerceIn(1, 5)
        speed = int("edge_light_animation_speed", 100).coerceIn(25, 200)
        length = int("edge_light_design_length", 35).coerceIn(5, 100)
        size = int("edge_light_design_size", 30).coerceIn(5, 100)
        density = int("edge_light_design_density", 50).coerceIn(10, 100)
        direction = int("edge_light_animation_direction", 0).takeIf { it in 0..1 } ?: 0
        spread = (int("edge_light_spread", 0) / 100f).coerceIn(0f, 0.6f)
        intensity = (int("edge_light_intensity", 0) / 100f).coerceIn(0f, 1f)
        style = EdgeLightRenderer.normalizeStyle(string("edge_light_style", "default"))
        effect = EdgeLightRenderer.normalizeEffect(string("edge_light_animation_effect", "none"))
        val mode = string("edge_light_color_mode", "accent")
        rainbow = mode == "rainbow"
        val accent = Utils.getColorAccentDefaultColor(context)
        color = when (mode) {
            "custom" -> int("edge_light_custom_color", Color.WHITE)
            "wallpaper" -> try {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() ?: accent
            } catch (_: Exception) { accent }
            else -> accent
        }
        if (EdgeLightRenderer.isDesign(style) || effect != "none" || rainbow) {
            motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2000L * 100 / speed
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = resources.getInteger(R.integer.edge_light_preview_pulse_duration_ms).toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                alpha = EdgeLightRenderer.pulseAlpha(it.animatedValue as Float, pulseCount)
            }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width.toFloat() / resources.displayMetrics.widthPixels.coerceAtLeast(1)
        renderer.configure(
            style, effect, strokeWidth * resources.displayMetrics.density * scale,
            length, size, density, direction, spread, intensity,
        )
        renderer.setColor(color, rainbow)
        renderer.setBounds(width.toFloat(), height.toFloat(), cornerRadii)
        val save = canvas.save()
        val hole = resources.getDimension(R.dimen.edge_light_preview_punchhole_size)
        val top = resources.getDimension(R.dimen.edge_light_preview_punchhole_margin_top)
        canvas.clipOutRect((width - hole) / 2, top, (width + hole) / 2, top + hole)
        renderer.draw(canvas, progress)
        canvas.restoreToCount(save)
    }

    private fun stopAnimations() {
        pulseAnimator?.removeAllUpdateListeners()
        pulseAnimator?.cancel()
        pulseAnimator = null
        motionAnimator?.removeAllUpdateListeners()
        motionAnimator?.cancel()
        motionAnimator = null
        progress = 0f
        alpha = 0f
    }

    companion object {
        private val WATCHED_KEYS = arrayOf(
            "edge_light_color_mode", "edge_light_custom_color", "edge_light_pulse_count",
            "edge_light_stroke_width", "edge_light_style", "edge_light_animation_effect",
            "edge_light_spread", "edge_light_intensity", "edge_light_animation_speed",
            "edge_light_design_length", "edge_light_design_size", "edge_light_design_density",
            "edge_light_animation_direction",
        )
    }
}
