/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package org.evolution.settings.fragments.statusbar

import android.content.ContentResolver
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import org.evolution.settings.utils.toArgb
import org.evolution.settings.utils.toHexString

@SearchIndexable
class CutoutProgressSettingsFragment : SettingsPreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    companion object {
        private const val KEY_MUSIC_RING_ENABLED = "cutout_progress_music_enabled"
        private const val KEY_TIMER_ENABLED = "cutout_progress_timer_enabled"
        private const val KEY_TIMER_FLAME_ENABLED = "cutout_progress_timer_flame_enabled"
        private const val KEY_TIMER_FLAME_SIZE = "cutout_progress_timer_flame_size_dp10"
        private const val KEY_AURORA_ENABLED = "cutout_progress_aurora_enabled"
        private const val KEY_AURORA_NOTIFICATIONS = "cutout_progress_aurora_notifications"
        private const val KEY_AURORA_NOTIFICATION_DURATION =
            "cutout_progress_aurora_notification_duration_ms"
        private const val KEY_MUSIC_COLOR_MODE = "cutout_progress_music_color_mode"
        private const val KEY_MUSIC_CUSTOM_COLOR = "cutout_progress_music_custom_color"

        private const val KEY_RING_COLOR_MODE = "cutout_progress_ring_color_mode"
        private const val COLOR_MODE_ACCENT = 0
        private const val COLOR_MODE_RAINBOW = 1
        private const val COLOR_MODE_CUSTOM = 2

        private const val MUSIC_COLOR_MODE_ALBUM_ICON = 0
        private const val MUSIC_COLOR_MODE_ACCENT = 1
        private const val MUSIC_COLOR_MODE_ALBUM_ART = 2
        private const val MUSIC_COLOR_MODE_CUSTOM = 3
        private const val PRESENTATION_PRIMARY = 0
        private const val PRESENTATION_INDEPENDENT = 1

        private const val KEY_RING_COLOR = "cutout_progress_ring_color"
        private const val KEY_RING_GAP = "cutout_progress_ring_gap_x1000"
        private const val KEY_ERROR_COLOR = "cutout_progress_error_color"
        private const val KEY_FLASH_COLOR = "cutout_progress_finish_flash_color"
        private const val KEY_BG_COLOR = "cutout_progress_bg_ring_color"
        private const val KEY_FINISH_STYLE = "cutout_progress_finish_style"
        private const val KEY_EASING = "cutout_progress_easing"
        private const val KEY_PERCENT_POSITION = "cutout_progress_percent_position"
        private const val KEY_FILENAME_POSITION = "cutout_progress_filename_position"
        private const val KEY_FILENAME_TRUNCATE = "cutout_progress_filename_truncate"

        private const val KEY_COMPLETION_PULSE = "cutout_progress_completion_pulse"
        private const val KEY_AUTO_GEOMETRY = "cutout_progress_auto_geometry"
        private const val KEY_PATH_MODE = "cutout_progress_path_mode"
        private const val KEY_RING_SCALE_X = "cutout_progress_ring_scale_x_x1000"
        private const val KEY_RING_SCALE_Y = "cutout_progress_ring_scale_y_x1000"
        private const val KEY_RING_OFFSET_X = "cutout_progress_ring_offset_x_dp10"
        private const val KEY_RING_OFFSET_Y = "cutout_progress_ring_offset_y_dp10"
        private const val KEY_DOWNLOAD_PRESENTATION = "cutout_progress_download_presentation"
        private const val KEY_MUSIC_PRESENTATION = "cutout_progress_music_presentation"
        private const val KEY_PRIMARY_PRIORITY = "cutout_progress_primary_priority"
        private const val KEY_MULTI_RING_SPACING = "cutout_progress_multi_ring_spacing_dp10"
        private const val KEY_MUSIC_WAVE_ENABLED = "cutout_progress_music_wave_enabled"
        private const val KEY_MUSIC_WAVE_AMPLITUDE = "cutout_progress_music_wave_amplitude_dp10"
        private const val KEY_MUSIC_WAVE_DENSITY = "cutout_progress_music_wave_density"
        private const val KEY_MUSIC_WAVE_SPEED = "cutout_progress_music_wave_speed"
        private const val KEY_TIMER_PRESENTATION = "cutout_progress_timer_presentation"
        private const val KEY_TIMER_COLOR_MODE = "cutout_progress_timer_color_mode"
        private const val KEY_TIMER_CUSTOM_COLOR = "cutout_progress_timer_custom_color"
        private const val KEY_TIMER_FLAME_COLOR = "cutout_progress_timer_flame_color"
        private const val KEY_AURORA_COLOR_MODE = "cutout_progress_aurora_color_mode"
        private const val KEY_AURORA_CUSTOM_COLOR = "cutout_progress_aurora_custom_color"
        private const val KEY_AURORA_NOTIFICATION_COLOR_MODE =
            "cutout_progress_aurora_notification_color_mode"

