/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.internal.util.evolution.ThemeUtils
import com.android.settings.R
import org.evolution.settings.utils.SystemUtils

class LockClockFontsPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val themeUtils = ThemeUtils.getInstance(context)
    private val pkgs: List<String> = themeUtils.getOverlayPackagesForCategory(CATEGORY, "android")

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context
        dialog?.dismiss()

        val current = getAppliedPkg()
        val time = pickerClockText(ctx)
        val options = pkgs.map { pkg ->
            PickerOption(label = if (pkg == "android") "Default" else getLabelSafe(ctx, pkg)) {
                // Loaded when the row scrolls into view, not for the whole list up front.
                val family = remember(pkg) { getTypefaceSafe(ctx, pkg)?.let { FontFamily(it) } }
                Text(
                    text = time,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = family,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(84.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        dialog = ctx.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = pkgs.indexOf(current).coerceAtLeast(0),
            onClosed = { dialog = null },
        ) { index ->
            val pkg = pkgs[index]
            if (pkg == current) return@showPickerDialog false
            applyFont(ctx, old = current, pkg = pkg)
            true
        }
    }

    private fun applyFont(ctx: Context, old: String, pkg: String) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.systemui_restart_title),
            message = ctx.getString(R.string.systemui_restart_message),
            confirmText = ctx.getString(R.string.action_yes),
            laterText = ctx.getString(R.string.systemui_restart_not_now),
            onConfirm = {
                applyOverlayInBackground(
                    old, pkg,
                    onFailed = {
                        Toast.makeText(ctx, R.string.toast_failed_apply_font, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    SystemUtils.restartSystemUI(ctx)
                }
            },
            onLater = {
                mainHandler.post {
                    themeUtils.setOverlayEnabled(CATEGORY, old, old)
                    themeUtils.setOverlayEnabled(CATEGORY, pkg, "android")
                }
            },
        )
    }

    private fun applyOverlayInBackground(
        oldPkg: String,
        newPkg: String,
        onFailed: () -> Unit,
        onDone: () -> Unit
    ) {
        Thread({
            try {
                themeUtils.setOverlayEnabled(CATEGORY, oldPkg, oldPkg)
                themeUtils.setOverlayEnabled(CATEGORY, newPkg, "android")
                mainHandler.post { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply lock clock font overlay", e)
                mainHandler.post { onFailed() }
            }
        }, "LockClockFontOverlay").start()
    }

    private fun getAppliedPkg(): String {
        return themeUtils.getOverlayInfos(CATEGORY)
            .firstOrNull { it.isEnabled }?.packageName ?: "android"
    }

    private fun updateSummary() {
        val applied = getAppliedPkg()
        summary = if (applied == "android") {
            "Default"
        } else {
            getLabelSafe(context, applied)
        }
    }

    private fun getTypefaceSafe(context: Context, pkg: String): Typeface? {
        return try {
            val pm = context.packageManager
            val res = if (pkg == "android") Resources.getSystem() else pm.getResourcesForApplication(pkg)
            val id = res.getIdentifier("config_clockFontFamily", "string", pkg)
            if (id == 0) return null
            Typeface.create(res.getString(id), Typeface.NORMAL)
        } catch (e: Exception) {
            Log.e(TAG, "Typeface load failed for pkg: $pkg", e)
            null
        }
    }

    companion object {
        private const val TAG = "LockClockFontsPreference"
        private const val CATEGORY = "android.theme.customization.lockscreen_clock_font"

        private fun getLabelSafe(context: Context, pkg: String): String {
            return try {
                val pm = context.packageManager
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }
}
