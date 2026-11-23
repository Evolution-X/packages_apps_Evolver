/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import com.android.settings.R
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One Google Fonts family. [variants] are "<weight>" or "<weight>i" (italic), e.g. "400",
 * "700i", sorted by weight with upright before italic.
 */
internal data class OnlineFont(
    val family: String,
    val category: String,
    val variants: List<String>,
    val popularity: Int,
    /** Scripts the family covers, e.g. "latin", "cyrillic". */
    val subsets: List<String>,
) {
    /** Regular when there is one, else the upright style closest to it. */
    val defaultVariant: String
        get() = if ("400" in variants) "400"
        else variants.filterNot { it.isItalic() }
            .minByOrNull { kotlin.math.abs(it.weight() - 400) }
            ?: variants.first()
}

/** File name for a family: letters and digits only. */
internal fun onlineFontFileName(family: String) = family.replace(Regex("[^A-Za-z0-9]"), "_")

internal fun String.isItalic(): Boolean = endsWith("i")

internal fun String.weight(): Int = removeSuffix("i").toIntOrNull() ?: 400

/**
 * Categories as fonts.google.com names them; null means no category. [ALL] shows every family
 * and [SAVED] the ones kept on the device.
 */
internal enum class OnlineFontCategory(val apiName: String?, val labelRes: Int) {
    ALL(null, R.string.font_filter_all),
    SAVED(null, R.string.font_filter_saved),
    SANS_SERIF("Sans Serif", R.string.font_filter_sans),
    SERIF("Serif", R.string.font_filter_serif),
    DISPLAY("Display", R.string.font_filter_display),
    HANDWRITING("Handwriting", R.string.font_filter_handwriting),
    MONOSPACE("Monospace", R.string.font_filter_mono),
}

/** Lists and downloads Google Fonts without an API key. */
internal object GoogleFontsCatalog {

    private const val TAG = "GoogleFontsCatalog"
    private const val METADATA_URL = "https://fonts.google.com/metadata/fonts"
    private const val CSS_URL = "https://fonts.googleapis.com/css"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L
    private const val MAX_CSS_BYTES = 64L * 1024L
    private const val MAX_PREVIEW_BYTES = 1L * 1024L * 1024L
    private const val METADATA_FILE = "metadata.json"
    private const val METADATA_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private val VARIANT = Regex("[1-9]00i?")
    private val SUBSET = Regex("[a-z0-9-]+")
    private val TTF_URL =
        Regex("""url\((https://fonts\.gstatic\.com/[^)\s'"]+)\)\s*format\(['"]truetype['"]\)""")

    class FetchException(message: String) : IOException(message)

    @Volatile
    private var cached: List<OnlineFont>? = null

    /**
     * The family list saved by the last [fetchFamilies], most popular first, or null when
     * there is none. Read from disk once per process. Blocking.
     */
    fun savedFamilies(dir: File): List<OnlineFont>? {
        cached?.let { return it }
        val file = File(dir, METADATA_FILE)
        if (!file.isFile) return null
        return runCatching { parse(file.readText()) }
            .onFailure { Log.w(TAG, "Saved font list unreadable", it); file.delete() }
            .getOrNull()
            ?.also { cached = it }
    }

    /** True when the saved list is missing or more than a day old. */
    fun isStale(dir: File): Boolean {
        val file = File(dir, METADATA_FILE)
        return !file.isFile || System.currentTimeMillis() - file.lastModified() >= METADATA_MAX_AGE_MS
    }

    /** Downloads the list, saves it for [savedFamilies] and returns it. Blocking. */
    fun fetchFamilies(dir: File): List<OnlineFont> {
        val json = String(fetchBytes(METADATA_URL, MAX_METADATA_BYTES), Charsets.UTF_8)
        val fonts = parse(json)
        dir.mkdirs()
        val temp = File(dir, "$METADATA_FILE.tmp")
        temp.writeText(json)
        if (!temp.renameTo(File(dir, METADATA_FILE))) temp.delete()
        cached = fonts
        return fonts
    }

