/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import lineageos.providers.LineageSettings

/**
 * Live visual summary for Quick Settings.
 *
 * Evolver only observes settings already implemented by SystemUI. Runtime rendering remains owned
 * by frameworks/base.
 */
class QuickSettingsPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
) : Preference(context, attrs, defStyleAttr) {

    private val resolver = context.contentResolver
    private var observing = false

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                notifyChanged()
            }
        }

    init {
        layoutResource = R.layout.preference_quick_settings_preview
        isSelectable = false
    }

    override fun onAttached() {
        super.onAttached()
        if (observing) return

        SYSTEM_KEYS.forEach {
            resolver.registerContentObserver(Settings.System.getUriFor(it), false, observer)
        }
        resolver.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER),
            false,
            observer,
        )
        observing = true
    }

    override fun onDetached() {
        if (observing) {
            resolver.unregisterContentObserver(observer)
            observing = false
        }
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val brightness = holder.findViewById(R.id.qs_preview_brightness) as View
        val media = holder.findViewById(R.id.qs_preview_media) as LinearLayout
        val progress = holder.findViewById(R.id.qs_preview_media_progress) as View
        val waveform = holder.findViewById(R.id.qs_preview_media_waveform) as View

        val showBrightness =
            LineageSettings.Secure.getIntForUser(
                resolver,
                LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER,
                1,
                UserHandle.USER_CURRENT,
            ) > 0
        brightness.visibility = if (showBrightness) View.VISIBLE else View.GONE

        val compactMedia =
            Settings.System.getIntForUser(
                resolver,
                KEY_COMPACT_MEDIA,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        media.minimumHeight = dp(if (compactMedia) 52 else 72)

        val showWaveform =
            Settings.System.getIntForUser(
                resolver,
                KEY_MEDIA_WAVEFORM,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        progress.visibility = if (showWaveform) View.GONE else View.VISIBLE
        waveform.visibility = if (showWaveform) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val KEY_COMPACT_MEDIA = "qs_compact_media_player_mode"
        private const val KEY_MEDIA_WAVEFORM = "media_waveform_seekbar"

        private val SYSTEM_KEYS = arrayOf(KEY_COMPACT_MEDIA, KEY_MEDIA_WAVEFORM)
    }
}