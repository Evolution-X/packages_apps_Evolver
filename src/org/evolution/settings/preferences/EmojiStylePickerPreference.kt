/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.util.AttributeSet
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.internal.util.evolution.SystemRestartUtils
import com.android.settings.R
import com.android.settingslib.development.SystemPropPoker
import kotlinx.coroutines.launch
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.fragments.themes.fonts.ExternalEmojiInstaller
import org.evolution.settings.fragments.themes.fonts.ExternalFontInstaller

/**
 * Emoji style picker: the bundled styles from persist.sys.ax_emoji_style, plus a user-picked
 * emoji font installed as the top priority und-Zsye fallback. An installed custom font wins
 * over any bundled style, so picking a bundled style removes it first.
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

    private var emojiPickerLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
        Thread({
            installer.repairIfStale()
            mainHandler.post { updateSummary() }
        }, "EmojiRepair").start()

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
        val hasCustom = installer.hasCustomEmoji()
        val offset = if (hasCustom) 1 else 0
        val current = SystemProperties.get(key, DEFAULT_STYLE)
        val options = buildList {
            if (hasCustom) add(PickerOption(customLabel(ctx)))
            entries.forEach { add(PickerOption(it)) }
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
            val value = values[index - offset]
            if (!hasCustom && value == current) return@showPickerDialog false
            applyStyle(ctx, value, hadCustom = hasCustom)
            true
        }
    }

    private fun applyStyle(ctx: Context, value: String, hadCustom: Boolean) {
        Thread({
            if (hadCustom) installer.resetEmojiUpdates()
            SystemProperties.set(key, value)
            SystemPropPoker.getInstance().poke()
            mainHandler.post {
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
        ctx.getString(R.string.emoji_custom_label, installer.installedName())

    private fun updateSummary() {
        summary = if (installer.hasCustomEmoji()) {
            customLabel(context)
        } else {
            val index = values.indexOf(SystemProperties.get(key, DEFAULT_STYLE))
            entries[if (index < 0) 0 else index]
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
            showEmojiPreviewDialog(uri, typeface, installer.previewCoverage(), activity)
        }
    }

    private fun showEmojiPreviewDialog(
        uri: Uri,
        typeface: Typeface,
        coverage: Pair<Int, Int>,
        activity: FragmentActivity
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
                        installEmojiFont(uri, activity)
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

    private fun installEmojiFont(uri: Uri, activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            val result = installer.installEmojiFromUri(uri)
            updateSummary()
            if (result == ExternalEmojiInstaller.Result.SUCCESS) {
                showRebootDialog(context)
            } else {
                showResult(result)
            }
        }
    }

    private fun showResult(result: ExternalEmojiInstaller.Result) {
        when (result) {
            ExternalEmojiInstaller.Result.SUCCESS -> Unit
            ExternalEmojiInstaller.Result.REVISION_TOO_OLD -> toast(R.string.toast_emoji_font_too_old)
            ExternalEmojiInstaller.Result.INVALID_FONT -> toast(R.string.toast_invalid_emoji_file)
            ExternalEmojiInstaller.Result.UPDATER_DISABLED ->
                toast(R.string.toast_emoji_updater_disabled)
            ExternalEmojiInstaller.Result.SLOTS_FULL -> toast(R.string.toast_emoji_slots_full)
            ExternalEmojiInstaller.Result.FAILED -> toast(R.string.toast_failed_apply_emoji)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEFAULT_STYLE = "android"
    }
}
