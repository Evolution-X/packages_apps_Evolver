/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

internal enum class RestartKind { SYSTEMUI, REBOOT }

internal data class PendingRestart(val kind: RestartKind, val fontLabel: String)

/**
 * Remembers that a font or emoji change still needs a SystemUI restart or a reboot, after "later" was
 * chosen in the dialog. It is kept in preferences, so the page, the Themes summary and a
 * restarted Settings all see it. It clears itself once the device has rebooted or SystemUI
 * is running in a new process.
 */
internal class FontRestartTracker(context: Context, prefsName: String = PREFS) {

    private val context: Context = context.applicationContext
    private val prefs: SharedPreferences =
        this.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun pending(): PendingRestart? {
        val kind = prefs.getString(KEY_KIND, null)
            ?.let { runCatching { RestartKind.valueOf(it) }.getOrNull() }
            ?: return null
        val rebooted = prefs.getInt(KEY_BOOT_COUNT, -1) != bootCount() ||
            SystemClock.elapsedRealtime() < prefs.getLong(KEY_MARKED_AT, 0L)
        if (rebooted) {
            clear()
            return null
        }
        if (kind == RestartKind.SYSTEMUI) {
            val markedPid = prefs.getInt(KEY_SYSTEMUI_PID, 0)
            val currentPid = systemUiPid()
            if (markedPid > 0 && currentPid > 0 && markedPid != currentPid) {
                clear()
                return null
            }
        }
        return PendingRestart(kind, prefs.getString(KEY_LABEL, null).orEmpty())
    }

    /** A reboot already pending is never downgraded to a SystemUI restart. */
    fun markPending(kind: RestartKind, fontLabel: String) {
        val merged = if (pending()?.kind == RestartKind.REBOOT) RestartKind.REBOOT else kind
        prefs.edit()
            .putString(KEY_KIND, merged.name)
            .putString(KEY_LABEL, fontLabel)
            .putInt(KEY_BOOT_COUNT, bootCount())
            .putLong(KEY_MARKED_AT, SystemClock.elapsedRealtime())
            .putInt(KEY_SYSTEMUI_PID, systemUiPid())
            .commit() // synchronous: the activity can be recreated right after
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun systemUiPid(): Int = runCatching {
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.processName == SYSTEMUI_PROCESS }?.pid ?: 0
    }.getOrDefault(0)

    private companion object {
        const val PREFS = "font_picker_state"
        const val KEY_KIND = "pending_restart_kind"
        const val KEY_LABEL = "pending_restart_label"
        const val KEY_BOOT_COUNT = "pending_restart_boot_count"
        const val KEY_MARKED_AT = "pending_restart_marked_at"
        const val KEY_SYSTEMUI_PID = "pending_restart_systemui_pid"
        const val SYSTEMUI_PROCESS = "com.android.systemui"
    }
}
