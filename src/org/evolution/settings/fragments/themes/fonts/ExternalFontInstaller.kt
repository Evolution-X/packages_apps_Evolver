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
import android.graphics.fonts.FontFamilyUpdateRequest
import android.graphics.fonts.FontFileUpdateRequest
import android.graphics.fonts.FontFileUtil
import android.graphics.fonts.FontManager
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.os.FileUtils
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
import java.nio.channels.FileChannel

class ExternalFontInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ExternalFontInstaller"
        private const val CUSTOM_FONT_FILE = "cust_font.ttf"
        private const val TEMP_PREVIEW_FONT = "preview_font.ttf"
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
        return@withContext Typeface.createFromFile(tempFile)
    }

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

        if (!applyFontToSystem(fontFile, postScriptName)) {
            fontFile.delete()
            return null
        }

        updateThemeOverlays()
        cleanupPreviewFont()
        return postScriptName
    }

    private suspend fun copyUriToCache(uri: Uri, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, fileName)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    cacheFile.outputStream().use { output ->
                        FileUtils.copy(input, output)
                    }
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to cache", e)
            null
        }
    }

    private fun extractPostScriptName(fontFile: File): String? =
        FileInputStream(fontFile).use { fis ->
            val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            FontFileUtil.getPostScriptName(buffer, 0)
        }

    private fun applyFontToSystem(fontFile: File, postScriptName: String): Boolean {
        return runCatching {
            val pfd = ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val fontFileUpdateRequest = FontFileUpdateRequest(pfd, ByteArray(0))

            val fontRegular = FontFamilyUpdateRequest.Font.Builder(
                postScriptName,
                FontStyle()
            ).build()

            val familyRegular = FontFamilyUpdateRequest.FontFamily.Builder(
                DEFAULT_FONT_FAMILY,
                listOf(fontRegular)
            ).build()

            val updateRequest = FontFamilyUpdateRequest.Builder()
                .addFontFileUpdateRequest(fontFileUpdateRequest)
                .addFontFamily(familyRegular)
                .build()

            val result = fontManager.updateFontFamily(
                updateRequest,
                fontManager.fontConfig.configVersion
            )

            if (result != FontManager.RESULT_SUCCESS) {
                showToast(R.string.toast_failed_apply_font)
                false
            } else true
        }.getOrElse {
            Log.e(TAG, "Failed to update system font", it)
            false
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

    private fun showToast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    fun resetFontUpdates() {
        fontManager.clearUpdates()
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
