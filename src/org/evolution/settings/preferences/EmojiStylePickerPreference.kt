/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.util.AttributeSet
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.internal.util.evolution.SystemRestartUtils
import com.android.settings.R
import com.android.settingslib.development.SystemPropPoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.fragments.themes.fonts.ExternalEmojiInstaller
import org.evolution.settings.fragments.themes.fonts.ExternalFontInstaller
import org.evolution.settings.utils.SystemUtils
import java.io.File

/**
 * Emoji style picker: the bundled styles from persist.sys.ax_emoji_style, plus a user-picked
 * emoji font installed as the top priority und-Zsye fallback. An installed custom font wins
 * over any bundled style, so picking a bundled style removes it first.
 *
 * The custom font goes through the framework's emoji API and takes effect once SystemUI and the
 * running apps restart. A bundled style is read at boot, so changing it still asks for a reboot.
 */
class EmojiStylePickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installer by lazy { ExternalEmojiInstaller(context) }

    private val entries: Array<String> =
        context.resources.getStringArray(R.array.emoji_style_entries)
    private val values: Array<String> =
        context.resources.getStringArray(R.array.emoji_style_values)

    // Read off the main thread, because asking the framework is a binder call.
    private var customName: String = ""
    private var customFile: File? = null

    private var emojiPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private val previewFamilies = HashMap<String, FontFamily?>()

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
        Thread({
            installer.dropLegacyInstall()
            refreshState()
        }, "EmojiState").start()

        val activity = context as? FragmentActivity ?: return
        emojiPickerLauncher = activity.activityResultRegistry.register(
            "emoji_picker_${key}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { handleEmojiUriSelected(it, activity) }
        }
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        emojiPickerLauncher?.unregister()
        emojiPickerLauncher = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context
        dialog?.dismiss()

        // The custom emoji font, when installed, is listed first and is the selected row.
        val hasCustom = customName.isNotEmpty()
        val offset = if (hasCustom) 1 else 0
        val current = SystemProperties.get(key, DEFAULT_STYLE)
        val options = buildList {
            if (hasCustom) {
                add(PickerOption(customLabel(ctx), trailing = previewTrailing(customFile)))
            }
            entries.forEachIndexed { i, entry ->
                add(PickerOption(entry, trailing = previewTrailing(STYLE_FONTS[values[i]]?.let(::File))))
            }
            // Only useful with a custom font to copy into the apps.
            if (hasCustom) add(PickerOption(ctx.getString(R.string.emoji_apps_reset)))
        }
        val selectedIndex = if (hasCustom) {
            0
        } else {
            values.indexOf(current).let { if (it < 0) 0 else it }
        }

        dialog = ctx.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            action = PickerAction(ctx.getString(R.string.add_emoji_font)) {
                emojiPickerLauncher?.launch(
                    arrayOf(
                        "font/ttf",
                        "font/otf",
                        "application/x-font-ttf",
                        "application/x-font-otf",
                        "application/octet-stream",
                    )
                )
            },
            onClosed = { dialog = null },
        ) { index ->
            // The active custom font can't be re-applied; leave the picker open.
            if (hasCustom && index == 0) return@showPickerDialog false
            if (hasCustom && index == options.lastIndex) {
                confirmResetApps(ctx)
                return@showPickerDialog true
            }
            val value = values[index - offset]
            if (!hasCustom && value == current) return@showPickerDialog false
            applyStyle(ctx, value, hadCustom = hasCustom)
            true
        }
    }

    /** Five sample emoji drawn in the style's own font, loaded when its row scrolls into view. */
    private fun previewTrailing(file: File?): (@Composable () -> Unit)? {
        if (file == null) return null
        return {
            val family = remember(file) { previewFamily(file) }
            if (family != null) {
                Text(
                    text = PREVIEW_EMOJI,
                    fontFamily = family,
                    fontSize = 20.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    private fun previewFamily(file: File): FontFamily? = previewFamilies.getOrPut(file.path) {
        if (!file.canRead()) return@getOrPut null
        runCatching { Typeface.Builder(file).build()?.let { FontFamily(it) } }.getOrNull()
    }

    private fun applyStyle(ctx: Context, value: String, hadCustom: Boolean) {
        Thread({
            if (hadCustom) {
                val result = installer.clear()
                if (result != ExternalEmojiInstaller.Result.SUCCESS) {
                    mainHandler.post { showResult(result) }
                    return@Thread
                }
            }
            SystemProperties.set(key, value)
            SystemPropPoker.getInstance().poke()
            mainHandler.post {
                if (hadCustom) {
                    customName = ""
                    customFile = null
                }
                updateSummary()
                if (hadCustom) {
                    showRebootDialog(ctx)
                } else {
                    SystemRestartUtils.showSystemRestartDialog(ctx)
                }
            }
        }, "EmojiStyle").start()
    }

    private fun customLabel(ctx: Context): String =
        ctx.getString(R.string.emoji_custom_label, customName)

    private fun applySummary() {
        summary = if (customName.isNotEmpty()) {
            customLabel(context)
        } else {
            val index = values.indexOf(SystemProperties.get(key, DEFAULT_STYLE))
            entries[if (index < 0) 0 else index]
        }
    }

    /** Asks the framework for the installed font, then updates the summary. */
    private fun updateSummary() {
        applySummary()
        Thread({ refreshState() }, "EmojiState").start()
    }

    private fun refreshState() {
        val name = installer.installedName()
        val file = if (name.isNotEmpty()) installer.installedFile() else null
        mainHandler.post {
            customName = name
            customFile = file
            applySummary()
        }
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

    private fun handleEmojiUriSelected(uri: Uri, activity: FragmentActivity) {
        activity.lifecycleScope.launch {
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
            showEmojiPreviewDialog(typeface, installer.previewCoverage())
        }
    }

    private fun showEmojiPreviewDialog(typeface: Typeface, coverage: Pair<Int, Int>) {
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
                        installEmojiFont()
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
    private fun installEmojiFont() {
        ExternalEmojiInstaller.scope.launch {
            val result = installer.installPreview()
            updateSummary()
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

    private fun confirmResetApps(ctx: Context) {
        ctx.showChoiceDialog(
            title = ctx.getString(R.string.emoji_apps_reset),
            message = ctx.getString(R.string.emoji_apps_confirm),
            confirmText = ctx.getString(R.string.emoji_apps_reset_button),
            laterText = ctx.getString(android.R.string.cancel),
            onConfirm = { resetApps() },
            onLater = {},
        )
    }

    private fun resetApps() {
        ExternalEmojiInstaller.scope.launch {
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

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEFAULT_STYLE = "android"

        // Grinning face, heart eyes, party popper, red heart, thumbs up.
        private const val PREVIEW_EMOJI =
            "\uD83D\uDE00\uD83D\uDE0D\uD83C\uDF89\u2764\uFE0F\uD83D\uDC4D"

        // Font behind each emoji_style_values entry, for the row previews only.
        private val STYLE_FONTS = mapOf(
            "android" to "/system/fonts/NotoColorEmoji.ttf",
            "ios" to "/product/fonts/LunarisIosEmoji.ttf",
            "samsung" to "/product/fonts/SamsungColorEmoji.ttf",
            "swiftui" to "/product/fonts/SwiftUIEmoji.ttf",
            "facebook" to "/product/fonts/FacebookEmoji.ttf",
        )
    }
}