    private fun parse(raw: String): List<OnlineFont> {
        // The endpoint has served its JSON behind an XSSI guard (")]}'") at times.
        val start = raw.indexOf('{')
        if (start < 0) throw FetchException("Font list isn't JSON")
        val list = JSONObject(raw.substring(start)).getJSONArray("familyMetadataList")
        val fonts = ArrayList<OnlineFont>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            runCatching { parseFamily(o) }
                .onFailure { Log.w(TAG, "Skipping font entry $i", it) }
                .getOrNull()
                ?.let(fonts::add)
        }
        // Family names key the grid, so they have to be unique.
        return fonts.distinctBy { it.family }.sortedBy { it.popularity }
    }

    private fun parseFamily(o: JSONObject): OnlineFont? {
        val family = o.getString("family").takeIf { it.isNotBlank() } ?: return null
        // The custom font replaces UI text, so it has to cover Latin. Color fonts (COLR)
        // aren't something the system UI font can be.
        val subsets = o.optJSONArray("subsets")
            ?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?.filter { SUBSET.matches(it) && it != "menu" }
            .orEmpty()
        val latin = "latin" in subsets
        val color = (o.optJSONArray("colorCapabilities")?.length() ?: 0) > 0
        if (!latin || color) return null
        val variants = o.optJSONObject("fonts")?.keys()?.asSequence()
            ?.filter { VARIANT.matches(it) }
            ?.sortedWith(compareBy<String> { it.weight() }.thenBy { it.isItalic() })
            ?.toList()
            .orEmpty()
        if (variants.isEmpty()) return null
        return OnlineFont(
            family = family,
            category = o.optString("category"),
            variants = variants,
            popularity = o.optInt("popularity", Int.MAX_VALUE),
            subsets = subsets,
        )
    }

    /**
     * Downloads the [text]-only cut of [font]'s default style to [target], replacing it in
     * one step, so a reader never sees half a file. Blocking.
     */
    fun fetchPreview(font: OnlineFont, text: String, target: File) {
        val url = ttfUrl(cssUrl(font.family, font.defaultVariant, subsets = emptyList(), text = text))
        download(url, target, MAX_PREVIEW_BYTES, onProgress = null)
    }

    /** Same as [fetchPreview] for the full file of one style, reporting progress 0..1. */
    fun fetchVariant(font: OnlineFont, variant: String, target: File, onProgress: ((Float) -> Unit)?) {
        require(variant in font.variants) { "${font.family} has no style $variant" }
        // Without subset= the API serves Latin only; the system font should cover every
        // script the family has.
        val url = ttfUrl(cssUrl(font.family, variant, subsets = font.subsets, text = null))
        download(url, target, ExternalFontInstaller.MAX_FONT_BYTES, onProgress)
    }

    private fun cssUrl(family: String, variant: String, subsets: List<String>, text: String?): String {
        val query = StringBuilder("$CSS_URL?family=")
            .append(encode(family)).append(':').append(variant)
        if (subsets.isNotEmpty()) query.append("&subset=").append(subsets.joinToString(","))
        if (text != null) query.append("&text=").append(encode(text))
        return query.toString()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun ttfUrl(cssUrl: String): String {
        val css = String(fetchBytes(cssUrl, MAX_CSS_BYTES), Charsets.UTF_8)
        return TTF_URL.find(css)?.groupValues?.get(1)
            ?: throw FetchException("No TrueType file offered for $cssUrl")
    }

    private fun download(url: String, target: File, limit: Long, onProgress: ((Float) -> Unit)?) {
        target.parentFile?.mkdirs()
        // Unique, so two downloads of the same file never share it.
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
        var done = false
        try {
            open(url).use { conn ->
                val length = conn.connection.contentLengthLong
                if (length > limit) throw FetchException("$url is larger than $limit bytes")
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) throw FetchException("$url is larger than $limit bytes")
                            output.write(buffer, 0, read)
                            if (onProgress != null && length > 0L) {
                                val percent = (total * 100 / length).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                        if (total == 0L) throw FetchException("$url is empty")
                    }
                }
            }
            if (!temp.renameTo(target)) throw FetchException("Can't move ${target.name}")
            done = true
        } finally {
            if (!done) temp.delete()
        }
    }

    private fun fetchBytes(url: String, limit: Long): ByteArray = open(url).use { conn ->
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw FetchException("$url exceeds $limit bytes")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private class Connection(val connection: HttpURLConnection) : Closeable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    private fun open(url: String): Connection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw FetchException("HTTP $code for $url")
        }
        return Connection(conn)
    }
}

