/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings

import com.android.internal.graphics.EdgeLightRenderer
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import androidx.preference.Preference

class EdgeLightSettings : SettingsPreferenceFragment() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.edge_light_settings)
    }

    override fun onResume() {
        super.onResume()
        WATCHED_KEYS.forEach {
            requireContext().contentResolver.registerContentObserver(
                Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL,
            )
        }
        updateControls()
    }

    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(observer)
        super.onPause()
    }

    private fun updateControls() {
        val resolver = requireContext().contentResolver
        val style = EdgeLightRenderer.normalizeStyle(Settings.System.getStringForUser(
            resolver, Settings.System.EDGE_LIGHT_STYLE, UserHandle.USER_CURRENT,
        ))
        val design = EdgeLightRenderer.isDesign(style)
        val mode = Settings.System.getStringForUser(
            resolver, Settings.System.EDGE_LIGHT_COLOR_MODE, UserHandle.USER_CURRENT,
        )
        val effect = EdgeLightRenderer.normalizeEffect(Settings.System.getStringForUser(
            resolver, Settings.System.EDGE_LIGHT_ANIMATION_EFFECT, UserHandle.USER_CURRENT,
        ))
        fun show(key: String, visible: Boolean) {
            findPreference<Preference>(key)?.isVisible = visible
        }
        show("edge_light_stroke_width", EdgeLightRenderer.usesStrokeWidth(style))
        show("edge_light_animation_effect", !design)
        show("edge_light_animation_speed", design || effect != "none" || mode == COLOR_MODE_RAINBOW)
        show("edge_light_animation_direction", design)
        show("edge_light_design_length", design && style in LENGTH_STYLES)
        show("edge_light_design_size", design && style in SIZE_STYLES)
        show("edge_light_design_density", design && style != "side_lights")
        show(KEY_CUSTOM_COLOR, mode == COLOR_MODE_CUSTOM)
        val display = Settings.System.getIntForUser(
            resolver, Settings.System.EDGE_LIGHT_DISPLAY_MODE, 0, UserHandle.USER_CURRENT,
        )
        show("edge_light_trigger_unlock_only", display == 1 || display == 2)
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val KEY_CUSTOM_COLOR = "edge_light_custom_color"
        private const val COLOR_MODE_CUSTOM = "custom"
        private const val COLOR_MODE_RAINBOW = "rainbow"

        private val WATCHED_KEYS = arrayOf(
            "edge_light_style", "edge_light_color_mode", "edge_light_display_mode",
            "edge_light_animation_effect",
        )
        private val LENGTH_STYLES = setOf(
            "chase_around", "mirror_rain", "corner_out", "fire_dance", "side_ripples",
            "flat_ripples", "flat_streaks", "side_streaks", "waves_around", "lights_around",
            "side_lights", "snow_particles", "bubble_particles", "dot_particles",
        )
        private val SIZE_STYLES = setOf(
            "fire_dance", "flat_bars", "side_bars", "bars_around", "flat_flutter",
            "side_flutter", "flat_waves", "side_waves", "waves_around", "strings_around",
            "side_braids", "side_lights", "serial_lights", "serials_around", "snow_particles",
            "bubble_particles", "dot_particles",
        )

        @JvmStatic
        fun reset(context: Context) {
            val resolver = context.contentResolver
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_ENABLED, 0, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_COLOR_MODE, "accent", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_CUSTOM_COLOR, Color.WHITE, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_PULSE_COUNT, 1, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_STROKE_WIDTH, 8, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_STYLE, "default", UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_ANIMATION_EFFECT, "none", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_ANIMATION_SPEED, 100, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_DESIGN_LENGTH, 35, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_DESIGN_SIZE, 30, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_DESIGN_DENSITY, 50, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_ANIMATION_DIRECTION, 0, UserHandle.USER_CURRENT)
        }
    }
}
