/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.res.Configuration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import org.evolution.settings.utils.SystemUtils
import java.io.File
import kotlin.math.min

class SoundPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: AlertDialog? = null
    private var recyclerView: RecyclerView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playingPath: String? = null

    // These are set from XML attributes or programmatically
    @JvmField var entries: Array<CharSequence> = emptyArray()
    @JvmField var entryValues: Array<CharSequence> = emptyArray()
    @JvmField var settingKey: String = ""
    @JvmField var defaultValue: String = ""

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
    }

    override fun onDetached() {
        releasePlayer()
        dialog?.dismiss()
        dialog = null
        recyclerView = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context
        val content = LayoutInflater.from(ctx).inflate(R.layout.selector_item_view, null)

        recyclerView = content.findViewById<RecyclerView>(R.id.recycler_view).apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(ctx)
            adapter = SoundAdapter(ctx)

            post {
                val isLandscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val fraction = if (isLandscape) 0.75f else 0.6f
                val maxHeight = (ctx.resources.displayMetrics.heightPixels * fraction).toInt()
                layoutParams.height = maxHeight
                requestLayout()
            }
        }

        dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnDismissListener {
                    releasePlayer()
                    dialog = null
                    recyclerView = null
                }
                dlg.show()
                applyDialogWidth(dlg)
                tintDialogAccent(dlg)
            }
    }

    private fun applyDialogWidth(dlg: AlertDialog) {
        val w = dlg.window ?: return
        val ctx = context
        val wm = ctx.getSystemService(WindowManager::class.java)
        val boundsWidthPx = try {
            wm.currentWindowMetrics.bounds.width()
        } catch (_: Throwable) {
            ctx.resources.displayMetrics.widthPixels
        }
        val density = ctx.resources.displayMetrics.density
        val maxDp = if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            DIALOG_MAX_WIDTH_DP_LANDSCAPE
        } else {
            DIALOG_MAX_WIDTH_DP
        }
        val maxWidthPx = (maxDp * density + 0.5f).toInt()
        val targetPx = (boundsWidthPx * 0.90f).toInt()
        w.setLayout(min(maxWidthPx, targetPx), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun tintDialogAccent(dlg: AlertDialog) {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) return
        val accent = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        if (accent == 0) return
        val w = dlg.window
        if (w != null) {
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = if (titleId != 0) w.decorView.findViewById<TextView>(titleId) else null
            titleView?.setTextColor(accent)
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
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

    private fun togglePreview(path: String, playButton: ImageButton, adapter: SoundAdapter) {
        if (playingPath == path && mediaPlayer?.isPlaying == true) {
            // Stop current
            releasePlayer()
            adapter.notifyPlayingChanged(null)
            return
        }

        // Stop previous
        releasePlayer()
        adapter.notifyPlayingChanged(path)

        try {
            val file = File(path)
            if (!file.exists()) return

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                start()
                setOnCompletionListener {
                    releasePlayer()
                    adapter.notifyPlayingChanged(null)
                }
            }
            playingPath = path
        } catch (_: Exception) {
            releasePlayer()
            adapter.notifyPlayingChanged(null)
        }
    }

    private inner class SoundAdapter(
        private val ctx: Context
    ) : RecyclerView.Adapter<SoundAdapter.SoundViewHolder>() {

        private var selectedValue: String = getCurrentValue()
        private var currentPlayingPath: String? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun notifyPlayingChanged(path: String?) {
            val old = currentPlayingPath
            currentPlayingPath = path
            // Refresh old and new playing items to update button icon
            entryValues.indexOfFirst { it.toString() == old }.takeIf { it >= 0 }
                ?.let { notifyItemChanged(it) }
            entryValues.indexOfFirst { it.toString() == path }.takeIf { it >= 0 }
                ?.let { notifyItemChanged(it) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SoundViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.sound_option, parent, false)
            return SoundViewHolder(v)
        }

        override fun onBindViewHolder(holder: SoundViewHolder, position: Int) {
            val label = entries[position].toString()
            val value = entryValues[position].toString()
            val isPlaying = currentPlayingPath == value && mediaPlayer?.isPlaying == true

            holder.title.text = label
            holder.title.setTextColor(resolveTextColorPrimary(ctx))
            holder.itemView.isActivated = (value == selectedValue)

            holder.playButton.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            holder.playButton.setOnClickListener {
                togglePreview(value, holder.playButton, this)
            }

            holder.itemView.setOnClickListener {
                if (value == selectedValue) return@setOnClickListener
                val old = selectedValue
                selectedValue = value
                notifyItemChanged(entryValues.indexOfFirst { it.toString() == old }.takeIf { it >= 0 } ?: 0)
                notifyItemChanged(position)
                dialog?.dismiss()
                releasePlayer()
                notifyPlayingChanged(null)
                Settings.Global.putString(ctx.contentResolver, settingKey, value)
                updateSummary()
                SystemUtils.showSystemUiRestartDialog(ctx)
            }
        }

        override fun getItemCount(): Int = entries.size

        private fun resolveTextColorPrimary(ctx: Context): Int {
            val tv = TypedValue()
            return if (ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
                if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
            } else 0xff000000.toInt()
        }

        inner class SoundViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.option_title)
            val playButton: ImageButton = itemView.findViewById(R.id.option_play)
        }
    }

    companion object {
        private const val DIALOG_MAX_WIDTH_DP = 360
        private const val DIALOG_MAX_WIDTH_DP_LANDSCAPE = 640
    }
}
