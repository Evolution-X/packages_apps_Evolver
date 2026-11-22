/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.ListPreference
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.evolution.VibrationUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.Utils
import com.android.settingslib.search.SearchIndexable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

import org.evolution.settings.fragments.lockscreen.chargingstyles.ChargingStyleView
import org.evolution.settings.fragments.lockscreen.chargingstyles.HyperBubblesView
import org.evolution.settings.fragments.lockscreen.chargingstyles.OneUiCapsuleView
import org.evolution.settings.fragments.lockscreen.chargingstyles.OriginOrbView
import org.evolution.settings.fragments.lockscreen.chargingstyles.SuperChargeView
import org.evolution.settings.preferences.SystemSettingSeekBarPreference

@SearchIndexable
class ChargingAnimationSettings : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var animationStylePref: ListPreference? = null
    private var rippleOpacityPref: SystemSettingSeekBarPreference? = null
    private var glowIntensityPref: SystemSettingSeekBarPreference? = null
    private var arcCountPref: SystemSettingSeekBarPreference? = null

    private var plugAnimationPref: ListPreference? = null

    private var preview: ChargingPreviewView? = null
    private var plugStage: FrameLayout? = null
    private var plugHint: TextView? = null
    private var styleChips: ChipGroup? = null
    private var modeLock: Chip? = null
    private var modePlug: Chip? = null
    private var plugMode = false
    private var started = false
    private var plugPlaying = false
    private var plugView: ChargingStyleView? = null
    private var previewPlug = -1
    private var syncingChips = false
    private val plugHandler = Handler(Looper.getMainLooper())
    private var previewStyle = -1
    private var previewColorMode = -1
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewObserver = object : ContentObserver(previewHandler) {
        override fun onChange(selfChange: Boolean) {
            syncPreview()
        }
    }

    companion object {
        const val TAG = "ChargingAnimationSettings"

        private const val STYLE_BUBBLE_STREAM = 0
        private const val STYLE_NEON = 1
        private const val STYLE_BEAM = 2
        private const val STYLE_PLASMA = 3
        private const val STYLE_QUANTUM_SPARKS = 4
        private const val STYLE_NEBULA = 5
        private const val STYLE_DIGITAL_MATRIX = 6
        private const val STYLE_GEOFLOW = 7

        private const val KEY_STYLE = "charging_animation_style"
        private const val KEY_COLOR_MODE = "charging_color_mode"
        private const val KEY_RIPPLE_OPACITY = "charging_ripple_opacity"
        private const val KEY_GLOW_INTENSITY = "charging_glow_intensity"
        private const val KEY_ARC_COUNT = "charging_arc_count"
        private const val KEY_PLUG = "charging_plug_animation"
        private const val PREVIEW_RESTART_MS = 350L
        private const val PLUG_REPLAY_MS = 900L

        // The plug-in animations are drawn for a full phone screen, so they are laid out at this
        // size and scaled down to fit the preview.
        private const val PLUG_PHONE_WIDTH_DP = 360f
        private const val PLUG_PHONE_HEIGHT_DP = 720f
        private const val PLUG_DEFAULT = 0
        private const val PLUG_ONEUI = 1
        private const val PLUG_HYPEROS = 2
        private const val PLUG_ORIGINOS = 3
        private const val PLUG_SUPERCHARGE = 4
        private val PREVIEW_KEYS = arrayOf(
            KEY_STYLE, KEY_COLOR_MODE, KEY_RIPPLE_OPACITY, KEY_GLOW_INTENSITY, KEY_ARC_COUNT,
            KEY_PLUG
        )
        
        private fun getHiddenKeysForStyle(style: Int): List<String> {
            return when (style) {
                STYLE_BUBBLE_STREAM -> listOf("charging_glow_intensity", "charging_arc_count")
                STYLE_NEON -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_BEAM -> listOf("charging_ripple_opacity", "charging_glow_intensity")
                STYLE_PLASMA -> emptyList()
                STYLE_QUANTUM_SPARKS -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_NEBULA -> listOf("charging_arc_count")
                STYLE_DIGITAL_MATRIX -> listOf("charging_ripple_opacity", "charging_arc_count")
                STYLE_GEOFLOW -> listOf("charging_glow_intensity", "charging_arc_count")
                else -> emptyList()
            }
        }

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider(R.xml.charging_animation_settings) {
            override fun getNonIndexableKeys(context: Context): List<String> {
                val keys = super.getNonIndexableKeys(context).toMutableList()
                val style = Settings.System.getInt(
                    context.contentResolver,
                    "charging_animation_style",
                    STYLE_PLASMA
                )

                keys.addAll(getHiddenKeysForStyle(style))
                
                return keys
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.charging_animation_settings)

        animationStylePref = findPreference("charging_animation_style")
        rippleOpacityPref = findPreference("charging_ripple_opacity")
        glowIntensityPref = findPreference("charging_glow_intensity")
        arcCountPref = findPreference("charging_arc_count")
        plugAnimationPref = findPreference(KEY_PLUG)

        animationStylePref?.onPreferenceChangeListener = this

        updatePreferenceVisibility()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Pinned above the list, so the preview stays in view while the options scroll.
        val header = setPinnedHeaderView(R.layout.charging_animation_preview)
        preview = header.findViewById(R.id.charging_preview)
        plugStage = header.findViewById(R.id.charging_plug_stage)
        plugHint = header.findViewById(R.id.charging_plug_hint)
        styleChips = header.findViewById(R.id.charging_style_chips)
        modeLock = header.findViewById(R.id.charging_mode_lock)
        modePlug = header.findViewById(R.id.charging_mode_plug)

        // The two mode chips act as a toggle: one is always on.
        modeLock?.setOnCheckedChangeListener { _, checked ->
            if (checked && plugMode) {
                setPlugMode(false)
            } else if (!checked && !plugMode) {
                modeLock?.isChecked = true
            }
        }
        modePlug?.setOnCheckedChangeListener { _, checked ->
            if (checked && !plugMode) {
                setPlugMode(true)
            } else if (!checked && plugMode) {
                modePlug?.isChecked = true
            }
        }
        plugMode = false
        rebuildChips()
    }

    private fun setPlugMode(plug: Boolean) {
        plugMode = plug
        modeLock?.isChecked = !plug
        modePlug?.isChecked = plug
        preview?.visibility = if (plug) View.GONE else View.VISIBLE
        plugStage?.visibility = if (plug) View.VISIBLE else View.GONE
        plugHint?.visibility = View.GONE
        rebuildChips()
        if (!started) return
        if (plug) {
            preview?.hide()
            previewPlug = readInt(KEY_PLUG, PLUG_DEFAULT)
            startPlug()
        } else {
            stopPlug()
            preview?.show()
        }
    }

    // One chip per style of whichever page is being edited, so a style can be picked and
    // watched without scrolling down to the list.
    private fun rebuildChips() {
        val group = styleChips ?: return
        val entries = resources.getStringArray(
            if (plugMode) R.array.charging_plug_animation_entries
            else R.array.charging_animation_style_entries
        )
        syncingChips = true
        group.removeAllViews()
        val inflater = LayoutInflater.from(group.context)
        entries.forEachIndexed { index, title ->
            val chip = inflater.inflate(R.layout.charging_style_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = title
            chip.tag = index
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked && !syncingChips) pickStyle(index)
            }
            group.addView(chip)
        }
        syncingChips = false
        syncChips()
    }

    private fun syncChips() {
        val group = styleChips ?: return
        val current = if (plugMode) readInt(KEY_PLUG, PLUG_DEFAULT).coerceIn(0, 4)
        else readInt(KEY_STYLE, 0).coerceIn(0, 7)
        syncingChips = true
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            val selected = chip.tag == current
            if (chip.isChecked != selected) chip.isChecked = selected
        }
        syncingChips = false
    }

    // Goes through the list preference, so what it shows and the options that depend on the
    // style follow the chip as they would follow the list.
    private fun pickStyle(index: Int) {
        val pref = (if (plugMode) plugAnimationPref else animationStylePref) ?: return
        val value = index.toString()
        if (value == pref.value || !pref.callChangeListener(value)) return
        pref.value = value
        if (!plugMode) updatePreferenceVisibility()
    }

    private fun startPlug() {
        stopPlug()
        val stage = plugStage ?: return
        // The stage has no size until it has been laid out after becoming visible.
        stage.post {
            if (!started || !plugMode || plugPlaying) return@post
            val style = readInt(KEY_PLUG, PLUG_DEFAULT)
            val view = plugStyleView(style)
            plugHint?.visibility = if (view == null) View.VISIBLE else View.GONE
            if (view == null || stage.height <= 0) return@post

            val density = resources.displayMetrics.density
            val phoneWidth = (PLUG_PHONE_WIDTH_DP * density).toInt()
            val phoneHeight = (PLUG_PHONE_HEIGHT_DP * density).toInt()
            val level = requireContext()
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50
            view.level = level
            view.speed = ChargingStyleView.SPEED_NORMAL
            view.label = getString(R.string.charging_plug_preview_label)
            view.pivotX = phoneWidth / 2f
            view.pivotY = phoneHeight / 2f
            val scale = stage.height / phoneHeight.toFloat()
            view.scaleX = scale
            view.scaleY = scale
            stage.addView(view, FrameLayout.LayoutParams(phoneWidth, phoneHeight, Gravity.CENTER))
            plugView = view
            plugPlaying = true
            view.play {
                if (plugPlaying) plugHandler.postDelayed({ startPlug() }, PLUG_REPLAY_MS)
            }
        }
    }

    private fun stopPlug() {
        plugPlaying = false
        plugHandler.removeCallbacksAndMessages(null)
        plugView?.let { plugStage?.removeView(it) }
        plugView = null
    }

    private fun plugStyleView(style: Int): ChargingStyleView? {
        val context = requireContext()
        return when (style) {
            PLUG_ONEUI -> OneUiCapsuleView(context)
            PLUG_HYPEROS -> HyperBubblesView(context)
            PLUG_ORIGINOS -> OriginOrbView(context)
            PLUG_SUPERCHARGE -> SuperChargeView(context)
            else -> null
        }
    }

    override fun onStart() {
        super.onStart()
        for (key in PREVIEW_KEYS) {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, previewObserver
            )
        }
        previewStyle = -1
        previewColorMode = -1
        previewPlug = readInt(KEY_PLUG, PLUG_DEFAULT)
        started = true
        syncPreview()
        if (plugMode) startPlug() else preview?.show()
    }

    override fun onStop() {
        super.onStop()
        contentResolver.unregisterContentObserver(previewObserver)
        previewHandler.removeCallbacksAndMessages(null)
        started = false
        stopPlug()
        preview?.hide()
    }

    // The preview plays whether or not the animation is switched on, so a style can be
    // judged before it is enabled. A new style or color mode restarts it, as the view only
    // starts its animators when it is shown.
    private fun syncPreview() {
        val view = preview ?: return
        val style = readInt(KEY_STYLE, 0).coerceIn(0, 7)
        val colorMode = readInt(KEY_COLOR_MODE, 0).coerceIn(0, 2)
        val level = requireContext()
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50

        view.accentColor = Utils.getColorAccentDefaultColor(requireContext())
        view.batteryLevel = level
        view.animationStyle = style
        view.colorMode = colorMode
        view.rippleOpacity = readInt(KEY_RIPPLE_OPACITY, 60) / 100f
        view.glowIntensity = readInt(KEY_GLOW_INTENSITY, 80) / 100f
        view.arcCount = readInt(KEY_ARC_COUNT, 3)

        syncChips()
        val plug = readInt(KEY_PLUG, PLUG_DEFAULT)
        if (plug != previewPlug) {
            previewPlug = plug
            if (plugMode) startPlug()
        }

        val restart = !plugMode && previewStyle != -1 &&
            (style != previewStyle || colorMode != previewColorMode)
        previewStyle = style
        previewColorMode = colorMode
        if (restart) {
            previewHandler.removeCallbacksAndMessages(null)
            view.hide()
            previewHandler.postDelayed({ view.show() }, PREVIEW_RESTART_MS)
        }
    }

    private fun readInt(key: String, default: Int): Int =
        Settings.System.getIntForUser(contentResolver, key, default, UserHandle.USER_CURRENT)

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key != null) {
            VibrationUtils.triggerVibration(context, 3)
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference.key == "charging_animation_style") {
            updatePreferenceVisibility()
        }
        return true
    }

    private fun updatePreferenceVisibility() {
        val style = Settings.System.getInt(
            contentResolver,
            "charging_animation_style",
            STYLE_PLASMA
        )

        when (style) {
            STYLE_BUBBLE_STREAM -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = false
            }
            STYLE_NEON -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_BEAM -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = true
            }
            STYLE_PLASMA -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = true
            }
            STYLE_QUANTUM_SPARKS -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_NEBULA -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_DIGITAL_MATRIX -> {
                rippleOpacityPref?.isVisible = false
                glowIntensityPref?.isVisible = true
                arcCountPref?.isVisible = false
            }
            STYLE_GEOFLOW -> {
                rippleOpacityPref?.isVisible = true
                glowIntensityPref?.isVisible = false
                arcCountPref?.isVisible = false
            }
        }
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.EVOLVER
    }
}
