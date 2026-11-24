/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.evolution.settings.utils.BootAnimationUtils
import org.json.JSONObject

/** One boot animation of the online catalog. [sha256] is lower case, [preview] may be null. */
internal data class OnlineBootAnimation(
    val id: String,
    val name: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val preview: String?,
)

/**
 * Reads bootanimation/bootanimation.json of vendor_themes and downloads the zips it lists,
 * checking size and SHA-256 on the way. [OnlineBootAnimationStore] keeps what was downloaded.
 */
internal object OnlineBootAnimationCatalog {

    /** Process-wide, so leaving the screen can't cancel a download halfway. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TAG = "OnlineBootAnimation"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/Evolution-X/vendor_themes/cnb/bootanimation/"
    private const val INDEX = "bootanimation.json"
    private const val SUPPORTED_VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_INDEX_BYTES = 256L * 1024L
    private const val MAX_PREVIEW_BYTES = 512L * 1024L
    private const val MAX_ZIP_BYTES = 100L * 1024L * 1024L
    private val SAFE_ID = Regex("[a-z0-9_]+")
    private val SAFE_PREVIEW = Regex("[A-Za-z0-9._-]+\\.png")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    class DownloadException(message: String) : IOException(message)
    class ChecksumException(message: String) : IOException(message)

    /** The catalog as served, as text, so a caller can keep it. Blocking. */
    fun fetchIndex(): String = String(fetchBytes(INDEX, MAX_INDEX_BYTES), Charsets.UTF_8)

    /** Parses [raw]; an entry that is malformed or repeats an id is skipped. */
    fun parse(raw: String): List<OnlineBootAnimation> {
        val json = JSONObject(raw)
        if (json.optInt("version", 0) > SUPPORTED_VERSION) {
            Log.w(TAG, "Catalog version ${json.optInt("version")} is newer than this build")
        }
        val array = json.optJSONArray("bootanimations") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parseEntry(array.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "Skipping catalog entry $i", it) }
                .getOrNull()
                ?.takeIf { seen.add(it.id) }
        }
    }

    private fun parseEntry(o: JSONObject): OnlineBootAnimation {
        val id = o.getString("id")
        val file = o.getString("file")
        val size = o.getLong("size")
        val sha256 = o.getString("sha256")
        val preview = o.optString("preview").ifEmpty { null }
        require(SAFE_ID.matches(id)) { "Bad id $id" }
        // The framework looks a style up by this name, so the two can't differ.
        require(id != BootAnimationUtils.NAME_DEFAULT && id != BootAnimationUtils.NAME_CUSTOM) {
            "Reserved id $id"
        }
        require(file == "bootanimation_$id.zip") { "Bad file $file" }
        require(size in 1..MAX_ZIP_BYTES) { "Bad size $size" }
        require(SHA256.matches(sha256)) { "Bad sha256" }
        require(preview == null || SAFE_PREVIEW.matches(preview)) { "Bad preview $preview" }
        return OnlineBootAnimation(
            id, o.optString("name").ifEmpty { id }, file, size, sha256.lowercase(), preview,
        )
    }

    /**
     * Downloads [entry] to [target], replacing it in one step, so the boot animation never sees
     * half a file. Throws [ChecksumException] when the file isn't the one the catalog lists.
     * Blocking.
     */
    fun download(entry: OnlineBootAnimation, target: File, onProgress: (Float) -> Unit) {
        val dir = target.parentFile ?: throw DownloadException("No folder for ${entry.file}")
        BootAnimationUtils.prepareDownloadDir(dir)
        val temp = File(dir, "${target.name}.part")
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
                        output.fd.sync()
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != entry.size || hash != entry.sha256) {
                throw ChecksumException("${entry.file} doesn't match the catalog")
            }
            // bootanimation runs as another user and has to be able to read it.
            temp.setReadable(true, false)
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

    /** Downloads the small preview picture of [entry] to [target]. Blocking. */
    fun downloadPreview(entry: OnlineBootAnimation, target: File) {
        val name = entry.preview ?: throw DownloadException("${entry.id} has no preview")
        val bytes = fetchBytes(name, MAX_PREVIEW_BYTES)
        // Keep only what decodes, so a bad file never gets cached.
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
            throw DownloadException("$name is not a picture")
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) throw DownloadException("Can't move $name")
        } finally {
            temp.delete()
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
 * The saved catalog, its preview pictures and the downloaded zips. The zips live where the
 * framework plays them from, [BootAnimationUtils.DOWNLOAD_DIR], and stay until deleted, so
 * switching back needs no network.
 */
internal class OnlineBootAnimationStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR)
    private val previewDir = File(context.applicationContext.cacheDir, PREVIEW_DIR)

    /** The catalog saved by the last [refresh], or empty before the first one. */
    fun saved(): List<OnlineBootAnimation> {
        val file = File(dir, INDEX)
        if (!file.isFile) return emptyList()
        return runCatching { OnlineBootAnimationCatalog.parse(file.readText()) }
            .onFailure { Log.w(TAG, "Saved boot animation list unreadable", it); file.delete() }
            .getOrDefault(emptyList())
    }

    /** True when the saved catalog is missing or more than a day old. */
    fun isStale(): Boolean {
        val file = File(dir, INDEX)
        return !file.isFile || System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS
    }

    /** Downloads the catalog and saves it. Throws when it can't be read. Blocking. */
    fun refresh() {
        val raw = OnlineBootAnimationCatalog.fetchIndex()
        OnlineBootAnimationCatalog.parse(raw)
        dir.mkdirs()
        val temp = File(dir, "$INDEX.tmp")
        temp.writeText(raw)
        if (!temp.renameTo(File(dir, INDEX))) temp.delete()
        // Downloaded zips stay even when they leave the catalog, one of them may be in use.
        // Only what a download that was cut short left behind is of no use.
        File(BootAnimationUtils.DOWNLOAD_DIR).listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    fun file(entry: OnlineBootAnimation) = File(BootAnimationUtils.DOWNLOAD_DIR, entry.file)

    fun isDownloaded(entry: OnlineBootAnimation): Boolean =
        file(entry).let { it.isFile && it.length() == entry.size }

    fun delete(entry: OnlineBootAnimation) {
        file(entry).delete()
    }

    /** The preview picture of [entry], fetched on first use; null when there is none. Blocking. */
    fun preview(entry: OnlineBootAnimation): Bitmap? {
        if (entry.preview == null) return null
        // The checksum is part of the name, so a changed zip gets its new picture.
        val target = File(previewDir, "${entry.id}_${entry.sha256.take(12)}.png")
        if (!target.isFile) {
            runCatching {
                OnlineBootAnimationCatalog.downloadPreview(entry, target)
                previewDir.listFiles { f ->
                    f.name.startsWith("${entry.id}_") && f.name != target.name
                }?.forEach { it.delete() }
            }.onFailure {
                Log.w(TAG, "No preview for ${entry.id}", it)
                return null
            }
        }
        return BitmapFactory.decodeFile(target.path)
    }

    companion object {
        private const val TAG = "OnlineBootAnimStore"
        private const val DIR = "online_bootanim"
        private const val PREVIEW_DIR = "online_bootanim_previews"
        private const val INDEX = "bootanimation.json"
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