        private const val AURORA_COLOR_MODE_SPECTRUM = 0
        private const val AURORA_COLOR_MODE_CUSTOM = 2

        private const val DEFAULT_COMPLETION_PULSE = 1
        private const val DEFAULT_AUTO_GEOMETRY = 1
        private const val DEFAULT_PATH_MODE = 1
        private const val DEFAULT_RING_SCALE_X = 1050
        private const val DEFAULT_RING_SCALE_Y = 600
        private const val DEFAULT_RING_OFFSET_X = 0
        private const val DEFAULT_RING_OFFSET_Y = 15
        private const val DEFAULT_RING_GAP = 1160
        private const val DEFAULT_DOWNLOAD_PRESENTATION = 0
        private const val DEFAULT_MUSIC_PRESENTATION = 0
        private const val DEFAULT_PRIMARY_PRIORITY = 0
        private const val DEFAULT_MULTI_RING_SPACING = 50
        private const val DEFAULT_MUSIC_WAVE_ENABLED = 0
        private const val DEFAULT_MUSIC_WAVE_AMPLITUDE = 25
        private const val DEFAULT_MUSIC_WAVE_DENSITY = 48
        private const val DEFAULT_MUSIC_WAVE_SPEED = 100
        private const val DEFAULT_TIMER_PRESENTATION = 0
        private const val DEFAULT_TIMER_COLOR_MODE = 0
        private const val DEFAULT_AURORA_COLOR_MODE = 0
        private const val DEFAULT_AURORA_NOTIFICATION_COLOR_MODE = 0

        private const val DEFAULT_RING_COLOR = 0xFF2196F3.toInt()
        private const val DEFAULT_ERROR_COLOR = 0xFFF44336.toInt()
        private const val DEFAULT_FLASH_COLOR = 0xFFFFFFFF.toInt()
        private const val DEFAULT_BG_COLOR = 0xFF808080.toInt()
        private const val DEFAULT_MUSIC_COLOR = 0xFF9C27B0.toInt()
        private const val DEFAULT_TIMER_COLOR = 0xFFFF8A00.toInt()
        private const val DEFAULT_TIMER_FLAME_COLOR = 0xFFFF6D00.toInt()
        private const val DEFAULT_AURORA_COLOR = 0xFF7C4DFF.toInt()