/**
 * The online font list, the previews of its cards and the files of the styles that are tried
 * or installed. All of it is cache: the list is refreshed once a day, and previews and styles
 * are dropped, least recently used first, once they pass a size limit. Styles that were saved
 * are the exception, see [SavedFontStore].
 */
internal class OnlineFontRepository(context: Context) {

    private val context: Context = context.applicationContext
    private val root = File(this.context.cacheDir, ROOT_DIR)
    private val previewDir = File(root, PREVIEW_DIR)
    private val fileDir = File(root, FILE_DIR)
    private val savedStore = SavedFontStore(this.context)

    /** The list from the last fetch, at once; null before the first one. */
    suspend fun savedFamilies(): List<OnlineFont>? = withContext(Dispatchers.IO) {
        GoogleFontsCatalog.savedFamilies(root)
    }

    fun familiesStale(): Boolean = GoogleFontsCatalog.isStale(root)

    suspend fun fetchFamilies(): List<OnlineFont> = withContext(Dispatchers.IO) {
        GoogleFontsCatalog.fetchFamilies(root)
    }

    /** Already-loaded preview, for the first frame of a card. */
    fun cachedPreview(font: OnlineFont): Typeface? = previews.get(previewKey(font))

    /**
     * The family name (plus the "Aa" sample) in the font itself: from the cached cut, else a
     * cut downloaded now. Null when none can be had; a failed download isn't retried until
     * [resetFailures] is called.
     */
    suspend fun preview(font: OnlineFont): Typeface? {
        val key = previewKey(font)
        previews.get(key)?.let { return it }
        val file = previewFile(font)
        val local = withContext(Dispatchers.IO) {
            if (file.isUsable()) {
                file.setLastModified(System.currentTimeMillis())
                load(file)
            } else {
                // A saved style draws the card without a network.
                savedStore.anyFile(font.family)?.let(::loadKept)
            }
        }
        if (local != null) return local.also { previews.put(key, it) }
        if (font.family in failedPreviews) return null
        return previewLimit.withPermit {
            withContext(Dispatchers.IO) {
                previews.get(key) ?: runCatching {
                    GoogleFontsCatalog.fetchPreview(font, previewText(font), file)
                    load(file) ?: error("Unusable preview for ${font.family}")
                }.onFailure {
                    Log.w(TAG, "Preview of ${font.family} unavailable: $it")
                    failedPreviews += font.family
                }.getOrNull()?.also { previews.put(key, it) }
            }
        }
    }

    // The cut holds only the sample's glyphs, and a translation can change them, so a cut is
    // kept per sample text.
    private fun previewKey(font: OnlineFont) = font.family + previewSample()

    private fun previewSample() = context.getString(R.string.font_sample_glyphs)

    private fun previewFile(font: OnlineFont): File {
        val sample = Integer.toHexString(previewSample().hashCode())
        return File(previewDir, "${onlineFontFileName(font.family)}-$sample.ttf")
    }

    private fun previewText(font: OnlineFont) = font.family + previewSample()

