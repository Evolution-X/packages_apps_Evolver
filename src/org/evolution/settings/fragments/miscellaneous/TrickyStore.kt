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
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TrickyStore : SettingsPreferenceFragment() {

    private enum class RevocationStatus { UNKNOWN, CHECKING, VALID, REVOKED, SUSPENDED, SOFT_BANNED }

    private var currentRevocationStatus: RevocationStatus = RevocationStatus.UNKNOWN

    // Prevents concurrent revocation checks (duplicate onResume after onCreate,
    // or rotation while a check is already in-flight).
    private var isCheckInProgress = false

    private val isTrickyStoreEnabled: Boolean
        get() = Settings.System.getInt(
            requireContext().contentResolver,
            TRICKYSTORE_ENABLED_KEY, 1
        ) != 0

    private val isOfficialBuild: Boolean
        get() = android.os.SystemProperties.get("ro.evolution.build.type", "") == "Official"

    // Guards against autoFetchIfNoKeybox() firing while the user is mid-import.
    private var isKeyboxPickerOpen = false
    private var softBannedSerialsCache: Set<String>? = null

    private val keyboxPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isKeyboxPickerOpen = false
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
                    saveLastFetchedTimestamp()
                    killGms()
                    toast(getString(R.string.ts_keybox_imported))
                    currentRevocationStatus = RevocationStatus.UNKNOWN
                    Settings.Secure.putString(
                        requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                    refreshStatus()
                    checkKeyboxRevocation()
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
            isKeyboxPickerOpen = true
            keyboxPicker.launch(intent)
            true
        }

        findPreference<Preference>("ts_delete_keybox")?.setOnPreferenceClickListener {
            showDeleteKeyboxDialog()
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

        findPreference<Preference>("ts_fetch_keybox")?.apply {
            if (isOfficialBuild) {
                setOnPreferenceClickListener {
                    fetchOfficialKeybox()
                    true
                }
            } else {
                isVisible = false
            }
        }

        findPreference<Preference>("ts_revocation_status")?.setOnPreferenceClickListener {
            checkKeyboxRevocation()
            true
        }

        currentRevocationStatus = when (
            Settings.Secure.getString(requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY)
        ) {
            "VALID"      -> RevocationStatus.VALID
            "REVOKED"    -> RevocationStatus.REVOKED
            "SUSPENDED"  -> RevocationStatus.SUSPENDED
            "SOFT_BANNED"-> RevocationStatus.SOFT_BANNED
            else         -> RevocationStatus.UNKNOWN
        }

        refreshStatus()

        // If a check was interrupted (e.g. by rotation), LAST_REVOCATION_CHECK_KEY was written
        // but LAST_REVOCATION_STATUS_KEY was not — force a re-check on next onResume by clearing
        // the timestamp so the 24h gate treats it as never checked.
        val lastChecked = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
        val lastStatus = Settings.Secure.getString(
            requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY)
        if (lastChecked > 0L && lastStatus.isNullOrEmpty()) {
            Settings.Secure.putLong(
                requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (isTrickyStoreEnabled) {
            val lastChecked = Settings.Secure.getLong(
                requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
            val overADay = System.currentTimeMillis() - lastChecked > 24 * 60 * 60 * 1000L
            if (overADay && !isCheckInProgress) {
                checkKeyboxRevocation()
            } else {
                autoFetchIfNoKeybox()
            }
        }
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
            if (targetCount > 0) getString(R.string.ts_target_apps_count, targetCount)
            else getString(R.string.ts_no_targets)

        val patchDate = Settings.Secure.getString(requireContext().contentResolver, PATCH_KEY)
        findPreference<Preference>("ts_security_patch")?.summary =
            if (!patchDate.isNullOrEmpty()) patchDate
            else getString(R.string.ts_no_patch)

        findPreference<Preference>("ts_verification_mode")?.summary = buildVerificationSummary()

        applyRevocationUi(currentRevocationStatus)

        updateFetchButtonState(keyboxExists)
    }

    private fun applyRevocationUi(status: RevocationStatus) {
        val pref = findPreference<Preference>("ts_revocation_status") ?: return
        if (!isAdded) return

        val (iconRes, summary) = when (status) {
            RevocationStatus.VALID -> Pair(
                R.drawable.ic_ts_status_valid,
                getString(R.string.ts_revocation_valid)
            )
            RevocationStatus.REVOKED -> Pair(
                R.drawable.ic_ts_status_revoked,
                getString(R.string.ts_revocation_revoked, "")
            )
            RevocationStatus.SUSPENDED -> Pair(
                R.drawable.ic_ts_status_suspended,
                getString(R.string.ts_revocation_suspended, "")
            )
            RevocationStatus.SOFT_BANNED -> Pair(
                R.drawable.ic_ts_status_revoked,
                getString(R.string.ts_revocation_soft_banned)
            )
            RevocationStatus.CHECKING -> Pair(
                R.drawable.ic_ts_status_unknown,
                getString(R.string.ts_revocation_checking)
            )
            RevocationStatus.UNKNOWN -> Pair(
                R.drawable.ic_ts_status_unknown,
                if (!Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY).isNullOrEmpty())
                    getString(R.string.ts_revocation_not_yet_checked)
                else
                    getString(R.string.ts_revocation_no_keybox)
            )
        }

        pref.setIcon(iconRes)
        val lastChecked = getLastRevocationCheckedFormatted()
        pref.summary = if (status == RevocationStatus.CHECKING || lastChecked == null)
            summary
        else
            "$summary\n${getString(R.string.ts_revocation_last_checked, lastChecked)}"
    }

    private fun updateFetchButtonState(keyboxExists: Boolean) {
        val fetchPref = findPreference<Preference>("ts_fetch_keybox") ?: return
        if (!isOfficialBuild) return

        val isValid = currentRevocationStatus == RevocationStatus.VALID

        if (isValid) {
            fetchPref.isEnabled = false
            fetchPref.summary = getString(R.string.ts_fetch_keybox_blocked)
        } else {
            fetchPref.isEnabled = true
            val timestamp = getLastFetchedFormatted()
            fetchPref.summary = if (timestamp != null && keyboxExists)
                getString(R.string.ts_fetch_keybox_last_fetched, timestamp)
            else
                getString(R.string.ts_fetch_keybox_summary)
        }
    }

    private fun isNoValidCooldownActive(): Boolean {
        val last = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY, 0L)
        return last > 0L && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L
    }

    private fun markNoValidKeyboxFound() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY,
            System.currentTimeMillis()
        )
    }

    private fun clearNoValidKeyboxCooldown() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY, 0L)
    }

    private fun checkKeyboxRevocation() {
        if (isCheckInProgress) return
        val raw = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        if (raw.isNullOrEmpty()) {
            currentRevocationStatus = RevocationStatus.UNKNOWN
            applyRevocationUi(RevocationStatus.UNKNOWN)
            updateFetchButtonState(keyboxExists = false)
            autoFetchIfNoKeybox()
            return
        }

        isCheckInProgress = true
        currentRevocationStatus = RevocationStatus.CHECKING
        applyRevocationUi(RevocationStatus.CHECKING)
        updateFetchButtonState(keyboxExists = true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { performRevocationCheck(raw) }
            }
            isCheckInProgress = false
            if (!isAdded) return@launch
            Settings.Secure.putLong(
                requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY,
                System.currentTimeMillis()
            )
            result.fold(
                onSuccess = { status ->
                    currentRevocationStatus = status
                    if (status != RevocationStatus.UNKNOWN) {
                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            LAST_REVOCATION_STATUS_KEY,
                            status.name
                        )
                    }
                    applyRevocationUi(status)
                    updateFetchButtonState(keyboxExists = true)
                    if (isOfficialBuild && status == RevocationStatus.REVOKED) {
                        Settings.Secure.putString(
                            requireContext().contentResolver, KEYBOX_KEY, "")
                        Settings.Secure.putLong(
                            requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                        currentRevocationStatus = RevocationStatus.UNKNOWN
                        refreshStatus()
                        toast(getString(R.string.ts_fetch_keybox_revoked_refetch))
                        if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
                    } else if (isOfficialBuild && status == RevocationStatus.SOFT_BANNED) {
                        toast(getString(R.string.ts_fetch_keybox_soft_banned_refetch))
                        if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
                    }
                },
                onFailure = { e ->
                    val pref = findPreference<Preference>("ts_revocation_status") ?: return@fold
                    pref.setIcon(when (currentRevocationStatus) {
                        RevocationStatus.VALID                        -> R.drawable.ic_ts_status_valid
                        RevocationStatus.REVOKED, RevocationStatus.SOFT_BANNED -> R.drawable.ic_ts_status_revoked
                        RevocationStatus.SUSPENDED                    -> R.drawable.ic_ts_status_suspended
                        else                                          -> R.drawable.ic_ts_status_unknown
                    })
                    pref.summary = getString(R.string.ts_revocation_error, e.message)
                }
            )
        }
    }

    private fun performRevocationCheck(raw: String): RevocationStatus {
        val xml = decodeKeyboxForRevocation(raw)
            ?: return RevocationStatus.UNKNOWN

        val serials = extractCertSerials(xml)
        if (serials.isEmpty()) return RevocationStatus.UNKNOWN

        val json = fetchRevocationJson()
            ?: return RevocationStatus.UNKNOWN   // network error — treat as unknown, not invalid

        val entries = json.optJSONObject("entries")
        if (entries != null) {
            for (serial in serials) {
                val entry = entries.optJSONObject(serial) ?: continue
                val status = entry.optString("status", "").uppercase()
                when (status) {
                    "REVOKED" -> return RevocationStatus.REVOKED
                    "SUSPENDED" -> return RevocationStatus.SUSPENDED
                }
            }
        }

        if (isKeyboxSoftBanned(serials)) return RevocationStatus.SOFT_BANNED

        return RevocationStatus.VALID
    }

    private fun decodeKeyboxForRevocation(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.startsWith("<")) return trimmed
        return try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            val asXml = String(decoded, Charsets.UTF_8).trim()
            if (asXml.startsWith("<")) asXml else null
        } catch (_: Exception) { null }
    }

    private fun extractCertSerials(xml: String): List<String> {
        val serials = mutableListOf<String>()
        val factory = CertificateFactory.getInstance("X.509")
        val matcher = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----"
        ).matcher(xml)
        while (matcher.find()) {
            try {
                val der = Base64.decode(
                    matcher.group(1)!!.replace("\\s".toRegex(), ""), Base64.DEFAULT)
                val cert = factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                serials.add(cert.serialNumber.toString(16).uppercase())
            } catch (_: Exception) {}
        }
        return serials
    }

    private fun fetchRevocationJson(): JSONObject? = try {
        val conn = URL(REVOCATION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() })
        else null
    } catch (_: Exception) { null }

    private fun isKeyboxSoftBanned(serials: List<String>): Boolean {
        val cached = softBannedSerialsCache
        if (cached != null) return serials.any { it in cached }

        val files = fetchSoftBannedFileList() ?: return false
        val allBanned = mutableSetOf<String>()
        for (filename in files) {
            val xml = fetchRawKeybox("$SOFTBANNED_RAW_BASE_URL$filename") ?: continue
            allBanned.addAll(extractCertSerials(xml))
        }
        softBannedSerialsCache = allBanned
        return serials.any { it in allBanned }
    }

    private fun fetchSoftBannedFileList(): List<String>? = try {
        val conn = URL(SOFTBANNED_API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val response = org.json.JSONArray(
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            )
            (0 until response.length()).mapNotNull { i ->
                response.optJSONObject(i)
                    ?.optString("name")
                    ?.takeIf { it.endsWith(".xml") }
            }
        } else null
    } catch (_: Exception) { null }

    private fun fetchRawKeybox(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == HttpURLConnection.HTTP_OK)
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        else null
    } catch (_: Exception) { null }

    private fun autoFetchIfNoKeybox() {
        if (!isOfficialBuild) return
        if (!isTrickyStoreEnabled) return
        if (isKeyboxPickerOpen) return
        if (isCheckInProgress) return
        if (isNoValidCooldownActive()) return
        val existing = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        if (!existing.isNullOrEmpty()) {
            // Only auto-fetch for UNKNOWN here; REVOKED and SOFT_BANNED are handled
            // inside checkKeyboxRevocation() after a fresh check result comes in.
            // Triggering a fetch based on stale persisted status risks a redundant
            // fetch race if a check is about to fire anyway.
            if (currentRevocationStatus == RevocationStatus.UNKNOWN) fetchOfficialKeybox(silent = true)
            return
        }
        fetchOfficialKeybox(silent = true)
    }

    private fun fetchOfficialKeybox(silent: Boolean = false) {
        val pref = findPreference<Preference>("ts_fetch_keybox")
        if (!silent) {
            pref?.isEnabled = false
            pref?.summary = getString(R.string.ts_fetch_keybox_fetching)
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(OFFICIAL_KEYBOX_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                        "HTTP ${conn.responseCode}"
                    }
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
            if (!silent) {
                updateFetchButtonState(keyboxExists = true)
            }
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { xml ->
                    if (!isTrickyStoreEnabled) return@fold
                    try {
                        val existing = Settings.Secure.getString(
                            requireContext().contentResolver, KEYBOX_KEY)
                        val existingXml = if (!existing.isNullOrEmpty())
                            decodeKeyboxForRevocation(existing) else null
                        if (existingXml != null && existingXml.trim() == xml.trim()) {
                            if (!silent) toast(getString(R.string.ts_fetch_keybox_same_file))
                            else toast(getString(R.string.ts_fetch_keybox_no_valid_found))
                            markNoValidKeyboxFound()
                            return@fold
                        }
                        val fetchedSerials = extractCertSerials(xml)
                        val revocationJson = withContext(Dispatchers.IO) { fetchRevocationJson() }
                        val entries = revocationJson?.optJSONObject("entries")
                        if (entries != null && fetchedSerials.isNotEmpty()) {
                            val fetchedRevoked = fetchedSerials.any { serial ->
                                entries.optJSONObject(serial)
                                    ?.optString("status", "")
                                    ?.uppercase() == "REVOKED"
                            }
                            if (fetchedRevoked) {
                                toast(getString(R.string.ts_fetch_keybox_no_valid_found))
                                markNoValidKeyboxFound()
                                return@fold
                            }
                        }
                        clearNoValidKeyboxCooldown()
                        softBannedSerialsCache = null
                        val encoded = Base64.encodeToString(
                            xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        Settings.Secure.putString(
                            requireContext().contentResolver, KEYBOX_KEY, encoded)
                        saveLastFetchedTimestamp()
                        killGms()
                        toast(getString(if (silent) R.string.ts_fetch_keybox_auto_replaced else R.string.ts_fetch_keybox_success))
                        currentRevocationStatus = RevocationStatus.UNKNOWN
                        refreshStatus()
                        checkKeyboxRevocation()
                    } catch (e: Exception) {
                        if (!silent) toast(getString(R.string.ts_fetch_keybox_failed, e.message ?: ""))
                    }
                },
                onFailure = { e ->
                    if (!silent) toast(getString(R.string.ts_fetch_keybox_failed, e.message ?: ""))
                    else {
                        toast(getString(R.string.ts_fetch_keybox_no_valid_found))
                        markNoValidKeyboxFound()
                    }
                }
            )
        }
    }

    private fun saveLastFetchedTimestamp() {
        Settings.Secure.putLong(
            requireContext().contentResolver,
            LAST_FETCHED_KEY,
            System.currentTimeMillis()
        )
    }

    private fun getLastFetchedFormatted(): String? {
        val millis = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
        if (millis == 0L) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun getLastRevocationCheckedFormatted(): String? {
        val millis = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
        if (millis == 0L) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
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
            if (auto > 0) add(getString(R.string.ts_verification_auto_count, auto))
            if (cert > 0) add(getString(R.string.ts_verification_cert_count, cert))
            if (leaf > 0) add(getString(R.string.ts_verification_leaf_count, leaf))
        }.joinToString(" · ")
    }

    private fun showDeleteKeyboxDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_delete_keybox_title)
            .setMessage(R.string.ts_delete_keybox_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                try {
                    Settings.Secure.putString(
                        requireContext().contentResolver, KEYBOX_KEY, "")
                    Settings.Secure.putLong(
                        requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                    toast(getString(R.string.ts_keybox_deleted))
                    currentRevocationStatus = RevocationStatus.UNKNOWN
                    Settings.Secure.putString(
                        requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                    clearNoValidKeyboxCooldown()
                    Settings.Secure.putLong(
                        requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPatchDateDialog() {
        val current = Settings.Secure.getString(requireContext().contentResolver, PATCH_KEY) ?: ""
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            hint = getString(R.string.ts_patch_date_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_security_patch)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_delete) { _, _ ->
                Settings.Secure.putString(
                    requireContext().contentResolver, PATCH_KEY, "")
                refreshStatus()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && !value.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
                    toast(getString(R.string.ts_invalid_patch_date))
                    return@setOnClickListener
                }
                Settings.Secure.putString(
                    requireContext().contentResolver, PATCH_KEY, value)
                refreshStatus()
                dialog.dismiss()
            }
            if (current.isEmpty()) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            }
        }

        dialog.show()
    }

    private fun killGms() {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(VENDING_PACKAGE)
            am.forceStopPackage(DROIDGUARD_PACKAGE)
            am.forceStopPackage(GMS_PACKAGE)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val KEYBOX_KEY            = "spoof_trickystore_keybox"
        private const val TARGET_KEY            = TrickyStoreAppSettings.TARGET_KEY
        internal const val PATCH_KEY            = "spoof_trickystore_patch"
        private const val LAST_FETCHED_KEY          = "spoof_trickystore_last_fetched"
        private const val LAST_REVOCATION_CHECK_KEY = "spoof_trickystore_last_revocation_check"
        private const val LAST_NO_VALID_KEY         = "spoof_trickystore_last_no_valid"
        private const val LAST_REVOCATION_STATUS_KEY = "spoof_trickystore_last_revocation_status"
        private const val VENDING_PACKAGE       = "com.android.vending"
        private const val DROIDGUARD_PACKAGE    = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE           = "com.google.android.gms"
        private const val REVOCATION_URL        = "https://android.googleapis.com/attestation/status"
        private const val OFFICIAL_KEYBOX_URL   =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/keybox.xml"
        private const val TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled"
        private const val SOFTBANNED_API_URL    =
            "https://git.evolution-x.org/api/v1/repos/EvoX/keybox/contents/softbanned"
        private const val SOFTBANNED_RAW_BASE_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/softbanned/"
    }
}
