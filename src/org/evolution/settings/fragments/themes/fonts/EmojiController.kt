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
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
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
 * Reads and changes the emoji style. Stock is the system emoji; every other style is an emoji
 * font installed through the framework's custom emoji API: one downloaded from the online
 * catalog, or one the person picked from a file. The framework puts it in front of the system
 * emoji fallbacks, so it takes effect once SystemUI and the running apps restart.
 */
class EmojiController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val installer by lazy { ExternalEmojiInstaller(context) }
    private val tracker = FontRestartTracker(context, "emoji_picker_state")
    private val online by lazy { OnlineEmojiStore(context) }
    private var catalogLoading = false

    /** The online style being downloaded, with how far it is (0..1), or null. */
    internal var downloading by mutableStateOf<Pair<String, Float>?>(null)
        private set

    /** Whether the online catalog could not be loaded and there is no saved one to show. */
    internal var catalogFailed by mutableStateOf(false)
        private set

    /** A restart or reboot that was put off and still has to happen, or null. */
    internal var pending by mutableStateOf<PendingRestart?>(tracker.pending())
        private set

    /** Sets the summary of [pref] to Stock at once, then to the installed font if any. */
    fun bindSummary(pref: Preference) {
        pref.summary = withPending(context.getString(R.string.emoji_style_stock))
        Thread({
            val name = installer.installedName()
            if (name.isNotEmpty()) {
                val label = online.installedEntry(name)?.name ?: customLabel(name)
                mainHandler.post { pref.summary = withPending(label) }
            }
        }, "EmojiState").start()
    }

    private fun withPending(label: String): String = when (tracker.pending()?.kind) {
        RestartKind.SYSTEMUI -> context.getString(R.string.font_summary_pending_systemui, label)
        RestartKind.REBOOT -> context.getString(R.string.font_summary_pending_reboot, label)
        null -> label
    }

    /** Re-reads the pending state, which clears itself once the restart has happened. */
    fun refreshPending() {
        pending = tracker.pending()
    }

    /** Does the restart or reboot that was put off. */
    fun restartPendingNow(ctx: Context) {
        when (pending?.kind ?: return) {
            RestartKind.REBOOT -> ExternalFontInstaller.rebootDevice()
            RestartKind.SYSTEMUI -> {
                tracker.clear()
                pending = null
                restartNow(ctx)
            }
        }
    }

    private fun markPending(kind: RestartKind, label: String) {
        tracker.markPending(kind, label)
        pending = tracker.pending()
    }

    /**
     * The custom font first, then Stock, then the online styles: those that are downloaded
     * with their own look, the others as a download. Blocks on binder calls.
     */
    fun loadOptions(): List<StyleOption> {
        installer.dropLegacyInstall()
        dropLegacyStyle()
        val name = installer.installedName()
        val file = if (name.isNotEmpty()) installer.installedFile() else null
        val styles = online.saved()
        // A font installed from the catalog is the card of that style, not a custom font.
        val fromCatalog = online.installedEntry(name)
        return buildList {
            if (name.isNotEmpty() && fromCatalog == null) {
                add(StyleOption(CUSTOM_KEY, customLabel(name), typefaceOf(file)))
            }
            add(
                StyleOption(
                    STOCK_KEY,
                    context.getString(R.string.emoji_style_stock),
                    typefaceOf(File(STOCK_EMOJI))
                )
            )
            styles.forEach { style ->
                val local = if (online.isDownloaded(style)) online.file(style) else null
                add(
                    StyleOption(
                        key = ONLINE_PREFIX + style.id,
                        label = style.name,
                        typeface = typefaceOf(local),
                        download = DownloadInfo(style.size, local != null)
                    )
                )
            }
        }
    }

    /**
     * [CUSTOM_KEY] while a custom font is installed, the key of the online style it was
     * installed from, else [STOCK_KEY]. Blocks.
     */
    fun appliedKey(): String {
        val name = installer.installedName()
        if (name.isEmpty()) return STOCK_KEY
        return online.installedEntry(name)?.let { ONLINE_PREFIX + it.id } ?: CUSTOM_KEY
    }

    /** Whether another emoji operation is running, which is also said to the person. */
    fun ensureIdle(): Boolean {
        if (ExternalEmojiInstaller.isBusy) toast(R.string.toast_emoji_busy)
        return !ExternalEmojiInstaller.isBusy
    }

    /**
     * Goes back to the stock emoji by removing the installed font, which also puts back the
     * emoji files that applying a font overwrote in apps such as Gboard. Like installing one it
     * ends in the restart dialog, which can be put off. Does nothing while another emoji
     * operation runs.
     */
    fun applyStock(onChanged: () -> Unit) {
        runExclusive {
            // Must finish before the restart dialog, or a quick restart cuts it off.
            val result = withContext(Dispatchers.IO) { installer.clear() }
            if (result != ExternalEmojiInstaller.Result.SUCCESS) {
                showResult(result)
                return@runExclusive
            }
            online.clearInstalled()
            onChanged()
            if (isScreenAlive()) {
                showRestartDialog(context, context.getString(R.string.emoji_style_stock))
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

    /**
     * Loads the online catalog when the saved one is missing or a day old, or at once when
     * [force] is set, then calls [onChanged]. Leaves a saved catalog alone when the network
     * fails.
     */
    fun refreshCatalog(force: Boolean = false, onChanged: () -> Unit) {
        if (catalogLoading) return
        catalogLoading = true
        ExternalEmojiInstaller.scope.launch {
            val failed = withContext(Dispatchers.IO) {
                if (!force && !online.isStale()) return@withContext false
                runCatching { online.refresh() }
                    .onFailure { Log.w(TAG, "Emoji catalog unavailable", it) }
                    .isFailure && online.saved().isEmpty()
            }
            catalogLoading = false
            catalogFailed = failed
            onChanged()
        }
    }

    /** Downloads the online style [key] and keeps it, one download at a time. */
    fun download(key: String, onChanged: () -> Unit) {
        if (downloading != null) {
            toast(R.string.toast_emoji_busy)
            return
        }
        downloading = key to 0f
        ExternalEmojiInstaller.scope.launch {
            val error = withContext(Dispatchers.IO) {
                val style = styleFor(key) ?: return@withContext R.string.emoji_error_download
                runCatching {
                    OnlineEmojiCatalog.download(style, online.file(style)) { downloading = key to it }
                }.exceptionOrNull()?.let {
                    Log.w(TAG, "Download of $key failed", it)
                    if (it is OnlineEmojiCatalog.ChecksumException) {
                        R.string.emoji_error_checksum
                    } else {
                        R.string.emoji_error_download
                    }
                }
            }
            downloading = null
            if (error != null) toast(error)
            onChanged()
        }
    }

    /** Deletes the downloaded file of the online style [key], which can be downloaded again. */
    fun deleteDownload(key: String, onChanged: () -> Unit) {
        ExternalEmojiInstaller.scope.launch {
            withContext(Dispatchers.IO) { styleFor(key)?.let(online::delete) }
            onChanged()
        }
    }

    /**
     * Installs the downloaded online style [key] as the system emoji. Like a custom font it
     * takes effect once SystemUI and the running apps restart, which can be put off.
     */
    fun applyOnline(key: String, onChanged: () -> Unit) {
        runExclusive {
            val style = withContext(Dispatchers.IO) {
                styleFor(key)?.takeIf { online.isDownloaded(it) }
            }
            if (style == null) {
                toast(R.string.emoji_error_download)
                return@runExclusive
            }
            val result = installer.installFile(online.file(style))
            if (result == ExternalEmojiInstaller.Result.SUCCESS) {
                withContext(Dispatchers.IO) { online.markInstalled(style, installer.installedName()) }
                onChanged()
                if (isScreenAlive()) showRestartDialog(context, style.name)
            } else {
                showResult(result)
            }
        }
    }

    private fun styleFor(key: String): OnlineEmoji? =
        online.saved().firstOrNull { ONLINE_PREFIX + it.id == key }

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
                online.clearInstalled()
                val label = withContext(Dispatchers.IO) { customLabel(installer.installedName()) }
                if (isScreenAlive()) showRestartDialog(context, label)
            } else {
                showResult(result)
            }
        }
    }

    private fun isScreenAlive(): Boolean {
        val activity = context as? Activity ?: return false
        return !activity.isFinishing && !activity.isDestroyed
    }

    /** SystemUI picks the font up when it restarts; running apps when their process does. */
    private fun showRestartDialog(ctx: Context, label: String) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.emoji_restart_title),
            message = ctx.getString(R.string.emoji_restart_message),
            confirmText = ctx.getString(R.string.emoji_restart_now),
            laterText = ctx.getString(R.string.action_later),
            onConfirm = { restartNow(ctx) },
            onLater = { markPending(RestartKind.SYSTEMUI, label) },
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
                online.clearInstalled()
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

    private fun customLabel(name: String): String = context.getString(R.string.emoji_custom_label, name)

    private fun typefaceOf(file: File?): Typeface? {
        if (file == null || !file.canRead()) return null
        return runCatching { Typeface.Builder(file).build() }.getOrNull()
    }

    /**
     * Builds that bundled the emoji styles kept the choice in a property that nothing reads
     * now. Put it back to Stock, which is what the framework already shows.
     */
    private fun dropLegacyStyle() {
        if (SystemProperties.get(LEGACY_STYLE_PROP, STOCK_KEY) == STOCK_KEY) return
        SystemProperties.set(LEGACY_STYLE_PROP, STOCK_KEY)
        SystemPropPoker.getInstance().poke()
    }

    private fun typefaceOf(file: File?): Typeface? {
        if (file == null || !file.canRead()) return null
        return runCatching { Typeface.Builder(file).build() }.getOrNull()
    }

    companion object {
        private const val TAG = "EmojiController"

        // Set by builds that bundled the emoji styles, which no longer exist.
        private const val LEGACY_STYLE_PROP = "persist.sys.ax_emoji_style"
        private const val STOCK_EMOJI = "/system/fonts/NotoColorEmoji.ttf"

        /** Key of the stock emoji in [StyleOption] and [appliedKey]. */
        const val STOCK_KEY = "android"

        /** Key of the installed custom font in [StyleOption] and [appliedKey]. */
        const val CUSTOM_KEY = "__custom_emoji__"

        /** Prefix of the key of an online style in [StyleOption] and [appliedKey]. */
        const val ONLINE_PREFIX = "online:"

        /** Whether [key] is an installed font rather than the stock emoji. */
        fun isInstalledFont(key: String) = key == CUSTOM_KEY || key.startsWith(ONLINE_PREFIX)

        // Grinning face, heart eyes, party popper, red heart, thumbs up.
        const val PREVIEW_EMOJI = "\uD83D\uDE00\uD83D\uDE0D\uD83C\uDF89\u2764\uFE0F\uD83D\uDC4D"
    }
}
