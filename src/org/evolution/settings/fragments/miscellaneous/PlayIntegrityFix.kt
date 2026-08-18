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
import java.net.URL
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
            isChecked = activeConfigData["spoofVendingSdk"].let { it == "1" || it == "true" }
            setOnPreferenceChangeListener { _, newValue ->
                updateConfigValue("spoofVendingSdk", if (newValue as Boolean) "1" else "0")
                true
            }
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

                val localPatch = try {
                    if (!content.isNullOrEmpty()) JSONObject(content).optString("SECURITY_PATCH", "") else ""
                } catch (_: Exception) { "" }

                val localPatchDate = parsePatchDate(localPatch)
                val serverPatchDate = parsePatchDate(matched.securityPatch)

                // Only replace once genuinely newer, or once the current one has expired.
                val shouldReplace = (serverPatchDate != null &&
                    (localPatchDate == null || serverPatchDate.after(localPatchDate))) ||
                    (daysLeft != null && daysLeft <= 0)

                if (!shouldReplace) return@launch

                if (!PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) return@launch

                val canaryMonth = matched.securityPatch.take(7) // YYYY-MM from YYYY-MM-DD
                val toSave = JSONObject().apply {
                    put("MANUFACTURER", matched.brand.replaceFirstChar { it.uppercase() })
                    put("BRAND", matched.brand)
                    put("MODEL", matched.model)
                    put("PRODUCT", matched.product)
                    put("DEVICE", matched.device)
                    put("FINGERPRINT", matched.fingerprint)
                    put("SECURITY_PATCH", matched.securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                    if (canaryMonth.length == 7) put("_canary_month", canaryMonth)
                    matched.releaseDate?.let { put("_canary_release_date", it) }
                    put("manually_imported", false)
                }
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

        findPreference<ListPreference>("pif_spoof_vending_finger")?.value =
            activeConfigData["spoofVendingFinger"] ?: "0"
        findPreference<SwitchPreferenceCompat>("pif_spoof_vending_sdk")?.isChecked =
            activeConfigData["spoofVendingSdk"].let { it == "1" || it == "true" }
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
                    updateConfigValue(key, v)
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
                        updateConfigValue(key, v)
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
                val sortedProfiles = profiles.sortedWith(
                    compareByDescending<PixelDeviceRepository.PixelProfile> {
                        it.device == currentDevice
                    }.thenByDescending {
                        PixelDeviceRepository.GENERATION_ORDER.indexOf(it.codename)
                            .let { idx -> if (idx < 0) -1 else PixelDeviceRepository.GENERATION_ORDER.size - idx }
                    }
                )
                val modelNames = sortedProfiles.map { it.model }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which ->
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
                val canaryMonth = profile.securityPatch.take(7) // YYYY-MM from YYYY-MM-DD
                val pifJson = JSONObject().apply {
                    put("MANUFACTURER", profile.brand.replaceFirstChar { it.uppercase() })
                    put("BRAND", profile.brand)
                    put("MODEL", profile.model)
                    put("PRODUCT", profile.product)
                    put("DEVICE", profile.device)
                    put("FINGERPRINT", profile.fingerprint)
                    put("SECURITY_PATCH", profile.securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                    if (canaryMonth.length == 7) put("_canary_month", canaryMonth)
                    profile.releaseDate?.let { put("_canary_release_date", it) }
                    put("manually_imported", false)
                }
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
    private fun updateConfigValue(key: String, value: String) {
        try {
            val existing = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
            val json = try { JSONObject(existing ?: "") } catch (e: Exception) { JSONObject() }
            json.put(key, value)
            Settings.Secure.putString(
                requireContext().contentResolver,
                PIF_CONFIG_KEY,
                json.toString(2)
            )
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
        private const val PIF_CONFIG_KEY = "spoof_pif_config"
        private const val PIF_CONFIG_NAME = "pif.json"
        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        private const val FALLBACK_PIF_URL = "https://raw.githubusercontent.com/Evolution-X/.github/refs/heads/main/profile/pif.json"
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
        private const val LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch"
        private const val MATCH_DEVICE_PROP = "ro.evolution.device"

        // PIXEL_DEVICE_GENERATION removed — use PixelDeviceRepository.GENERATION_ORDER

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

        private fun parsePatchDate(patch: String): java.util.Date? = try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(patch)
        } catch (_: Exception) { null }

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
