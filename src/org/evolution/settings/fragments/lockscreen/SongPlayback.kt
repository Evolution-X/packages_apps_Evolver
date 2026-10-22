/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.widget.Toast

import com.android.settings.R

/**
 * Plays the song with the generic play-from-search intent, like the play button of the
 * indication. A single handler is launched directly, several get the system chooser.
 */
internal fun playSong(context: Context, title: String, artist: String) {
    val query = listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" ")
    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(SearchManager.QUERY, query)
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
        if (artist.isNotEmpty()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
    }
    val packages = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .distinct()
    try {
        when (packages.size) {
            0 -> showNoMusicApp(context)
            1 -> context.startActivity(intent.setPackage(packages[0]))
            else -> context.startActivity(Intent.createChooser(intent, null))
        }
    } catch (e: ActivityNotFoundException) {
        showNoMusicApp(context)
    }
}

private fun showNoMusicApp(context: Context) {
    Toast.makeText(context, R.string.nowplaying_saved_no_music_app, Toast.LENGTH_SHORT).show()
}
