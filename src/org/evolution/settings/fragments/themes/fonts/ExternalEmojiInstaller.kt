/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FallbackFontUpdateRequest
import android.graphics.fonts.FontFamilyUpdateRequest
import android.graphics.fonts.FontFileUpdateRequest
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ExternalEmojiInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ExternalEmojiInstaller"
        private const val CUSTOM_EMOJI_FILE = "cust_emoji.ttf"
        private const val TEMP_PREVIEW_EMOJI = "preview_emoji.ttf"
        private const val MAX_EMOJI_BYTES = 64L * 1024L * 1024L
        private const val EMOJI_LANG = "und-Zsye"
        private const val STOCK_EMOJI_PS_NAME = "NotoColorEmoji"
        const val CUSTOM_EMOJI_NAME_KEY = "custom_emoji_name"
        // Unicode 15.0 and 16.0 emoji: moose, goose, shaking face, fingerprint, leafless tree,
        // splatter, face with bags under eyes.
        private val NEWEST_EMOJI = intArrayOf(
            0x1FACE, 0x1FABF, 0x1FAE8, 0x1FAC6, 0x1FABE, 0x1FADF, 0x1FAE9
        )
    }

    enum class Result { SUCCESS, FAILED, INVALID_FONT, REVISION_TOO_OLD, UPDATER_DISABLED, SLOTS_FULL }

    private val fontManager: FontManager = context.getSystemService(FontManager::class.java)

    fun installedName(): String =
        Settings.Secure.getString(context.contentResolver, CUSTOM_EMOJI_NAME_KEY) ?: ""

    fun hasCustomEmoji(): Boolean = installedName().isNotEmpty()

    private fun isCustomEmojiActive(): Boolean {
        val name = installedName()
        return fontManager.fontConfig.fontFamilies
            .filter { it.localeList.toLanguageTags() == EMOJI_LANG }
            .any { family -> family.fontList.any { it.postScriptName == name } }
    }

    /**
     * The framework drops an updated emoji font that is older than the preinstalled one, for
     * example after an update, while the saved name stays set. Re-applies the saved font when
     * it is no longer in the font config and forgets it when that fails. Blocking.
     */
    fun repairIfStale() {
        if (!hasCustomEmoji() || isCustomEmojiActive()) return
        if (reinstallSaved() && isCustomEmojiActive()) return
        Log.w(TAG, "Custom emoji font is no longer active, clearing it")
        File(context.filesDir, CUSTOM_EMOJI_FILE).delete()
        Settings.Secure.putString(context.contentResolver, CUSTOM_EMOJI_NAME_KEY, "")
    }

    private fun mapFile(file: File): ByteBuffer = FileInputStream(file).use { fis ->
        fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
    }

    /** Null when [file] can be installed as an emoji font, otherwise the reason it can't. */
    private fun checkFont(file: File): Result? = runCatching {
        val buffer = mapFile(file)
        val psName = FontFileUtil.getPostScriptName(buffer, 0)
        when {
            psName == null -> Result.INVALID_FONT
            // Installing under a name the system already uses would replace that font.
            psName != STOCK_EMOJI_PS_NAME && psName != installedName() &&
                usesSystemName(psName) -> Result.INVALID_FONT
            !hasEmojiGlyph(file) -> Result.INVALID_FONT
            else -> null
        }
    }.getOrDefault(Result.INVALID_FONT)

    private fun usesSystemName(psName: String): Boolean {
        val config = fontManager.fontConfig
        return (config.fontFamilies + config.namedFamilyLists.flatMap { it.families })
            .any { family -> family.fontList.any { it.postScriptName == psName } }
    }

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

    suspend fun installEmojiFromUri(uri: Uri): Result = withContext(Dispatchers.IO) {
        // Check the new font before touching the installed one, so a bad pick can't remove it.
        val candidate = File(context.filesDir, "$CUSTOM_EMOJI_FILE.new")
        if (!copyUriToFile(uri, candidate)) return@withContext Result.FAILED
        val problem = checkFont(candidate)
        if (problem != null) {
            candidate.delete()
            return@withContext problem
        }
        val file = File(context.filesDir, CUSTOM_EMOJI_FILE)
        val backup = File(context.filesDir, "$CUSTOM_EMOJI_FILE.bak")
        val oldName = installedName()
        backup.delete()
        // Fallback slots are limited and priorities only go up, so replacing an
        // installed emoji font starts from a clean slate. Keep the old one to put back.
        if (oldName.isNotEmpty()) {
            runCatching { file.copyTo(backup, overwrite = true) }
            resetEmojiUpdates()
        }
        file.delete()
        val result = if (candidate.renameTo(file)) {
            applyEmojiFile(file, persistName = true)
        } else {
            candidate.delete()
            Result.FAILED
        }
        if (result != Result.SUCCESS) {
            file.delete()
            if (backup.isFile && backup.renameTo(file)) {
                Settings.Secure.putString(context.contentResolver, CUSTOM_EMOJI_NAME_KEY, oldName)
                reinstallSaved()
            }
        }
        backup.delete()
        File(context.cacheDir, TEMP_PREVIEW_EMOJI).delete()
        result
    }

    /**
     * Re-applies the saved custom emoji font. Used after [FontManager.clearUpdates], which
     * drops emoji and text font updates together. Blocking; call off the main thread.
     */
    fun reinstallSaved(): Boolean {
        if (!hasCustomEmoji()) return false
        val file = File(context.filesDir, CUSTOM_EMOJI_FILE)
        if (!file.isFile) return false
        return applyEmojiFile(file, persistName = false) == Result.SUCCESS
    }

    /**
     * Removes the custom emoji font. [FontManager.clearUpdates] also drops the custom text
     * font, so that one is re-applied from its saved file. Blocking.
     */
    fun resetEmojiUpdates() {
        runCatching { fontManager.clearUpdates() }
            .onFailure { Log.e(TAG, "Failed to clear font updates", it) }
        ExternalFontInstaller(context).reinstallSavedFont()
        File(context.filesDir, CUSTOM_EMOJI_FILE).delete()
        File(context.cacheDir, TEMP_PREVIEW_EMOJI).delete()
        Settings.Secure.putString(context.contentResolver, CUSTOM_EMOJI_NAME_KEY, "")
    }

    // A downgrade means an older revision when installing the file, but a priority that is
    // not above the existing fallbacks when adding the fallback; both mean the slots are stale.
    private fun resultFor(code: Int, fallbackStep: Boolean): Result = when (code) {
        FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED -> Result.UPDATER_DISABLED
        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
        FontManager.RESULT_ERROR_INVALID_FONT_NAME -> Result.INVALID_FONT
        FontManager.RESULT_ERROR_DOWNGRADING ->
            if (fallbackStep) Result.SLOTS_FULL else Result.REVISION_TOO_OLD
        FontManager.RESULT_ERROR_INVALID_ARGUMENT,
        FontManager.RESULT_ERROR_INVALID_PRIORITY -> Result.SLOTS_FULL
        else -> Result.FAILED
    }.also { Log.w(TAG, "Emoji font update failed with code $code -> $it") }

    private fun applyEmojiFile(file: File, persistName: Boolean): Result = runCatching {
        checkFont(file)?.let { return@runCatching it }
        val psName = FontFileUtil.getPostScriptName(mapFile(file), 0)
            ?: return@runCatching Result.INVALID_FONT

        // 1) install the file (empty signature is accepted on this ROM)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val result = fontManager.updateFontFamily(
                FontFamilyUpdateRequest.Builder()
                    .addFontFileUpdateRequest(FontFileUpdateRequest(pfd, ByteArray(0)))
                    .build(),
                fontManager.fontConfig.configVersion
            )
            if (result != FontManager.RESULT_SUCCESS) {
                return@runCatching resultFor(result, fallbackStep = false)
            }
        }

        // 2) insert it above every existing und-Zsye family
        val maxPriority = fontManager.fontConfig.fontFamilies
            .filter { it.localeList.toLanguageTags() == EMOJI_LANG }
            .maxOfOrNull { it.priority } ?: -1
        val font = FontFamilyUpdateRequest.Font.Builder(psName, FontStyle()).build()
        val request = FallbackFontUpdateRequest.Builder()
            .addFont(font)
            .setLanguages(EMOJI_LANG)
            .setPriority(maxPriority + 1)
            .build()
        val fallbackResult = fontManager.updateFontFallbacks(listOf(request))
        if (fallbackResult != FontManager.RESULT_SUCCESS) {
            return@runCatching resultFor(fallbackResult, fallbackStep = true)
        }
        if (persistName) {
            Settings.Secure.putString(context.contentResolver, CUSTOM_EMOJI_NAME_KEY, psName)
        }
        Result.SUCCESS
    }.onFailure { Log.e(TAG, "Failed to apply emoji font", it) }
        .getOrDefault(Result.FAILED)
}
