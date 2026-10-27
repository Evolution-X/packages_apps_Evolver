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
import android.widget.Toast
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.util.evolution.ThemeUtils
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

class ExternalFontInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ExternalFontInstaller"
        private const val CUSTOM_FONT_FILE = "cust_font.ttf"
        private const val TEMP_PREVIEW_FONT = "preview_font.ttf"
        private const val MAX_FONT_BYTES = 32L * 1024L * 1024L
        private const val OVERLAY_CATEGORY_FONT = "android.theme.customization.font"
        private const val DEFAULT_FONT_PACKAGE = "android"
        const val DEFAULT_FONT_FAMILY = "google-sans-flex"
        private const val DEFAULT_FONT_OVERLAY = "com.android.theme.font.googlesansflex"

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

    // The saved font is re-applied after every FontManager.clearUpdates(), so it lives in
    // filesDir where the system can't purge it. Installs from before that were kept in
    // cacheDir, so look there too.
    private fun savedFontFile(): File? =
        File(context.filesDir, CUSTOM_FONT_FILE).takeIf { it.isFile }
            ?: File(context.cacheDir, CUSTOM_FONT_FILE).takeIf { it.isFile }

    fun loadInstalledTypeface(): Typeface? = runCatching {
        savedFontFile()?.let { Typeface.createFromFile(it) }
    }.getOrNull()

    suspend fun installFontFromUri(uri: Uri): String? {
        val fontFile = copyUriToCache(uri, CUSTOM_FONT_FILE) ?: run {
            showToast(R.string.toast_failed_apply_font)
            return null
        }

        val postScriptName = extractPostScriptName(fontFile) ?: run {
            showToast(R.string.toast_invalid_font_file)
            fontFile.delete()
            return null
        }

        withContext(Dispatchers.IO) { dropLegacyCustomFont() }

        if (!applyFontToSystem(fontFile)) {
            fontFile.delete()
            return null
        }

        withContext(Dispatchers.IO) {
            runCatching {
                fontFile.copyTo(File(context.filesDir, CUSTOM_FONT_FILE), overwrite = true)
                fontFile.delete()
            }.onFailure { Log.e(TAG, "Failed to keep the custom font in filesDir", it) }
        }
        updateThemeOverlays()
        cleanupPreviewFont()
        return postScriptName
    }

    private suspend fun copyUriToCache(uri: Uri, fileName: String): File? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, fileName)
            val tempFile = File(context.cacheDir, "$fileName.tmp")
            try {
                tempFile.delete()
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext null
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { input ->
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
                Log.e(TAG, "Failed to copy URI to cache", e)
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

    private fun applyFontToSystem(fontFile: File): Boolean {
        return runCatching {
            val result = ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fontManager.installCustomFont(it) }

            if (result != FontManager.RESULT_SUCCESS) {
                Log.e(TAG, "installCustomFont failed with result $result")
                showToast(R.string.toast_failed_apply_font)
                false
            } else true
        }.getOrElse {
            Log.e(TAG, "Failed to update system font", it)
            false
        }
    }

    /**
     * Custom fonts used to be installed as a replacement of the [DEFAULT_FONT_FAMILY] family.
     * That family is merged after the font from [FontManager.installCustomFont] and would keep
     * winning, so such an install is dropped before the new one goes in. The emoji font goes
     * down with it and is put back. Blocking.
     */
    private fun dropLegacyCustomFont() {
        val saved = Settings.Secure.getString(context.contentResolver, "custom_font_name")
        if (saved.isNullOrEmpty() || fontManager.customFontName != null) return
        runCatching { fontManager.clearUpdates() }
            .onFailure { Log.e(TAG, "Failed to clear the legacy custom font", it) }
        ExternalEmojiInstaller(context).reinstallSaved()
    }

    /**
     * Re-applies the saved custom text font. Used after [FontManager.clearUpdates], which also
     * drops it. Blocking; call off the main thread.
     */
    fun reinstallSavedFont(): Boolean {
        val name = Settings.Secure.getString(context.contentResolver, "custom_font_name")
        if (name.isNullOrEmpty()) return false
        val fontFile = savedFontFile() ?: return false
        if (extractPostScriptName(fontFile) == null) return false
        return applyFontToSystem(fontFile)
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

    private fun showToast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    fun resetFontUpdates() {
        fontManager.clearUpdates()
        // clearUpdates() drops the custom emoji font too; put it back.
        ExternalEmojiInstaller(context).reinstallSaved()
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
