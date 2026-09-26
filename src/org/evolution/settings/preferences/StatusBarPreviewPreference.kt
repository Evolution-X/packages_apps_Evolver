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
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R

/**
 * Lightweight live preview for the status bar customization page.
 *
 * This preference only visualizes settings already owned by SystemUI. It does not implement
 * SystemUI behavior or duplicate runtime logic in Evolver.
 */
class StatusBarPreviewPreference @JvmOverloads constructor(
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
        layoutResource = R.layout.preference_status_bar_preview
        isSelectable = false
    }

    override fun onAttached() {
        super.onAttached()
        if (observing) return

        SYSTEM_KEYS.forEach {
            resolver.registerContentObserver(Settings.System.getUriFor(it), false, observer)
        }
        SECURE_KEYS.forEach {
            resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer)
        }
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

        val clock = holder.findViewById(R.id.status_bar_preview_clock) as TextClock
        val logo = holder.findViewById(R.id.status_bar_preview_logo) as ImageView
        val traffic = holder.findViewById(R.id.status_bar_preview_traffic) as TextView
        val battery = holder.findViewById(R.id.status_bar_preview_battery) as TextView
        val dynamicBar = holder.findViewById(R.id.status_bar_preview_dynamic_bar) as View

        val clockPosition =
            Settings.System.getIntForUser(
                resolver,
                KEY_CLOCK_POSITION,
                CLOCK_POSITION_LEFT,
                UserHandle.USER_CURRENT,
            )
        val clockLayoutParams = clock.layoutParams as FrameLayout.LayoutParams
        val isRtl = clock.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val horizontalGravity =
            when (clockPosition) {
                CLOCK_POSITION_RIGHT -> if (isRtl) Gravity.START else Gravity.END
                CLOCK_POSITION_CENTER -> Gravity.CENTER_HORIZONTAL
                else -> if (isRtl) Gravity.END else Gravity.START
            }
        clockLayoutParams.gravity = Gravity.CENTER_VERTICAL or horizontalGravity
        clock.layoutParams = clockLayoutParams

        logo.visibility =
            if (
                Settings.System.getIntForUser(
                    resolver,
                    KEY_STATUS_BAR_LOGO,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        traffic.visibility =
            if (
                Settings.System.getIntForUser(
                    resolver,
                    KEY_NETWORK_TRAFFIC,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val showBatteryPercent =
            Settings.System.getIntForUser(
                resolver,
                KEY_BATTERY_PERCENT,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        battery.text = if (showBatteryPercent) "85%" else "85"

        dynamicBar.visibility =
            if (
                Settings.Secure.getIntForUser(
                    resolver,
                    KEY_DYNAMIC_BAR,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    companion object {
        private const val KEY_CLOCK_POSITION = "status_bar_clock"
        private const val KEY_STATUS_BAR_LOGO = "status_bar_logo"
        private const val KEY_NETWORK_TRAFFIC = "network_traffic_enabled"
        private const val KEY_BATTERY_PERCENT = "status_bar_show_battery_percent"
        private const val KEY_DYNAMIC_BAR = "ax_dynamic_bar_enabled"

        private const val CLOCK_POSITION_RIGHT = 0
        private const val CLOCK_POSITION_CENTER = 1
        private const val CLOCK_POSITION_LEFT = 2

        private val SYSTEM_KEYS =
            arrayOf(
                KEY_CLOCK_POSITION,
                KEY_STATUS_BAR_LOGO,
                KEY_NETWORK_TRAFFIC,
                KEY_BATTERY_PERCENT,
            )

        private val SECURE_KEYS = arrayOf(KEY_DYNAMIC_BAR)
    }
}