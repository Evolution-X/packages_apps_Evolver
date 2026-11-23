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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
import java.io.File

/**
 * Reads and changes the system font: the font overlays, plus a user-picked font file that is
 * installed through [ExternalFontInstaller]. An installed custom font wins over any overlay, so
 * picking an overlay removes it first and asks for a reboot.
 */
class FontController(private val context: Context) {

    private val themeUtils = ThemeUtils.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while a font is being applied or installed; the pages hold Apply back meanwhile. */
    var busy by mutableStateOf(false)
        private set

    private val defaultLabel: String get() = context.getString(R.string.font_default)

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
        return if (applied == "android") defaultLabel else labelOf(applied)
    }

    /** The custom font first, then the default, then the overlays by name. Blocks on lookups. */
    fun loadOptions(): List<StyleOption> {
        val packages = themeUtils.getOverlayPackagesForCategory(CATEGORY, "android")
        val labels = packages.filter { it != "android" }.associateWith { labelOf(it) }
        val overlays = packages.filter { it != "android" }
            .sortedBy { labels.getValue(it).lowercase() }
        return buildList {
            val custom = customFontName()
            if (custom.isNotEmpty()) {
                add(StyleOption(
                    CUSTOM_KEY,
                    custom,
                    ExternalFontInstaller(context).loadInstalledTypeface() ?: typefaceOf("android")
                ))
            }
            add(StyleOption("android", defaultLabel, typefaceOf("android"), FontCategory.SANS))
            overlays.forEach {
                add(StyleOption(it, labels.getValue(it), typefaceOf(it), CATEGORIES[it]))
            }
        }
    }

    /**
     * Switches from the font [old] (a package, or [CUSTOM_KEY]) to the overlay [pkg]. Leaving a
     * custom font removes it and needs a reboot; an overlay swap only restarts SystemUI. [label]
     * names the font in the dialog.
     */
    fun apply(ctx: Context, old: String, pkg: String, label: String, onChanged: () -> Unit) {
        if (busy) return
        val reboot = old == CUSTOM_KEY
        ctx.showChoiceDialog(
            title = ctx.getString(
                if (reboot) R.string.reboot_required_title else R.string.systemui_restart_title
            ),
            message = ctx.getString(
                if (reboot) R.string.font_apply_reboot_message
                else R.string.font_apply_systemui_message,
                label
            ),
            confirmText = ctx.getString(
                if (reboot) R.string.font_apply_reboot_now else R.string.font_apply_restart_now
            ),
            laterText = ctx.getString(
                if (reboot) R.string.font_apply_reboot_later else R.string.font_apply_restart_later
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
                busy = true
                Thread({
                    try {
                        if (reboot) ExternalFontInstaller(ctx).resetFontUpdates()
                        mainHandler.post {
                            busy = false
                            if (reboot) clearCustomFontState()
                            themeUtils.setOverlayEnabled(
                                CATEGORY, if (reboot) appliedPackage() else old, old
                            )
                            themeUtils.setOverlayEnabled(CATEGORY, pkg, "android")
                            onChanged()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply font overlay", e)
                        mainHandler.post { busy = false }
                    }
                }, "FontOverlay").start()
            },
        )
    }

    /** Loads the picked font file, shows a preview of it and installs it once confirmed. */
    fun importFont(uri: Uri, scope: CoroutineScope, onChanged: () -> Unit) {
        if (busy) return
        val installer = ExternalFontInstaller(context)
        busy = true
        scope.launch {
            val typeface = try {
                installer.loadTypefaceFromUri(uri)
            } finally {
                busy = false
            }
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
                        Text(
                            text = stringResource(R.string.font_install_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
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
        launchInstall(scope, onChanged) { installer.installFontFromUri(uri) }
    }

    /** Runs [install] with [busy] set, then shows what came of it. */
    private fun launchInstall(
        scope: CoroutineScope,
        onChanged: () -> Unit,
        install: suspend () -> InstallResult
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                finishInstall(install(), onChanged)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Installs a font file that is already on the device, such as a downloaded one, once the
     * user has read what installing does. An imported font gets the same text in its preview.
     */
    fun installFile(file: File, scope: CoroutineScope, onChanged: () -> Unit) {
        val installer = ExternalFontInstaller(context)
        context.showChoiceDialog(
            title = context.getString(R.string.add_font),
            message = context.getString(R.string.font_install_message),
            confirmText = context.getString(R.string.add_font),
            laterText = context.getString(android.R.string.cancel),
            onConfirm = {
                launchInstall(scope, onChanged) { installer.installFontFromFile(file) }
            },
            onLater = {},
        )
    }

    // A custom font is read at boot, so it asks for a reboot once it is installed.
    private fun finishInstall(result: InstallResult, onChanged: () -> Unit) {
        val installed = when (result) {
            is InstallResult.Installed -> result
            is InstallResult.Failed -> {
                Toast.makeText(
                    context,
                    ExternalFontInstaller.errorMessage(context, result.code),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        Settings.Secure.putString(
            context.contentResolver, CUSTOM_FONT_NAME, installed.postScriptName
        )
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

    private fun applyOverlayInBackground(
        ctx: Context,
        oldPkg: String,
        newPkg: String,
        resetCustom: Boolean,
        onFailed: () -> Unit,
        onDone: () -> Unit
    ) {
        busy = true
        Thread({
            try {
                if (resetCustom) ExternalFontInstaller(ctx).resetFontUpdates()
                themeUtils.setOverlayEnabled(CATEGORY, oldPkg, oldPkg)
                themeUtils.setOverlayEnabled(CATEGORY, newPkg, "android")
                mainHandler.post {
                    busy = false
                    onDone()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply font overlay", e)
                mainHandler.post {
                    busy = false
                    onFailed()
                }
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

        /** Key of the installed custom font in [StyleOption] and [appliedKey]. */
        const val CUSTOM_KEY = "__custom_font__"

        // The look of each bundled font overlay, picked from how it renders. A font that is not
        // listed, such as one added later, only shows under All.
        private val CATEGORIES = mapOf(
            "com.android.theme.font.atkinsonhyperlegible" to FontCategory.SANS,
            "com.android.theme.font.bariol" to FontCategory.SANS,
            "com.android.theme.font.cagliostro" to FontCategory.SANS,
            "com.android.theme.font.cocon" to FontCategory.SANS,
            "com.android.theme.font.comfortaa" to FontCategory.SANS,
            "com.android.theme.font.evosans" to FontCategory.SANS,
            "com.android.theme.font.evolve" to FontCategory.SANS,
            "com.android.theme.font.exotwo" to FontCategory.SANS,
            "com.android.theme.font.generalsans" to FontCategory.SANS,
            "com.android.theme.font.harmonysans" to FontCategory.SANS,
            "com.android.theme.font.ibmplex" to FontCategory.SANS,
            "org.lineageos.overlay.font.lato" to FontCategory.SANS,
            "com.android.theme.font.linotte" to FontCategory.SANS,
            "com.android.theme.font.nokiapure" to FontCategory.SANS,
            "com.android.theme.font.nunito" to FontCategory.SANS,
            "com.android.theme.font.ohmyfont" to FontCategory.SANS,
            "com.android.theme.font.oneuisans" to FontCategory.SANS,
            "com.android.theme.font.oneplussans" to FontCategory.SANS,
            "com.android.theme.font.oneplusslate" to FontCategory.SANS,
            "com.android.theme.font.opendyslexic" to FontCategory.SANS,
            "com.android.theme.font.oswald" to FontCategory.SANS,
            "com.android.theme.font.play" to FontCategory.SANS,
            "com.android.theme.font.poppins" to FontCategory.SANS,
            "org.voltage.theme.font.recursive_linear" to FontCategory.SANS,
            "com.android.theme.font.reemkufi" to FontCategory.SANS,
            "com.android.theme.font.robotocondensed" to FontCategory.SANS,
            "com.android.theme.font.rookery" to FontCategory.SANS,
            "org.lineageos.overlay.font.rubik" to FontCategory.SANS,
            "com.android.theme.font.samsungone" to FontCategory.SANS,
            "com.android.theme.font.sanfrancisco" to FontCategory.SANS,
            "com.android.theme.font.sansserifpro" to FontCategory.SANS,
            "com.android.theme.font.sonysketch" to FontCategory.SANS,
            "com.android.theme.font.SpaceGrotesk" to FontCategory.SANS,
            "com.android.theme.font.storopia" to FontCategory.SANS,
            "com.android.theme.font.ubuntu" to FontCategory.SANS,
            "com.android.theme.font.volte" to FontCategory.SANS,
            "com.android.theme.font.amarante" to FontCategory.SERIF,
            "com.android.theme.font.quando" to FontCategory.SERIF,
            "com.android.theme.font.surfer" to FontCategory.SERIF,
            "com.android.theme.font.accuratist" to FontCategory.DISPLAY,
            "com.android.theme.font.aclonica" to FontCategory.DISPLAY,
            "com.android.theme.font.comicsans" to FontCategory.DISPLAY,
            "com.android.theme.font.cookierun" to FontCategory.DISPLAY,
            "com.android.theme.font.coolstory" to FontCategory.DISPLAY,
            "com.android.theme.font.dtsans" to FontCategory.DISPLAY,
            "com.android.theme.font.fifa2018" to FontCategory.DISPLAY,
            "com.android.theme.font.mercy" to FontCategory.DISPLAY,
            "com.android.theme.font.nothingdot" to FontCategory.DISPLAY,
            "com.android.theme.font.papyrus" to FontCategory.DISPLAY,
            "org.voltage.theme.font.recursive_casual" to FontCategory.DISPLAY,
            "com.android.theme.font.cooljazz" to FontCategory.SCRIPT,
            "com.android.theme.font.grandhotel" to FontCategory.SCRIPT,
            "com.android.theme.font.redressed" to FontCategory.SCRIPT,
            "com.android.theme.font.rosemary" to FontCategory.SCRIPT,
            "com.android.theme.font.dtmono" to FontCategory.MONO,
        )
    }
}
