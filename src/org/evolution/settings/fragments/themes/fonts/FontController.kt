/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.android.internal.util.evolution.ThemeUtils
import com.android.internal.util.evolution.ThemeUtils.FONT_KEY
import com.android.settings.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.preferences.showChoiceDialog
import org.evolution.settings.preferences.showComposeDialog
import org.evolution.settings.utils.SystemUtils

/** One entry of the font picker: the default, an installed overlay or the custom font. */
data class FontOption(val key: String, val label: String, val typeface: Typeface?)

/**
 * Reads and changes the system font: the font overlays, plus a user-picked font file that is
 * installed through [ExternalFontInstaller]. An installed custom font wins over any overlay, so
 * picking an overlay removes it first and asks for a reboot.
 */
class FontController(private val context: Context) {

    private val themeUtils = ThemeUtils.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun customFontName(): String =
        Settings.Secure.getString(context.contentResolver, CUSTOM_FONT_NAME) ?: ""

    fun appliedPackage(): String =
        themeUtils.getOverlayInfos(CATEGORY).firstOrNull { it.isEnabled }?.packageName ?: "android"

    /** [CUSTOM_KEY] while a custom font is installed, else the applied overlay package. */
    fun appliedKey(): String = if (customFontName().isNotEmpty()) CUSTOM_KEY else appliedPackage()

    fun getSummary(): String {
        val custom = customFontName()
        if (custom.isNotEmpty()) return custom
        val applied = appliedPackage()
        return if (applied == "android") DEFAULT_LABEL else labelOf(applied)
    }

    /** The custom font first, then the default, then the overlays by name. Blocks on lookups. */
    fun loadOptions(): List<FontOption> {
        val packages = themeUtils.getOverlayPackagesForCategory(CATEGORY, "android")
        val labels = packages.filter { it != "android" }.associateWith { labelOf(it) }
        val overlays = packages.filter { it != "android" }
            .sortedBy { labels.getValue(it).lowercase() }
        return buildList {
            val custom = customFontName()
            if (custom.isNotEmpty()) {
                add(FontOption(
                    CUSTOM_KEY,
                    custom,
                    ExternalFontInstaller(context).loadInstalledTypeface() ?: typefaceOf("android")
                ))
            }
            add(FontOption("android", DEFAULT_LABEL, typefaceOf("android")))
            overlays.forEach { add(FontOption(it, labels.getValue(it), typefaceOf(it))) }
        }
    }

