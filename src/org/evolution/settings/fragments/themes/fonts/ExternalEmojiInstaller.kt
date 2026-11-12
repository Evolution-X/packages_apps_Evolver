/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.themes.fonts

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Talks to the custom emoji API of [FontManager]. The framework stores the font and puts it in
 * front of the system emoji fallbacks, so it takes effect in every process started afterwards
 * and needs no reboot.
 */
class ExternalEmojiInstaller(context: Context) {

    companion object {
        private const val TAG = "ExternalEmojiInstaller"
        private const val TEMP_PREVIEW_EMOJI = "preview_emoji.ttf"
        private const val RESULT_EXCEPTION = Int.MIN_VALUE
        private const val KILL_REASON = "system emoji changed"
        private const val GMS_PACKAGE = "com.google.android.gms"

        /** Where FontManagerService keeps the installed font (UpdatableFontDir). */
        private const val INSTALLED_EMOJI_DIR = "/data/fonts/emoji"

        /** Same limit as UpdatableFontDir.MAX_CUSTOM_EMOJI_FILE_SIZE. */
        private const val MAX_EMOJI_BYTES = 128L * 1024L * 1024L

        // Earlier builds kept their own copy of the font and its name.
        private const val LEGACY_EMOJI_FILE = "cust_emoji.ttf"
        private const val LEGACY_NAME_KEY = "custom_emoji_name"

        // Unicode 15.0 and 16.0 emoji: moose, goose, shaking face, fingerprint, leafless tree,
        // splatter, face with bags under eyes.
        private val NEWEST_EMOJI = intArrayOf(
            0x1FACE, 0x1FABF, 0x1FAE8, 0x1FAC6, 0x1FABE, 0x1FADF, 0x1FAE9
        )

        /**
         * Runs emoji work that must outlive the screen, so leaving it can't cancel an install
         * halfway and skip the restart prompt that follows.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    enum class Result { SUCCESS, FAILED, INVALID_FONT, UPDATER_DISABLED, NOT_INSTALLED }

    private val context: Context = context.applicationContext

    private val fontManager: FontManager = this.context.getSystemService(FontManager::class.java)

    /** Name of the installed custom emoji font, empty when there is none. Blocking. */
    fun installedName(): String = runCatching { fontManager.customEmojiName }
        .onFailure { Log.e(TAG, "getCustomEmojiName failed", it) }
        .getOrNull()
        .orEmpty()

    /** The installed custom emoji font, for previews. Blocking. */
    fun installedFile(): File? = runCatching {
        fontManager.fontConfig.fontFamilies
            .flatMap { it.fontList }
            .firstOrNull { it.file.parent == INSTALLED_EMOJI_DIR }
            ?.file
    }.onFailure { Log.e(TAG, "getFontConfig failed", it) }.getOrNull()

    /**
     * Earlier builds installed the font as an unsigned font update and kept a copy and its name
     * here. The framework stores the font now and keeps its name, so drop the leftovers.
     * Blocking.
     */
    fun dropLegacyInstall() {
        val resolver = context.contentResolver
        val hadName = !Settings.Secure.getString(resolver, LEGACY_NAME_KEY).isNullOrEmpty()
        val files = listOf("", ".new", ".bak").map { File(context.filesDir, LEGACY_EMOJI_FILE + it) }
        if (!hadName && files.none { it.exists() }) return
        files.forEach { it.delete() }
        Settings.Secure.putString(resolver, LEGACY_NAME_KEY, "")
    }

    private fun mapFile(file: File): ByteBuffer = FileInputStream(file).use { fis ->
        fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
    }

    /** Null when [file] can be installed as an emoji font, otherwise the reason it can't. */
    private fun checkFont(file: File): Result? = runCatching {
        val psName = FontFileUtil.getPostScriptName(mapFile(file), 0)
        when {
            psName.isNullOrEmpty() -> Result.INVALID_FONT
            !hasEmojiGlyph(file) -> Result.INVALID_FONT
            else -> null
        }
    }.getOrDefault(Result.INVALID_FONT)

    // Checked without the system fallback so a text font can't pass on the system emoji font.
    private fun hasGlyphs(file: File, text: String): Boolean = runCatching {
        val family = FontFamily.Builder(Font.Builder(file).build()).build()
        val typeface = Typeface.CustomFallbackBuilder(family).build()
        Paint().apply { this.typeface = typeface }.hasGlyph(text)
    }.getOrDefault(false)

    private fun hasEmojiGlyph(file: File): Boolean = hasGlyphs(file, "\uD83D\uDE00")

    private fun copyUriToFile(uri: Uri, file: File): Boolean {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_EMOJI_BYTES) { "bad emoji font size" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0) { "bad emoji font size" }
                }
            } ?: return@runCatching false
            file.delete()
            temp.renameTo(file)
        }.onFailure {
            Log.e(TAG, "Failed to copy emoji font", it)
            temp.delete()
        }.getOrDefault(false)
    }

    suspend fun loadTypefaceFromUri(uri: Uri): Typeface? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, TEMP_PREVIEW_EMOJI)
        if (!copyUriToFile(uri, file)) return@withContext null
        runCatching {
            if (FontFileUtil.getPostScriptName(mapFile(file), 0) == null) {
                file.delete()
                return@runCatching null
            }
            Typeface.createFromFile(file)
        }.onFailure {
            Log.e(TAG, "Failed to create preview emoji typeface", it)
            file.delete()
        }.getOrNull()
    }

    /**
     * Null when the font loaded by [loadTypefaceFromUri] can be installed, otherwise the reason
     * it can't, so a bad pick is reported before the preview instead of after Add.
     */
    suspend fun previewProblem(): Result? = withContext(Dispatchers.IO) {
        checkFont(File(context.cacheDir, TEMP_PREVIEW_EMOJI))
    }

    /**
     * How many of the newest emoji the previewed font has its own glyph for, as covered to total.
     * The preview itself can't show this, because a missing glyph falls back to the system font.
     */
    suspend fun previewCoverage(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, TEMP_PREVIEW_EMOJI)
        NEWEST_EMOJI.count { hasGlyphs(file, String(Character.toChars(it))) } to NEWEST_EMOJI.size
    }

    /** Removes the copy made by [loadTypefaceFromUri]. */
    fun discardPreview() {
        File(context.cacheDir, TEMP_PREVIEW_EMOJI).delete()
    }

    /** Installs the font loaded by [loadTypefaceFromUri] as the system emoji. */
    suspend fun installPreview(): Result = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, TEMP_PREVIEW_EMOJI)
        if (!file.isFile) return@withContext Result.FAILED
        checkFont(file)?.let { return@withContext it }
        val code = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                fontManager.installCustomEmoji(it)
            }
        }.onFailure { Log.e(TAG, "installCustomEmoji failed", it) }
            .getOrDefault(RESULT_EXCEPTION)
        resultFor(code).also { if (it == Result.SUCCESS) file.delete() }
    }

    /** Removes the custom emoji font. Blocking; call off the main thread. */
    fun clear(): Result {
        val code = runCatching { fontManager.clearCustomEmoji() }
            .onFailure { Log.e(TAG, "clearCustomEmoji failed", it) }
            .getOrDefault(RESULT_EXCEPTION)
        return resultFor(code)
    }

    /**
     * Copies the custom emoji font over the emoji fonts that apps bundle or download and stops
     * the Google Play services font provider from serving its own. Returns the number of app
     * files written, or a negative FontManager.RESULT_* code. Blocking.
     */
    fun applyToApps(): Int = runCatching { fontManager.applyCustomEmojiToApps() }
        .onFailure { Log.e(TAG, "applyCustomEmojiToApps failed", it) }
        .getOrDefault(RESULT_EXCEPTION)

    /**
     * Apps such as Gboard keep rendered emoji until their process dies. killUid rather than a
     * force stop, so apps keep their alarms and pushes. Blocking.
     */
    fun killRunningApps() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val uids = runCatching { am.runningAppProcesses }
            .onFailure { Log.e(TAG, "getRunningAppProcesses failed", it) }
            .getOrNull().orEmpty()
            .filter { Process.isApplicationUid(it.uid) && it.uid != Process.myUid() }
            .filterNot { proc ->
                proc.pkgList.orEmpty().any { it == GMS_PACKAGE || isPersistent(it) }
            }
            .mapTo(HashSet()) { it.uid }
        val service = ActivityManager.getService()
        uids.forEach { uid ->
            runCatching {
                service.killUid(UserHandle.getAppId(uid), UserHandle.getUserId(uid), KILL_REASON)
            }.onFailure { Log.w(TAG, "killUid $uid failed", it) }
        }
    }

    private fun isPersistent(pkg: String): Boolean = runCatching {
        (context.packageManager.getApplicationInfo(pkg, 0).flags and
            ApplicationInfo.FLAG_PERSISTENT) != 0
    }.getOrDefault(false)

    private fun resultFor(code: Int): Result = when (code) {
        FontManager.RESULT_SUCCESS -> Result.SUCCESS
        FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED -> Result.UPDATER_DISABLED
        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
        FontManager.RESULT_ERROR_INVALID_FONT_NAME -> Result.INVALID_FONT
        FontManager.RESULT_ERROR_FONT_NOT_FOUND -> Result.NOT_INSTALLED
        else -> Result.FAILED
    }.also { if (it != Result.SUCCESS) Log.w(TAG, "Emoji font update failed with code $code -> $it") }
}
