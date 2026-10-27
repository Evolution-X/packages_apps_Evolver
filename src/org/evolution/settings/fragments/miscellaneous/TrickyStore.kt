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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.android.settings.core.SubSettingLauncher
import android.provider.Settings
import android.util.Base64
import android.util.Xml
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.StringReader
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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.json.JSONObject

class TrickyStore : SettingsPreferenceFragment() {

    private val ui = TsUiState()

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

    // Set by a manual keybox import; consumed once the revocation check that follows it
    // finishes, so the Play Store wipe prompt never competes with a keep/replace dialog.
    private var offerWipeAfterCheck = false

    // ---- Trust anchors (SHA-256 of DER-encoded cert) ------------------------

    companion object {
        private const val TAG = "TrickyStore"

        // Fallback trust anchors, used only if the live roots fetch fails and
        // no cached copy exists yet. Google may add/rotate roots at any time;
        // the live fetch from ROOTS_URL is the source of truth, this list
        // exists purely so first-run/offline behaviour degrades safely
        // instead of trusting nothing.
        // Google Hardware Attestation Root (RSA-4096), original 2019 issuance
        private const val FALLBACK_ROOT_RSA_2019_SHA256 =
            "1EF1A04B8BA58AB94589AC498C8982A783F24EA7307E0159A0C3A73B377D87CC"
        // Google Hardware Attestation Root (RSA-4096), reissued 2022
        private const val FALLBACK_ROOT_RSA_2022_SHA256 =
            "CEDB1CB6DC896AE5EC797348BCE9286753C2B38EE71CE0FBE34A9A1248800DFC"
        // Google Hardware Attestation Root (EC P-384) "Key Attestation CA1", 2025
        private const val FALLBACK_ROOT_EC_2025_SHA256 =
            "6D9DB4CE6C5C0B293166D08986E05774A8776CEB525D9E4329520DE12BA4BCC0"

        private const val ROOTS_URL = "https://android.googleapis.com/attestation/root"
        private const val ROOTS_CACHE_KEY       = "spoof_trickystore_cached_roots"
        private const val ROOTS_CACHED_AT_KEY   = "spoof_trickystore_roots_cached_at"
        private val ROOTS_CACHE_TTL_MS = TimeUnit.DAYS.toMillis(30)

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

        private const val CACHED_REVOKED_SERIALS_KEY = "spoof_trickystore_cached_revoked_serials"
        private const val CACHED_REVOKED_AT_KEY       = "spoof_trickystore_cached_revoked_at"
        private val CACHE_TRUST_WINDOW_MS = TimeUnit.DAYS.toMillis(7)

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
            "https://gitea.com/Evolution-X/keybox/raw/branch/main/keybox.xml"

        /** Warn user when the leaf cert expires within this window. */
        private val EXPIRY_WARN_MS = TimeUnit.DAYS.toMillis(14)

        /**
         * Puts the spoofing settings back to a fresh-install state: the PIF
         * config, patch level and GameProps config are cleared and the
         * TrickyStore target list is reseeded with the defaults. The keybox is
         * kept, since it may be the user's own and the official one is kept
         * current by AxSpoofManager anyway. Mirrors AlwaysStrong's
         * reset_defaults.sh, which also keeps the keybox.
         *
         * Nothing is fetched here. AxSpoofManager watches the PIF config and,
         * a few seconds after it goes empty, refreshes the fingerprint and patch
         * level itself (from the built-in profiles when offline). Order matters
         * for that: the fetch cooldown is zeroed first and the PIF config is
         * cleared last, so the refresh sees a fully reset state.
         */
        @JvmStatic
        fun resetAllSpoofDefaults(context: Context) {
            val resolver = context.contentResolver
            Settings.Secure.putLong(resolver, PlayIntegrityFix.LAST_AUTO_FETCH_KEY, 0L)
            Settings.Secure.putString(
                resolver, TARGET_KEY, TrickyStoreAppSettings.buildDefaultTargetSeed())
            Settings.Secure.putString(resolver, PATCH_KEY, "")
            Settings.Secure.putString(resolver, Settings.Secure.SPOOF_GAMEPROPS_CONFIG, "")
            Settings.Secure.putString(resolver, PlayIntegrityFix.PIF_CONFIG_KEY, "")
        }
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
                // The framework rejects a keybox that fails these checks, so refuse it here
                // instead of storing something that silently never takes effect.
                val problem = keyboxProblem(bytes.toString(Charsets.UTF_8))
                if (problem != null) {
                    toast(getString(R.string.ts_fetch_keybox_failed, problem))
                    return@registerForActivityResult
                }
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
                offerWipeAfterCheck = true
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

    private val actions = object : TsActions {
        override fun onEnabledChange(enabled: Boolean) {
            Settings.System.putInt(
                requireContext().contentResolver, TRICKYSTORE_ENABLED_KEY, if (enabled) 1 else 0)
            ui.enabled = enabled
            if (enabled) refreshStatus()
        }

        override fun onFetchKeybox() = fetchOfficialKeybox()

        override fun onImportKeybox() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            isKeyboxPickerOpen = true
            keyboxPicker.launch(intent)
        }

        override fun onDeleteKeybox() = showDeleteKeyboxDialog()

        override fun onCheckRevocation() = checkKeyboxRevocation()

        override fun onManageTargets() {
            SubSettingLauncher(requireContext())
                .setDestination(TrickyStoreAppSettings::class.java.name)
                .setTitleRes(R.string.ts_manage_target_apps)
                .setSourceMetricsCategory(getMetricsCategory())
                .launch()
        }

        override fun onImportTargets() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
            }
            targetPicker.launch(intent)
        }

        override fun onEditPatch() = showPatchDateDialog()

        override fun onConfirmDeleteKeybox() = deleteKeybox()

        // Explicit request: ignore the cooldown, keep the old keybox on failure
        override fun onReplaceKeybox() = fetchOfficialKeybox(silent = false)

        override fun onSavePatch(value: String): String? {
            if (value.isNotEmpty() && !isValidPatchContent(value)) {
                return getString(R.string.ts_invalid_patch_date)
            }
            Settings.Secure.putString(requireContext().contentResolver, PATCH_KEY, value)
            refreshStatus()
            return null
        }

        override fun onWipePlayStore(wipe: Boolean) {
            if (!wipe) return
            try {
                requireContext().packageManager.clearApplicationUserData(VENDING_PACKAGE, null)
            } catch (_: Exception) {}
        }

        override fun onDeletePatch() {
            Settings.Secure.putString(requireContext().contentResolver, PATCH_KEY, "")
            refreshStatus()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ExpressiveTheme {
                TrickyStoreContent(state = ui, actions = actions)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.tricky_store_title)

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
        val resolver = requireContext().contentResolver
        val keyboxExists = !Settings.Secure.getString(resolver, KEYBOX_KEY).isNullOrEmpty()

        val targetContent = Settings.Secure.getString(resolver, TARGET_KEY)
        ui.enabled = isTrickyStoreEnabled
        ui.keyboxExists = keyboxExists
        ui.targetCount = targetContent?.lines()?.count { it.isNotBlank() } ?: 0

        val patchDate = Settings.Secure.getString(resolver, PATCH_KEY)
        ui.patchStale = !patchDate.isNullOrEmpty() && isPatchDateStale(patchDate)
        ui.patchSummary =
            if (!patchDate.isNullOrEmpty()) {
                if (ui.patchStale) getString(R.string.ts_patch_stale_warning, patchDate)
                else patchDate
            } else {
                getString(R.string.ts_no_patch)
            }

        ui.verificationSummary = buildVerificationSummary()

        applyRevocationUi(currentRevocationStatus, currentRevocationReason)
        updateFetchButtonState(keyboxExists)
    }

    private fun applyRevocationUi(status: RevocationStatus, reason: String = "") {
        if (!isAdded) return

        val reasonSuffix = if (reason.isNotEmpty()) " ($reason)" else ""

        // Chip label, chip tone and the descriptive summary for each status.
        val (chip, tone, summary) = when (status) {
            RevocationStatus.VALID -> Triple(
                getString(R.string.ts_chip_valid), ExpressiveTone.Positive,
                getString(R.string.ts_revocation_valid))
            RevocationStatus.REVOKED -> Triple(
                getString(R.string.ts_revocation_revoked, ""), ExpressiveTone.Negative,
                getString(R.string.ts_revocation_revoked, reasonSuffix))
            RevocationStatus.SUSPENDED -> Triple(
                getString(R.string.ts_revocation_suspended, ""), ExpressiveTone.Warning,
                getString(R.string.ts_revocation_suspended, reasonSuffix))
            RevocationStatus.EXPIRING_SOON -> Triple(
                getString(R.string.ts_chip_expiring), ExpressiveTone.Warning,
                getString(R.string.ts_revocation_expiring_soon))
            RevocationStatus.CHAIN_INVALID -> Triple(
                getString(R.string.ts_chip_invalid), ExpressiveTone.Negative,
                getString(R.string.ts_revocation_chain_invalid))
            RevocationStatus.UNTRUSTED_ROOT -> Triple(
                getString(R.string.ts_chip_unknown_root), ExpressiveTone.Warning,
                getString(R.string.ts_revocation_untrusted_root))
            RevocationStatus.CHECKING -> Triple(
                getString(R.string.ts_chip_checking), ExpressiveTone.Neutral,
                getString(R.string.ts_revocation_checking))
            RevocationStatus.UNKNOWN -> Triple(
                getString(R.string.ts_chip_unchecked), ExpressiveTone.Neutral,
                if (!Settings.Secure.getString(
                        requireContext().contentResolver, KEYBOX_KEY).isNullOrEmpty())
                    getString(R.string.ts_revocation_not_yet_checked)
                else
                    getString(R.string.ts_revocation_no_keybox))
        }

        ui.revocationChip = chip
        ui.revocationTone = tone
        ui.revocationChecking = status == RevocationStatus.CHECKING
        val lastChecked = getLastRevocationCheckedFormatted()
        ui.revocationSummary = if (status == RevocationStatus.CHECKING || lastChecked == null)
            summary
        else
            "$summary\n${getString(R.string.ts_revocation_last_checked, lastChecked)}"
    }

    private fun updateFetchButtonState(keyboxExists: Boolean) {
        ui.fetching = false
        val isBlockingStatus = currentRevocationStatus == RevocationStatus.VALID

        if (isBlockingStatus) {
            ui.fetchEnabled = false
            ui.fetchSummary = getString(R.string.ts_fetch_keybox_blocked)
        } else {
            ui.fetchEnabled = true
            val timestamp = getLastFetchedFormatted()
            ui.fetchSummary = if (timestamp != null && keyboxExists)
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
                    offerWipeIfPending(status)
                },
                onFailure = { e ->
                    ui.revocationChecking = false
                    ui.revocationSummary = getString(R.string.ts_revocation_error, e.message)
                    offerWipeIfPending(null)
                }
            )
        }
    }

    private fun handleActionableStatus(status: RevocationStatus) {
        when (status) {
            RevocationStatus.REVOKED, RevocationStatus.SUSPENDED -> {
                // Never wipe here: a revoked keybox may still be better than none.
                // fetchOfficialKeybox swaps it only once a non-revoked, unexpired
                // replacement has been fetched.
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

        // 1. Parse the certificate chain of every <Key>. A keybox normally holds an ECDSA
        // and an RSA chain, which must be validated one at a time: as a single list the
        // EC root is checked against the RSA leaf and always fails.
        val chains = extractChains(xml)
        if (chains.isEmpty()) return Pair(RevocationStatus.UNKNOWN, "")

        // 2. Validate each chain: each cert must be signed by the next, root must be trusted
        val chainError = chains.firstNotNullOfOrNull { validateChain(it) }
        if (chainError != null) {
            val status = if (chainError == "UNTRUSTED_ROOT")
                RevocationStatus.UNTRUSTED_ROOT
            else
                RevocationStatus.CHAIN_INVALID
            return Pair(status, chainError)
        }

        // 4. Check leaf cert expiry, on whichever leaf runs out first
        val leafExpiry = chains.minOf { it.first().notAfter.time }
        val now = System.currentTimeMillis()
        if (leafExpiry < now) {
            // Already expired – treat like chain invalid so it gets replaced
            return Pair(RevocationStatus.CHAIN_INVALID, "CERT_EXPIRED")
        }
        val expiringStatus = if (leafExpiry - now < EXPIRY_WARN_MS)
            RevocationStatus.EXPIRING_SOON else null

        // 5. Fetch Google's revocation JSON
        val serials = serialKeys(chains.flatten())
        val revJson = fetchRevocationJson()
            ?: return checkCachedRevocation(serials)

        // 6. Check each serial against the revocation list
        val entries = revJson.optJSONObject("entries")
        if (entries != null) {
            for (serial in serials) {
                val entry = entries.optJSONObject(serial) ?: continue
                val status = entry.optString("status", "").uppercase()
                val reason = entry.optString("reason", "").uppercase()
                when (status) {
                    "REVOKED"   -> {
                        cacheRevokedSerial(serial)
                        return Pair(RevocationStatus.REVOKED, reason)
                    }
                    "SUSPENDED" -> {
                        cacheRevokedSerial(serial)
                        return Pair(RevocationStatus.SUSPENDED, reason)
                    }
                }
            }
        }
        // Live check passed cleanly — clear any stale cached bad-serial entry
        // for this keybox so a future offline fallback doesn't misfire.
        clearCachedRevokedSerials()

        // 7. If the cert is expiring soon, surface that now (it passed all other checks)
        if (expiringStatus != null) {
            val expiryFormatted = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(leafExpiry))
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

    /**
     * The certificate chain of each <Key>, leaf first. A document without <Key> blocks is
     * treated as one chain.
     */
    private fun extractChains(xml: String): List<List<X509Certificate>> {
        val chains = Regex("<Key\\b[^>]*>([\\s\\S]*?)</Key>").findAll(xml)
            .map { extractCertificates(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()
        if (chains.isNotEmpty()) return chains
        return extractCertificates(xml).let { if (it.isEmpty()) emptyList() else listOf(it) }
    }

    /**
     * The spellings a serial can have in Google's revocation list: lowercase hex without
     * leading zeros for 128-bit serials, plain decimal for the old 64-bit ones.
     */
    private fun serialKeys(certs: List<X509Certificate>): List<String> =
        certs.flatMap { listOf(it.serialNumber.toString(16), it.serialNumber.toString()) }
            .distinct()

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

        // Root must match a live-fetched Google attestation root, or (if the
        // live/cached fetch is unavailable) one of the fallback fingerprints.
        // Live anchors are compared by public key, not certificate hash: Google
        // reissues a root under a new certificate with the same key (the 2019 RSA
        // root became the 2022 one), so keyboxes still chaining to the old
        // certificate would never match by hash. The signature checks above tie
        // the chain to that key. Fallbacks are certificate hashes.
        val trustAnchors = getTrustAnchors()
        val rootFingerprint = sha256Hex(root.encoded)
        val trusted = if (trustAnchors.isNotEmpty()) {
            val rootKey = sha256Hex(root.publicKey.encoded)
            trustAnchors.any { sha256Hex(it.publicKey.encoded) == rootKey }
        } else {
            val fallbackRoots = setOf(
                normalise(FALLBACK_ROOT_RSA_2019_SHA256),
                normalise(FALLBACK_ROOT_RSA_2022_SHA256),
                normalise(FALLBACK_ROOT_EC_2025_SHA256),
            )
            rootFingerprint in fallbackRoots
        }
        if (!trusted) {
            Log.w(TAG, "untrusted root: fingerprint=$rootFingerprint " +
                "liveAnchors=${trustAnchors.size}")
            return "UNTRUSTED_ROOT"
        }

        return null  // chain is valid
    }

    // ---- Network helpers ----------------------------------------------------

    private fun fetchRevocationJson(): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFreshConnection(REVOCATION_URL)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() })
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Opens a connection with cache-busting so a stale CDN-cached 200 isn't
     * mistaken for a fresh answer. The android.googleapis.com/attestation
     * endpoints sit behind a CDN that can serve outdated data even past
     * their Cache-Control max-age.
     */
    private fun openFreshConnection(url: String): HttpURLConnection {
        val busted = url + (if ('?' in url) "&" else "?") + "_=${System.nanoTime()}"
        val conn = URL(busted).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Cache-Control", "max-age=0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        return conn
    }

    /**
     * Returns the current set of Google attestation root certificates.
     * Tries a live fetch from ROOTS_URL first; falls back to a cached copy
     * (however old) if the fetch fails; returns empty if there is no cache
     * at all, in which case the caller falls back to the hardcoded
     * FALLBACK_ROOT_*_SHA256 fingerprints.
     */
    private fun getTrustAnchors(): Set<X509Certificate> {
        val resolver = requireContext().contentResolver
        val cached = Settings.Secure.getString(resolver, ROOTS_CACHE_KEY)
        val cachedAt = Settings.Secure.getLong(resolver, ROOTS_CACHED_AT_KEY, 0L)
        val now = System.currentTimeMillis()
        val cacheIsFresh = !cached.isNullOrEmpty() &&
            cachedAt > 0L &&
            cachedAt <= now &&
            now - cachedAt <= ROOTS_CACHE_TTL_MS

        if (cacheIsFresh) {
            parseRootsJson(cached!!)?.let { return it }
        }

        val live = fetchTrustAnchorsLive()
        if (live != null) {
            cacheTrustAnchors(live.second)
            return live.first
        }

        if (!cached.isNullOrEmpty()) {
            parseRootsJson(cached)?.let { return it }
        }
        return emptySet()
    }

    /** Returns (parsed certs, raw json) on success, null on any failure. */
    private fun fetchTrustAnchorsLive(): Pair<Set<X509Certificate>, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFreshConnection(ROOTS_URL)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val certs = parseRootsJson(raw)
                if (certs != null) {
                    Log.d(TAG, "fetched ${certs.size} live attestation root(s)")
                    Pair(certs, raw)
                } else {
                    Log.w(TAG, "roots fetch: response parsed to zero valid certs")
                    null
                }
            } else {
                Log.w(TAG, "roots fetch failed: HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "roots fetch failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseRootsJson(raw: String): Set<X509Certificate>? = try {
        val array = org.json.JSONArray(raw)
        val factory = CertificateFactory.getInstance("X.509")
        (0 until array.length()).mapNotNull { i ->
            try {
                val pem = array.getString(i)
                factory.generateCertificate(
                    ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))
                ) as X509Certificate
            } catch (_: Exception) { null }
        }.toSet().ifEmpty { null }
    } catch (_: Exception) { null }

    private fun cacheTrustAnchors(raw: String) {
        Settings.Secure.putString(requireContext().contentResolver, ROOTS_CACHE_KEY, raw)
        Settings.Secure.putLong(
            requireContext().contentResolver, ROOTS_CACHED_AT_KEY, System.currentTimeMillis())
    }

    // ---- Offline revocation cache --------------------------------------------

    /**
     * Records that [serial] was seen as revoked/suspended on a successful live
     * check, so a later fetch failure can still flag it. Downgrade-only: this
     * cache is never consulted to assert VALID, only REVOKED/SUSPENDED.
     */
    private fun cacheRevokedSerial(serial: String) {
        Settings.Secure.putString(
            requireContext().contentResolver, CACHED_REVOKED_SERIALS_KEY, serial)
        Settings.Secure.putLong(
            requireContext().contentResolver, CACHED_REVOKED_AT_KEY,
            System.currentTimeMillis())
    }

    private fun clearCachedRevokedSerials() {
        Settings.Secure.putString(
            requireContext().contentResolver, CACHED_REVOKED_SERIALS_KEY, "")
        Settings.Secure.putLong(
            requireContext().contentResolver, CACHED_REVOKED_AT_KEY, 0L)
    }

    /**
     * Fallback used when the live revocation fetch fails. Only ever returns
     * REVOKED/SUSPENDED (if a cached serial for this keybox is still within
     * the trust window) or UNKNOWN — never VALID, since we cannot confirm
     * that today without a successful live check.
     */
    private fun checkCachedRevocation(serials: List<String>): Pair<RevocationStatus, String> {
        val cachedAt = Settings.Secure.getLong(
            requireContext().contentResolver, CACHED_REVOKED_AT_KEY, 0L)
        if (cachedAt == 0L ||
            System.currentTimeMillis() - cachedAt > CACHE_TRUST_WINDOW_MS) {
            return Pair(RevocationStatus.UNKNOWN, "")
        }
        val cachedSerial = Settings.Secure.getString(
            requireContext().contentResolver, CACHED_REVOKED_SERIALS_KEY)
        return if (!cachedSerial.isNullOrEmpty() && serials.contains(cachedSerial)) {
            Pair(RevocationStatus.SUSPENDED, "CACHED_OFFLINE")
        } else {
            Pair(RevocationStatus.UNKNOWN, "")
        }
    }

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
        if (!silent) {
            ui.fetching = true
            ui.fetchSummary = getString(R.string.ts_fetch_keybox_fetching)
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(OFFICIAL_KEYBOX_URL).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                            "HTTP ${conn.responseCode}"
                        }
                        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    } finally {
                        conn.disconnect()
                    }
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
                            if (currentRevocationStatus == RevocationStatus.REVOKED ||
                                currentRevocationStatus == RevocationStatus.SUSPENDED) {
                                markNoValidKeyboxFound()
                            }
                            return@fold
                        }

                        keyboxProblem(xml)?.let { reason ->
                            if (!silent) toast(getString(R.string.ts_fetch_keybox_failed, reason))
                            markNoValidKeyboxFound()
                            return@fold
                        }

                        val fetchedChains = extractChains(xml)
                        val fetchedCerts = fetchedChains.flatten()

                        // Earliest-expiring leaf, same as the status check
                        val leafExpiry = fetchedChains.minOfOrNull { it.first().notAfter.time }
                        val now = System.currentTimeMillis()
                        if (leafExpiry != null && leafExpiry < now) {
                            if (!silent) toast(getString(
                                R.string.ts_fetch_keybox_failed, "Cert already expired"))
                            markNoValidKeyboxFound()
                            return@fold
                        }

                        // Do not swap a keybox for one whose chain or root is bad. With
                        // nothing installed, anything beats no keybox, so only gate a swap.
                        if (!existing.isNullOrEmpty()) {
                            val chainError = withContext(Dispatchers.IO) {
                                fetchedChains.firstNotNullOfOrNull { validateChain(it) }
                            }
                            if (chainError != null) {
                                if (!silent) toast(getString(
                                    R.string.ts_fetch_keybox_failed, chainError))
                                markNoValidKeyboxFound()
                                return@fold
                            }
                        }

                        val fetchedSerials = serialKeys(fetchedCerts)

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
        ui.dialog = TsDialog.DeleteKeybox
    }

    private fun deleteKeybox() {
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

    /**
     * Offers to clear Play Store data once per manual keybox import, after its revocation check.
     * Skipped when the check raised its own dialog, or flagged the keybox revoked/suspended (it is
     * about to be replaced), and never for the silent auto-fetch, which only force-stops.
     */
    private fun offerWipeIfPending(status: RevocationStatus?) {
        if (!offerWipeAfterCheck) return
        offerWipeAfterCheck = false
        if (!isAdded || ui.dialog != null) return
        if (status == RevocationStatus.REVOKED || status == RevocationStatus.SUSPENDED) return
        ui.dialog = TsDialog.WipePlayStore
    }

    // The three prompts below are raised from the async revocation check. They never replace a
    // dialog that is already open, so a half-typed patch date is not lost; the status is
    // persisted and prompts again on the next check.

    private fun showUntrustedRootDialog() {
        if (!isAdded || ui.dialog != null) return
        ui.dialog = TsDialog.UntrustedRoot
    }

    private fun showChainInvalidDialog() {
        if (!isAdded || ui.dialog != null) return
        ui.dialog = TsDialog.ChainInvalid
    }

    private fun showExpiringSoonDialog() {
        if (!isAdded || ui.dialog != null) return
        val expiryStr = currentRevocationReason.ifEmpty { getString(R.string.ts_expiry_unknown) }
        ui.dialog = TsDialog.ExpiringSoon(expiryStr)
    }

    /**
     * Returns true if [value] is valid security_patch.txt content.
     * Accepts:
     *   - A single YYYY-MM-DD date
     *   - A YYYY-MM-<DD> template (e.g. "YYYY-MM-05")
     *   - Multi-line per-package block format:
     *       [com.package.name]
     *       system=no
     *       all=2026-07-05
     */
    private fun isValidPatchContent(value: String): Boolean {
        val dateRe    = Regex("""\d{4}-\d{2}-\d{2}""")
        val templateRe = Regex("""YYYY-MM-\d{2}""")
        if (dateRe.matches(value.trim())) return true
        if (templateRe.matches(value.trim())) return true
        return value.lines()
            .filter { it.isNotBlank() && !it.trim().startsWith("#") }
            .all { line ->
                val t = line.trim()
                when {
                    t.startsWith("[") && t.endsWith("]") -> true  // section header
                    t.contains("=") -> {
                        val v = t.substringAfter("=").trim()
                        dateRe.matches(v) ||
                        templateRe.matches(v) ||
                        v.equals("no", ignoreCase = true) ||
                        v.equals("prop", ignoreCase = true)
                    }
                    else -> false
                }
            }
    }

    private fun showPatchDateDialog() {
        val current = Settings.Secure.getString(
            requireContext().contentResolver, PATCH_KEY) ?: ""
        ui.dialog = TsDialog.PatchDate(current)
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
            // Force-stop only, like AxSpoofManager.killGmsFamily(). Clearing Play Store data
            // on every keybox import/fetch (including the silent auto-replace) signed users out.
        } catch (_: Exception) {}
    }

    /**
     * Same acceptance rules as the framework: TrickyStoreService.isValidKeyboxXml() plus
     * KeyBoxManager's check that NumberOfKeyboxes matches either the number of <Keybox>
     * elements or the number of <Key> elements holding a complete key (algorithm + private
     * key + certificate). Returns null when the keybox is acceptable, otherwise a short
     * reason.
     */
    private fun keyboxProblem(rawXml: String): String? {
        // isValidKeyboxXml() looks at the raw text, KeyBoxManager parses the sanitized one.
        if (!rawXml.contains("<Key algorithm=\"ecdsa\">") && !rawXml.contains("<Key algorithm=\"rsa\">")) {
            return "No ECDSA or RSA key block"
        }
        if (!rawXml.contains("<serial>") && !rawXml.contains("DeviceID")) {
            return "No serial or DeviceID"
        }
        val xml = sanitizeKeyboxXml(rawXml)
        var declared: Int? = null
        var parsedBoxes = 0
        var parsedKeys = 0
        var sawKeyboxTag = false
        var boxHasKey = false
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var inKey = false
            var algorithm: String? = null
            var hasPrivateKey = false
            var certs = 0
            var text: StringBuilder? = null
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (p.name) {
                            "Keybox" -> {
                                sawKeyboxTag = true
                                boxHasKey = false
                            }
                            "Key" -> {
                                inKey = true
                                algorithm = p.getAttributeValue(null, "algorithm")
                                hasPrivateKey = false
                                certs = 0
                            }
                            "PrivateKey", "Certificate" -> if (inKey) text = StringBuilder()
                            "NumberOfKeyboxes" -> if (!inKey) text = StringBuilder()
                        }
                    }
                    XmlPullParser.TEXT -> text?.append(p.text)
                    XmlPullParser.END_TAG -> {
                        when (p.name) {
                            "PrivateKey" -> {
                                if (text?.isNotBlank() == true) hasPrivateKey = true
                                text = null
                            }
                            "Certificate" -> {
                                if (text?.isNotBlank() == true) certs++
                                text = null
                            }
                            "NumberOfKeyboxes" -> {
                                declared = text?.toString()?.trim()?.toIntOrNull()
                                text = null
                            }
                            "Key" -> {
                                inKey = false
                                if (algorithm != null && hasPrivateKey && certs > 0) {
                                    parsedKeys++
                                    boxHasKey = true
                                }
                            }
                            "Keybox" -> {
                                if (boxHasKey) parsedBoxes++
                                boxHasKey = false
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            return "Not a valid keybox XML"
        }
        // A document without <Keybox> wrappers holds a single implicit box.
        if (!sawKeyboxTag && parsedKeys > 0) parsedBoxes = 1
        val count = declared
        // Generators disagree on whether NumberOfKeyboxes counts <Keybox> or <Key> elements,
        // and KeyBoxManager accepts either. Only a document matching neither is incomplete.
        if (count != null && count != parsedBoxes && count != parsedKeys) {
            return "Declares $count keybox(es) but $parsedBoxes box(es) / $parsedKeys key(s) parsed"
        }
        return null
    }

    /** KeyBoxManager.sanitizeXml(): trim, strip a leading BOM, strip XML comments. */
    private fun sanitizeKeyboxXml(content: String): String {
        var s = content.trim()
        for (bom in listOf("\uFEFF", "\uFFFE", "\u0000\uFEFF")) {
            if (s.startsWith(bom)) s = s.substring(bom.length)
        }
        return s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim()
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

    /**
     * Returns true if [patchDate] (a single YYYY-MM-DD value, not the
     * multi-line per-package block format) is more than 12 months old.
     * Unparsable input returns false — we don't warn on values we can't read.
     */
    private fun isPatchDateStale(patchDate: String): Boolean {
        val dateRe = Regex("""\d{4}-\d{2}-\d{2}""")
        val trimmed = patchDate.trim()
        if (!dateRe.matches(trimmed)) return false
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val patchMs = sdf.parse(trimmed)?.time ?: return false
            System.currentTimeMillis() - patchMs > TimeUnit.DAYS.toMillis(365)
        } catch (_: Exception) {
            false
        }
    }

    // ---- Misc ---------------------------------------------------------------

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER
}
