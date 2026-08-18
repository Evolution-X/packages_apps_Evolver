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
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.evolution.PixelDeviceRepository
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.TrickyStore
import org.json.JSONObject

class PlayIntegrityFix : SettingsPreferenceFragment() {

    private val isPifEnabled: Boolean
        get() = Settings.System.getInt(
            requireContext().contentResolver,
            PIF_ENABLED_KEY, 1
        ) != 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeConfigData: Map<String, String> = emptyMap()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val content = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                    } ?: ""
                    val normalized = normalizePifPayload(content)
                    // Validate fingerprint before saving imported config
                    val fp = try { JSONObject(normalized).optString("FINGERPRINT", "") } catch (_: Exception) { "" }
                    if (fp.isNotEmpty() && !PixelDeviceRepository.isValidFingerprint(fp)) {
                        toast(getString(R.string.pif_failed, getString(R.string.pif_invalid_fingerprint)))
                        return@let
                    }
                    val stamped = JSONObject(normalized).apply {
                        put("manually_imported", true)
                    }.toString(2)
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        PIF_CONFIG_KEY,
                        stamped
                    )
                    try {
                        val patch = JSONObject(normalized).optString("SECURITY_PATCH")
                        if (patch.isNotEmpty()) {
                            updatePatchDateIfSimple(requireContext().contentResolver, patch)
                        }
                    } catch (_: Exception) {}
                    killGmsWithConfirmation {
                        toast(getString(R.string.pif_imported_as, PIF_CONFIG_NAME))
                        refreshStatus()
                    }
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.play_integrity_fix)

        findPreference<Preference>("pif_fetch_beta")?.setOnPreferenceClickListener {
            fetchDevicesForChannel()
            true
        }

        findPreference<Preference>("pif_import_config")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
            true
        }

        findPreference<Preference>("pif_delete_config")?.setOnPreferenceClickListener {
            showDeleteDialog()
            true
        }

        findPreference<ListPreference>("pif_spoof_vending_finger")?.apply {
            val current = activeConfigData["spoofVendingFinger"] ?: "0"
            value = current
            setOnPreferenceChangeListener { _, newValue ->
                updateConfigValue("spoofVendingFinger", newValue as String)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("pif_spoof_vending_sdk")?.apply {
            isChecked = isVendingSdkOn(activeConfigData["spoofVendingSdk"])
            setOnPreferenceChangeListener { _, newValue ->
                // The framework reads this as a level: 1 = SDK 32, N > 1 = SDK N, capped at the
                // real SDK. The switch writes 1 or 0.
                updateConfigValue("spoofVendingSdk", if (newValue as Boolean) "1" else "0")
                true
            }
        }

        FLAG_PREFS.forEach { (prefKey, flag) ->
            findPreference<SwitchPreferenceCompat>(prefKey)?.apply {
                isChecked = isFlagOn(activeConfigData[flag.name], flag.default)
                setOnPreferenceChangeListener { _, newValue ->
                    updateConfigValue(flag.name, if (newValue as Boolean) "1" else "0")
                    true
                }
            }
        }

        findPreference<Preference>("pif_reset_advanced")?.setOnPreferenceClickListener {
            showResetAdvancedDialog()
            true
        }

        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (isPifEnabled && !isAutoFetchCooldownActive()) autoFetchIfStale()
    }

    private fun isAutoFetchCooldownActive(): Boolean {
        val last = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_AUTO_FETCH_KEY, 0L)
        return last > 0L && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L
    }

    private fun markAutoFetchDone() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_AUTO_FETCH_KEY,
            System.currentTimeMillis()
        )
    }

    private fun clearAutoFetchCooldown() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_AUTO_FETCH_KEY, 0L)
    }

    private fun autoFetchIfStale() {
        if (!isPifEnabled) return
        val content = Settings.Secure.getString(
            requireContext().contentResolver, PIF_CONFIG_KEY
        )
        val isManuallyImported = try {
            !content.isNullOrEmpty() && JSONObject(content).optBoolean("manually_imported", false)
        } catch (_: Exception) { false }

        if (isManuallyImported) return

        val localMonth = try {
            if (!content.isNullOrEmpty()) JSONObject(content).optString("_canary_month", "") else ""
        } catch (_: Exception) { "" }
        val localRelease = try {
            if (!content.isNullOrEmpty()) JSONObject(content).optString("_canary_release_date", null) else null
        } catch (_: Exception) { null }
        val daysLeft = if (localMonth.isNotEmpty()) {
            PixelDeviceRepository.getDaysUntilExpiry(localMonth, localRelease)
        } else null

        // Still comfortably valid — nothing to check yet, and don't burn the
        // daily cooldown checking in for no reason.
        if (daysLeft != null && daysLeft > REFETCH_WINDOW_DAYS) return

        markAutoFetchDone()
        scope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    PixelDeviceRepository.getProfiles(requireContext(), true)
                }
                val defaultCodename = PixelDeviceRepository.getDefaultPhoneCodename(profiles)
                val matched = withContext(Dispatchers.IO) {
                    PixelDeviceRepository.getProfileByCodename(requireContext(), defaultCodename, false)
                } ?: return@launch

                if (!PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) return@launch

                // Same rule as AxSpoofManager.refreshPixelFingerprintIfStale(): nothing to do
                // when it is already the current fingerprint (the daily cooldown has started).
                val currentFingerprint = try {
                    if (!content.isNullOrEmpty()) JSONObject(content).optString("FINGERPRINT", "") else ""
                } catch (_: Exception) { "" }
                if (matched.fingerprint == currentFingerprint) return@launch

                val toSave = buildProfileConfig(content, matched)
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    PIF_CONFIG_KEY,
                    toSave.toString(2)
                )
                updatePatchDateIfSimple(requireContext().contentResolver, matched.securityPatch)
                // Background auto-fetch shouldn't interrupt with a dialog — stop GMS
                // only, skip the Play Store data wipe prompt.
                stopGmsPackages()
                refreshStatus()
            } catch (_: Exception) {}
        }
    }

    private fun refreshStatus() {
        val content = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
        activeConfigData = if (!content.isNullOrEmpty()) readConfigData(content) else emptyMap()
        val exists = activeConfigData.isNotEmpty()

        val activePref = findPreference<Preference>("pif_active_config")
        if (exists) {
            val model = activeConfigData["MODEL"] ?: ""
            val fingerprint = activeConfigData["FINGERPRINT"] ?: ""
            val ageDays = PixelDeviceRepository.getPatchAgeDays(activeConfigData["SECURITY_PATCH"] ?: "")
            val ageStr = ageDays?.let { " · ${it}d ago" } ?: ""
            val expiryStr = activeConfigData["_canary_month"]?.let { month ->
                getCanaryExpiryString(month, activeConfigData["_canary_release_date"])
            }?.let { " · $it" } ?: ""
            activePref?.title = PIF_CONFIG_NAME
            activePref?.summary = if (model.isNotEmpty()) {
                "MODEL: $model$ageStr$expiryStr" +
                if (fingerprint.isNotEmpty()) "\nFINGERPRINT: $fingerprint" else ""
            } else {
                getString(R.string.pif_config_loaded)
            }
        } else {
            activePref?.title = getString(R.string.pif_active_config)
            activePref?.summary = getString(R.string.pif_no_config)
        }

        findPreference<Preference>("pif_delete_config")?.isEnabled = exists

        populateConfigDetails(activeConfigData)

        // The framework stops bootstrapping a fingerprint once the config is non-empty, and a
        // toggle on an empty config would create one holding only that flag.
        findPreference<ListPreference>("pif_spoof_vending_finger")?.apply {
            val current = activeConfigData["spoofVendingFinger"].orEmpty()
            // PIFork and the framework also accept a literal FINGERPRINT here. The list can't
            // show one, so leave the stored value alone and say what is set.
            val isCustom = current.isNotEmpty() && current !in listOf("0", "1", "true", "false")
            value = when {
                isCustom -> current
                current == "1" || current.equals("true", true) -> "1"
                else -> "0"
            }
            summary = if (isCustom) getString(R.string.pif_vending_finger_custom_summary, current)
                else getString(R.string.pif_spoof_vending_finger_summary)
            isEnabled = exists
        }
        findPreference<SwitchPreferenceCompat>("pif_spoof_vending_sdk")?.apply {
            isChecked = isVendingSdkOn(activeConfigData["spoofVendingSdk"])
            isEnabled = exists
        }
        FLAG_PREFS.forEach { (prefKey, flag) ->
            findPreference<SwitchPreferenceCompat>(prefKey)?.apply {
                isChecked = isFlagOn(activeConfigData[flag.name], flag.default)
                isEnabled = exists
            }
        }
        findPreference<Preference>("pif_reset_advanced")?.isEnabled = exists
    }

    /** Drops every spoof* flag so the framework's defaults apply again. Keeps the fingerprint. */
    private fun showResetAdvancedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_reset_advanced_title)
            .setMessage(R.string.pif_reset_advanced_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    val existing = Settings.Secure.getString(
                        requireContext().contentResolver, PIF_CONFIG_KEY)
                    val json = JSONObject(existing ?: return@setPositiveButton)
                    json.keys().asSequence().filter { it.startsWith("spoof") }.toList()
                        .forEach { json.remove(it) }
                    Settings.Secure.putString(
                        requireContext().contentResolver, PIF_CONFIG_KEY, json.toString(2))
                    stopGmsPackages()
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun populateConfigDetails(data: Map<String, String>) {
        val category = findPreference<PreferenceCategory>("pif_config_details_category") ?: return
        category.removeAll()

        if (data.isEmpty()) return

        val intKeys = setOf("DEVICE_INITIAL_SDK_INT", "SDK_INT")

        val displayOrder = listOf(
            "MODEL", "MANUFACTURER", "BRAND", "PRODUCT", "DEVICE",
            "FINGERPRINT", "SECURITY_PATCH", "ID", "RELEASE", "DEVICE_INITIAL_SDK_INT"
        )

        for (key in displayOrder) {
            val value = data[key] ?: continue
            category.addPreference(androidx.preference.EditTextPreference(requireContext()).apply {
                this.title = key
                this.summary = value
                this.text = value
                dialogTitle = key
                setOnPreferenceChangeListener { _, newValue ->
                    val v = (newValue as? String)?.trim() ?: return@setOnPreferenceChangeListener false
                    if (v.isEmpty()) {
                        toast(getString(R.string.pif_failed, "Value cannot be empty"))
                        return@setOnPreferenceChangeListener false
                    }
                    if (key in intKeys && v.toIntOrNull() == null) {
                        toast(getString(R.string.pif_failed, "Must be a valid integer"))
                        return@setOnPreferenceChangeListener false
                    }
                    if (key == "FINGERPRINT" && !PixelDeviceRepository.isValidFingerprint(v)) {
                        toast(getString(R.string.pif_failed, getString(R.string.pif_invalid_fingerprint)))
                        return@setOnPreferenceChangeListener false
                    }
                    updateConfigValue(key, v, manualEdit = true)
                    true
                }
            })
        }

        data.keys.filter {
            it !in displayOrder
                && !it.startsWith("spoof")
                && !it.startsWith("_")
                && it != "DEBUG"
                && it != "verboseLogs"
                && it != "manually_imported"
        }
            .forEach { key ->
                category.addPreference(androidx.preference.EditTextPreference(requireContext()).apply {
                    this.title = key
                    this.summary = data[key]
                    this.text = data[key]
                    dialogTitle = key
                    setOnPreferenceChangeListener { _, newValue ->
                        val v = (newValue as? String)?.trim() ?: return@setOnPreferenceChangeListener false
                        if (v.isEmpty()) {
                            toast(getString(R.string.pif_failed, "Value cannot be empty"))
                            return@setOnPreferenceChangeListener false
                        }
                        if (key in intKeys && v.toIntOrNull() == null) {
                            toast(getString(R.string.pif_failed, "Must be a valid integer"))
                            return@setOnPreferenceChangeListener false
                        }
                        updateConfigValue(key, v, manualEdit = true)
                        true
                    }
                })
            }
    }

    private fun showDeleteDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pif_delete_title, PIF_CONFIG_NAME))
            .setMessage(R.string.pif_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                try {
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        PIF_CONFIG_KEY,
                        null
                    )
                    clearAutoFetchCooldown()
                    toast(getString(R.string.pif_deleted, PIF_CONFIG_NAME))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchDevicesForChannel() {
        val fetchPref = findPreference<Preference>("pif_fetch_beta") ?: return
        fetchPref.summary = getString(R.string.pif_fetching)
        fetchPref.isEnabled = false

        scope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    PixelDeviceRepository.getProfiles(requireContext(), true)
                }

                if (profiles.isEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_devices_found)))
                    return@launch
                }

                val currentDevice = android.os.SystemProperties.get(MATCH_DEVICE_PROP, "")
                val preferredASeries = PixelDeviceRepository.getPreferredASeriesCodename(profiles)
                val sortedProfiles = profiles.sortedWith(
                    compareByDescending<PixelDeviceRepository.PixelProfile> {
                        it.device == currentDevice
                    }.thenByDescending {
                        PixelDeviceRepository.A_SERIES_ORDER.contains(it.codename)
                    }.thenByDescending {
                        PixelDeviceRepository.GENERATION_ORDER.indexOf(it.codename)
                            .let { idx -> if (idx < 0) -1 else PixelDeviceRepository.GENERATION_ORDER.size - idx }
                    }
                )
                val modelNames = sortedProfiles.map {
                    if (PixelDeviceRepository.A_SERIES_ORDER.contains(it.codename)) {
                        "${it.model} — ${getString(R.string.pif_recommended_suffix)}"
                    } else {
                        it.model
                    }
                }.toTypedArray()
                val preselectedIndex = sortedProfiles.indexOfFirst { it.codename == preferredASeries }
                    .let { if (it < 0) 0 else it }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setSingleChoiceItems(modelNames, preselectedIndex) { dialog, which ->
                        dialog.dismiss()
                        saveProfileAsPif(sortedProfiles[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref.summary = getString(R.string.pif_fetch_pixel_beta_summary)
                fetchPref.isEnabled = true
            }
        }
    }

    private fun saveProfileAsPif(profile: PixelDeviceRepository.PixelProfile) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta")
        fetchPref?.summary = getString(R.string.pif_generating)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                if (!isPifEnabled) return@launch
                if (!PixelDeviceRepository.isValidFingerprint(profile.fingerprint)) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_invalid_fingerprint)))
                    return@launch
                }
                val pifJson = buildProfileConfig(
                    Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY),
                    profile,
                )
                withContext(Dispatchers.IO) {
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        PIF_CONFIG_KEY,
                        pifJson.toString(2)
                    )
                    updatePatchDateIfSimple(requireContext().contentResolver, profile.securityPatch)
                }
                killGmsWithConfirmation {
                    toast(getString(R.string.pif_fetched_model, profile.model))
                    refreshStatus()
                }
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.summary = getString(R.string.pif_fetch_pixel_beta_summary)
                fetchPref?.isEnabled = true
            }
        }
    }

    /**
     * Updates a key-value pair in the active config stored in Settings.Secure.
     * If no config exists yet, creates a new JSON object with just this value.
     */
    private fun updateConfigValue(key: String, value: String, manualEdit: Boolean = false) {
        try {
            val existing = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
            val json = try { JSONObject(existing ?: "") } catch (e: Exception) { JSONObject() }
            json.put(key, value)
            if (key == "FINGERPRINT") {
                // These describe the old fingerprint. Drop them so the framework derives them
                // from the new one (PlayIntegritySpoofService.deriveFieldsFromFingerprint).
                listOf("ID", "INCREMENTAL", "TYPE", "TAGS", "RELEASE",
                    "_canary_month", "_canary_release_date").forEach { json.remove(it) }
            }
            // A hand-edited field must survive the auto-refresh, which skips manually_imported.
            if (manualEdit) json.put("manually_imported", true)
            Settings.Secure.putString(
                requireContext().contentResolver,
                PIF_CONFIG_KEY,
                json.toString(2)
            )
            stopGmsPackages()
            refreshStatus()
        } catch (e: Exception) {
            toast(getString(R.string.pif_failed, e.message ?: ""))
        }
    }

    private fun stopGmsPackages() {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(VENDING_PACKAGE)
            am.forceStopPackage(DROIDGUARD_PACKAGE)
            am.forceStopPackage(GMS_PACKAGE)
            am.forceStopPackage(GMS_PERSISTENT_PACKAGE)
            am.forceStopPackage(RKPD_PACKAGE)
            am.forceStopPackage(GSF_PACKAGE)
            am.forceStopPackage(CONTACT_KEYS_PACKAGE)
            am.forceStopPackage(SAFETY_CORE_PACKAGE)
            am.forceStopPackage(VELVET_PACKAGE)
        } catch (_: Exception) {}
    }

    private fun wipeVendingData() {
        try {
            requireContext().packageManager.clearApplicationUserData(
                VENDING_PACKAGE, null)
        } catch (_: Exception) {}
    }

    /**
     * Force-stops the GMS/Vending package family immediately (no data loss, safe
     * to run without confirmation), then prompts before wiping Play Store app data,
     * since that's destructive (signed-in state, download queue, etc.) and the
     * fingerprint/config change itself doesn't strictly require it.
     */
    private fun killGmsWithConfirmation(onDone: () -> Unit) {
        stopGmsPackages()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_wipe_playstore_title)
            .setMessage(R.string.pif_wipe_playstore_message)
            .setPositiveButton(R.string.pif_wipe_confirm) { _, _ ->
                wipeVendingData()
                onDone()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onDone()
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val TAG = "PlayIntegrityFix"
        internal const val PIF_CONFIG_KEY = "spoof_pif_config"
        private const val PIF_CONFIG_NAME = "pif.json"
        private const val VENDING_PACKAGE           = "com.android.vending"
        private const val DROIDGUARD_PACKAGE        = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE               = "com.google.android.gms"
        private const val GMS_PERSISTENT_PACKAGE    = "com.google.android.gms.persistent"
        private const val RKPD_PACKAGE              = "com.google.android.rkpdapp"
        private const val GSF_PACKAGE               = "com.google.android.gsf"
        private const val CONTACT_KEYS_PACKAGE      = "com.google.android.contactkeys"
        private const val SAFETY_CORE_PACKAGE       = "com.google.android.safetycore"
        private const val VELVET_PACKAGE            = "com.google.android.googlequicksearchbox"
        private const val REFETCH_WINDOW_DAYS = 15L
        private const val PIF_ENABLED_KEY = "spoof_pif_enabled"
        internal const val LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch"
        private const val MATCH_DEVICE_PROP = "ro.evolution.device"

        private data class Flag(val name: String, val default: Boolean)

        // PIFork's advanced flags with the framework's defaults (PlayIntegritySpoofService).
        // spoofProvider is off there, where PIFork defaults it to on, because it would bypass
        // the keybox attestation done in the same call.
        private val FLAG_PREFS = mapOf(
            "pif_spoof_build" to Flag("spoofBuild", true),
            "pif_spoof_props" to Flag("spoofProps", true),
            "pif_spoof_provider" to Flag("spoofProvider", false),
            "pif_spoof_signature" to Flag("spoofSignature", false),
        )

        private fun isFlagOn(value: String?, default: Boolean): Boolean =
            if (value.isNullOrEmpty()) default else value == "1" || value.equals("true", true)

        /** spoofVendingSdk is a level: 0 off, 1 = SDK 32, N > 1 = SDK N. */
        private fun isVendingSdkOn(value: String?): Boolean =
            value.equals("true", true) || (value?.toIntOrNull() ?: 0) > 0

        // PIXEL_DEVICE_GENERATION removed — use PixelDeviceRepository.GENERATION_ORDER

        /**
         * Config for [profile]. Only the spoof flags (spoofVendingFinger,
         * spoofVendingSdk, ...) and log settings are carried over from [existing],
         * so picking or refreshing a fingerprint doesn't silently put them back to
         * the service's defaults; everything describing the old fingerprint is
         * dropped.
         */
        private fun buildProfileConfig(
            existing: String?,
            profile: PixelDeviceRepository.PixelProfile,
        ): JSONObject {
            val old = try { JSONObject(existing ?: "") } catch (_: Exception) { JSONObject() }
            val canaryMonth = profile.securityPatch.take(7) // YYYY-MM from YYYY-MM-DD
            return JSONObject().apply {
                old.keys().asSequence()
                    .filter { it.startsWith("spoof") || it == "verboseLogs" || it == "DEBUG" }
                    .forEach { put(it, old.get(it)) }
                put("MANUFACTURER", profile.brand.replaceFirstChar { it.uppercase() })
                put("BRAND", profile.brand)
                put("MODEL", profile.model)
                put("PRODUCT", profile.product)
                put("DEVICE", profile.device)
                put("FINGERPRINT", profile.fingerprint)
                put("SECURITY_PATCH", profile.securityPatch)
                put("DEVICE_INITIAL_SDK_INT", "32")
                if (profile.isCanary && canaryMonth.length == 7) put("_canary_month", canaryMonth)
                if (profile.isCanary) profile.releaseDate?.let { put("_canary_release_date", it) }
                put("manually_imported", false)
            }
        }

        /**
         * Writes [patch] to PATCH_KEY only when the existing value is empty or
         * a plain YYYY-MM-DD date. Per-package block content (lines containing
         * '[', '=', or 'system=no') is preserved unchanged so that TEE-SIM
         * style configs are not overwritten by canary auto-fetch.
         */
        private fun updatePatchDateIfSimple(
            resolver: android.content.ContentResolver,
            patch: String,
        ) {
            val existing = Settings.Secure.getString(resolver, TrickyStore.PATCH_KEY) ?: ""
            val isSimple = existing.isEmpty() ||
                existing.trim().matches(Regex("""\d{4}-\d{2}-\d{2}"""))
            if (isSimple) {
                Settings.Secure.putString(resolver, TrickyStore.PATCH_KEY, patch)
            }
        }

        /**
         * Given a canary month string (YYYY-MM), estimates the expiry date as
         * ~6 weeks from the 1st of that month (or from [releaseDate] when known)
         * and returns a human-readable string: "expires YYYY-MM-DD" or
         * "expired YYYY-MM-DD" if past.
         */
        private fun getCanaryExpiryString(canaryMonth: String, releaseDate: String? = null): String? {
            val daysLeft = PixelDeviceRepository.getDaysUntilExpiry(canaryMonth, releaseDate) ?: return null
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val expiry = java.util.Date(System.currentTimeMillis() + daysLeft * 24 * 60 * 60 * 1000)
            val expiryStr = sdf.format(expiry)
            return if (daysLeft < 0) "expired $expiryStr" else "expires $expiryStr"
        }

        /**
         * Reads the config from a JSON string (stored in Settings.Secure).
         * Also handles legacy prop-format strings in case an old value is present.
         */
        private fun readConfigData(content: String): Map<String, String> {
            return try {
                val result = mutableMapOf<String, String>()
                val trimmed = content.trim()
                if (trimmed.startsWith("{")) {
                    val json = JSONObject(trimmed)
                    json.keys().forEach { key -> result[key] = json.optString(key, "") }
                } else {
                    trimmed.lines().forEach { line ->
                        val l = line.trim()
                        if (l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("//")) {
                            val eq = l.indexOf('=')
                            if (eq > 0) result[l.substring(0, eq).trim()] = l.substring(eq + 1).trim()
                        }
                    }
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read config", e)
                emptyMap()
            }
        }

        /**
         * Normalises an imported PIF payload (JSON or prop-format) to a JSON string
         * suitable for storage in Settings.Secure.
         */
        private fun normalizePifPayload(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            if (trimmed.startsWith("{")) return trimmed
            val json = JSONObject()
            trimmed.lines().forEach { line ->
                val stripped = line.trim()
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//")) return@forEach
                val eq = stripped.indexOf('=')
                if (eq > 0) {
                    val key = stripped.substring(0, eq).trim()
                    val value = stripped.substring(eq + 1).trim().substringBefore('#').trim()
                    if (key.isNotEmpty()) json.put(key, value)
                }
            }
            return json.toString(2)
        }
    }
}
