/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.Preference

import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.evolution.PixelPropsUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.deviceinfo.DeviceNameUtils
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable

import org.evolution.settings.utils.HttpCachePrefs
import org.evolution.settings.utils.NetworkUtils
import org.evolution.settings.utils.UrlUtils

import org.json.JSONArray
import org.json.JSONObject

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.Executors

@SearchIndexable
class About : SettingsPreferenceFragment(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val TAG = "About"

        private const val KEY_CURRENT_MAINTAINERS = "current_maintainers"

        private const val OTA_TREE_API_TEMPLATE =
            "https://api.github.com/repos/Evolution-X/OTA/git/trees/%s?recursive=1"
        private const val OTA_RAW_BASE_TEMPLATE =
            "https://raw.githubusercontent.com/Evolution-X/OTA/%s/"

        // Branch lookup order for OTA maintainer resolution. CNB (Android 17) is
        // tried first; bka (Android 16) and vic (Android 15) are legacy fallbacks
        // for maintainers who don't yet have a published CNB entry. A maintainer
        // who already has a CNB entry is never shadowed by a bka/vic entry for the
        // same key, even if that lower-priority entry covers different devices.
        private val OTA_BRANCHES = listOf("cnb", "bka", "vic")

        private const val PREFS_NAME = "about_ota_maintainers_cache"
        private const val PREF_ENTRIES_JSON = "entries_json"
        private const val PREF_LAST_UPDATED = "last_updated"
        private const val PREF_PAYPAL_JSON = "paypal_json"
        private const val CACHE_KEY_TREE_PREFIX = "ota_tree_"

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER =
            BaseSearchIndexProvider(R.xml.evolution_settings_about)
    }

    private val mExecutor = Executors.newSingleThreadExecutor()
    private val mMainHandler = Handler(Looper.getMainLooper())

    private lateinit var mAvatarLoader: GithubAvatarLoader
    private lateinit var mPrefs: SharedPreferences
    private lateinit var mTreeCaches: Map<String, HttpCachePrefs>

    @Volatile private var mDestroyed = false

    private var mCachedMaintainerList: List<MaintainerInfo> = emptyList()
    private var mCachedUpdatedAt: Long = 0L

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.evolution_settings_about)

        if (PixelPropsUtils.isCustomForkBuild()) {
            preferenceScreen?.removeAll()
            return
        }

        mPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        mTreeCaches = OTA_BRANCHES.associateWith { branch ->
            HttpCachePrefs(mPrefs, CACHE_KEY_TREE_PREFIX + branch)
        }
        mAvatarLoader = GithubAvatarLoader.getInstance()

        val avatarMap = linkedMapOf(
            "about_founder_1" to "joeyhuab",
            "about_founder_2" to "AnierinBliss",
            "about_founder_3" to "RealAkito",
            "about_member_1"  to "TechPanelGM",
            "about_member_2"  to "AidanWarner97",
            "about_member_3"  to "Onelots",
            "about_member_4"  to "manidweep",
            "about_member_5"  to "apelete",
        )
        for ((key, username) in avatarMap) {
            findPreference<Preference>(key)?.let {
                mAvatarLoader.load(requireContext(), it, username)
            }
        }

        setupTeamMemberPreferences(readCachedGithubToPaypal())

        findPreference<Preference>(KEY_CURRENT_MAINTAINERS)?.setOnPreferenceClickListener {
            openMaintainersSheet()
            true
        }

        loadCurrentMaintainers()
    }

    override fun onDestroy() {
        mDestroyed = true
        if (!mExecutor.isShutdown) mExecutor.shutdown()
        super.onDestroy()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?) = false

    override fun getMetricsCategory() = MetricsEvent.EVOLVER

    // -------------------------------------------------------------------------
    // Sheet
    // -------------------------------------------------------------------------

    private fun openMaintainersSheet() {
        if (!isUiSafe()) return
        val sheet = CurrentMaintainersSheet().apply {
            entries   = toUiEntries(mCachedMaintainerList)
            isLoading = mCachedMaintainerList.isEmpty()
        }
        sheet.show(parentFragmentManager, CurrentMaintainersSheet.TAG)
        mTreeCaches.values.forEach { it.invalidate() }
        loadCurrentMaintainers(notifySheet = true)
    }

    // -------------------------------------------------------------------------
    // Maintainer loading
    // -------------------------------------------------------------------------

    private fun loadCurrentMaintainers(notifySheet: Boolean = false) {
        val cached = readCachedMaintainers()
        val cachedUpdatedAt = mPrefs.getLong(PREF_LAST_UPDATED, 0L)
        if (cached.isNotEmpty()) {
            mCachedMaintainerList = cached
            mCachedUpdatedAt = cachedUpdatedAt
            setupTeamMemberPreferences(readCachedGithubToPaypal())
        }

        val hasCache = cached.isNotEmpty()
        val anyStale = mTreeCaches.values.any { it.isStale }
        if (hasCache && !anyStale && !notifySheet) return

        mExecutor.execute {
            val result = fetchMaintainersFromOtaWithConditionalTree()
            if (result == null) {
                mTreeCaches.values.forEach { it.touchLastCheck() }
                return@execute
            }
            if (result.notModified) {
                mTreeCaches.values.forEach { it.touchLastCheck() }
                if (notifySheet) {
                    mMainHandler.post { refreshOpenSheet(mCachedMaintainerList) }
                }
                return@execute
            }

            val updatedAt = System.currentTimeMillis()
            writeMaintainersCache(result.maintainers, result.githubToPaypal, updatedAt)
            for ((branch, etag) in result.branchEtags) {
                val lastModified = result.branchLastModified[branch] ?: ""
                mTreeCaches[branch]?.write(etag, lastModified)
            }

            val finalMaintainers = result.maintainers
            val finalGithubToPaypal = result.githubToPaypal

            mMainHandler.post {
                if (!isUiSafe()) return@post
                mCachedMaintainerList = finalMaintainers
                mCachedUpdatedAt = updatedAt
                setupTeamMemberPreferences(finalGithubToPaypal)
                if (notifySheet) refreshOpenSheet(finalMaintainers)
            }
        }
    }

    private fun refreshOpenSheet(maintainers: List<MaintainerInfo>) {
        if (!isUiSafe()) return
        val sheet = parentFragmentManager
            .findFragmentByTag(CurrentMaintainersSheet.TAG) as? CurrentMaintainersSheet
        sheet?.refresh(toUiEntries(maintainers))
    }

    // -------------------------------------------------------------------------
    // Team member preferences
    // -------------------------------------------------------------------------

    private fun setupTeamMemberPreferences(githubToPaypal: Map<String, String>) {
        val hardcodedDonate = mapOf(
            "joeyhuab"      to "https://linktr.ee/joeyhuab",
            "anierinbliss"  to "https://www.paypal.me/AnierinB",
            "realakito"     to "https://t.me/RealAkito",
            "aidanwarner97" to "https://linktr.ee/aidanlw",
            "manidweep"     to "https://paypal.me/manidreddy1",
            "apelete"       to "https://www.linkedin.com/posts/apelete_evolutionx-activity-7429791532091797504-ND3i",
        )

        val teamGithubMap = linkedMapOf(
            "about_founder_1" to "joeyhuab",
            "about_founder_2" to "AnierinBliss",
            "about_founder_3" to "RealAkito",
            "about_member_1"  to "TechPanelGM",
            "about_member_2"  to "AidanWarner97",
            "about_member_3"  to "Onelots",
            "about_member_4"  to "manidweep",
            "about_member_5"  to "apelete",
        )

        for ((prefKey, github) in teamGithubMap) {
            val pref = findPreference<Preference>(prefKey) ?: continue
            val key = github.lowercase(Locale.ROOT)

            val donateUrl = githubToPaypal[key] ?: hardcodedDonate[key]
            val githubUrl = UrlUtils.buildGithubUrl(github)
            val hasGithub = githubUrl != null
            val hasDonate = UrlUtils.isValidHttpUrl(donateUrl)
            val finalDonate = if (hasDonate) donateUrl else null

            pref.isSelectable = hasGithub || hasDonate
            if (!hasGithub && !hasDonate) continue

            if (hasGithub && hasDonate) {
                val name = pref.title?.toString() ?: github
                pref.setOnPreferenceClickListener {
                    showMaintainerLinkDialog(name, githubUrl!!, finalDonate!!)
                    true
                }
            } else {
                val only = if (hasGithub) githubUrl!! else finalDonate!!
                pref.setOnPreferenceClickListener { openUrl(only); true }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs / URL helpers
    // -------------------------------------------------------------------------

    private fun showMaintainerLinkDialog(name: String, githubUrl: String, donateUrl: String) {
        if (!isUiSafe()) return
        val items = arrayOf<CharSequence>(
            getString(R.string.maintainer_link_github),
            getString(R.string.maintainer_link_donate),
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(items) { _, which ->
                if (which == 0) openUrl(githubUrl) else openUrl(donateUrl)
            }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open URL: $url", e)
        }
    }

    // -------------------------------------------------------------------------
    // OTA fetch
    // -------------------------------------------------------------------------

    /**
     * Resolves the full current-maintainers list by walking OTA_BRANCHES in
     * priority order (cnb, then bka, then vic). Each branch's device tree is
     * fetched and aggregated independently, then merged: a maintainer key
     * (maintainer + github, case-insensitive) already satisfied by a
     * higher-priority branch is skipped entirely in lower-priority branches,
     * even if that lower-priority branch would have contributed different
     * devices for the same maintainer. Maintainers with no cnb entry at all
     * still fall through to bka/vic normally.
     */
    private fun fetchMaintainersFromOtaWithConditionalTree(): FetchResult? {
        val branchEtags = LinkedHashMap<String, String>()
        val branchLastModified = LinkedHashMap<String, String>()
        val mergedMaintainers = LinkedHashMap<String, AggregatedMaintainer>()
        val githubToPaypal = LinkedHashMap<String, String>()
        var anyBranchSucceeded = false
        var allBranchesNotModified = true

        for (branch in OTA_BRANCHES) {
            val branchCache = mTreeCaches.getValue(branch)
            val branchResult = fetchBranchTree(branch, branchCache) ?: continue

            anyBranchSucceeded = true
            branchEtags[branch] = branchResult.treeEtag
            branchLastModified[branch] = branchResult.treeLastModified

            if (branchResult.notModified) continue
            allBranchesNotModified = false

            for ((key, aggregated) in branchResult.aggregate) {
                if (mergedMaintainers.containsKey(key)) continue
                mergedMaintainers[key] = aggregated
                if (aggregated.github.isNotEmpty() && UrlUtils.isValidHttpUrl(aggregated.paypal)) {
                    githubToPaypal[aggregated.github.lowercase(Locale.ROOT)] = aggregated.paypal
                }
            }
        }

        if (!anyBranchSucceeded) return null
        if (allBranchesNotModified) return FetchResult.notModified()

        val result = mutableListOf<MaintainerInfo>()
        for (m in mergedMaintainers.values) {
            val sortedEntries = m.deviceEntries.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
            val summary   = sortedEntries.joinToString(", ") { it.label }
            val donateUrl = if (UrlUtils.isValidHttpUrl(m.paypal)) m.paypal else null
            val clickUrl  = donateUrl ?: UrlUtils.buildGithubUrl(m.github)
            val forumUrls = sortedEntries
                .filter { it.forumUrl != null }
                .map { it.label to it.forumUrl!! }
            result.add(MaintainerInfo(m.maintainer, summary, m.github, donateUrl, clickUrl, forumUrls))
        }
        result.sortWith(Comparator.comparing { it.maintainer.lowercase(Locale.ROOT) })

        return FetchResult(result, githubToPaypal, branchEtags, branchLastModified)
    }

    /**
     * Fetches and aggregates the maintainer tree for a single branch. Returns
     * null only on hard failure (network/parse error, non-2xx); a branch with
     * an empty or all-unmaintained tree still returns a (possibly empty)
     * successful result so its ETag is recorded and it isn't retried every load.
     */
    private fun fetchBranchTree(branch: String, branchCache: HttpCachePrefs): BranchFetchResult? {
        return try {
            val treeApi = String.format(OTA_TREE_API_TEMPLATE, branch)
            val rawBase = String.format(OTA_RAW_BASE_TEMPLATE, branch)

            val r = NetworkUtils.fetchWithStatus(
                treeApi,
                branchCache.buildHeaders("application/vnd.github+json")
            )

            if (r.isNotModified) {
                return BranchFetchResult(emptyMap(), r.etag, r.lastModified, notModified = true)
            }
            if (!r.isOk || r.bytes == null) {
                Log.w(TAG, "Tree API failed for $branch: HTTP ${r.statusCode}")
                return null
            }

            val treeJson = r.bodyAsString()
            if (treeJson.isNullOrEmpty()) {
                Log.w(TAG, "Empty OTA tree response for $branch")
                return null
            }

            val root = JSONObject(treeJson)
            val tree = root.optJSONArray("tree")
            if (tree == null || tree.length() == 0) {
                return BranchFetchResult(emptyMap(), r.etag, r.lastModified)
            }

            val aggregate = LinkedHashMap<String, AggregatedMaintainer>()

            for (i in 0 until tree.length()) {
                val node = tree.optJSONObject(i) ?: continue
                val type = UrlUtils.trimToEmpty(node.optString("type", ""))
                val path = UrlUtils.trimToEmpty(node.optString("path", ""))
                if (type != "blob") continue
                if (!path.startsWith("builds/") || !path.endsWith(".json")) continue

                val rawUrl = rawBase + path
                try {
                    val deviceJson = NetworkUtils.fetchString(rawUrl, null)
                    if (deviceJson.isNullOrEmpty()) continue

                    val deviceRoot = JSONObject(deviceJson)
                    val response = deviceRoot.optJSONArray("response") ?: continue
                    if (response.length() == 0) continue

                    val entry = response.optJSONObject(0) ?: continue
                    if (!entry.optBoolean("currently_maintained", false)) continue

                    val maintainer = UrlUtils.trimToEmpty(entry.optString("maintainer", ""))
                    val oem        = UrlUtils.trimToEmpty(entry.optString("oem", ""))
                    val device     = UrlUtils.trimToEmpty(entry.optString("device", ""))
                    val github     = UrlUtils.trimToEmpty(entry.optString("github", ""))
                    val paypal     = UrlUtils.trimToEmpty(entry.optString("paypal", ""))
                    val forum      = UrlUtils.trimToEmpty(entry.optString("forum", ""))

                    if (maintainer.isEmpty() || device.isEmpty()) continue

                    val deviceLabel = formatDeviceLabel(oem, device)
                    val key = normalizeKey(maintainer, github)
                    var bucket = aggregate[key]
                    if (bucket == null) {
                        bucket = AggregatedMaintainer(maintainer, github, paypal)
                        aggregate[key] = bucket
                    } else {
                        if (bucket.paypal.isEmpty() && UrlUtils.isValidHttpUrl(paypal)) {
                            bucket.paypal = paypal
                        }
                    }
                    val forumLink = if (UrlUtils.isValidHttpUrl(forum)) forum else null
                    bucket.deviceEntries.add(DeviceEntry(deviceLabel, forumLink))

                } catch (e: Exception) {
                    Log.d(TAG, "Failed parsing OTA JSON: $branch/$path", e)
                }
            }

            BranchFetchResult(aggregate, r.etag, r.lastModified)

        } catch (e: Exception) {
            Log.e(TAG, "Failed loading OTA maintainers for $branch", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Cache read / write
    // -------------------------------------------------------------------------

    private fun readCachedMaintainers(): List<MaintainerInfo> {
        val json = UrlUtils.trimToEmpty(mPrefs.getString(PREF_ENTRIES_JSON, ""))
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<MaintainerInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val maintainer = UrlUtils.trimToEmpty(obj.optString("maintainer", ""))
                val summary    = UrlUtils.trimToEmpty(obj.optString("summary", ""))
                val github     = UrlUtils.trimToEmpty(obj.optString("github", ""))
                val clickUrl   = UrlUtils.trimToEmpty(obj.optString("click_url", ""))
                if (maintainer.isEmpty()) continue
                if (summary.isEmpty() && clickUrl.isEmpty()) continue
                val cachedDonate = if (clickUrl.contains("github.com")) null else clickUrl.ifEmpty { null }
                val forumsArr = obj.optJSONArray("forum_urls")
                val cachedForumUrls = mutableListOf<Pair<String, String>>()
                if (forumsArr != null) {
                    for (j in 0 until forumsArr.length()) {
                        val fo    = forumsArr.optJSONObject(j) ?: continue
                        val label = UrlUtils.trimToEmpty(fo.optString("label", ""))
                        val url   = UrlUtils.trimToEmpty(fo.optString("url",   ""))
                        if (label.isNotEmpty() && UrlUtils.isValidHttpUrl(url)) {
                            cachedForumUrls.add(label to url)
                        }
                    }
                }
                list.add(MaintainerInfo(maintainer, summary, github, cachedDonate, clickUrl, cachedForumUrls))
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse maintainer cache", e)
            emptyList()
        }
    }

    private fun writeMaintainersCache(
        list: List<MaintainerInfo>,
        githubToPaypal: Map<String, String>,
        updatedAt: Long,
    ) {
        val arr = JSONArray()
        for (info in list) {
            val obj = JSONObject()
            try {
                obj.put("maintainer", info.maintainer)
                obj.put("summary",    info.summary)
                obj.put("github",     info.github)
                obj.put("click_url",  info.clickUrl ?: "")
                val forumsArr = JSONArray()
                for ((label, url) in info.forumUrls) {
                    val fo = JSONObject()
                    fo.put("label", label)
                    fo.put("url",   url)
                    forumsArr.put(fo)
                }
                obj.put("forum_urls", forumsArr)
            } catch (_: Exception) {}
            arr.put(obj)
        }

        val paypalObj = JSONObject()
        for ((k, v) in githubToPaypal) {
            try { paypalObj.put(k, v) } catch (_: Exception) {}
        }

        mPrefs.edit()
            .putString(PREF_ENTRIES_JSON, arr.toString())
            .putString(PREF_PAYPAL_JSON,  paypalObj.toString())
            .putLong(PREF_LAST_UPDATED,   updatedAt)
            .apply()
    }

    private fun readCachedGithubToPaypal(): Map<String, String> {
        val json = UrlUtils.trimToEmpty(mPrefs.getString(PREF_PAYPAL_JSON, ""))
        if (json.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun toUiEntries(list: List<MaintainerInfo>): List<MaintainerUiEntry> =
        list.map { info ->
            MaintainerUiEntry(
                maintainer = info.maintainer,
                devices    = info.summary,
                github     = info.github ?: "",
                donateUrl  = info.donateUrl,
                forumUrls  = info.forumUrls,
            )
        }

    private fun isUiSafe() = !mDestroyed && isAdded && context != null

    private fun formatDeviceLabel(oem: String, device: String): String {
        val clean = UrlUtils.trimToEmpty(device)
        if (clean.isEmpty()) return ""
        return DeviceNameUtils.prefixIfNeeded(oem, device)
    }

    private fun normalizeKey(maintainer: String, github: String) =
        "${UrlUtils.trimToEmpty(maintainer).lowercase(Locale.ROOT)}|${UrlUtils.trimToEmpty(github).lowercase(Locale.ROOT)}"

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class DeviceEntry(
        val label: String,
        val forumUrl: String?,
    )

    private data class AggregatedMaintainer(
        val maintainer: String,
        val github: String,
        var paypal: String,
        val deviceEntries: LinkedHashSet<DeviceEntry> = LinkedHashSet(),
    ) {
        init {
            paypal = if (UrlUtils.isValidHttpUrl(paypal)) paypal else ""
        }
    }

    private data class MaintainerInfo(
        val maintainer: String,
        val summary: String,
        val github: String,
        val donateUrl: String?,
        val clickUrl: String?,
        val forumUrls: List<Pair<String, String>>,
    )

    private data class BranchFetchResult(
        val aggregate: Map<String, AggregatedMaintainer>,
        val treeEtag: String,
        val treeLastModified: String,
        val notModified: Boolean = false,
    )

    private data class FetchResult(
        val maintainers: List<MaintainerInfo>,
        val githubToPaypal: Map<String, String>,
        val branchEtags: Map<String, String>,
        val branchLastModified: Map<String, String>,
        val notModified: Boolean = false,
    ) {
        companion object {
            fun notModified() = FetchResult(
                maintainers        = emptyList(),
                githubToPaypal     = emptyMap(),
                branchEtags        = emptyMap(),
                branchLastModified = emptyMap(),
                notModified        = true,
            )
        }
    }
}
