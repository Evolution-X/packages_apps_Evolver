/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.preference.Preference
import com.android.settings.R
import org.evolution.settings.fragments.miscellaneous.iconButtonFocusRing
import org.evolution.settings.utils.SystemUtils
import java.io.File

class SoundPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null
    private var mediaPlayer: MediaPlayer? = null

    /** Path of the sound being previewed. Read by the rows, so they redraw when it changes. */
    private var playingPath by mutableStateOf<String?>(null)

    // These are set from XML attributes or programmatically
    @JvmField var entries: Array<CharSequence> = emptyArray()
    @JvmField var entryValues: Array<CharSequence> = emptyArray()
    @JvmField var settingKey: String = ""
    @JvmField var defaultValue: String = ""

    fun applyConfig() {
        updateSummary()
    }

    override fun onDetached() {
        releasePlayer()
        dialog?.dismiss()
        dialog = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context
        dialog?.dismiss()

        val current = getCurrentValue()
        val selectedIndex = entryValues.indexOfFirst { it.toString() == current }.coerceAtLeast(0)
        val options = entries.indices.map { index ->
            val value = entryValues[index].toString()
            PickerOption(label = entries[index].toString(), trailing = {
                val playing = playingPath == value
                IconButton(
                    modifier = Modifier.iconButtonFocusRing(),
                    onClick = { togglePreview(value) },
                ) {
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.ic_sound_pause else R.drawable.ic_sound_play
                        ),
                        contentDescription = stringResource(
                            if (playing) R.string.sound_preview_stop else R.string.sound_preview_play
                        ),
                    )
                }
            })
        }

        dialog = ctx.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            onClosed = {
                releasePlayer()
                dialog = null
            },
        ) { index ->
            val value = entryValues[index].toString()
            // Same sound again: leave the picker open.
            if (value == current) return@showPickerDialog false
            releasePlayer()
            Settings.Global.putString(ctx.contentResolver, settingKey, value)
            updateSummary()
            SystemUtils.showSystemUiRestartDialog(ctx)
            true
        }
    }

    private fun getCurrentValue(): String {
        return Settings.Global.getString(context.contentResolver, settingKey) ?: defaultValue
    }

    private fun updateSummary() {
        val current = getCurrentValue()
        val index = entryValues.indexOfFirst { it.toString() == current }
        summary = if (index >= 0) entries[index] else current
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        playingPath = null
    }

    private fun togglePreview(path: String) {
        // Tapping the sound that's playing stops it.
        if (playingPath == path && mediaPlayer?.isPlaying == true) {
            releasePlayer()
            return
        }

        releasePlayer()

        try {
            val file = File(path)
            if (!file.exists()) return

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                start()
                setOnCompletionListener { releasePlayer() }
            }
            playingPath = path
        } catch (_: Exception) {
            releasePlayer()
        }
    }
}
