/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsDimension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.evolution.settings.fragments.miscellaneous.ExpressiveEmptyState
import org.evolution.settings.fragments.miscellaneous.ExpressiveGroup
import org.evolution.settings.fragments.miscellaneous.ExpressiveRow
import org.evolution.settings.fragments.miscellaneous.ExpressiveScreen
import org.evolution.settings.fragments.miscellaneous.ExpressiveTheme
import org.evolution.settings.fragments.miscellaneous.expressiveInset
import org.evolution.settings.fragments.miscellaneous.iconButtonFocusRing

/**
 * Lists the songs saved with the heart of the non-Pixel Now Playing indication.
 *
 * The list is the one SystemUI keeps in Settings.System, see [SavedSongsStore], newest first.
 * Tapping a song plays it in the music app, the cross removes it.
 */
class NowPlayingSavedSongs : SettingsPreferenceFragment() {

    companion object {
        private const val TAG = "NowPlayingSavedSongs"
    }

    private var songs by mutableStateOf<List<SavedSong>>(emptyList())

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.nowplaying_saved_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ExpressiveTheme {
                SavedSongsContent(songs = songs, onPlay = ::play, onRemove = ::remove)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        songs = readSongs()
    }

    override fun getMetricsCategory(): Int = MetricsEvent.EVOLVER

    private fun readSongs(): List<SavedSong> {
        return try {
            val array = SavedSongsStore.read(requireContext())
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val title = entry.optString("title")
                if (title.isEmpty()) {
                    null
                } else {
                    SavedSong(title, entry.optString("artist"), entry.optString("albumArt").ifEmpty { null })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable saved songs list", e)
            emptyList()
        }
    }

    /** Removes the song. The list is read again first because SystemUI may have changed it. */
    private fun remove(song: SavedSong) {
        SavedSongsStore.remove(requireContext(), song.title, song.artist)
        songs = readSongs()
    }

    private fun play(song: SavedSong) = playSong(requireContext(), song.title, song.artist)
}

private class SavedSong(val title: String, val artist: String, val artUrl: String?)

private val ArtSize = 48.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SavedSongsContent(
    songs: List<SavedSong>,
    onPlay: (SavedSong) -> Unit,
    onRemove: (SavedSong) -> Unit,
) {
    val context = LocalContext.current
    // Covers of songs that were removed are not needed any more.
    LaunchedEffect(songs) {
        withContext(Dispatchers.IO) {
            SavedSongArt.prune(context, songs.mapNotNull { it.artUrl })
        }
    }
    ExpressiveScreen {
        if (songs.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.nowplaying_saved_empty_title),
                description = stringResource(R.string.nowplaying_saved_empty_summary),
                modifier = Modifier.expressiveInset(),
            )
        } else {
            ExpressiveGroup(title = "") {
                songs.forEach { song ->
                    item {
                        ExpressiveRow(
                            title = song.title,
                            summary = song.artist,
                            leading = { SongArt(song.artUrl) },
                            onClick = { onPlay(song) },
                            trailing = {
                                IconButton(
                                    shapes = IconButtonDefaults.shapes(),
                                    modifier = Modifier.iconButtonFocusRing(),
                                    onClick = { onRemove(song) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.nowplaying_saved_remove),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The cover of a saved song, or a music note while it loads or when the song has none. */
@Composable
private fun SongArt(url: String?) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { ArtSize.roundToPx() }
    val art by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url == null) {
            null
        } else {
            withContext(Dispatchers.IO) { SavedSongArt.load(context, url, targetPx) }
                ?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .size(ArtSize)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = art
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ArtSize),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
