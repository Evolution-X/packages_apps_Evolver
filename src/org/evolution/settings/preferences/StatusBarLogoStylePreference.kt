/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.util.AttributeSet
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.utils.StatusBarLogoImageUtils

class StatusBarLogoStylePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: ComposeDialogHandle? = null
    private var logoPickerLauncher: ActivityResultLauncher<Intent>? = null

    private val pm: PackageManager = context.packageManager
    private val sysUiRes: Resources? = try {
        pm.getResourcesForApplication(SYSTEMUI_PACKAGE)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val logoDrawables: Array<String> =
        context.resources.getStringArray(R.array.statusbar_logo_drawables)

    private val logoLabels: Array<String> = run {
        val labels = context.resources.getStringArray(R.array.statusbar_logo_labels)
        if (labels.size == logoDrawables.size) labels else logoDrawables
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()

        val activity = context as? FragmentActivity ?: return
        logoPickerLauncher = activity.activityResultRegistry.register(
            "logo_picker_${key}",
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.data?.let { handleLogoUriSelected(it, activity) }
        }
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        logoPickerLauncher?.unregister()
        logoPickerLauncher = null
        super.onDetached()
    }

    override fun onClick() {
        dialog?.dismiss()

        val current = getCurrentValue()
        val selectedIndex = if (current in logoDrawables.indices) current else 0
        val options = logoDrawables.indices.map { index ->
            val icon = getIconDrawable(index)
            PickerOption(labelForIndex(index).orEmpty()) { PickerDrawableIcon(icon) }
        }

        // "Add logo" sits next to Cancel, like "Add font" in the font picker.
        val customIndex = logoDrawables.indexOf(CUSTOM_IMAGE_DRAWABLE)
        val addLogo = if (customIndex >= 0 && logoPickerLauncher != null) {
            PickerAction(context.getString(R.string.status_bar_logo_add)) { launchLogoPicker() }
        } else {
            null
        }

        dialog = context.showPickerDialog(
            title = title,
            options = options,
            selectedIndex = selectedIndex,
            action = addLogo,
            onClosed = { dialog = null },
        ) { position ->
            if (position != selectedIndex) {
                if (!callChangeListener(position)) return@showPickerDialog false
                Settings.System.putIntForUser(
                    context.contentResolver,
                    SETTING_KEY,
                    position,
                    UserHandle.USER_CURRENT
                )
                summary = labelForIndex(position).orEmpty()
            }
            true
        }
    }

    private fun launchLogoPicker() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            logoPickerLauncher?.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.quick_settings_header_needs_gallery, Toast.LENGTH_LONG)
                .show()
        }
    }

    /** Saves the picked image as the custom logo and switches to the custom logo style. */
    private fun handleLogoUriSelected(uri: Uri, activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                StatusBarLogoImageUtils.saveLogoImage(context, uri)
            }
            if (savedPath == null) {
                Toast.makeText(context, R.string.quick_settings_header_image_error, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            Settings.System.putStringForUser(
                context.contentResolver,
                Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI,
                savedPath,
                UserHandle.USER_CURRENT
            )
            val customIndex = logoDrawables.indexOf(CUSTOM_IMAGE_DRAWABLE)
            if (customIndex >= 0 && callChangeListener(customIndex)) {
                Settings.System.putIntForUser(
                    context.contentResolver,
                    SETTING_KEY,
                    customIndex,
                    UserHandle.USER_CURRENT
                )
                summary = labelForIndex(customIndex).orEmpty()
            }
        }
    }

    private fun getIconDrawable(styleIndex: Int): Drawable? {
        val res = sysUiRes ?: return null
        if (styleIndex < 0 || styleIndex >= logoDrawables.size) return null

        val name = logoDrawables[styleIndex]
        val id = res.getIdentifier(name, "drawable", SYSTEMUI_PACKAGE)
        if (id == 0) return null

        return try {
            res.getDrawable(id, context.theme)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getCurrentValue(): Int {
        return Settings.System.getIntForUser(
            context.contentResolver,
            SETTING_KEY,
            0,
            UserHandle.USER_CURRENT
        )
    }

    private fun updateSummary() {
        val label = labelForIndex(getCurrentValue()).orEmpty()
        summary = label
    }

    private fun labelForIndex(index: Int): String? {
        if (index < 0 || index >= logoLabels.size) return null
        return logoLabels[index]
    }

    companion object {
        private const val SETTING_KEY = "status_bar_logo_style"
        private const val CUSTOM_IMAGE_DRAWABLE = "custom_image"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    }
}