    /**
     * Switches from the font [old] (a package, or [CUSTOM_KEY]) to the overlay [pkg]. Leaving a
     * custom font removes it and needs a reboot; an overlay swap only restarts SystemUI.
     */
    fun apply(ctx: Context, old: String, pkg: String, onChanged: () -> Unit) {
        val reboot = old == CUSTOM_KEY
        ctx.showChoiceDialog(
            title = ctx.getString(
                if (reboot) R.string.reboot_required_title else R.string.systemui_restart_title
            ),
            message = ctx.getString(
                if (reboot) R.string.reboot_required_message else R.string.systemui_restart_message
            ),
            confirmText = ctx.getString(
                if (reboot) R.string.action_reboot_now else R.string.action_yes
            ),
            laterText = ctx.getString(
                if (reboot) R.string.action_later else R.string.systemui_restart_not_now
            ),
            onConfirm = {
                applyOverlayInBackground(
                    ctx,
                    if (reboot) appliedPackage() else old,
                    pkg,
                    resetCustom = reboot,
                    onFailed = {
                        Toast.makeText(ctx, R.string.toast_failed_apply_font, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    if (reboot) clearCustomFontState()
                    onChanged()
                    if (reboot) {
                        ExternalFontInstaller.rebootDevice()
                    } else {
                        SystemUtils.restartSystemUI(ctx)
                    }
                }
            },
            onLater = {
                Thread({
                    if (reboot) ExternalFontInstaller(ctx).resetFontUpdates()
                    mainHandler.post {
                        if (reboot) clearCustomFontState()
                        themeUtils.setOverlayEnabled(
                            CATEGORY, if (reboot) appliedPackage() else old, old
                        )
                        themeUtils.setOverlayEnabled(CATEGORY, pkg, "android")
                        onChanged()
                    }
                }, "FontOverlay").start()
            },
        )
    }

    /** Loads the picked font file, shows a preview of it and installs it once confirmed. */
    fun importFont(uri: Uri, scope: CoroutineScope, onChanged: () -> Unit) {
        val installer = ExternalFontInstaller(context)
        scope.launch {
            val typeface = installer.loadTypefaceFromUri(uri)
            if (typeface == null) {
                Toast.makeText(context, R.string.toast_invalid_font_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showImportPreviewDialog(uri, typeface, installer, scope, onChanged)
        }
    }

    private fun showImportPreviewDialog(
        uri: Uri,
        typeface: Typeface,
        installer: ExternalFontInstaller,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        context.showComposeDialog { dismiss ->
            ExpressiveDialog(
                onDismissRequest = dismiss,
                title = {
                    Text(
                        text = stringResource(R.string.font_preview_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                text = {
                    Column(Modifier.expressiveVerticalScroll()) {
                        Text(
                            text = stringResource(R.string.font_preview_quote),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            fontFamily = FontFamily(typeface),
                        )
                    }
                },
                confirmButton = {
                    ExpressiveTextButton(onClick = {
                        dismiss()
                        installImported(uri, installer, scope, onChanged)
                    }) { Text(stringResource(R.string.add_font)) }
                },
                dismissButton = {
                    ExpressiveTextButton(onClick = dismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }

    private fun installImported(
        uri: Uri,
        installer: ExternalFontInstaller,
        scope: CoroutineScope,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val postScriptName = installer.installFontFromUri(uri) ?: return@launch
            Settings.Secure.putString(context.contentResolver, CUSTOM_FONT_NAME, postScriptName)
            onChanged()
            context.showChoiceDialog(
                title = context.getString(R.string.reboot_required_title),
                message = context.getString(R.string.reboot_required_custom_font_title),
                confirmText = context.getString(R.string.action_reboot_now),
                laterText = context.getString(R.string.action_later),
                onConfirm = { ExternalFontInstaller.rebootDevice() },
                onLater = {},
            )
        }
    }

    private fun applyOverlayInBackground(
        ctx: Context,
        oldPkg: String,
        newPkg: String,
        resetCustom: Boolean,
        onFailed: () -> Unit,
        onDone: () -> Unit
    ) {
        Thread({
            try {
                if (resetCustom) ExternalFontInstaller(ctx).resetFontUpdates()
                themeUtils.setOverlayEnabled(CATEGORY, oldPkg, oldPkg)
                themeUtils.setOverlayEnabled(CATEGORY, newPkg, "android")
                mainHandler.post { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply font overlay", e)
                mainHandler.post { onFailed() }
            }
        }, "FontOverlay").start()
    }

    private fun clearCustomFontState() {
        Settings.Secure.putString(context.contentResolver, CUSTOM_FONT_NAME, "")
    }

    private fun typefaceOf(pkg: String): Typeface? {
        // The default font is the always-on Google Sans Flex overlay's family.
        if (pkg == "android") {
            return Typeface.getSystemDefaultTypeface(ExternalFontInstaller.DEFAULT_FONT_FAMILY)
        }
        return try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val id = res.getIdentifier("config_bodyFontFamily", "string", pkg)
            if (id == 0) return null
            // Typeface.create() would hand back the font in use for names like "google-sans".
            Typeface.getSystemDefaultTypeface(res.getString(id))
        } catch (e: Exception) {
            Log.e(TAG, "Typeface load failed for pkg: $pkg", e)
            null
        }
    }

    private fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "FontController"
        private const val CATEGORY = FONT_KEY
        private const val CUSTOM_FONT_NAME = "custom_font_name"
        private const val DEFAULT_LABEL = "Default"

        /** Key of the installed custom font in [FontOption] and [appliedKey]. */
        const val CUSTOM_KEY = "__custom_font__"
    }
}
