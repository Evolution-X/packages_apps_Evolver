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
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
                    checkKeyboxRevocation()
                } catch (e: Exception) {
                    toast(getString(R.string.ts_failed, e.message ?: ""))
                }
            }
        }
    }

    private val isOfficialBuild: Boolean
        get() = android.os.SystemProperties.get("ro.evolution.build.type", "") == "Official"

    private fun checkKeyboxRevocation() {
        val raw = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        val pref = findPreference<Preference>("ts_revocation_status") ?: return
        if (raw.isNullOrEmpty()) {
            pref.summary = getString(R.string.ts_revocation_no_keybox)
            return
        }
        pref.summary = getString(R.string.ts_revocation_checking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { performRevocationCheck(raw) }
            }
            result.fold(
                onSuccess = { (summary) -> pref.summary = summary },
                onFailure = { e -> pref.summary = getString(R.string.ts_revocation_error, e.message) }
            )
        }
    }

    private fun performRevocationCheck(raw: String): Pair<String, Unit> {
        val xml = decodeKeyboxForRevocation(raw)
            ?: return Pair(getString(R.string.ts_revocation_error, "Cannot decode keybox"), Unit)
        val serials = extractCertSerials(xml)
        if (serials.isEmpty())
            return Pair(getString(R.string.ts_revocation_error, "No certs found"), Unit)
        val json = fetchRevocationJson()
            ?: return Pair(getString(R.string.ts_revocation_network_error), Unit)
        val entries = json.optJSONObject("entries")
            ?: return Pair(getString(R.string.ts_revocation_valid), Unit)
        for (serial in serials) {
            val entry = entries.optJSONObject(serial) ?: continue
            val status = entry.optString("status", "").uppercase()
            val reason = entry.optString("reason", "")
            if (status == "REVOKED") {
                if (isOfficialBuild) {
                    lifecycleScope.launch {
                        toast(getString(R.string.ts_fetch_keybox_revoked_refetch))
                        fetchOfficialKeybox(silent = true)
                    }
                }
                return Pair(getString(R.string.ts_revocation_revoked, reason), Unit)
            }
            if (status == "SUSPENDED")
                return Pair(getString(R.string.ts_revocation_suspended, reason), Unit)
        }
        return Pair(getString(R.string.ts_revocation_valid), Unit)
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

    private fun autoFetchIfNoKeybox() {
        if (!isOfficialBuild) return
        val existing = Settings.Secure.getString(requireContext().contentResolver, KEYBOX_KEY)
        if (!existing.isNullOrEmpty()) return
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
                pref?.isEnabled = true
                pref?.summary = getString(R.string.ts_fetch_keybox_summary)
            }
            result.fold(
                onSuccess = { xml ->
                    try {
                        val existing = Settings.Secure.getString(
                            requireContext().contentResolver, KEYBOX_KEY)
                        val existingXml = if (!existing.isNullOrEmpty())
                            decodeKeyboxForRevocation(existing) else null
                        if (existingXml != null && existingXml.trim() == xml.trim()) {
                            if (!silent) toast(getString(R.string.ts_fetch_keybox_same_file))
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
                            if (fetchedRevoked) return@fold
                        }
                        val encoded = Base64.encodeToString(
                            xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        Settings.Secure.putString(
                            requireContext().contentResolver, KEYBOX_KEY, encoded)
                        killGms()
                        if (!silent) toast(getString(R.string.ts_fetch_keybox_success))
                        refreshStatus()
                        checkKeyboxRevocation()
                    } catch (e: Exception) {
                        if (!silent) toast(getString(R.string.ts_fetch_keybox_failed, e.message ?: ""))
                    }
                },
                onFailure = { e ->
                    if (!silent) toast(getString(R.string.ts_fetch_keybox_failed, e.message ?: ""))
                }
            )
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

        findPreference<Preference>("ts_revocation_status")?.isEnabled = false

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        checkKeyboxRevocation()
        autoFetchIfNoKeybox()
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
    }

    private fun buildVerificationSummary(): String {
        val content = Settings.Secure.getString(
            requireContext().contentResolver, TARGET_KEY
        ) ?: return getString(R.string.ts_verification_mode_auto)

        var auto = 0
        var cert = 0
        var leaf = 0
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
                        requireContext().contentResolver,
                        KEYBOX_KEY,
                        ""
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
                    requireContext().contentResolver,
                    PATCH_KEY,
                    ""
                )
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
                    requireContext().contentResolver,
                    PATCH_KEY,
                    value
                )
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

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        private const val KEYBOX_KEY = "spoof_trickystore_keybox"
        private const val TARGET_KEY = TrickyStoreAppSettings.TARGET_KEY
        internal const val PATCH_KEY = "spoof_trickystore_patch"
        private const val VENDING_PACKAGE = "com.android.vending"
        private const val DROIDGUARD_PACKAGE = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val REVOCATION_URL = "https://android.googleapis.com/attestation/status"
        private const val OFFICIAL_KEYBOX_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/keybox.xml"
    }
}
