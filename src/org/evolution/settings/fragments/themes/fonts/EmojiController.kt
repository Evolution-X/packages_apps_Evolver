/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import com.android.internal.util.evolution.SystemRestartUtils
import com.android.settings.R
import com.android.settingslib.development.SystemPropPoker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.preferences.showChoiceDialog
import org.evolution.settings.preferences.showComposeDialog
import org.evolution.settings.utils.SystemUtils

/**
 * Reads and changes the emoji style: the bundled styles from persist.sys.ax_emoji_style, plus a
 * user-picked emoji font installed as the top priority und-Zsye fallback. An installed custom font
 * wins over any bundled style, so picking a bundled style removes it first.
 *
 * The custom font goes through the framework's emoji API and takes effect once SystemUI and the
 * running apps restart. A bundled style is read at boot, so changing it still asks for a reboot.
 */
class EmojiController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val installer by lazy { ExternalEmojiInstaller(context) }

    private val entries: Array<String> =
        context.resources.getStringArray(R.array.emoji_style_entries)
    private val values: Array<String> =
        context.resources.getStringArray(R.array.emoji_style_values)

    /** Sets the summary of [pref] to the bundled style at once, then to the custom font if any. */
    fun bindSummary(pref: Preference) {
        pref.summary = styleLabel()
        Thread({
            val name = installer.installedName()
            if (name.isNotEmpty()) mainHandler.post { pref.summary = customLabel(name) }
        }, "EmojiState").start()
    }

    /** The custom font first, then the bundled styles. Blocks on binder calls. */
    fun loadOptions(): List<StyleOption> {
        installer.dropLegacyInstall()
        val name = installer.installedName()
        val file = if (name.isNotEmpty()) installer.installedFile() else null
        return buildList {
            if (name.isNotEmpty()) add(StyleOption(CUSTOM_KEY, customLabel(name), typefaceOf(file)))
            entries.forEachIndexed { i, entry ->
                add(StyleOption(values[i], entry, typefaceOf(STYLE_FONTS[values[i]]?.let(::File))))
            }
        }
    }

    /** [CUSTOM_KEY] while a custom font is installed, else the bundled style. Blocks. */
    fun appliedKey(): String =
        if (installer.installedName().isNotEmpty()) CUSTOM_KEY else values[styleIndex()]

    /** Whether another emoji operation is running, which is also said to the person. */
    fun ensureIdle(): Boolean {
        if (ExternalEmojiInstaller.isBusy) toast(R.string.toast_emoji_busy)
        return !ExternalEmojiInstaller.isBusy
    }

    /**
     * Switches to the bundled style [value]. Leaving a custom font ([hadCustom]) removes it and
     * needs a reboot; a bundled style only restarts SystemUI. Does nothing while another emoji
     * operation runs.
     */
    fun apply(ctx: Context, value: String, hadCustom: Boolean, onChanged: () -> Unit) {
        runExclusive {
            // Also puts back the emoji files that applying a custom font overwrote in apps such
            // as Gboard. Must finish before the restart dialog, or a quick reboot cuts it off.
            val result = withContext(Dispatchers.IO) { installer.clear() }
            if (hadCustom && result != ExternalEmojiInstaller.Result.SUCCESS) {
                showResult(result)
                return@runExclusive
            }
            withContext(Dispatchers.IO) {
                SystemProperties.set(KEY, value)
                SystemPropPoker.getInstance().poke()
            }
            onChanged()
            if (hadCustom) {
                showRebootDialog(ctx)
            } else {
                SystemRestartUtils.showSystemRestartDialog(ctx)
            }
        }
    }

    /** Loads the picked emoji font file, shows a preview of it and installs it once confirmed. */
    fun importEmoji(uri: Uri, scope: CoroutineScope, onChanged: () -> Unit) {
        // The preview shares one cache file with an install that may still be running.
        if (!ensureIdle()) return
        scope.launch {
            val typeface = installer.loadTypefaceFromUri(uri)
            if (typeface == null) {
                Toast.makeText(context, R.string.toast_invalid_emoji_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val problem = installer.previewProblem()
            if (problem != null) {
                installer.discardPreview()
                showResult(problem)
                return@launch
            }
            showPreviewDialog(typeface, installer.previewCoverage(), onChanged)
        }
    }

    /** Copies the custom emoji font into the apps, after asking. */
    fun confirmResetApps(ctx: Context) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.emoji_apps_reset),
            message = ctx.getString(R.string.emoji_apps_confirm),
            confirmText = ctx.getString(R.string.emoji_apps_reset_button),
            laterText = ctx.getString(android.R.string.cancel),
            onConfirm = { resetApps() },
            onLater = {},
        )
    }

    /**
     * Puts back the app fonts an earlier reset overwrote, after asking. Picking the already
     * selected style can't, because that does nothing.
     */
    fun confirmRestoreApps(ctx: Context) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.emoji_apps_restore),
            message = ctx.getString(R.string.emoji_apps_restore_confirm),
            confirmText = ctx.getString(R.string.emoji_apps_restore_button),
            laterText = ctx.getString(android.R.string.cancel),
            onConfirm = { restoreApps() },
            onLater = {},
        )
    }

    private fun showPreviewDialog(
        typeface: Typeface,
        coverage: Pair<Int, Int>,
        onChanged: () -> Unit
    ) {
        context.showComposeDialog { dismiss ->
            ExpressiveDialog(
                onDismissRequest = { dismiss(); installer.discardPreview() },
                title = {
                    Text(
                        text = stringResource(R.string.emoji_preview_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                text = {
                    Column(Modifier.expressiveVerticalScroll()) {
                        Text(
                            text = stringResource(R.string.emoji_preview_sample),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 32.sp),
                            fontFamily = FontFamily(typeface),
                        )
                        Text(
                            text = stringResource(
                                R.string.emoji_preview_coverage, coverage.first, coverage.second
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    ExpressiveTextButton(onClick = {
                        dismiss()
                        installPreview(onChanged)
                    }) { Text(stringResource(R.string.add_emoji_font)) }
                },
                dismissButton = {
                    ExpressiveTextButton(onClick = { dismiss(); installer.discardPreview() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }

    // Runs in the process-wide scope so leaving the screen can't cancel it halfway.
    private fun installPreview(onChanged: () -> Unit) {
        runExclusive {
            val result = installer.installPreview()
            onChanged()
            if (result == ExternalEmojiInstaller.Result.SUCCESS) {
                if (isScreenAlive()) showRestartDialog(context)
            } else {
                showResult(result)
            }
        }
    }

    private fun isScreenAlive(): Boolean {
        val activity = context as? Activity ?: return false
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun showRebootDialog(ctx: Context) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.reboot_required_title),
            message = ctx.getString(R.string.reboot_required_custom_emoji_title),
            confirmText = ctx.getString(R.string.action_reboot_now),
            laterText = ctx.getString(R.string.action_later),
            onConfirm = { ExternalFontInstaller.rebootDevice() },
            onLater = {},
        )
    }

    /** SystemUI picks the font up when it restarts; running apps when their process does. */
    private fun showRestartDialog(ctx: Context) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.emoji_restart_title),
            message = ctx.getString(R.string.emoji_restart_message),
            confirmText = ctx.getString(R.string.emoji_restart_now),
            laterText = ctx.getString(R.string.action_later),
            onConfirm = { restartNow(ctx) },
            onLater = {},
        )
    }

    private fun restartNow(ctx: Context) {
        Thread({
            installer.killRunningApps()
            mainHandler.post { SystemUtils.restartSystemUI(ctx) }
        }, "EmojiRestart").start()
    }

    private fun resetApps() {
        runExclusive {
            val count = withContext(Dispatchers.IO) { installer.applyToApps() }
            when {
                count >= 0 -> Toast.makeText(
                    context,
                    context.resources.getQuantityString(R.plurals.emoji_apps_done, count, count),
                    Toast.LENGTH_LONG
                ).show()
                count == FontManager.RESULT_ERROR_FONT_NOT_FOUND ->
                    toast(R.string.toast_emoji_not_installed)
                else -> toast(R.string.toast_failed_apply_emoji)
            }
        }
    }

    /**
     * clearCustomEmoji does this whether or not a custom font is installed, so it also repairs
     * apps left with the old font after the custom one was removed.
     */
    private fun restoreApps() {
        runExclusive {
            val result = withContext(Dispatchers.IO) { installer.clear() }
            if (result == ExternalEmojiInstaller.Result.SUCCESS) {
                Toast.makeText(context, R.string.emoji_apps_restored, Toast.LENGTH_LONG).show()
            } else {
                showResult(result)
            }
        }
    }

    private fun showResult(result: ExternalEmojiInstaller.Result) {
        when (result) {
            ExternalEmojiInstaller.Result.SUCCESS -> Unit
            ExternalEmojiInstaller.Result.INVALID_FONT -> toast(R.string.toast_invalid_emoji_file)
            ExternalEmojiInstaller.Result.UPDATER_DISABLED ->
                toast(R.string.toast_emoji_updater_disabled)
            ExternalEmojiInstaller.Result.NOT_INSTALLED -> toast(R.string.toast_emoji_not_installed)
            ExternalEmojiInstaller.Result.FAILED -> toast(R.string.toast_failed_apply_emoji)
        }
    }

    /** Runs [block] unless another emoji operation is running, and says so when it isn't. */
    private fun runExclusive(block: suspend () -> Unit) {
        if (!ExternalEmojiInstaller.runExclusive(block)) toast(R.string.toast_emoji_busy)
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private fun styleIndex(): Int =
        values.indexOf(SystemProperties.get(KEY, DEFAULT_STYLE)).let { if (it < 0) 0 else it }

    private fun styleLabel(): String = entries[styleIndex()]

    private fun customLabel(name: String): String = context.getString(R.string.emoji_custom_label, name)

    private fun typefaceOf(file: File?): Typeface? {
        if (file == null || !file.canRead()) return null
        return runCatching { Typeface.Builder(file).build() }.getOrNull()
    }

    companion object {
        private const val KEY = "persist.sys.ax_emoji_style"
        private const val DEFAULT_STYLE = "android"

        /** Key of the installed custom font in [StyleOption] and [appliedKey]. */
        const val CUSTOM_KEY = "__custom_emoji__"

        // Grinning face, heart eyes, party popper, red heart, thumbs up.
        const val PREVIEW_EMOJI = "\uD83D\uDE00\uD83D\uDE0D\uD83C\uDF89\u2764\uFE0F\uD83D\uDC4D"

        // Font behind each emoji_style_values entry, for the previews only.
        private val STYLE_FONTS = mapOf(
            "android" to "/system/fonts/NotoColorEmoji.ttf",
            "ios" to "/product/fonts/LunarisIosEmoji.ttf",
            "samsung" to "/product/fonts/SamsungColorEmoji.ttf",
            "swiftui" to "/product/fonts/SwiftUIEmoji.ttf",
            "facebook" to "/product/fonts/FacebookEmoji.ttf",
        )
    }
}
