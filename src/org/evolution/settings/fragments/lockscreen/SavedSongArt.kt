/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Album art of the saved songs. SystemUI only stores the https cover URL, so the covers are
 * fetched here and kept in the cache directory, one file per URL, plus a small in-memory cache
 * of decoded bitmaps for scrolling.
 */
internal object SavedSongArt {
    private const val TAG = "SavedSongArt"
    private const val DIR = "now_playing_art"
    private const val TIMEOUT_MS = 8000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val RETRY_AFTER_MS = 10 * 60 * 1000L

    private val memory = LruCache<String, Bitmap>(32)

    /** When a cover last failed to load, so a dead URL is not fetched again on every visit. */
    private val failures = LruCache<String, Long>(64)

    private fun recentlyFailed(url: String): Boolean {
        val at = failures.get(url) ?: return false
        return SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS
    }

    private fun fileFor(context: Context, url: String): File =
        File(File(context.cacheDir, DIR), name(url))

    private fun name(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Blocking, call off the main thread. Returns null when the cover cannot be loaded. */
    fun load(context: Context, url: String, targetPx: Int): Bitmap? {
        if (!url.startsWith("https://")) return null
        memory.get(url)?.let { return it }
        val file = fileFor(context, url)
        if (!file.exists()) {
            if (recentlyFailed(url)) return null
            if (!download(url, file)) {
                failures.put(url, SystemClock.elapsedRealtime())
                return null
            }
        }
        val bitmap = decode(file, targetPx)
        if (bitmap == null) {
            file.delete()
            failures.put(url, SystemClock.elapsedRealtime())
            return null
        }
        failures.remove(url)
        memory.put(url, bitmap)
        return bitmap
    }

    /** Deletes cached covers of songs that are no longer in [urls]. */
    fun prune(context: Context, urls: Collection<String>) {
        val dir = File(context.cacheDir, DIR)
        val keep = urls.map { name(it) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun download(url: String, target: File): Boolean {
        val dir = target.parentFile ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val temp = File(dir, target.name + ".tmp")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            var total = 0
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) return false
                        output.write(buffer, 0, read)
                    }
                }
            }
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download cover", e)
            false
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }

    private fun decode(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