        @JvmStatic
        fun ensureCalibratedDefaults(resolver: ContentResolver) {
            val defaults = mapOf(
                KEY_COMPLETION_PULSE to DEFAULT_COMPLETION_PULSE,
                KEY_AUTO_GEOMETRY to DEFAULT_AUTO_GEOMETRY,
                KEY_PATH_MODE to DEFAULT_PATH_MODE,
                KEY_RING_SCALE_X to DEFAULT_RING_SCALE_X,
                KEY_RING_SCALE_Y to DEFAULT_RING_SCALE_Y,
                KEY_RING_OFFSET_X to DEFAULT_RING_OFFSET_X,
                KEY_RING_OFFSET_Y to DEFAULT_RING_OFFSET_Y,
                KEY_RING_GAP to DEFAULT_RING_GAP,
                KEY_DOWNLOAD_PRESENTATION to DEFAULT_DOWNLOAD_PRESENTATION,
                KEY_MUSIC_PRESENTATION to DEFAULT_MUSIC_PRESENTATION,
                KEY_PRIMARY_PRIORITY to DEFAULT_PRIMARY_PRIORITY,
                KEY_MULTI_RING_SPACING to DEFAULT_MULTI_RING_SPACING,
                KEY_MUSIC_WAVE_ENABLED to DEFAULT_MUSIC_WAVE_ENABLED,
                KEY_MUSIC_WAVE_AMPLITUDE to DEFAULT_MUSIC_WAVE_AMPLITUDE,
                KEY_MUSIC_WAVE_DENSITY to DEFAULT_MUSIC_WAVE_DENSITY,
                KEY_MUSIC_WAVE_SPEED to DEFAULT_MUSIC_WAVE_SPEED,
                KEY_TIMER_PRESENTATION to DEFAULT_TIMER_PRESENTATION,
                KEY_TIMER_COLOR_MODE to DEFAULT_TIMER_COLOR_MODE,
                KEY_AURORA_COLOR_MODE to DEFAULT_AURORA_COLOR_MODE,
                KEY_AURORA_NOTIFICATION_COLOR_MODE to DEFAULT_AURORA_NOTIFICATION_COLOR_MODE
            )

            defaults.forEach { (key, value) ->
                if (Settings.Secure.getStringForUser(resolver, key, UserHandle.USER_CURRENT) == null) {
                    Settings.Secure.putIntForUser(resolver, key, value, UserHandle.USER_CURRENT)
                }
            }
        }

