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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TrickyStore : SettingsPreferenceFragment() {

    // ----- Revocation status enum -------------------------------------------

    private enum class RevocationStatus {
        UNKNOWN, CHECKING, VALID, REVOKED, SUSPENDED, EXPIRING_SOON,
        CHAIN_INVALID, UNTRUSTED_ROOT
    }

    // ---- State --------------------------------------------------------------

    private var currentRevocationStatus: RevocationStatus = RevocationStatus.UNKNOWN
    private var currentRevocationReason: String = ""   // e.g. "KEY_COMPROMISE"
    private var isCheckInProgress = false
    private var isKeyboxPickerOpen = false

    // ---- Trust anchors (SHA-256 of DER-encoded cert) ------------------------

    companion object {
        // Google Hardware Attestation Root (RSA-4096)
        // Original issuance 2019, serial d50ff25ba3f2d6b3, valid until 2034
        private const val GOOGLE_ROOT_RSA_2019_SHA256 =
            "1EF1A04B8BA58AB94589AC498C8982A783F24EA7307E0159A0C3A73B377D87CC"

        // Google Hardware Attestation Root (RSA-4096)
        // Reissued 2022, serial f1c172a699eaf51d, valid until 2042
        // Same public key as 2019 root, different cert (currently served by /attestation/root)
        private const val GOOGLE_ROOT_RSA_2022_SHA256 =
            "CEDB1CB6DC896AE5EC797348BCE9286753C2B38EE71CE0FBE34A9A1248800DFC"

        // Google Hardware Attestation Root (EC P-384) "Key Attestation CA1"
        // Issued 2025, serial 84a9d0297b0eb58ae7ff0e80de760605, valid until 2035
        // RKP devices use this root exclusively from April 2026 onward
        private const val GOOGLE_ROOT_EC_2025_SHA256 =
            "C6E5DC76BD81307046A3CCC979F0FAC6BDDEF46CC9B533B2134EB0E99F67550E"

        // Keys & setting names
        private const val KEYBOX_KEY                 = "spoof_trickystore_keybox"
        private const val KEYBOX_SOURCE_KEY          = "spoof_trickystore_keybox_source"
        private const val KEYBOX_SOURCE_OFFICIAL     = "official"
        private const val KEYBOX_SOURCE_USER         = "user"
        private const val TARGET_KEY                 = TrickyStoreAppSettings.TARGET_KEY
        internal const val PATCH_KEY                 = "spoof_trickystore_patch"
        private const val LAST_FETCHED_KEY           = "spoof_trickystore_last_fetched"
        private const val LAST_REVOCATION_CHECK_KEY  = "spoof_trickystore_last_revocation_check"
        private const val LAST_NO_VALID_KEY          = "spoof_trickystore_last_no_valid"
        private const val LAST_REVOCATION_STATUS_KEY = "spoof_trickystore_last_revocation_status"
        private const val LAST_REVOCATION_REASON_KEY = "spoof_trickystore_last_revocation_reason"

        private const val TRICKYSTORE_ENABLED_KEY    = "spoof_trickystore_enabled"

        private const val VENDING_PACKAGE            = "com.android.vending"
        private const val DROIDGUARD_PACKAGE         = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE                = "com.google.android.gms"
        private const val GMS_PERSISTENT_PACKAGE     = "com.google.android.gms.persistent"
        private const val RKPD_PACKAGE               = "com.google.android.rkpdapp"
        private const val GSF_PACKAGE                = "com.google.android.gsf"
        private const val CONTACT_KEYS_PACKAGE       = "com.google.android.contactkeys"
        private const val SAFETY_CORE_PACKAGE        = "com.google.android.safetycore"
        private const val VELVET_PACKAGE             = "com.google.android.googlequicksearchbox"

        private const val REVOCATION_URL     =
            "https://android.googleapis.com/attestation/status?encrypted=0"
        private const val OFFICIAL_KEYBOX_URL =
            "https://v15.next.forgejo.org/EvolutionX/keybox/raw/branch/main/keybox.xml"

        /** Warn user when the leaf cert expires within this window. */
        private val EXPIRY_WARN_MS = TimeUnit.DAYS.toMillis(14)
    }

    // ---- Properties ---------------------------------------------------------

    private val isTrickyStoreEnabled: Boolean
        get() = Settings.System.getInt(
            requireContext().contentResolver, TRICKYSTORE_ENABLED_KEY, 1) != 0

    // ---- Activity-result launchers ------------------------------------------

    private val keyboxPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isKeyboxPickerOpen = false
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.data?.let { uri ->
            try {
                val bytes = requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() } ?: ByteArray(0)
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Settings.Secure.putString(
                    requireContext().contentResolver, KEYBOX_KEY, encoded)
                Settings.Secure.putString(
                    requireContext().contentResolver, KEYBOX_SOURCE_KEY, KEYBOX_SOURCE_USER)
                saveLastFetchedTimestamp()
                killGms()
                toast(getString(R.string.ts_keybox_imported))
                currentRevocationStatus = RevocationStatus.UNKNOWN
                currentRevocationReason = ""
                Settings.Secure.putString(
                    requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                refreshStatus()
                checkKeyboxRevocation()
            } catch (e: Exception) {
                toast(getString(R.string.ts_failed, e.message ?: ""))
            }
        }
    }

    private val targetPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.data?.let { uri ->
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
                Settings.Secure.putString(
                    requireContext().contentResolver, TARGET_KEY, text)
                toast(getString(R.string.ts_target_list_imported))
                refreshStatus()
            } catch (e: Exception) {
                toast(getString(R.string.ts_failed, e.message ?: ""))
            }
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

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

        findPreference<Preference>("ts_fetch_keybox")?.setOnPreferenceClickListener {
            fetchOfficialKeybox()
            true
        }

        findPreference<Preference>("ts_revocation_status")?.setOnPreferenceClickListener {
            checkKeyboxRevocation()
            true
        }

        // Restore persisted status
        currentRevocationStatus = when (
            Settings.Secure.getString(
                requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY)
        ) {
            "VALID"          -> RevocationStatus.VALID
            "REVOKED"        -> RevocationStatus.REVOKED
            "SUSPENDED"      -> RevocationStatus.SUSPENDED
            "EXPIRING_SOON"  -> RevocationStatus.EXPIRING_SOON
            "CHAIN_INVALID"  -> RevocationStatus.CHAIN_INVALID
            "UNTRUSTED_ROOT" -> RevocationStatus.UNTRUSTED_ROOT
            else             -> RevocationStatus.UNKNOWN
        }
        currentRevocationReason = Settings.Secure.getString(
            requireContext().contentResolver, LAST_REVOCATION_REASON_KEY) ?: ""

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (!isTrickyStoreEnabled) return

        val lastChecked = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_REVOCATION_CHECK_KEY, 0L)
        val overADay = System.currentTimeMillis() - lastChecked > TimeUnit.HOURS.toMillis(24)
        if (overADay && !isCheckInProgress) {
            checkKeyboxRevocation()
        } else {
            autoFetchIfNoKeybox()
        }
    }

    // ---- UI refresh ---------------------------------------------------------

    private fun refreshStatus() {
        val keyboxExists = !Settings.Secure.getString(
            requireContext().contentResolver, KEYBOX_KEY).isNullOrEmpty()

        val targetContent = Settings.Secure.getString(
            requireContext().contentResolver, TARGET_KEY)
        val targetCount = targetContent?.lines()?.count { it.isNotBlank() } ?: 0

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

        applyRevocationUi(currentRevocationStatus, currentRevocationReason)
        updateFetchButtonState(keyboxExists)
    }

    private fun applyRevocationUi(status: RevocationStatus, reason: String = "") {
        val pref = findPreference<Preference>("ts_revocation_status") ?: return
        if (!isAdded) return

        val reasonSuffix = if (reason.isNotEmpty()) " ($reason)" else ""

        val (iconRes, summary) = when (status) {
            RevocationStatus.VALID -> Pair(
                R.drawable.ic_ts_status_valid,
                getString(R.string.ts_revocation_valid)
            )
            RevocationStatus.REVOKED -> Pair(
                R.drawable.ic_ts_status_revoked,
                getString(R.string.ts_revocation_revoked, reasonSuffix)
            )
            RevocationStatus.SUSPENDED -> Pair(
                R.drawable.ic_ts_status_suspended,
                getString(R.string.ts_revocation_suspended, reasonSuffix)
            )
            RevocationStatus.EXPIRING_SOON -> Pair(
                R.drawable.ic_ts_status_suspended,  // amber warning icon
                getString(R.string.ts_revocation_expiring_soon)
            )
            RevocationStatus.CHAIN_INVALID -> Pair(
                R.drawable.ic_ts_status_revoked,
                getString(R.string.ts_revocation_chain_invalid)
            )
            RevocationStatus.UNTRUSTED_ROOT -> Pair(
                R.drawable.ic_ts_status_suspended,  // amber
                getString(R.string.ts_revocation_untrusted_root)
            )
            RevocationStatus.CHECKING -> Pair(
                R.drawable.ic_ts_status_unknown,
                getString(R.string.ts_revocation_checking)
            )
            RevocationStatus.UNKNOWN -> Pair(
                R.drawable.ic_ts_status_unknown,
                if (!Settings.Secure.getString(
                        requireContext().contentResolver, KEYBOX_KEY).isNullOrEmpty())
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

        val isBlockingStatus = currentRevocationStatus == RevocationStatus.VALID

        if (isBlockingStatus) {
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

    // ---- Revocation check ---------------------------------------------------

    private fun checkKeyboxRevocation() {
        if (isCheckInProgress) return
        val raw = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        if (raw.isNullOrEmpty()) {
            currentRevocationStatus = RevocationStatus.UNKNOWN
            currentRevocationReason = ""
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

            result.fold(
                onSuccess = { (status, reason) ->
                    currentRevocationStatus = status
                    currentRevocationReason = reason

                    if (status != RevocationStatus.UNKNOWN) {
                        Settings.Secure.putLong(
                            requireContext().contentResolver,
                            LAST_REVOCATION_CHECK_KEY, System.currentTimeMillis())
                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            LAST_REVOCATION_STATUS_KEY, status.name)
                        Settings.Secure.putString(
                            requireContext().contentResolver,
                            LAST_REVOCATION_REASON_KEY, reason)
                    }

                    applyRevocationUi(status, reason)
                    updateFetchButtonState(keyboxExists = true)
                    refreshStatus()  // update keybox-info row too

                    handleActionableStatus(status)
                },
                onFailure = { e ->
                    val pref = findPreference<Preference>("ts_revocation_status")
                        ?: return@fold
                    pref.setIcon(when (currentRevocationStatus) {
                        RevocationStatus.VALID                         -> R.drawable.ic_ts_status_valid
                        RevocationStatus.REVOKED,
                        RevocationStatus.CHAIN_INVALID                 -> R.drawable.ic_ts_status_revoked
                        RevocationStatus.UNTRUSTED_ROOT,
                        RevocationStatus.SUSPENDED,
                        RevocationStatus.EXPIRING_SOON                 -> R.drawable.ic_ts_status_suspended
                        else                                           -> R.drawable.ic_ts_status_unknown
                    })
                    pref.summary = getString(R.string.ts_revocation_error, e.message)
                }
            )
        }
    }

    private fun handleActionableStatus(status: RevocationStatus) {
        when (status) {
            RevocationStatus.REVOKED, RevocationStatus.SUSPENDED -> {
                Settings.Secure.putString(requireContext().contentResolver, KEYBOX_KEY, "")
                Settings.Secure.putLong(requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                currentRevocationStatus = RevocationStatus.UNKNOWN
                refreshStatus()
                toast(getString(
                    if (status == RevocationStatus.REVOKED)
                        R.string.ts_fetch_keybox_revoked_refetch
                    else
                        R.string.ts_fetch_keybox_suspended_refetch
                ))
                if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
            }
            RevocationStatus.EXPIRING_SOON -> {
                showExpiringSoonDialog()
            }
            RevocationStatus.CHAIN_INVALID -> {
                if (isUserImportedKeybox()) showChainInvalidDialog()
                // else: official keybox with chain issues — leave it alone,
                // strong integrity may still pass
            }
            RevocationStatus.UNTRUSTED_ROOT -> {
                if (isUserImportedKeybox()) showUntrustedRootDialog()
                // else: same, leave official keybox in place
            }
            else -> Unit
        }
    }

    // ---- Core revocation logic (runs on IO dispatcher) ----------------------

    private fun performRevocationCheck(raw: String): Pair<RevocationStatus, String> {
        val xml = decodeKeyboxXml(raw)
            ?: return Pair(RevocationStatus.UNKNOWN, "")

        // 1. Parse certificates
        val certs = extractCertificates(xml)
        if (certs.isEmpty()) return Pair(RevocationStatus.UNKNOWN, "")

        // 2. Validate chain: each cert must be signed by the next, root must be trusted
        val chainError = validateChain(certs)
        if (chainError != null) {
            val status = if (chainError == "UNTRUSTED_ROOT")
                RevocationStatus.UNTRUSTED_ROOT
            else
                RevocationStatus.CHAIN_INVALID
            return Pair(status, chainError)
        }

        // 4. Check leaf cert expiry
        val leafCert = certs.first()
        val now = System.currentTimeMillis()
        if (leafCert.notAfter.time < now) {
            // Already expired – treat like chain invalid so it gets replaced
            return Pair(RevocationStatus.CHAIN_INVALID, "CERT_EXPIRED")
        }
        val expiringStatus = if (leafCert.notAfter.time - now < EXPIRY_WARN_MS)
            RevocationStatus.EXPIRING_SOON else null

        // 5. Fetch Google's revocation JSON
        val serials = certs.map { it.serialNumber.toString(16).lowercase() }
        val revJson = fetchRevocationJson()
            ?: return Pair(RevocationStatus.UNKNOWN, "")

        // 6. Check each serial against the revocation list
        val entries = revJson.optJSONObject("entries")
        if (entries != null) {
            for (serial in serials) {
                val entry = entries.optJSONObject(serial) ?: continue
                val status = entry.optString("status", "").uppercase()
                val reason = entry.optString("reason", "").uppercase()
                when (status) {
                    "REVOKED"   -> return Pair(RevocationStatus.REVOKED, reason)
                    "SUSPENDED" -> return Pair(RevocationStatus.SUSPENDED, reason)
                }
            }
        }

        // 7. If the cert is expiring soon, surface that now (it passed all other checks)
        if (expiringStatus != null) {
            val expiryFormatted = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(leafCert.notAfter)
            return Pair(expiringStatus, expiryFormatted)
        }

        return Pair(RevocationStatus.VALID, "")
    }

    // ---- Certificate parsing ------------------------------------------------

    private fun decodeKeyboxXml(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.startsWith("<")) return trimmed
        return try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            val asXml = String(decoded, Charsets.UTF_8).trim()
            if (asXml.startsWith("<")) asXml else null
        } catch (_: Exception) { null }
    }

    private fun extractCertificates(xml: String): List<X509Certificate> {
        val certs = mutableListOf<X509Certificate>()
        val factory = CertificateFactory.getInstance("X.509")
        val matcher = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----"
        ).matcher(xml)
        while (matcher.find()) {
            try {
                val der = Base64.decode(
                    matcher.group(1)!!.replace("\\s".toRegex(), ""), Base64.DEFAULT)
                certs.add(
                    factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate)
            } catch (_: Exception) {}
        }
        return certs
    }

    /**
     * Validates the certificate chain:
     * - Each cert[i] must be signed by cert[i+1]
     * - The last cert must be self-signed and its public-key fingerprint must
     *   match one of the known Google attestation root fingerprints.
     *
     * Returns null if the chain is valid, or a short error string on failure.
     */
    private fun validateChain(certs: List<X509Certificate>): String? {
        if (certs.size < 2) return "CHAIN_TOO_SHORT"

        // Verify each link: cert[i] signed by cert[i+1]
        for (i in 0 until certs.size - 1) {
            try {
                certs[i].verify(certs[i + 1].publicKey)
            } catch (_: Exception) {
                return "BAD_SIGNATURE_AT_$i"
            }
        }

        // Root must be self-signed
        val root = certs.last()
        try {
            root.verify(root.publicKey)
        } catch (_: Exception) {
            return "ROOT_NOT_SELF_SIGNED"
        }

        // Root public-key must match a known Google attestation root
        val rootFingerprint = sha256Hex(root.encoded)
        val knownRoots = setOf(
            normalise(GOOGLE_ROOT_RSA_2019_SHA256),
            normalise(GOOGLE_ROOT_RSA_2022_SHA256),
            normalise(GOOGLE_ROOT_EC_2025_SHA256),
        )
        if (rootFingerprint !in knownRoots) {
            return "UNTRUSTED_ROOT"
        }

        return null  // chain is valid
    }

    // ---- Network helpers ----------------------------------------------------

    private fun fetchRevocationJson(): JSONObject? = try {
        val conn = URL(REVOCATION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() })
        else null
    } catch (_: Exception) { null }

    // ---- Auto-fetch ---------------------------------------------------------

    private fun autoFetchIfNoKeybox() {
        if (!isTrickyStoreEnabled) return
        if (isKeyboxPickerOpen) return
        if (isCheckInProgress) return
        if (isNoValidCooldownActive()) return

        val existing = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        if (!existing.isNullOrEmpty()) {
            // Only replace when we have a definitively non-functional keybox
            if (currentRevocationStatus == RevocationStatus.REVOKED ||
                currentRevocationStatus == RevocationStatus.SUSPENDED) {
                fetchOfficialKeybox(silent = true)
            }
            return
        }
        fetchOfficialKeybox(silent = true)
    }

    // ---- Official keybox fetch ----------------------------------------------

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

            if (!silent) updateFetchButtonState(keyboxExists = true)
            if (!isAdded) return@launch

            result.fold(
                onSuccess = { xml ->
                    if (!isTrickyStoreEnabled) return@fold
                    try {
                        // Deduplicate: don't install same content we already have
                        val existing = Settings.Secure.getString(
                            requireContext().contentResolver, KEYBOX_KEY)
                        val existingXml = if (!existing.isNullOrEmpty())
                            decodeKeyboxXml(existing) else null
                        if (existingXml != null && existingXml.trim() == xml.trim()) {
                            if (!silent) toast(getString(R.string.ts_fetch_keybox_same_file))
                            return@fold
                        }

                        val fetchedCerts = extractCertificates(xml)

                        // Check expiry of the leaf cert
                        val leafExpiry = fetchedCerts.firstOrNull()?.notAfter
                        val now = System.currentTimeMillis()
                        if (leafExpiry != null && leafExpiry.time < now) {
                            if (!silent) toast(getString(
                                R.string.ts_fetch_keybox_failed, "Cert already expired"))
                            markNoValidKeyboxFound()
                            return@fold
                        }

                        val fetchedSerials = fetchedCerts.map {
                            it.serialNumber.toString(16).lowercase()
                        }

                        // Check revocation
                        val revocationJson = withContext(Dispatchers.IO) { fetchRevocationJson() }
                        val entries = revocationJson?.optJSONObject("entries")
                        var fetchedInvalid = false
                        if (entries != null) {
                            fetchedInvalid = fetchedSerials.any { serial ->
                                val e = entries.optJSONObject(serial)
                                val s = e?.optString("status", "")?.uppercase()
                                s == "REVOKED" || s == "SUSPENDED"
                            }
                        }

                        if (fetchedInvalid) {
                            toast(getString(R.string.ts_fetch_keybox_no_valid_found))
                            markNoValidKeyboxFound()
                            return@fold
                        }

                        clearNoValidKeyboxCooldown()

                        val encoded = Base64.encodeToString(
                            xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        Settings.Secure.putString(
                            requireContext().contentResolver, KEYBOX_KEY, encoded)
                        Settings.Secure.putString(
                            requireContext().contentResolver, KEYBOX_SOURCE_KEY, KEYBOX_SOURCE_OFFICIAL)
                        saveLastFetchedTimestamp()
                        killGms()
                        toast(getString(
                            if (silent) R.string.ts_fetch_keybox_auto_replaced
                            else R.string.ts_fetch_keybox_success))
                        currentRevocationStatus = RevocationStatus.UNKNOWN
                        currentRevocationReason = ""
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

    // ---- Cooldown helpers ---------------------------------------------------

    private fun isNoValidCooldownActive(): Boolean {
        val last = Settings.Secure.getLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY, 0L)
        return last > 0L &&
               System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(24)
    }

    private fun markNoValidKeyboxFound() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY,
            System.currentTimeMillis())
    }

    private fun clearNoValidKeyboxCooldown() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_NO_VALID_KEY, 0L)
    }

    // ---- Timestamp helpers --------------------------------------------------

    private fun saveLastFetchedTimestamp() {
        Settings.Secure.putLong(
            requireContext().contentResolver, LAST_FETCHED_KEY,
            System.currentTimeMillis())
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

    // ---- Verification-mode summary ------------------------------------------

    private fun buildVerificationSummary(): String {
        val content = Settings.Secure.getString(
            requireContext().contentResolver, TARGET_KEY)
            ?: return getString(R.string.ts_verification_mode_auto)

        var auto = 0; var cert = 0; var leaf = 0
        content.lines().forEach { line ->
            val t = line.trim()
            if (t.isNotBlank()) when {
                t.endsWith("!") -> cert++
                t.endsWith("?") -> leaf++
                else            -> auto++
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

    // ---- Dialogs ------------------------------------------------------------

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
                    currentRevocationReason = ""
                    Settings.Secure.putString(
                        requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                    Settings.Secure.putString(
                        requireContext().contentResolver, LAST_REVOCATION_REASON_KEY, "")
                    Settings.Secure.putString(
                        requireContext().contentResolver, KEYBOX_SOURCE_KEY, "")
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

    private fun showUntrustedRootDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_untrusted_root_title)
            .setMessage(R.string.ts_untrusted_root_message)
            .setPositiveButton(R.string.ts_untrusted_root_keep) { _, _ ->
                // User acknowledges — leave keybox installed, don't refetch
            }
            .setNegativeButton(R.string.ts_untrusted_root_replace) { _, _ ->
                Settings.Secure.putString(requireContext().contentResolver, KEYBOX_KEY, "")
                Settings.Secure.putLong(requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_REASON_KEY, "")
                currentRevocationStatus = RevocationStatus.UNKNOWN
                currentRevocationReason = ""
                refreshStatus()
                if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
            }
            .setCancelable(false)
            .show()
    }

    private fun showChainInvalidDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_chain_invalid_title)
            .setMessage(R.string.ts_chain_invalid_message)
            .setPositiveButton(R.string.ts_untrusted_root_keep) { _, _ -> }
            .setNegativeButton(R.string.ts_untrusted_root_replace) { _, _ ->
                Settings.Secure.putString(requireContext().contentResolver, KEYBOX_KEY, "")
                Settings.Secure.putLong(requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_REASON_KEY, "")
                currentRevocationStatus = RevocationStatus.UNKNOWN
                currentRevocationReason = ""
                refreshStatus()
                if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
            }
            .setCancelable(false)
            .show()
    }

    private fun showExpiringSoonDialog() {
        if (!isAdded) return
        val expiryStr = currentRevocationReason.ifEmpty { getString(R.string.ts_expiry_unknown) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_expiring_soon_title)
            .setMessage(getString(R.string.ts_expiring_soon_message, expiryStr))
            .setPositiveButton(R.string.ts_untrusted_root_keep) { _, _ -> }
            .setNegativeButton(R.string.ts_untrusted_root_replace) { _, _ ->
                Settings.Secure.putString(requireContext().contentResolver, KEYBOX_KEY, "")
                Settings.Secure.putLong(requireContext().contentResolver, LAST_FETCHED_KEY, 0L)
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_STATUS_KEY, "")
                Settings.Secure.putString(requireContext().contentResolver, LAST_REVOCATION_REASON_KEY, "")
                currentRevocationStatus = RevocationStatus.UNKNOWN
                currentRevocationReason = ""
                refreshStatus()
                if (!isNoValidCooldownActive()) fetchOfficialKeybox(silent = true)
            }
            .setCancelable(false)
            .show()
    }

    private fun showPatchDateDialog() {
        val current =
            Settings.Secure.getString(requireContext().contentResolver, PATCH_KEY) ?: ""
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
                Settings.Secure.putString(requireContext().contentResolver, PATCH_KEY, "")
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

    private fun isUserImportedKeybox(): Boolean =
        Settings.Secure.getString(
            requireContext().contentResolver, KEYBOX_SOURCE_KEY) == KEYBOX_SOURCE_USER

    // ---- GMS kill -----------------------------------------------------------

    private fun killGms() {
        try {
            val am =
                requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            listOf(
                VENDING_PACKAGE, DROIDGUARD_PACKAGE, GMS_PACKAGE,
                GMS_PERSISTENT_PACKAGE, RKPD_PACKAGE, GSF_PACKAGE,
                CONTACT_KEYS_PACKAGE, SAFETY_CORE_PACKAGE, VELVET_PACKAGE
            ).forEach { am.forceStopPackage(it) }
            requireContext().packageManager.clearApplicationUserData(VENDING_PACKAGE, null)
        } catch (_: Exception) {}
    }

    // ---- Crypto helpers -----------------------------------------------------

    /** SHA-256 of [input] bytes, returned as contiguous uppercase hex. */
    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** Strip colons/spaces and uppercase for consistent comparison. */
    private fun normalise(fingerprint: String) =
        fingerprint.replace(":", "").replace(" ", "").uppercase()

    // ---- Misc ---------------------------------------------------------------

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER
}
