/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.nio.charset.StandardCharsets

class TrickyStore : SettingsPreferenceFragment() {

    private val keyboxPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: ByteArray(0)
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        KEYBOX_KEY,
                        encoded
                    )
                    killGms()
                    toast(getString(R.string.ts_keybox_imported))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
        }
    }

    private val targetPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val text = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                    } ?: ""
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        TARGET_KEY,
                        text
                    )
                    toast(getString(R.string.ts_target_list_imported))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.tricky_store)

        findPreference<Preference>("ts_import_keybox")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            keyboxPicker.launch(intent)
            true
        }

        findPreference<Preference>("ts_delete_keybox")?.setOnPreferenceClickListener {
            showDeleteKeyboxDialog()
            true
        }

        findPreference<Preference>("ts_manage_targets")?.setOnPreferenceClickListener {
            showTargetAppPicker()
            true
        }

        findPreference<Preference>("ts_security_patch")?.setOnPreferenceClickListener {
            showPatchDateDialog()
            true
        }

        findPreference<Preference>("ts_import_targets")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
            }
            targetPicker.launch(intent)
            true
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        val keyboxExists = !Settings.Secure.getString(
            requireContext().contentResolver, KEYBOX_KEY
        ).isNullOrEmpty()

        val targetContent = Settings.Secure.getString(requireContext().contentResolver, TARGET_KEY)
        val targetCount = if (!targetContent.isNullOrEmpty()) {
            targetContent.lines().count { it.isNotBlank() }
        } else 0

        findPreference<Preference>("ts_import_keybox")?.summary =
            if (keyboxExists) getString(R.string.ts_keybox_installed)
            else getString(R.string.ts_no_keybox)

        findPreference<Preference>("ts_delete_keybox")?.isEnabled = keyboxExists

        findPreference<Preference>("ts_manage_targets")?.summary =
            if (targetCount > 0) {
                val mode = readCurrentMode()
                getString(R.string.ts_target_apps_count, targetCount) + " · $mode"
            } else getString(R.string.ts_no_targets)

            val patchDate = Settings.Secure.getString(requireContext().contentResolver, PATCH_KEY)
            findPreference<Preference>("ts_security_patch")?.summary =
                if (!patchDate.isNullOrEmpty()) patchDate
                else getString(R.string.ts_no_patch)

            findPreference<Preference>("ts_verification_mode")?.summary = buildVerificationSummary()
        }

    private fun buildVerificationSummary(): String {
        val content = Settings.Secure.getString(
            requireContext().contentResolver, TARGET_KEY
        ) ?: return getString(R.string.ts_verification_mode_auto)

        var auto = 0; var cert = 0; var leaf = 0
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) when {
                trimmed.endsWith("!") -> cert++
                trimmed.endsWith("?") -> leaf++
                else                  -> auto++
            }
        }

        if (auto == 0 && cert == 0 && leaf == 0)
            return getString(R.string.ts_verification_mode_auto)

        return buildList {
            if (auto > 0) add("$auto auto")
            if (cert > 0) add("$cert cert")
            if (leaf > 0) add("$leaf leaf")
        }.joinToString(" · ")
    }

    private fun readCurrentMode(): String {
        val targetContent = Settings.Secure.getString(requireContext().contentResolver, TARGET_KEY)
            ?: return "auto"
        val firstLine = targetContent.lines().firstOrNull { it.isNotBlank() } ?: return "auto"
        return when {
            firstLine.trimEnd().endsWith("!") -> "cert"
            firstLine.trimEnd().endsWith("?") -> "leaf"
            else -> "auto"
        }
    }

    private fun showDeleteKeyboxDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_delete_keybox_title)
            .setMessage(R.string.ts_delete_keybox_message)
            .setPositiveButton(R.string.ts_delete) { _, _ ->
                try {
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        KEYBOX_KEY,
                        null
                    )
                    toast(getString(R.string.ts_keybox_deleted))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTargetAppPicker() {
        val sheet = TrickyStoreAppPickerSheet()
        sheet.onDismissed = { refreshStatus() }
        sheet.show(parentFragmentManager, TrickyStoreAppPickerSheet.TAG)
    }

    private fun showPatchDateDialog() {
        val current = Settings.Secure.getString(requireContext().contentResolver, PATCH_KEY) ?: ""
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            hint = "YYYY-MM-DD"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_security_patch)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    PATCH_KEY,
                    value.ifEmpty { null }
                )
                refreshStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.ts_delete) { _, _ ->
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    PATCH_KEY,
                    null
                )
                refreshStatus()
            }
            .show()
    }

    private fun killGms() {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(VENDING_PACKAGE)
            am.forceStopPackage(DROIDGUARD_PACKAGE)
            am.forceStopPackage(GMS_PACKAGE)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val KEYBOX_KEY = "spoof_trickystore_keybox"
        private const val TARGET_KEY = TrickyStoreAppPickerSheet.TARGET_KEY
        private const val PATCH_KEY = "spoof_trickystore_patch"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE = "com.google.android.gms"
    }
}