    @JvmField
    val SEARCH_INDEX_DATA_PROVIDER =
        BaseSearchIndexProvider(R.xml.cutout_progress_settings)
    }

    private lateinit var ringColorModePref: ListPreference
    private lateinit var musicColorModePref: ListPreference
    private lateinit var timerColorModePref: ListPreference
    private lateinit var auroraColorModePref: ListPreference
    private lateinit var auroraNotificationColorModePref: ListPreference
    private lateinit var downloadPresentationPref: ListPreference
    private lateinit var musicPresentationPref: ListPreference
    private lateinit var timerPresentationPref: ListPreference
    private lateinit var primaryPriorityPref: ListPreference
    private lateinit var multiRingSpacingPref: Preference
    private lateinit var autoGeometryPref: Preference
    private lateinit var pathModePref: Preference
    private lateinit var scaleXPref: Preference
    private lateinit var scaleYPref: Preference
    private lateinit var offsetXPref: Preference
    private lateinit var offsetYPref: Preference
    private lateinit var ringGapPref: Preference
    private lateinit var timerEnabledPref: Preference
    private lateinit var timerFlameEnabledPref: Preference
    private lateinit var timerFlameSizePref: Preference
    private lateinit var musicEnabledPref: Preference
    private lateinit var musicWaveEnabledPref: Preference
    private lateinit var musicWaveAmplitudePref: Preference
    private lateinit var musicWaveDensityPref: Preference
    private lateinit var musicWaveSpeedPref: Preference
    private lateinit var auroraEnabledPref: Preference
    private lateinit var auroraNotificationsPref: Preference
    private lateinit var auroraNotificationDurationPref: Preference

    private lateinit var ringColorPref: Preference
    private lateinit var errorColorPref: Preference
    private lateinit var flashColorPref: Preference
    private lateinit var bgColorPref: Preference
    private lateinit var musicColorPref: Preference
    private lateinit var timerColorPref: Preference
    private lateinit var timerFlameColorPref: Preference
    private lateinit var auroraColorPref: Preference

    private lateinit var finishStylePref: ListPreference
    private lateinit var easingPref: ListPreference
    private lateinit var pctPosPref: ListPreference
    private lateinit var fnamePosPref: ListPreference
    private lateinit var fnameTruncPref: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        ensureCalibratedDefaults(requireContext().contentResolver)
        addPreferencesFromResource(R.xml.cutout_progress_settings)

        ringColorModePref = findPreference(KEY_RING_COLOR_MODE)!!
        musicColorModePref = findPreference(KEY_MUSIC_COLOR_MODE)!!
        timerColorModePref = findPreference(KEY_TIMER_COLOR_MODE)!!
        auroraColorModePref = findPreference(KEY_AURORA_COLOR_MODE)!!
        auroraNotificationColorModePref = findPreference(KEY_AURORA_NOTIFICATION_COLOR_MODE)!!
        downloadPresentationPref = findPreference(KEY_DOWNLOAD_PRESENTATION)!!
        musicPresentationPref = findPreference(KEY_MUSIC_PRESENTATION)!!
        timerPresentationPref = findPreference(KEY_TIMER_PRESENTATION)!!
        primaryPriorityPref = findPreference(KEY_PRIMARY_PRIORITY)!!
        multiRingSpacingPref = findPreference(KEY_MULTI_RING_SPACING)!!
        autoGeometryPref = findPreference(KEY_AUTO_GEOMETRY)!!
        pathModePref = findPreference(KEY_PATH_MODE)!!
        scaleXPref = findPreference(KEY_RING_SCALE_X)!!
        scaleYPref = findPreference(KEY_RING_SCALE_Y)!!
        offsetXPref = findPreference(KEY_RING_OFFSET_X)!!
        offsetYPref = findPreference(KEY_RING_OFFSET_Y)!!
        ringGapPref = findPreference(KEY_RING_GAP)!!
        timerEnabledPref = findPreference(KEY_TIMER_ENABLED)!!
        timerFlameEnabledPref = findPreference(KEY_TIMER_FLAME_ENABLED)!!
        timerFlameSizePref = findPreference(KEY_TIMER_FLAME_SIZE)!!
        musicEnabledPref = findPreference(KEY_MUSIC_RING_ENABLED)!!
        musicWaveEnabledPref = findPreference(KEY_MUSIC_WAVE_ENABLED)!!
        musicWaveAmplitudePref = findPreference(KEY_MUSIC_WAVE_AMPLITUDE)!!
        musicWaveDensityPref = findPreference(KEY_MUSIC_WAVE_DENSITY)!!
        musicWaveSpeedPref = findPreference(KEY_MUSIC_WAVE_SPEED)!!
        auroraEnabledPref = findPreference(KEY_AURORA_ENABLED)!!
        auroraNotificationsPref = findPreference(KEY_AURORA_NOTIFICATIONS)!!
        auroraNotificationDurationPref = findPreference(KEY_AURORA_NOTIFICATION_DURATION)!!

        ringColorPref = findPreference(KEY_RING_COLOR)!!
        errorColorPref = findPreference(KEY_ERROR_COLOR)!!
        flashColorPref = findPreference(KEY_FLASH_COLOR)!!
        bgColorPref = findPreference(KEY_BG_COLOR)!!
        musicColorPref = findPreference(KEY_MUSIC_CUSTOM_COLOR)!!
        timerColorPref = findPreference(KEY_TIMER_CUSTOM_COLOR)!!
        timerFlameColorPref = findPreference(KEY_TIMER_FLAME_COLOR)!!
        auroraColorPref = findPreference(KEY_AURORA_CUSTOM_COLOR)!!

        finishStylePref = findPreference(KEY_FINISH_STYLE)!!
        easingPref = findPreference(KEY_EASING)!!
        pctPosPref = findPreference(KEY_PERCENT_POSITION)!!
        fnamePosPref = findPreference(KEY_FILENAME_POSITION)!!
        fnameTruncPref = findPreference(KEY_FILENAME_TRUNCATE)!!

        refreshColorSummaries()
        syncListPreferences()

        val storedMode = readSecureInt(KEY_RING_COLOR_MODE, COLOR_MODE_ACCENT)
        ringColorModePref.value = storedMode.toString()
        updateColorPickerVisibility(storedMode, KEY_RING_COLOR_MODE)

        val storedMusicMode = readSecureInt(KEY_MUSIC_COLOR_MODE, MUSIC_COLOR_MODE_ALBUM_ICON)
        musicColorModePref.value = storedMusicMode.toString()
        updateColorPickerVisibility(storedMusicMode, KEY_MUSIC_COLOR_MODE)

        val storedTimerMode = readSecureInt(KEY_TIMER_COLOR_MODE, DEFAULT_TIMER_COLOR_MODE)
        timerColorModePref.value = storedTimerMode.toString()
        updateColorPickerVisibility(storedTimerMode, KEY_TIMER_COLOR_MODE)

        val storedAuroraMode = readSecureInt(KEY_AURORA_COLOR_MODE, DEFAULT_AURORA_COLOR_MODE)
        auroraColorModePref.value = storedAuroraMode.toString()
        updateColorPickerVisibility(storedAuroraMode, KEY_AURORA_COLOR_MODE)

        updateLayerPreferenceVisibility(
            readSecureInt(KEY_DOWNLOAD_PRESENTATION, DEFAULT_DOWNLOAD_PRESENTATION),
            readSecureInt(KEY_MUSIC_PRESENTATION, DEFAULT_MUSIC_PRESENTATION),
            readSecureInt(KEY_TIMER_PRESENTATION, DEFAULT_TIMER_PRESENTATION)
        )
        updateManualGeometryVisibility(
            readSecureInt(KEY_AUTO_GEOMETRY, DEFAULT_AUTO_GEOMETRY) != 0
        )
        updateNestedDependencyState()

        ringColorModePref.onPreferenceChangeListener = this
        musicColorModePref.onPreferenceChangeListener = this
        timerColorModePref.onPreferenceChangeListener = this
        auroraColorModePref.onPreferenceChangeListener = this
        auroraNotificationColorModePref.onPreferenceChangeListener = this
        downloadPresentationPref.onPreferenceChangeListener = this
        musicPresentationPref.onPreferenceChangeListener = this
        timerPresentationPref.onPreferenceChangeListener = this
        autoGeometryPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            updateManualGeometryVisibility(value as Boolean)
            true
        }

        timerEnabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateTimerFlameDependency(
                    value as Boolean, isSecureEnabled(KEY_TIMER_FLAME_ENABLED, true)
                )
                true
            }
        timerFlameEnabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateTimerFlameDependency(
                    isSecureEnabled(KEY_TIMER_ENABLED), value as Boolean
                )
                true
            }
        musicEnabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateMusicWaveDependency(
                    value as Boolean, isSecureEnabled(KEY_MUSIC_WAVE_ENABLED)
                )
                true
            }
        musicWaveEnabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateMusicWaveDependency(
                    isSecureEnabled(KEY_MUSIC_RING_ENABLED), value as Boolean
                )
                true
            }
        auroraEnabledPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateAuroraNotificationDependency(
                    value as Boolean, isSecureEnabled(KEY_AURORA_NOTIFICATIONS, true)
                )
                true
            }
        auroraNotificationsPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, value ->
                updateAuroraNotificationDependency(
                    isSecureEnabled(KEY_AURORA_ENABLED), value as Boolean
                )
                true
            }

        ringColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_ring_color_title),
                key = KEY_RING_COLOR,
                default = DEFAULT_RING_COLOR
            )
            true
        }
        errorColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_error_color_title),
                key = KEY_ERROR_COLOR,
                default = DEFAULT_ERROR_COLOR
            )
            true
        }
        flashColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_finish_flash_color_title),
                key = KEY_FLASH_COLOR,
                default = DEFAULT_FLASH_COLOR
            )
            true
        }
        bgColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_bg_ring_color_title),
                key = KEY_BG_COLOR,
                default = DEFAULT_BG_COLOR
            )
            true
        }
        musicColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_music_custom_color_title),
                key = KEY_MUSIC_CUSTOM_COLOR,
                default = DEFAULT_MUSIC_COLOR
            )
            true
        }
        timerColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_timer_custom_color_title),
                key = KEY_TIMER_CUSTOM_COLOR,
                default = DEFAULT_TIMER_COLOR
            )
            true
        }
        timerFlameColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_timer_flame_color_title),
                key = KEY_TIMER_FLAME_COLOR,
                default = DEFAULT_TIMER_FLAME_COLOR
            )
            true
        }
        auroraColorPref.setOnPreferenceClickListener {
            showColorPicker(
                title = getString(R.string.cutout_progress_aurora_custom_color_title),
                key = KEY_AURORA_CUSTOM_COLOR,
                default = DEFAULT_AURORA_COLOR
            )
            true
        }

        finishStylePref.onPreferenceChangeListener = this
        easingPref.onPreferenceChangeListener = this
        pctPosPref.onPreferenceChangeListener = this
        fnamePosPref.onPreferenceChangeListener = this
        fnameTruncPref.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val intValue = (newValue as? String)?.toIntOrNull() ?: return false

        if (preference.key == KEY_RING_COLOR_MODE
            || preference.key == KEY_MUSIC_COLOR_MODE
            || preference.key == KEY_TIMER_COLOR_MODE
            || preference.key == KEY_AURORA_COLOR_MODE) {
            updateColorPickerVisibility(intValue, preference.key)
            return true
        }

        if (preference.key == KEY_DOWNLOAD_PRESENTATION
            || preference.key == KEY_MUSIC_PRESENTATION
            || preference.key == KEY_TIMER_PRESENTATION) {
            val downloadMode = if (preference.key == KEY_DOWNLOAD_PRESENTATION) intValue
                else readSecureInt(KEY_DOWNLOAD_PRESENTATION, DEFAULT_DOWNLOAD_PRESENTATION)
            val musicMode = if (preference.key == KEY_MUSIC_PRESENTATION) intValue
                else readSecureInt(KEY_MUSIC_PRESENTATION, DEFAULT_MUSIC_PRESENTATION)
            val timerMode = if (preference.key == KEY_TIMER_PRESENTATION) intValue
                else readSecureInt(KEY_TIMER_PRESENTATION, DEFAULT_TIMER_PRESENTATION)
            updateLayerPreferenceVisibility(downloadMode, musicMode, timerMode)
            return true
        }

        // SecureSettingListPreference persists the accepted value through SecureSettingsStore.
        // Writing it manually here would notify SystemUI twice for the same user action.
        return true
    }
    private fun updateNestedDependencyState() {
        updateTimerFlameDependency(
            isSecureEnabled(KEY_TIMER_ENABLED),
            isSecureEnabled(KEY_TIMER_FLAME_ENABLED, true)
        )
        updateMusicWaveDependency(
            isSecureEnabled(KEY_MUSIC_RING_ENABLED),
            isSecureEnabled(KEY_MUSIC_WAVE_ENABLED)
        )
        updateAuroraNotificationDependency(
            isSecureEnabled(KEY_AURORA_ENABLED),
            isSecureEnabled(KEY_AURORA_NOTIFICATIONS, true)
        )
    }

    private fun updateTimerFlameDependency(timerEnabled: Boolean, flameEnabled: Boolean) {
        val enabled = timerEnabled && flameEnabled
        timerFlameColorPref.isEnabled = enabled
        timerFlameSizePref.isEnabled = enabled
    }

    private fun updateMusicWaveDependency(musicEnabled: Boolean, waveEnabled: Boolean) {
        val enabled = musicEnabled && waveEnabled
        musicWaveAmplitudePref.isEnabled = enabled
        musicWaveDensityPref.isEnabled = enabled
        musicWaveSpeedPref.isEnabled = enabled
    }

    private fun updateAuroraNotificationDependency(
        auroraEnabled: Boolean,
        notificationsEnabled: Boolean
    ) {
        val enabled = auroraEnabled && notificationsEnabled
        auroraNotificationColorModePref.isEnabled = enabled
        auroraNotificationDurationPref.isEnabled = enabled
    }

    private fun isSecureEnabled(key: String, defaultValue: Boolean = false): Boolean =
        readSecureInt(key, if (defaultValue) 1 else 0) != 0


    private fun updateColorPickerVisibility(mode: Int, key: String) {
        when (key) {
            KEY_RING_COLOR_MODE -> ringColorPref.isVisible = (mode == COLOR_MODE_CUSTOM)
            KEY_MUSIC_COLOR_MODE -> musicColorPref.isVisible = (mode == MUSIC_COLOR_MODE_CUSTOM)
            KEY_TIMER_COLOR_MODE -> timerColorPref.isVisible = (mode == COLOR_MODE_CUSTOM)
            KEY_AURORA_COLOR_MODE -> auroraColorPref.isVisible = (mode == AURORA_COLOR_MODE_CUSTOM)
        }
    }

    private fun updateManualGeometryVisibility(autoEnabled: Boolean) {
        val showManual = !autoEnabled
        pathModePref.isVisible = showManual
        scaleXPref.isVisible = showManual
        scaleYPref.isVisible = showManual
        offsetXPref.isVisible = showManual
        offsetYPref.isVisible = showManual
        ringGapPref.isVisible = showManual
    }

    private fun updateLayerPreferenceVisibility(
        downloadMode: Int,
        musicMode: Int,
        timerMode: Int
    ) {
        val modes = listOf(downloadMode, musicMode, timerMode)
        multiRingSpacingPref.isVisible = modes.any { it == PRESENTATION_INDEPENDENT }
        primaryPriorityPref.isVisible =
            modes.count { it == PRESENTATION_PRIMARY } > 1
                || modes.count { it == PRESENTATION_INDEPENDENT } > 1
    }

    private fun syncListPreferences() {
        listOf(
            finishStylePref to 0,
            easingPref to 0,
            pctPosPref to 0,
            fnamePosPref to 4,
            fnameTruncPref to 0,
            downloadPresentationPref to DEFAULT_DOWNLOAD_PRESENTATION,
            musicPresentationPref to DEFAULT_MUSIC_PRESENTATION,
            timerPresentationPref to DEFAULT_TIMER_PRESENTATION,
            timerColorModePref to DEFAULT_TIMER_COLOR_MODE,
            auroraColorModePref to DEFAULT_AURORA_COLOR_MODE,
            auroraNotificationColorModePref to DEFAULT_AURORA_NOTIFICATION_COLOR_MODE,
            primaryPriorityPref to DEFAULT_PRIMARY_PRIORITY
        ).forEach { (pref, default) ->
            pref.value = readSecureInt(pref.key, default).toString()
        }
    }

    private fun showColorPicker(title: String, key: String, default: Int) {
        val currentArgb = readSecureInt(key, default)
        val currentHex = argbToHex(currentArgb)

        val dialog = CutoutProgressColorPickerDialogFragment.newInstance(
            title = title,
            colorHex = currentHex
        )
        dialog.setOnColorSelectedListener { color: Color ->
            writeSecureInt(key, color.toArgb())
            refreshColorSummaries()
        }
        dialog.show(parentFragmentManager, CutoutProgressColorPickerDialogFragment.TAG)
    }

    private fun refreshColorSummaries() {
        ringColorPref.summary = "#${argbToHex(readSecureInt(KEY_RING_COLOR, DEFAULT_RING_COLOR))}"
        errorColorPref.summary = "#${argbToHex(readSecureInt(KEY_ERROR_COLOR, DEFAULT_ERROR_COLOR))}"
        flashColorPref.summary = "#${argbToHex(readSecureInt(KEY_FLASH_COLOR, DEFAULT_FLASH_COLOR))}"
        bgColorPref.summary = "#${argbToHex(readSecureInt(KEY_BG_COLOR, DEFAULT_BG_COLOR))}"
        musicColorPref.summary = "#${argbToHex(readSecureInt(KEY_MUSIC_CUSTOM_COLOR, DEFAULT_MUSIC_COLOR))}"
        timerColorPref.summary = "#${argbToHex(readSecureInt(KEY_TIMER_CUSTOM_COLOR, DEFAULT_TIMER_COLOR))}"
        timerFlameColorPref.summary = "#${argbToHex(readSecureInt(KEY_TIMER_FLAME_COLOR, DEFAULT_TIMER_FLAME_COLOR))}"
        auroraColorPref.summary = "#${argbToHex(readSecureInt(KEY_AURORA_CUSTOM_COLOR, DEFAULT_AURORA_COLOR))}"
    }

    private fun readSecureInt(key: String, default: Int): Int =
        Settings.Secure.getIntForUser(
            requireContext().contentResolver, key, default, UserHandle.USER_CURRENT
        )

    private fun writeSecureInt(key: String, value: Int) {
        Settings.Secure.putIntForUser(
            requireContext().contentResolver, key, value, UserHandle.USER_CURRENT
        )
    }

    private fun argbToHex(argb: Int): String =
        String.format("%06X", 0xFFFFFF and argb)

    override fun getMetricsCategory(): Int = MetricsEvent.EVOLVER
}
