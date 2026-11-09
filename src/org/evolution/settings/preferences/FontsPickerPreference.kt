/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.internal.util.evolution.ThemeUtils
import com.android.internal.util.evolution.ThemeUtils.FONT_KEY
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.fragments.themes.fonts.ExternalFontInstaller
import org.evolution.settings.utils.SystemUtils

class FontsPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val themeUtils = ThemeUtils.getInstance(context)
    private val pkgs: List<String> = themeUtils.getOverlayPackagesForCategory(CATEGORY, "android")
        .let { all ->
            val (default, rest) = all.partition { it == "android" }
            default + rest.sortedBy { getLabelSafe(context, it).lowercase() }
        }

    private var fontPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingCustomFontTypeface: Typeface? = null
    private var pendingCustomFontUri: Uri? = null
    private var customFontName: String = ""
    private var hasCustomFont: Boolean = false

    private val customTypeface: Typeface by lazy {
        ExternalFontInstaller(context).loadInstalledTypeface()
            ?: Typeface.create(ExternalFontInstaller.DEFAULT_FONT_FAMILY, Typeface.NORMAL)
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        loadCustomFontState()
        updateSummary()

        val activity = context as? FragmentActivity ?: return
        fontPickerLauncher = activity.activityResultRegistry.register(
            "font_picker_${key}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { handleFontUriSelected(it, activity) }
        }
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        fontPickerLauncher?.unregister()
        fontPickerLauncher = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context
        dialog?.dismiss()

        // The custom font, when installed, is listed first and is the selected row.
        val offset = if (hasCustomFont) 1 else 0
        val current = if (hasCustomFont) CUSTOM_PKG_KEY else getAppliedPkg()
        val options = buildList {
            if (hasCustomFont) {
                add(PickerOption(customFontName, labelFontFamily = { FontFamily(customTypeface) }))
            }
            pkgs.forEach { pkg ->
                add(
                    PickerOption(
                        label = if (pkg == "android") "Default" else getLabelSafe(ctx, pkg),
                        labelFontFamily = { getTypefaceSafe(ctx, pkg)?.let { FontFamily(it) } },
                    )
                )
            }
        }
        val selectedIndex = if (hasCustomFont) {
            0
        } else {
            pkgs.indexOf(current).let { if (it < 0) 0 else it }
        }

        dialog = ctx.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            action = PickerAction(ctx.getString(R.string.add_font)) {
                fontPickerLauncher?.launch(arrayOf("font/ttf", "font/otf"))
            },
            onClosed = { dialog = null },
        ) { index ->
            // The active custom font can't be re-applied; leave the picker open.
            if (hasCustomFont && index == 0) return@showPickerDialog false
            val pkg = pkgs[index - offset]
            if (pkg == current) return@showPickerDialog false
            applyFont(ctx, old = current, pkg = pkg)
            true
        }
    }

    private fun applyFont(ctx: Context, old: String, pkg: String) {
        val reboot = old == CUSTOM_PKG_KEY
        ctx.showChoiceDialog(
            title = ctx.getString(
                if (reboot) R.string.reboot_required_title else R.string.systemui_restart_title
            ),
            message = ctx.getString(
                if (reboot) R.string.reboot_required_message else R.string.systemui_restart_message
            ),
            confirmText = ctx.getString(if (reboot) R.string.action_reboot_now else R.string.action_yes),
            laterText = ctx.getString(
                if (reboot) R.string.action_later else R.string.systemui_restart_not_now
            ),
            onConfirm = {
                applyOverlayInBackground(
                    ctx,
                    if (reboot) getAppliedPkg() else old,
                    pkg,
                    resetCustom = reboot,
                    onFailed = {
                        Toast.makeText(ctx, R.string.toast_failed_apply_font, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    if (reboot) clearCustomFontState()
                    updateSummary()
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
                            CATEGORY, if (reboot) getAppliedPkg() else old, old
                        )
                        themeUtils.setOverlayEnabled(CATEGORY, pkg, "android")
                        updateSummary()
                    }
                }, "FontOverlay").start()
            },
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
        customFontName = ""
        hasCustomFont = false
        Settings.Secure.putString(context.contentResolver, "custom_font_name", "")
    }

    private fun getAppliedPkg(): String {
        return themeUtils.getOverlayInfos(CATEGORY)
            .firstOrNull { it.isEnabled }?.packageName ?: "android"
    }

    private fun loadCustomFontState() {
        customFontName = Settings.Secure.getString(
            context.contentResolver, "custom_font_name"
        ) ?: ""
        hasCustomFont = customFontName.isNotEmpty()
    }

    private fun updateSummary() {
        summary = when {
            hasCustomFont -> customFontName
            else -> {
                val applied = getAppliedPkg()
                if (applied == "android") "Default" else getLabelSafe(context, applied)
            }
        }
    }

    private fun handleFontUriSelected(uri: Uri, activity: FragmentActivity) {
        val installer = ExternalFontInstaller(context)
        activity.lifecycleScope.launch {
            val typeface = installer.loadTypefaceFromUri(uri)
            if (typeface == null) {
                Toast.makeText(context, R.string.toast_invalid_font_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingCustomFontTypeface = typeface
            pendingCustomFontUri = uri
            showCustomFontPreviewDialog(uri, typeface, installer, activity)
        }
    }

    private fun showCustomFontPreviewDialog(
        uri: Uri,
        typeface: Typeface,
        installer: ExternalFontInstaller,
        activity: FragmentActivity
    ) {
        val clear = {
            pendingCustomFontUri = null
            pendingCustomFontTypeface = null
        }
        context.showComposeDialog { dismiss ->
            ExpressiveDialog(
                onDismissRequest = { dismiss(); clear() },
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
                        installCustomFont(uri, installer, activity)
                    }) { Text(stringResource(R.string.add_font)) }
                },
                dismissButton = {
                    ExpressiveTextButton(onClick = { dismiss(); clear() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }

    private fun installCustomFont(
        uri: Uri,
        installer: ExternalFontInstaller,
        activity: FragmentActivity
    ) {
        activity.lifecycleScope.launch {
            val postScriptName = installer.installFontFromUri(uri)
            if (postScriptName != null) {
                Settings.Secure.putString(
                    context.contentResolver, "custom_font_name", postScriptName
                )
                customFontName = postScriptName
                hasCustomFont = true
                updateSummary()
                context.showChoiceDialog(
                    title = context.getString(R.string.reboot_required_title),
                    message = context.getString(R.string.reboot_required_custom_font_title),
                    confirmText = context.getString(R.string.action_reboot_now),
                    laterText = context.getString(R.string.action_later),
                    onConfirm = { ExternalFontInstaller.rebootDevice() },
                    onLater = {},
                )
            }
            pendingCustomFontUri = null
            pendingCustomFontTypeface = null
        }
    }

    private fun getTypefaceSafe(context: Context, pkg: String): Typeface? {
        return try {
            val pm = context.packageManager
            val res = if (pkg == "android") Resources.getSystem() else pm.getResourcesForApplication(pkg)
            val id = res.getIdentifier("config_bodyFontFamily", "string", pkg)
            if (id == 0) return null
            Typeface.create(res.getString(id), Typeface.NORMAL)
        } catch (e: Exception) {
            Log.e(TAG, "Typeface load failed for pkg: $pkg", e)
            null
        }
    }

    companion object {
        private const val TAG = "FontsPickerPreference"
        private const val CATEGORY = FONT_KEY
        private const val CUSTOM_PKG_KEY = "__custom_font__"

        private fun getLabelSafe(context: Context, pkg: String): String {
            return try {
                val pm = context.packageManager
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }
}
