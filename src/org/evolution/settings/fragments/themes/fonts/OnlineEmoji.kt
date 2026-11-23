/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** One emoji font of the online catalog. [sha256] is lower case. */
internal class OnlineEmoji(
    val id: String,
    val name: String,
    val file: String,
    val size: Long,
    val sha256: String,
)

/**
 * Reads emoji/emoji.json of vendor_themes and downloads the fonts it lists, checking size and
 * SHA-256 on the way. Nothing here touches the system emoji; [OnlineEmojiStore] keeps the files.
 */
internal object OnlineEmojiCatalog {

    private const val TAG = "OnlineEmojiCatalog"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/Evolution-X/vendor_themes/cnb/emoji/"
    private const val INDEX = "emoji.json"
    private const val SUPPORTED_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_INDEX_BYTES = 256L * 1024L
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    class DownloadException(message: String) : IOException(message)
    class ChecksumException(message: String) : IOException(message)

    /** The catalog as served, as text, so a caller can keep it. Blocking. */
    fun fetchIndex(): String = String(fetchBytes(INDEX, MAX_INDEX_BYTES), Charsets.UTF_8)

    /** Parses [raw]; an entry that is malformed or repeats an id is skipped. */
    fun parse(raw: String): List<OnlineEmoji> {
        val json = JSONObject(raw)
        if (json.optInt("version", 0) > SUPPORTED_VERSION) {
            Log.w(TAG, "Catalog version ${json.optInt("version")} is newer than this build")
        }
        val array = json.optJSONArray("emoji") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parseEntry(array.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "Skipping catalog entry $i", it) }
                .getOrNull()
                ?.takeIf { seen.add(it.id) }
        }
    }

    private fun parseEntry(o: JSONObject): OnlineEmoji {
        val id = o.getString("id")
        val file = o.getString("file")
        val size = o.getLong("size")
        val sha256 = o.getString("sha256")
        require(SAFE_NAME.matches(id)) { "Bad id $id" }
        require(SAFE_NAME.matches(file) && !file.startsWith(".")) { "Bad file $file" }
        require(size in 1..ExternalEmojiInstaller.MAX_EMOJI_BYTES) { "Bad size $size" }
        require(SHA256.matches(sha256)) { "Bad sha256" }
        return OnlineEmoji(id, o.optString("name").ifEmpty { id }, file, size, sha256.lowercase())
    }

    /**
     * Downloads [entry] to [target], replacing it in one step, so a reader never sees half a
     * file. Throws [ChecksumException] when the file isn't the one the catalog lists. Blocking.
     */
    fun download(entry: OnlineEmoji, target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        var done = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open(entry.file).use { conn ->
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > entry.size) {
                                throw ChecksumException("${entry.file} is larger than listed")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = (total * 100 / entry.size).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != entry.size || hash != entry.sha256) {
                throw ChecksumException("${entry.file} doesn't match the catalog")
            }
            target.delete()
            if (!temp.renameTo(target)) throw DownloadException("Can't move ${entry.file}")
            done = true
        } catch (e: ChecksumException) {
            throw e
        } catch (e: DownloadException) {
            throw e
        } catch (e: Exception) {
            throw DownloadException("Download of ${entry.file} failed: $e")
        } finally {
            if (!done) temp.delete()
        }
    }

    private fun fetchBytes(name: String, limit: Long): ByteArray = open(name).use { conn ->
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw DownloadException("$name exceeds $limit bytes")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private class Connection(val connection: HttpURLConnection) : Closeable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    private fun open(name: String): Connection {
        val conn = URL(BASE_URL + name).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw DownloadException("HTTP $code for $name")
        }
        return Connection(conn)
    }
}

/**
 * The saved catalog, the downloaded fonts and which of them is installed as the system emoji.
 * Downloads live in app data and stay until deleted, so switching back needs no network.
 */
internal class OnlineEmojiStore(context: Context) {

    private val context: Context = context.applicationContext
    private val dir = File(this.context.filesDir, DIR)
    private val prefs: SharedPreferences =
        this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The catalog saved by the last [refresh], or empty before the first one. */
    fun saved(): List<OnlineEmoji> {
        val file = File(dir, INDEX)
        if (!file.isFile) return emptyList()
        return runCatching { OnlineEmojiCatalog.parse(file.readText()) }
            .onFailure { Log.w(TAG, "Saved emoji list unreadable", it); file.delete() }
            .getOrDefault(emptyList())
    }

    /** True when the saved catalog is missing or more than a day old. */
    fun isStale(): Boolean {
        val file = File(dir, INDEX)
        return !file.isFile || System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS
    }

    /** Downloads the catalog and saves it. Throws when it can't be read. Blocking. */
    fun refresh() {
        val raw = OnlineEmojiCatalog.fetchIndex()
        OnlineEmojiCatalog.parse(raw)
        dir.mkdirs()
        val temp = File(dir, "$INDEX.tmp")
        temp.writeText(raw)
        if (!temp.renameTo(File(dir, INDEX))) temp.delete()
        // Entries that left the catalog, and files that changed, are no use any more.
        val keep = saved().filter { isDownloaded(it) }.mapTo(HashSet()) { it.file }
        dir.listFiles()
            ?.filter {
                it.name != INDEX && !it.name.endsWith(".part") && it.name !in keep &&
                    it.name != installedFileName()
            }
            ?.forEach { it.delete() }
    }

    fun file(entry: OnlineEmoji) = File(dir, entry.file)

    fun isDownloaded(entry: OnlineEmoji): Boolean =
        file(entry).let { it.isFile && it.length() == entry.size }

    fun delete(entry: OnlineEmoji) {
        file(entry).delete()
    }

    /** Remembers that [entry] is what the framework's custom emoji font was installed from. */
    fun markInstalled(entry: OnlineEmoji, fontName: String) {
        prefs.edit()
            .putString(KEY_ID, entry.id)
            .putString(KEY_FILE, entry.file)
            .putString(KEY_NAME, fontName)
            .apply()
    }

    fun clearInstalled() {
        prefs.edit().clear().apply()
    }

    /** The entry that the installed custom emoji font (called [fontName]) came from, or null. */
    fun installedEntry(fontName: String): OnlineEmoji? {
        if (fontName.isEmpty() || prefs.getString(KEY_NAME, null) != fontName) return null
        val id = prefs.getString(KEY_ID, null) ?: return null
        return saved().firstOrNull { it.id == id }
    }

    private fun installedFileName(): String? = prefs.getString(KEY_FILE, null)

    companion object {
        private const val TAG = "OnlineEmojiStore"
        private const val DIR = "online_emoji"
        private const val INDEX = "emoji.json"
        private const val PREFS = "online_emoji_state"
        private const val KEY_ID = "installed_id"
        private const val KEY_FILE = "installed_file"
        private const val KEY_NAME = "installed_name"
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
