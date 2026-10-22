/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

import org.json.JSONArray
import org.json.JSONObject

import java.util.Locale

/**
 * The saved songs list SystemUI keeps in Settings.System under [KEY], newest first. Entries are
 * matched on normalized title and artist, the same way SystemUI does it.
 */
internal object SavedSongsStore {
    private const val TAG = "SavedSongsStore"
    const val KEY = "now_playing_saved"
    private const val KEY_ALBUM_ART = "albumArt"

    // Same limits as SystemUI's SavedSongs, so a write from here fits the shared setting space.
    private const val MAX_ENTRIES = 100
    private const val MAX_CHARS = 8000

    private val WHITESPACE = Regex("\\s+")

    fun normalize(value: String?): String =
        value.orEmpty().trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    fun read(context: Context): JSONArray {
        val json = Settings.System.getStringForUser(
            context.contentResolver, KEY, UserHandle.USER_CURRENT
        )
        if (json.isNullOrEmpty()) return JSONArray()
        return try {
            JSONArray(json)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable saved songs list", e)
            JSONArray()
        }
    }

    private fun matches(entry: JSONObject, title: String, artist: String): Boolean =
        normalize(entry.optString("title")) == title &&
            normalize(entry.optString("artist")) == artist

    fun remove(context: Context, title: String, artist: String) {
        val t = normalize(title)
        val a = normalize(artist)
        val saved = read(context)
        val kept = ArrayList<JSONObject>()
        for (i in 0 until saved.length()) {
            val entry = saved.optJSONObject(i) ?: continue
            if (!matches(entry, t, a)) kept.add(entry)
        }
        write(context, kept)
    }

    /**
     * Writes [entries] within the limits: covers of the oldest entries are dropped first, since a
     * URL is much longer than the rest of an entry, and entries only after that.
     */
    private fun write(context: Context, entries: List<JSONObject>) {
        val limited = entries.take(MAX_ENTRIES)
        var oldest = limited.size - 1
        while (oldest >= 0 && toArray(limited, limited.size).toString().length > MAX_CHARS) {
            limited[oldest].remove(KEY_ALBUM_ART)
            oldest--
        }
        var count = limited.size
        while (count > 0 && toArray(limited, count).toString().length > MAX_CHARS) count--
        try {
            Settings.System.putStringForUser(
                context.contentResolver,
                KEY,
                toArray(limited, count).toString(),
                UserHandle.USER_CURRENT,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write saved songs", e)
        }
    }

    private fun toArray(entries: List<JSONObject>, count: Int): JSONArray {
        val array = JSONArray()
        for (i in 0 until count) array.put(entries[i])
        return array
    }
}