    /**
     * One style for previewing and installing: the cached file, else downloaded now. Throws
     * on failure. Cancelling the calling coroutine stops a download at the next chunk.
     */
    suspend fun download(font: OnlineFont, variant: String, onProgress: (Float) -> Unit): File {
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val cached = File(fileDir, "${onlineFontFileName(font.family)}-$variant.ttf")
            if (cached.isUsable()) {
                cached.setLastModified(System.currentTimeMillis())
                return@withContext cached
            }
            fileDir.mkdirs()
            // A saved style is copied into the cache, so what the caller gets is the same
            // file as for a download and removing the saved style can't pull it away.
            savedStore.file(font.family, variant)?.let { saved ->
                val temp = File(fileDir, "${cached.name}.${System.nanoTime()}.part")
                try {
                    saved.copyTo(temp, overwrite = true)
                    if (temp.renameTo(cached)) return@withContext cached
                } finally {
                    temp.delete()
                }
            }
            GoogleFontsCatalog.fetchVariant(font, variant, cached) {
                if (job?.isActive == false) throw CancellationException()
                onProgress(it)
            }
            cached
        }
    }

    /** The styles kept on the device, the one saved last first. */
    suspend fun saved(): List<SavedFont> = withContext(Dispatchers.IO) { savedStore.all() }

    /** Keeps [variant] of [font], which [file] holds, until it is removed. Returns the list. */
    suspend fun save(font: OnlineFont, variant: String, file: File): List<SavedFont> =
        withContext(Dispatchers.IO) { savedStore.save(font, variant, file) }

    /** Forgets a saved style. Returns the list. */
    suspend fun unsave(font: OnlineFont, variant: String): List<SavedFont> =
        withContext(Dispatchers.IO) { savedStore.remove(font.family, variant) }

    /** Like [load] but leaves the file alone, which isn't the cache's to delete. */
    private fun loadKept(file: File): Typeface? = runCatching { Typeface.createFromFile(file) }
        .getOrNull()
        ?.takeUnless { it === Typeface.DEFAULT }

    /** Null when the file isn't a font the system can draw, which also deletes it. */
    fun load(file: File): Typeface? = runCatching { Typeface.createFromFile(file) }
        .getOrNull()
        ?.takeUnless { it === Typeface.DEFAULT }
        .also { if (it == null) file.delete() }

    /** Failed previews are tried again once per visit to the screen. */
    fun resetFailures() = failedPreviews.clear()

    /** Keeps the caches within their limits, least recently used first out. Async. */
    fun trimCache() {
        ioScope.launch {
            trim(fileDir, MAX_STYLE_CACHE_BYTES)
            trim(previewDir, MAX_PREVIEW_CACHE_BYTES)
        }
    }

    private fun trim(dir: File, limit: Long) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles().orEmpty()
        // Leftovers of downloads that never finished.
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > STALE_PART_MS }
            .forEach { it.delete() }
        var total = 0L
        files.filter { it.name.endsWith(".ttf") }
            .sortedByDescending { it.lastModified() }
            .forEach { file ->
                total += file.length()
                if (total > limit) file.delete()
            }
    }

    private fun File.isUsable() = isFile && length() > 0L

    companion object {
        private const val TAG = "OnlineFontRepository"
        private const val ROOT_DIR = "online_fonts"
        private const val PREVIEW_DIR = "previews"
        private const val FILE_DIR = "files"
        private const val MAX_STYLE_CACHE_BYTES = 128L * 1024L * 1024L
        private const val MAX_PREVIEW_CACHE_BYTES = 16L * 1024L * 1024L
        private const val STALE_PART_MS = 60L * 60L * 1000L

        /** Previews download in parallel, but not all ~1,800 at once while flinging. */
        private val previewLimit = Semaphore(6)
        private val previews = LruCache<String, Typeface>(300)
        private val failedPreviews: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** Trimming outlives the screen. */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
