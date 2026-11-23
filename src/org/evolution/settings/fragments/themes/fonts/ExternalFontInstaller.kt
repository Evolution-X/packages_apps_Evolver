/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.util.evolution.ThemeUtils
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel

/** What an install gave: the font's PostScript name, or why it failed. */
sealed interface InstallResult {
    data class Installed(val postScriptName: String) : InstallResult

    /** [code] is a FontManager.RESULT_* value, or [RESULT_UNREADABLE] or [RESULT_EXCEPTION]. */
    data class Failed(val code: Int) : InstallResult
}

class ExternalFontInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ExternalFontInstaller"
        private const val CUSTOM_FONT_FILE = "cust_font.ttf"
        private const val TEMP_PREVIEW_FONT = "preview_font.ttf"
        const val MAX_FONT_BYTES = 64L * 1024L * 1024L

        /** Our own codes, kept clear of FontManager's RESULT_* values (-1..-9, -10001..). */
        const val RESULT_EXCEPTION = -20000
        const val RESULT_UNREADABLE = -20001
        private const val OVERLAY_CATEGORY_FONT = "android.theme.customization.font"
        private const val DEFAULT_FONT_PACKAGE = "android"
        const val DEFAULT_FONT_FAMILY = "google-sans-flex"
        private const val DEFAULT_FONT_OVERLAY = "com.android.theme.font.googlesansflex"

        /** User-facing explanation of why [InstallResult.Failed] happened. */
        fun errorMessage(context: Context, code: Int): String {
            val maxMb = (MAX_FONT_BYTES / (1024L * 1024L)).toInt()
            return when (code) {
                FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED ->
                    context.getString(R.string.font_error_updater_disabled)
                FontManager.RESULT_ERROR_INVALID_FONT_FILE ->
                    context.getString(R.string.font_error_invalid_file, maxMb)
                FontManager.RESULT_ERROR_INVALID_FONT_NAME ->
                    context.getString(R.string.font_error_invalid_name)
                FontManager.RESULT_ERROR_DOWNGRADING ->
                    context.getString(R.string.font_error_downgrading)
                FontManager.RESULT_ERROR_VERIFICATION_FAILURE ->
                    context.getString(R.string.font_error_verification)
                FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG ->
                    context.getString(R.string.font_error_storage)
                RESULT_UNREADABLE -> context.getString(R.string.font_error_unreadable, maxMb)
                else -> context.getString(R.string.font_install_failed, code)
            }
        }

        fun rebootDevice() {
            runCatching {
                val binder = ServiceManager.getService("statusbar")
                val statusBarService = IStatusBarService.Stub.asInterface(binder)
                statusBarService.reboot(false, "system_font_change")
            }.onFailure {
                Log.e(TAG, "Failed to reboot device via statusbar service", it)
            }
        }
    }

    private val fontManager: FontManager = context.getSystemService(FontManager::class.java)

    suspend fun loadTypefaceFromUri(uri: Uri): Typeface? = withContext(Dispatchers.IO) {
        val tempFile = copyUriToCache(uri, TEMP_PREVIEW_FONT) ?: return@withContext null
        val postScriptName = extractPostScriptName(tempFile) ?: run {
            tempFile.delete()
            return@withContext null
        }
        return@withContext runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure {
            Log.e(TAG, "Failed to create preview typeface for $postScriptName", it)
            tempFile.delete()
        }.getOrNull()
    }

    // The saved copy lives in filesDir where the system can't purge it. Installs from before
    // that were kept in cacheDir, so look there too.
    private fun savedFontFile(): File? =
        File(context.filesDir, CUSTOM_FONT_FILE).takeIf { it.isFile }
            ?: File(context.cacheDir, CUSTOM_FONT_FILE).takeIf { it.isFile }

    fun loadInstalledTypeface(): Typeface? = runCatching {
        savedFontFile()?.let { Typeface.createFromFile(it) }
    }.getOrNull()

    suspend fun installFontFromUri(uri: Uri): InstallResult =
        installCachedFont(copyUriToCache(uri, CUSTOM_FONT_FILE))

    /** Same as [installFontFromUri] for a font file that is already on the device. */
    suspend fun installFontFromFile(file: File): InstallResult =
        installCachedFont(copyFileToCache(file, CUSTOM_FONT_FILE))

    private suspend fun installCachedFont(copied: File?): InstallResult {
        val fontFile = copied ?: return InstallResult.Failed(RESULT_UNREADABLE)

        val postScriptName = extractPostScriptName(fontFile) ?: run {
            fontFile.delete()
            return InstallResult.Failed(FontManager.RESULT_ERROR_INVALID_FONT_FILE)
        }

        val code = installCustomFont(fontFile)
        if (code != FontManager.RESULT_SUCCESS) {
            fontFile.delete()
            return InstallResult.Failed(code)
        }

        withContext(Dispatchers.IO) {
            runCatching {
                fontFile.copyTo(File(context.filesDir, CUSTOM_FONT_FILE), overwrite = true)
                fontFile.delete()
            }.onFailure { Log.e(TAG, "Failed to keep the custom font in filesDir", it) }
        }
        updateThemeOverlays()
        cleanupPreviewFont()
        return InstallResult.Installed(postScriptName)
    }

    private suspend fun copyUriToCache(uri: Uri, fileName: String): File? =
        copyToCache(fileName) {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }

    private suspend fun copyFileToCache(source: File, fileName: String): File? =
        copyToCache(fileName) { source.inputStream() }

    // [open] runs inside the try, so a source that can't be opened gives null like any other
    // failure to copy.
    private suspend fun copyToCache(fileName: String, open: () -> InputStream?): File? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, fileName)
            val tempFile = File(context.cacheDir, "$fileName.tmp")
            try {
                tempFile.delete()
                val source = open() ?: return@withContext null
                source.use { input ->
                    FileOutputStream(tempFile, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_FONT_BYTES) {
                                throw IllegalArgumentException("Font file exceeds size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                if (tempFile.length() <= 0L) {
                    tempFile.delete()
                    return@withContext null
                }

                if (cacheFile.exists() && !cacheFile.delete()) {
                    throw IllegalStateException("Could not replace cached font")
                }
                if (!tempFile.renameTo(cacheFile)) {
                    throw IllegalStateException("Could not install cached font")
                }
                cacheFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy font to cache", e)
                tempFile.delete()
                null
            }
        }

    private fun extractPostScriptName(fontFile: File): String? = runCatching {
        if (!fontFile.isFile || fontFile.length() <= 0L || fontFile.length() > MAX_FONT_BYTES) {
            return@runCatching null
        }
        FileInputStream(fontFile).use { fis ->
            val size = fis.channel.size()
            if (size <= 0L || size > MAX_FONT_BYTES) return@use null
            val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            FontFileUtil.getPostScriptName(buffer, 0)
        }
    }.onFailure {
        Log.e(TAG, "Failed to inspect font file", it)
    }.getOrNull()

    /** Hands the file to FontManager, off the main thread. Returns its RESULT_* code. */
    private suspend fun installCustomFont(fontFile: File): Int = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fontManager.installCustomFont(it) }
        }.onFailure {
            Log.e(TAG, "Failed to update system font", it)
        }.getOrDefault(RESULT_EXCEPTION).also {
            if (it != FontManager.RESULT_SUCCESS) Log.e(TAG, "installCustomFont failed with $it")
        }
    }

    private fun updateThemeOverlays() {
        val resolver = context.contentResolver
        val userId = UserHandle.myUserId()

        val json = runCatching {
            val current = Settings.Secure.getStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                userId
            )
            if (current.isNullOrEmpty()) JSONObject() else JSONObject(current)
        }.getOrElse { JSONObject() }

        runCatching {
            if (json.has(OVERLAY_CATEGORY_FONT)) json.remove(OVERLAY_CATEGORY_FONT)
            json.put(OVERLAY_CATEGORY_FONT, DEFAULT_FONT_OVERLAY)

            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString(),
                userId
            )
        }.onFailure { Log.e(TAG, "Failed to persist custom font overlay", it) }
    }

    /** Removes the custom text font. The custom emoji font is separate and stays. Blocking. */
    fun resetFontUpdates() {
        val result = fontManager.clearCustomFont()
        if (result != FontManager.RESULT_SUCCESS) {
            Log.e(TAG, "clearCustomFont failed with result $result")
        }
        ThemeUtils.getInstance(context).setOverlayEnabled(
            OVERLAY_CATEGORY_FONT, DEFAULT_FONT_PACKAGE, DEFAULT_FONT_PACKAGE
        )
        cleanupPreviewFont()
    }

    private fun cleanupPreviewFont() {
        try {
            val previewFile = File(context.cacheDir, TEMP_PREVIEW_FONT)
            if (previewFile.exists()) previewFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup preview font", e)
        }
    }
}
