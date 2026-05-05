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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeConfigData: Map<String, String> = emptyMap()
    private var autoFetchDone = false

    private enum class PifChannel { LATEST_RELEASE, CANARY }

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
                            Settings.Secure.putString(requireContext().contentResolver, TrickyStore.PATCH_KEY, patch)
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
            showChannelSelectionDialog()
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

        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        autoFetchDone = false
        autoFetchIfStale()
    }

    private fun autoFetchIfStale() {
        val content = Settings.Secure.getString(
            requireContext().contentResolver, PIF_CONFIG_KEY
        )
        val isManuallyImported = try {
            !content.isNullOrEmpty() && JSONObject(content).optBoolean("manually_imported", false)
        } catch (_: Exception) { false }

        val shouldFetch = if (isManuallyImported) {
            false
        } else if (content.isNullOrEmpty()) {
            true
        } else {
            val ageDays = try {
                val patch = JSONObject(content).optString("SECURITY_PATCH", "")
                getPatchAgeDays(patch)
            } catch (_: Exception) { null }
            ageDays != null && ageDays > AUTO_FETCH_STALE_DAYS
        }
        if (shouldFetch && !autoFetchDone) {
            autoFetchDone = true
            autoFetchLatestRelease()
        }
    }

    private fun autoFetchLatestRelease() {
        scope.launch {
            try {
                // Try evo hosted pif.json first
                val evoResult = withContext(Dispatchers.IO) { fetchFallbackPif() }
                val result = if (evoResult is PifFetchResult.Success) {
                    evoResult
                } else {
                    // Fall back to pixel canary scraper
                    val (devices, apiKey) = withContext(Dispatchers.IO) { fetchAvailableCanaryDevices() }
                    if (devices.isNotEmpty() && !apiKey.isNullOrEmpty()) {
                        val preferred = devices.firstOrNull { it.device == "komodo" }
                            ?: devices.firstOrNull { it.device == "blazer" }
                            ?: devices.first()
                        withContext(Dispatchers.IO) { buildCanaryPifFromDevice(preferred, apiKey) }
                    } else {
                        PifFetchResult.Error("No config available")
                    }
                }
                if (result is PifFetchResult.Success) {
                    val fp = result.pifData.optString("FINGERPRINT", "")
                    if (!isValidFingerprint(fp)) return@launch
                    val toSave = JSONObject(result.pifData.toString()).apply {
                        put("manually_imported", false)
                    }
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        PIF_CONFIG_KEY,
                        toSave.toString(2)
                    )
                    result.pifData.optString("SECURITY_PATCH")
                        .takeIf { it.isNotEmpty() }?.let {
                            Settings.Secure.putString(
                                requireContext().contentResolver, TrickyStore.PATCH_KEY, it
                            )
                        }
                    killGms()
                    refreshStatus()
                }
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
            activePref?.title = PIF_CONFIG_NAME
            activePref?.summary = if (model.isNotEmpty()) {
                "MODEL: $model$ageStr" +
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

        data.keys.filter { it !in displayOrder && !it.startsWith("spoof") && it != "DEBUG" && it != "verboseLogs" && it != "manually_imported" }
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
                    toast(getString(R.string.pif_deleted, PIF_CONFIG_NAME))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelSelectionDialog() {
        val channels = arrayOf(
            getString(R.string.pif_channel_latest_release),
            getString(R.string.pif_channel_canary_release)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_select_channel)
            .setItems(channels) { _, which ->
                val channel = if (which == 0) PifChannel.LATEST_RELEASE else PifChannel.CANARY
                fetchDevicesForChannel(channel)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchDevicesForChannel(channel: PifChannel) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta") ?: return
        fetchPref.summary = getString(R.string.pif_fetching)
        fetchPref.isEnabled = false

        scope.launch {
            try {
                val (devices, apiKey) = withContext(Dispatchers.IO) {
                    when (channel) {
                        PifChannel.LATEST_RELEASE -> fetchAvailableDevices() to null
                        PifChannel.CANARY -> fetchAvailableCanaryDevices()
                    }
                }

                if (devices.isEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_devices_found)))
                    return@launch
                }

                if (channel == PifChannel.CANARY && apiKey.isNullOrEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_api_key)))
                    return@launch
                }

                val modelNames = devices.map { it.model }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which ->
                        generateAndSavePif(devices[which], channel, apiKey)
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

    private fun generateAndSavePif(device: PifDevice, channel: PifChannel, apiKey: String?) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta")
        fetchPref?.summary = getString(R.string.pif_generating)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (channel) {
                        PifChannel.LATEST_RELEASE -> buildPifFromDevice(device)
                        PifChannel.CANARY -> buildCanaryPifFromDevice(device, apiKey ?: "")
                    }
                }
                when (result) {
                    is PifFetchResult.Success -> {
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
                            Settings.Secure.putString(requireContext().contentResolver, TrickyStore.PATCH_KEY, it)
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

    /**
     * Force-stops Vending, DroidGuard, and GMS so the new fingerprint
     * takes effect immediately without waiting for a natural restart.
     */
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
        private const val TAG = "PlayIntegrityFix"
        private const val PIF_CONFIG_KEY = "spoof_pif_config"
        private const val PIF_CONFIG_NAME = "pif.json"
        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        private const val FALLBACK_PIF_URL = "https://raw.githubusercontent.com/Evolution-X/.github/refs/heads/main/profile/pif.json"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val AUTO_FETCH_STALE_DAYS = 30L

        private val DEVICE_MODEL_MAP = mapOf(
            "oriole" to "Pixel 6",
            "raven" to "Pixel 6 Pro",
            "bluejay" to "Pixel 6a",
            "panther" to "Pixel 7",
            "cheetah" to "Pixel 7 Pro",
            "lynx" to "Pixel 7a",
            "shiba" to "Pixel 8",
            "tangorpro" to "Pixel Tablet",
            "felix" to "Pixel Fold",
            "husky" to "Pixel 8 Pro",
            "akita" to "Pixel 8a",
            "tokay" to "Pixel 9",
            "caiman" to "Pixel 9 Pro",
            "komodo" to "Pixel 9 Pro XL",
            "comet" to "Pixel 9 Pro Fold",
            "tegu" to "Pixel 9a",
            "frankel" to "Pixel 10",
            "blazer" to "Pixel 10 Pro",
            "mustang" to "Pixel 10 Pro XL",
            "rango" to "Pixel 10 Pro Fold",
            "stallion" to "Pixel 10a",
        )

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

        private fun fetchPartialUrl(url: String, maxBytes: Int): String {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.getInputStream().use { input ->
                val buf = ByteArray(512)
                val sb = StringBuilder()
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sb.append(String(buf, 0, read, StandardCharsets.ISO_8859_1))
                    total += read
                }
                return sb.toString()
            }
        }

        data class PifDevice(
            val product: String,
            val device: String,
            val model: String,
            val otaUrl: String,
        )

        private sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        /**
         * Phase 1: Scrape Google developer site to find all available beta devices.
         */
        private fun fetchAvailableDevices(): List<PifDevice> {
            val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
            val knownVersions = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                .findAll(versionsHtml).map { it.groupValues[1].toInt() }.toSet().sortedDescending()

            val maxVersion = knownVersions.firstOrNull() ?: return emptyList()
            val versions = listOf(maxVersion + 1) + knownVersions

            for (version in versions) {
                try {
                    val downloadUrl = "$GOOGLE_URL/about/versions/$version/download-ota"
                    val otaHtml = URL(downloadUrl).readText(StandardCharsets.UTF_8)
                    val otaList = Regex("""href="(https://dl\.google\.com/[^"]*ota/([^/"]+_beta)[^"]*?)"""")
                        .findAll(otaHtml).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    if (otaList.isEmpty()) continue

                    val devices = mutableListOf<PifDevice>()
                    val seen = mutableSetOf<String>()
                    for ((otaUrl, product) in otaList) {
                        val device = product.replace("_beta", "")
                        if (device in seen) continue
                        seen.add(device)
                        val model = DEVICE_MODEL_MAP[device] ?: device
                        devices.add(PifDevice(product, device, model, otaUrl))
                    }
                    if (devices.isNotEmpty()) return devices
                } catch (_: Exception) { continue }
            }
            return emptyList()
        }

        /**
         * Phase 2: Fetch OTA metadata for a specific device and build pif JSON.
         */
        private fun buildPifFromDevice(pifDevice: PifDevice): PifFetchResult {
            try {
                val partial = fetchPartialUrl(pifDevice.otaUrl, 4096)

                val fingerprint = Regex("""post-build=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract fingerprint")
                val securityPatch = Regex("""security-patch-level=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract security patch")

                val fpParts = fingerprint.split("/")
                val release = fpParts.getOrNull(2)?.substringAfter(":", "") ?: ""
                val buildId = fpParts.getOrNull(3) ?: ""

                val pifJson = JSONObject().apply {
                    put("MODEL", pifDevice.model)
                    put("MANUFACTURER", "Google")
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "34")
                }
                return PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                return PifFetchResult.Error("Failed: ${e.message}")
            }
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
                                .ifEmpty { DEVICE_MODEL_MAP[device] ?: device }
                            devices.add(
                                PifDevice(
                                    product = "${device}_beta",
                                    device = device,
                                    model = model,
                                    otaUrl = "",
                                )
                            )
                        }

                        if (devices.isEmpty()) continue

                        val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                        val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value

                        return devices to apiKey
                    } catch (_: Exception) { continue }
                }

                return emptyList<PifDevice>() to null
            } catch (e: Exception) {
                Log.e(TAG, "Canary device fetch failed", e)
                return emptyList<PifDevice>() to null
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

                val pifJson = JSONObject().apply {
                    put("MODEL", pifDevice.model)
                    put("MANUFACTURER", "Google")
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "34")
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
