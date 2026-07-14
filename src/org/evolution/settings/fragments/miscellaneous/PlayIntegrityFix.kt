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
                    if (fp.isNotEmpty() && !isValidFingerprint(fp)) {
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
                    killGms()
                    toast(getString(R.string.pif_imported_as, PIF_CONFIG_NAME))
                    refreshStatus()
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

        markAutoFetchDone()
        scope.launch {
            try {
                val serverResult = withContext(Dispatchers.IO) { fetchFallbackPif() }
                if (serverResult !is PifFetchResult.Success) return@launch

                val localPatch = try {
                    if (!content.isNullOrEmpty()) JSONObject(content).optString("SECURITY_PATCH", "") else ""
                } catch (_: Exception) { "" }
                val serverPatch = serverResult.pifData.optString("SECURITY_PATCH", "")

                val localPatchDate = parsePatchDate(localPatch)
                val serverPatchDate = parsePatchDate(serverPatch)

                val resultToSave: PifFetchResult.Success = when {
                    // Server has a newer patch than local — take server
                    serverPatchDate != null && (localPatchDate == null || serverPatchDate.after(localPatchDate)) -> {
                        serverResult
                    }
                    // Server is not newer — check if server patch is stale (>21 days)
                    (getPatchAgeDays(serverPatch) ?: 0L) > AUTO_FETCH_STALE_DAYS -> {
                        val (devices, apiKey) = withContext(Dispatchers.IO) { fetchAvailableCanaryDevices() }
                        if (devices.isNotEmpty() && !apiKey.isNullOrEmpty()) {
                            val preferred = getMatchingPixelDevice(devices)
                                ?: devices.random()
                            val betaResult = withContext(Dispatchers.IO) { buildCanaryPifFromDevice(preferred, apiKey) }
                            if (betaResult is PifFetchResult.Success) betaResult else return@launch
                        } else {
                            return@launch
                        }
                    }
                    // Server is fresh and not newer than local — nothing to do
                    else -> return@launch
                }

                val fp = resultToSave.pifData.optString("FINGERPRINT", "")
                if (!isValidFingerprint(fp)) return@launch

                val toSave = JSONObject(resultToSave.pifData.toString()).apply {
                    put("manually_imported", false)
                }
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    PIF_CONFIG_KEY,
                    toSave.toString(2)
                )
                resultToSave.pifData.optString("SECURITY_PATCH")
                    .takeIf { it.isNotEmpty() }?.let {
                        updatePatchDateIfSimple(requireContext().contentResolver, it)
                    }
                killGms()
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
            val ageDays = getPatchAgeDays(activeConfigData["SECURITY_PATCH"] ?: "")
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
                val (devices, apiKey) = withContext(Dispatchers.IO) {
                    fetchAvailableCanaryDevices()
                }

                if (devices.isEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_devices_found)))
                    return@launch
                }

                if (apiKey.isNullOrEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_api_key)))
                    return@launch
                }

                val sortedDevices = devices.sortedWith(
                    compareByDescending<PifDevice> {
                        it.device == android.os.SystemProperties.get(MATCH_DEVICE_PROP, "")
                    }.thenByDescending {
                        PIXEL_DEVICE_GENERATION[it.device] ?: 0
                    }
                )
                val modelNames = sortedDevices.map { it.model }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which ->
                        generateAndSavePif(sortedDevices[which], apiKey)
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

    private fun generateAndSavePif(device: PifDevice, apiKey: String) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta")
        fetchPref?.summary = getString(R.string.pif_generating)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    buildCanaryPifFromDevice(device, apiKey)
                }
                when (result) {
                    is PifFetchResult.Success -> {
                        if (!isPifEnabled) return@launch
                        // Validate fingerprint format before persisting
                        val fp = result.pifData.optString("FINGERPRINT", "")
                        if (!isValidFingerprint(fp)) {
                            toast(getString(R.string.pif_failed, getString(R.string.pif_invalid_fingerprint)))
                            return@launch
                        }
                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            PIF_CONFIG_KEY,
                            result.pifData.toString(2)
                        )
                        result.pifData.optString("SECURITY_PATCH").takeIf { it.isNotEmpty() }?.let {
                            updatePatchDateIfSimple(requireContext().contentResolver, it)
                        }
                        killGms()
                        toast(getString(R.string.pif_fetched_model, result.model))
                        refreshStatus()
                    }
                    is PifFetchResult.Error -> {
                        toast(getString(R.string.pif_failed, result.message))
                    }
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

    private fun killGms() {
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
            requireContext().packageManager.clearApplicationUserData(
                VENDING_PACKAGE, null)
        } catch (_: Exception) {}
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
        private const val AUTO_FETCH_STALE_DAYS = 21L
        private const val PIF_ENABLED_KEY = "spoof_pif_enabled"
        private const val LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch"
        private const val MATCH_DEVICE_PROP = "ro.evolution.device"

        private val PIXEL_DEVICE_GENERATION = mapOf(
            "rango"      to 10,  // Pixel 10 Pro Fold
            "mustang"    to 10,  // Pixel 10 Pro XL
            "blazer"     to 10,  // Pixel 10 Pro
            "frankel"    to 10,  // Pixel 10
            "comet"      to 9,   // Pixel 9 Pro Fold
            "komodo"     to 9,   // Pixel 9 Pro XL
            "caiman"     to 9,   // Pixel 9 Pro
            "tokay"      to 9,   // Pixel 9
            "tegu"       to 9,   // Pixel 9a
            "akita"      to 8,   // Pixel 8a
            "husky"      to 8,   // Pixel 8 Pro
            "shiba"      to 8,   // Pixel 8
        )

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
         * Validates that a fingerprint string matches the expected Android format:
         * brand/product/device:VERSION/ID/INCREMENTAL:TYPE/KEYS
         */
        private fun isValidFingerprint(fp: String): Boolean =
            Regex("""^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/[^:]+:[^/]+/[^:]+$""").matches(fp)

        /**
         * Returns the number of days elapsed since the given YYYY-MM-DD security patch date,
         * or null if the date cannot be parsed.
         */
        private fun getPatchAgeDays(patch: String): Long? = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val diff = System.currentTimeMillis() - (sdf.parse(patch)?.time ?: return null)
            diff / (1000L * 60 * 60 * 24)
        } catch (_: Exception) { null }

        /**
         * Given a canary month string (YYYY-MM), estimates the expiry date as
         * ~6 weeks from the 1st of that month and returns a human-readable
         * string: "expires YYYY-MM-DD" or "expired YYYY-MM-DD" if past.
         */
        private fun getCanaryExpiryString(canaryMonth: String, releaseDate: String? = null): String? = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            // Prefer the real release date (from the factory image's Last-Modified
            // header) when we have one; fall back to assuming the 1st of the canary
            // month only when it's unavailable, e.g. older saved configs.
            val base = sdf.parse(releaseDate ?: "$canaryMonth-01") ?: return null
            val expiry = java.util.Date(base.time + 42L * 24 * 60 * 60 * 1000)
            val expiryStr = sdf.format(expiry)
            if (expiry.before(java.util.Date())) "expired $expiryStr"
            else "expires $expiryStr"
        } catch (_: Exception) { null }

        /**
         * Returns the current device's codename if it appears in the filtered
         * Pixel 8+ device list, enabling autopif4-style --match behaviour on
         * actual Pixel hardware. Returns null on non-Pixel or unrecognized devices.
         */
        private fun getMatchingPixelDevice(devices: List<PifDevice>): PifDevice? {
            return try {
                val codename = android.os.SystemProperties.get(MATCH_DEVICE_PROP, "")
                // No longer requires the device to be in PIXEL_DEVICE_GENERATION — that
                // map is just a sort-order preference, and would otherwise prevent
                // matching on a current device that isn't in it yet.
                devices.firstOrNull { it.device == codename }
            } catch (_: Exception) { null }
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

        data class PifDevice(
            val product: String,
            val device: String,
            val model: String,
        )

        private sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        private fun fetchAvailableCanaryDevices(): Pair<List<PifDevice>, String?> {
            try {
                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val knownVersions = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll(versionsHtml).map { it.groupValues[1].toInt() }.toSet().sortedDescending()

                val maxVersion = knownVersions.firstOrNull() ?: return emptyList<PifDevice>() to null
                val versions = listOf(maxVersion + 1) + knownVersions

                val rowPattern = Regex(
                    """<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""",
                    RegexOption.DOT_MATCHES_ALL
                )

                for (version in versions) {
                    try {
                        val latestHtml = URL("$GOOGLE_URL/about/versions/$version")
                            .readText(StandardCharsets.UTF_8)
                        val qprPath = Regex("""href="(/about/versions/$version/qpr(\d+)/download-ota)"""")
                            .findAll(latestHtml)
                            .map { it.groupValues[2].toInt() to it.groupValues[1] }
                            .maxByOrNull { it.first }
                            ?.second ?: continue

                        val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)

                        val devices = mutableListOf<PifDevice>()
                        val seen = mutableSetOf<String>()
                        rowPattern.findAll(fiHtml).forEach { match ->
                            val device = match.groupValues[1]
                            if (device in seen) return@forEach
                            seen.add(device)
                            val model = match.groupValues[2].trim()
                                .ifEmpty {
                                    PixelDeviceRepository.DEVICE_MODEL_MAP[device] as? String
                                        ?: device
                                }
                            devices.add(
                                PifDevice(
                                    product = "${device}_beta",
                                    device = device,
                                    model = model,
                                )
                            )
                        }

                        if (devices.isEmpty()) continue

                        val apiKey = fetchFlashToolApiKey()

                        // Don't filter by PIXEL_DEVICE_GENERATION here — that map only
                        // covers devices known when this was last updated, and would
                        // otherwise silently hide a newer Pixel from the picker. Unknown
                        // devices just sort last in fetchDevicesForChannel() instead.
                        return devices to apiKey
                    } catch (_: Exception) { continue }
                }

                return emptyList<PifDevice>() to null
            } catch (e: Exception) {
                Log.e(TAG, "Canary device fetch failed", e)
                return emptyList<PifDevice>() to null
            }
        }

        /**
         * Extracts the Flash Tool API key from flash.android.com. Prefers the
         * targeted data-client-config attribute (matching upstream autopif4.sh),
         * falling back to a generic AIza-prefixed key scan if the page structure
         * changes and the targeted extraction comes up empty.
         */
        private fun fetchFlashToolApiKey(): String? {
            val flashHtml = try {
                URL(FLASH_URL).readText(StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return null
            }

            val targeted = Regex("""data-client-config="([^"]*)"""")
                .find(flashHtml)?.groupValues?.get(1)
                ?.split(";")?.getOrNull(1)
                ?.split("&")?.getOrNull(0)

            return targeted ?: Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value
        }

        /**
         * HEAD-requests the factory image to read its Last-Modified header,
         * giving a precise canary release date instead of assuming the 1st of
         * the canary month. Returns a yyyy-MM-dd string, or null if it can't
         * be determined.
         */
        private fun fetchLastModifiedDate(url: String): String? {
            return try {
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val lastModified = conn.getHeaderField("Last-Modified")
                conn.disconnect()
                lastModified?.let {
                    val parsed = java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                    ).parse(it)
                    parsed?.let { d -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d) }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun buildCanaryPifFromDevice(pifDevice: PifDevice, apiKey: String): PifFetchResult {
            try {
                if (apiKey.isEmpty()) return PifFetchResult.Error("Flash Tool API key unavailable")

                val buildsUrl = "$FLASH_API?product=${pifDevice.product}&key=$apiKey"
                val buildsConn = URL(buildsUrl).openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val buildsJson = buildsConn.getInputStream().use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }

                val root = JSONObject(buildsJson)
                val buildsArray = root.optJSONArray("flashstationBuild")
                    ?: return PifFetchResult.Error("No flashstationBuild array in Flash Tool response")

                var id: String? = null
                var incremental: String? = null
                var canaryId: String? = null
                var factoryImageUrl: String? = null

                for (i in buildsArray.length() - 1 downTo 0) {
                    val b = buildsArray.optJSONObject(i) ?: continue
                    val meta = b.optJSONObject("previewMetadata") ?: continue
                    if (!meta.optBoolean("canary")) continue

                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    if (rc.isEmpty() || bid.isEmpty()) continue

                    id = rc
                    incremental = bid
                    canaryId = meta.optString("id").takeIf { it.contains("canary-") }
                    factoryImageUrl = b.optString("factoryImageDownloadUrl").takeIf { it.isNotEmpty() }
                        ?: meta.optString("factoryImageDownloadUrl").takeIf { it.isNotEmpty() }
                    break
                }

                if (id == null || incremental == null) {
                    return PifFetchResult.Error("No canary build found for ${pifDevice.product}")
                }

                val fingerprint =
                    "google/${pifDevice.product}/${pifDevice.device}:CANARY/$id/$incremental:user/release-keys"

                val canaryMonth = canaryId?.let {
                    Regex("""canary-(\d{4})(\d{2})""").find(it)?.let { m ->
                        "${m.groupValues[1]}-${m.groupValues[2]}"
                    }
                } ?: return PifFetchResult.Error("Failed to derive canary month id")

                val securityPatch = try {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1)
                        ?: "$canaryMonth-05"
                } catch (e: Exception) {
                    Log.d(TAG, "Bulletin fetch failed, using estimated patch: ${e.message}")
                    "$canaryMonth-05"
                }

                val releaseDate = factoryImageUrl?.let { fetchLastModifiedDate(it) }

                val pifJson = JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("BRAND", "google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("DEVICE", pifDevice.device)
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                    put("_canary_month", canaryMonth)
                    releaseDate?.let { put("_canary_release_date", it) }
                    put("_comment_canary", "Canary Released: ${releaseDate ?: canaryMonth} | Estimated Expiry: ~6 weeks from release")
                }

                return PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                return PifFetchResult.Error("Failed: ${e.message}")
            }
        }

        /**
         * Fetches the Evolution X hosted pif.json as a fallback when the live
         * Google OTA scraper returns no devices (e.g. no network at first boot
         * or Google has not published a new beta build yet).
         */
        private fun fetchFallbackPif(): PifFetchResult {
            return try {
                val content = URL(FALLBACK_PIF_URL).readText(StandardCharsets.UTF_8)
                val json = JSONObject(content)
                val fp = json.optString("FINGERPRINT", "")
                if (fp.isEmpty() || !isValidFingerprint(fp)) {
                    PifFetchResult.Error("Invalid fingerprint in fallback pif.json")
                } else {
                    PifFetchResult.Success(
                        json.optString("MODEL", "Unknown"),
                        json
                    )
                }
            } catch (e: Exception) {
                PifFetchResult.Error("Fallback fetch failed: ${e.message ?: ""}")
            }
        }
    }
}
