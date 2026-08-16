/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package org.evolution.settings.fragments.lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

import androidx.preference.Preference

import com.android.settings.R
import com.android.settings.preferences.BasePreferenceFragment

import org.evolution.settings.utils.ImageUtils
import org.evolution.settings.utils.PreferenceUtils

class ClockStyles : BasePreferenceFragment(R.xml.clock_styles),
    Preference.OnPreferenceChangeListener {

    companion object {
        private const val KEY_CUSTOM_AOD_IMAGE = "lockscreen_custom_image"
        private const val CUSTOM_IMAGE_REQUEST_CODE = 1001
    }

    private var mCustomImagePreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        mCustomImagePreference = findPreference(KEY_CUSTOM_AOD_IMAGE)

        updateCustomImagePreference()
        showDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen())
        updateCustomImagePreference()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == KEY_CUSTOM_AOD_IMAGE) {
            try {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                intent.type = "image/*"
                startActivityForResult(intent, CUSTOM_IMAGE_REQUEST_CODE)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    R.string.quick_settings_header_needs_gallery,
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        if (requestCode == CUSTOM_IMAGE_REQUEST_CODE
            && resultCode == Activity.RESULT_OK
            && result != null
        ) {
            val imgUri: Uri? = result.data
            if (imgUri != null) {
                val savedImagePath = ImageUtils.saveImageToInternalStorage(
                    context,
                    imgUri,
                    "lockscreen_aod_image",
                    "LOCKSCREEN_CUSTOM_AOD_IMAGE"
                )
                if (savedImagePath != null) {
                    Settings.System.putStringForUser(
                        requireContext().contentResolver,
                        "custom_aod_image_uri",
                        savedImagePath,
                        UserHandle.USER_CURRENT
                    )
                    mCustomImagePreference?.summary = savedImagePath
                }
            }
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return false
    }

    private fun showDisclaimer() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.clock_styles_disclaimer_title))
            .setMessage(ctx.getString(R.string.clock_styles_disclaimer_message))
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun updateCustomImagePreference() {
        val pref = mCustomImagePreference ?: return
        val ctx = context ?: return

        val imagePath = Settings.System.getString(ctx.contentResolver, "custom_aod_image_uri")
        pref.summary = imagePath ?: ctx.getString(R.string.lockscreen_custom_image_pick_summary)
    }
}
