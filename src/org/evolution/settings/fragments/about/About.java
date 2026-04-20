/*
 * Copyright (C) 2019-2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SearchIndexable
public class About extends SettingsPreferenceFragment implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "About";

    private static final String KEY_CURRENT_MAINTAINERS = "current_maintainers";
    private static final String KEY_CURRENT_MAINTAINERS_LOADING = "current_maintainers_loading";

    private static final String OTA_TREE_API =
            "https://api.github.com/repos/Evolution-X/OTA/git/trees/bka?recursive=1";
    private static final String OTA_RAW_BASE =
            "https://raw.githubusercontent.com/Evolution-X/OTA/bka/";

    // Persistent cache
    private static final String PREFS_NAME = "about_ota_maintainers_cache";
    private static final String PREF_ENTRIES_JSON = "entries_json";
    private static final String PREF_LAST_UPDATED = "last_updated";
    private static final String PREF_LAST_CHECK = "last_check";
    private static final String PREF_TREE_ETAG = "tree_etag";
    private static final String PREF_TREE_LAST_MOD = "tree_last_mod";

    // Revalidate once a day (will still refresh sooner if no cache exists).
    private static final long CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private GithubAvatarLoader mAvatarLoader;
    private SharedPreferences mPrefs;
    private volatile boolean mDestroyed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_about);

        mPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        mAvatarLoader = new GithubAvatarLoader();

        Map<String, String> avatarMap = new LinkedHashMap<>();
        avatarMap.put("about_founder_1", "joeyhuab");
        avatarMap.put("about_founder_2", "AnierinBliss");
        avatarMap.put("about_founder_3", "RealAkito");
        avatarMap.put("about_member_1", "TechPanelGM");
        avatarMap.put("about_member_2", "AidanWarner97");
        avatarMap.put("about_member_3", "Onelots");
        avatarMap.put("about_member_4", "manidweep");
        avatarMap.put("about_member_5", "apelete");

        for (Map.Entry<String, String> entry : avatarMap.entrySet()) {
            Preference pref = findPreference(entry.getKey());
            if (pref != null) {
                mAvatarLoader.load(requireContext(), pref, entry.getValue());
            }
        }

        loadCurrentMaintainers();
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        if (!mExecutor.isShutdown()) {
            mExecutor.shutdown();
        }
        if (mAvatarLoader != null) {
            mAvatarLoader.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    private void loadCurrentMaintainers() {
        PreferenceCategory category = findPreference(KEY_CURRENT_MAINTAINERS);
        if (category == null) {
            Log.w(TAG, "Missing preference category: " + KEY_CURRENT_MAINTAINERS);
            return;
        }

        showLoadingState(category);

        // 1) Immediate render from persistent cache if available.
        List<MaintainerInfo> cached = readCachedMaintainers();
        long cachedUpdatedAt = mPrefs.getLong(PREF_LAST_UPDATED, 0L);
        if (!cached.isEmpty()) {
            populateMaintainerCategory(cached, cachedUpdatedAt);
        }

        // 2) Revalidate only when stale OR no cache.
        boolean hasCache = !cached.isEmpty();
        long lastCheck = mPrefs.getLong(PREF_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        boolean stale = (now - lastCheck) >= CACHE_MAX_AGE_MS;

        if (hasCache && !stale) {
            return;
        }

        mExecutor.execute(() -> {
            FetchResult result = fetchMaintainersFromOtaWithConditionalTree();
            if (result == null) {
                mPrefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                return;
            }

            if (result.notModified) {
                mPrefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                return;
            }

            long updatedAt = System.currentTimeMillis();
            writeMaintainersCache(result.maintainers, updatedAt, result.treeEtag, result.treeLastModified);

            final List<MaintainerInfo> finalMaintainers = result.maintainers;
            final long finalUpdatedAt = updatedAt;
            mMainHandler.post(() -> {
                if (!isUiSafe()) return;
                populateMaintainerCategory(finalMaintainers, finalUpdatedAt);
            });
        });
    }

    private void showLoadingState(PreferenceCategory category) {
        category.removeAll();
        category.setSummary(null);

        Preference loading = new Preference(requireContext());
        loading.setKey(KEY_CURRENT_MAINTAINERS_LOADING);
        loading.setTitle(R.string.about_current_maintainers_loading_title);
        loading.setSummary(R.string.about_current_maintainers_loading_summary);
        loading.setSelectable(false);
        category.addPreference(loading);
    }

    private void populateMaintainerCategory(List<MaintainerInfo> maintainers, long updatedAtMs) {
        if (!isUiSafe()) return;

        PreferenceCategory category = findPreference(KEY_CURRENT_MAINTAINERS);
        if (category == null) return;

        category.removeAll();
        category.setSummary(buildLastUpdatedSummary(updatedAtMs));

        if (maintainers == null || maintainers.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setKey("current_maintainers_empty");
            empty.setTitle(R.string.about_current_maintainers_empty_title);
            empty.setSummary(R.string.about_current_maintainers_empty_summary);
            empty.setSelectable(false);
            category.addPreference(empty);
            return;
        }

        for (int i = 0; i < maintainers.size(); i++) {
            MaintainerInfo info = maintainers.get(i);

            Preference pref = new Preference(requireContext());
            pref.setKey("current_maintainer_" + i);
            pref.setTitle(info.maintainer);
            pref.setSummary(info.summary);

            final String clickUrl = info.clickUrl;
            final boolean clickable = isValidHttpUrl(clickUrl);
            pref.setSelectable(clickable);
            pref.setOnPreferenceClickListener(clickable ? p -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(clickUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to open maintainer link: " + clickUrl, e);
                }
                return true;
            } : null);

            category.addPreference(pref);

            if (!TextUtils.isEmpty(info.github)) {
                mAvatarLoader.load(requireContext(), pref, info.github);
            }
        }
    }

    private String buildLastUpdatedSummary(long updatedAtMs) {
        if (updatedAtMs <= 0) {
            return getString(R.string.about_current_maintainers_last_updated_unknown);
        }
        CharSequence relative = DateUtils.getRelativeTimeSpanString(
                updatedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
        return getString(R.string.about_current_maintainers_last_updated, relative);
    }

    private FetchResult fetchMaintainersFromOtaWithConditionalTree() {
        HttpURLConnection treeConn = null;
        try {
            URL url = new URL(OTA_TREE_API);
            treeConn = (HttpURLConnection) url.openConnection();
            treeConn.setConnectTimeout(8000);
            treeConn.setReadTimeout(8000);
            treeConn.setRequestProperty("Accept", "application/vnd.github+json");

            String oldEtag = trimToEmpty(mPrefs.getString(PREF_TREE_ETAG, ""));
            String oldLastMod = trimToEmpty(mPrefs.getString(PREF_TREE_LAST_MOD, ""));
            if (!oldEtag.isEmpty()) treeConn.setRequestProperty("If-None-Match", oldEtag);
            if (!oldLastMod.isEmpty()) treeConn.setRequestProperty("If-Modified-Since", oldLastMod);

            treeConn.connect();
            int code = treeConn.getResponseCode();

            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return FetchResult.notModified();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Tree API failed: HTTP " + code);
                return null;
            }

            String treeJson = readFully(treeConn.getInputStream());
            if (TextUtils.isEmpty(treeJson)) {
                Log.w(TAG, "Empty OTA tree response");
                return null;
            }

            JSONObject root = new JSONObject(treeJson);
            JSONArray tree = root.optJSONArray("tree");
            if (tree == null || tree.length() == 0) {
                return new FetchResult(Collections.emptyList(),
                        trimToEmpty(treeConn.getHeaderField("ETag")),
                        trimToEmpty(treeConn.getHeaderField("Last-Modified")));
            }

            Map<String, AggregatedMaintainer> aggregate = new LinkedHashMap<>();

            for (int i = 0; i < tree.length(); i++) {
                JSONObject node = tree.optJSONObject(i);
                if (node == null) continue;

                String type = trimToEmpty(node.optString("type", ""));
                String path = trimToEmpty(node.optString("path", ""));
                if (!"blob".equals(type)) continue;
                if (!path.startsWith("builds/") || !path.endsWith(".json")) continue;

                String rawUrl = OTA_RAW_BASE + path;

                try {
                    String deviceJson = fetchStringFromUrl(rawUrl, false);
                    if (TextUtils.isEmpty(deviceJson)) continue;

                    JSONObject deviceRoot = new JSONObject(deviceJson);
                    JSONArray response = deviceRoot.optJSONArray("response");
                    if (response == null || response.length() == 0) continue;

                    JSONObject entry = response.optJSONObject(0);
                    if (entry == null) continue;
                    if (!entry.optBoolean("currently_maintained", false)) continue;

                    String maintainer = trimToEmpty(entry.optString("maintainer", ""));
                    String device = trimToEmpty(entry.optString("device", ""));
                    String github = trimToEmpty(entry.optString("github", ""));
                    String paypal = trimToEmpty(entry.optString("paypal", ""));

                    if (TextUtils.isEmpty(maintainer) || TextUtils.isEmpty(device)) continue;

                    String key = normalizeKey(maintainer, github);
                    AggregatedMaintainer bucket = aggregate.get(key);
                    if (bucket == null) {
                        bucket = new AggregatedMaintainer(maintainer, github, paypal);
                        aggregate.put(key, bucket);
                    } else if (TextUtils.isEmpty(bucket.paypal) && isValidHttpUrl(paypal)) {
                        bucket.paypal = paypal;
                    }

                    bucket.devices.add(device);
                } catch (Exception e) {
                    Log.d(TAG, "Failed parsing OTA JSON: " + path, e);
                }
            }

            List<MaintainerInfo> result = new ArrayList<>();
            for (AggregatedMaintainer m : aggregate.values()) {
                List<String> devices = new ArrayList<>(m.devices);
                devices.sort(String.CASE_INSENSITIVE_ORDER);

                String summary = TextUtils.join(", ", devices);
                String clickUrl = isValidHttpUrl(m.paypal)
                        ? m.paypal
                        : buildGithubUrlOrNull(m.github);

                result.add(new MaintainerInfo(m.maintainer, summary, m.github, clickUrl));
            }

            result.sort(Comparator.comparing(info -> info.maintainer.toLowerCase(Locale.ROOT)));

            return new FetchResult(
                    result,
                    trimToEmpty(treeConn.getHeaderField("ETag")),
                    trimToEmpty(treeConn.getHeaderField("Last-Modified")));
        } catch (Exception e) {
            Log.e(TAG, "Failed loading OTA maintainers", e);
            return null;
        } finally {
            if (treeConn != null) treeConn.disconnect();
        }
    }

    private String fetchStringFromUrl(String urlString, boolean githubApi) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (githubApi) {
                connection.setRequestProperty("Accept", "application/vnd.github+json");
            }
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return "";

            try (InputStream in = connection.getInputStream()) {
                return readFully(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readFully(InputStream in) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(in);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private List<MaintainerInfo> readCachedMaintainers() {
        String json = trimToEmpty(mPrefs.getString(PREF_ENTRIES_JSON, ""));
        if (json.isEmpty()) return Collections.emptyList();

        try {
            JSONArray arr = new JSONArray(json);
            List<MaintainerInfo> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String maintainer = trimToEmpty(obj.optString("maintainer", ""));
                String summary = trimToEmpty(obj.optString("summary", ""));
                String github = trimToEmpty(obj.optString("github", ""));
                String clickUrl = trimToEmpty(obj.optString("click_url", ""));
                if (maintainer.isEmpty()) continue;
                list.add(new MaintainerInfo(maintainer, summary, github, clickUrl));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse maintainer cache", e);
            return Collections.emptyList();
        }
    }

    private void writeMaintainersCache(List<MaintainerInfo> list, long updatedAt, String etag, String lastMod) {
        JSONArray arr = new JSONArray();
        for (MaintainerInfo i : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("maintainer", i.maintainer);
                obj.put("summary", i.summary);
                obj.put("github", i.github);
                obj.put("click_url", i.clickUrl);
            } catch (Exception ignored) {
            }
            arr.put(obj);
        }

        mPrefs.edit()
                .putString(PREF_ENTRIES_JSON, arr.toString())
                .putLong(PREF_LAST_UPDATED, updatedAt)
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                .putString(PREF_TREE_ETAG, trimToEmpty(etag))
                .putString(PREF_TREE_LAST_MOD, trimToEmpty(lastMod))
                .apply();
    }

    private static String normalizeKey(String maintainer, String github) {
        return trimToEmpty(maintainer).toLowerCase(Locale.ROOT) + "|"
                + trimToEmpty(github).toLowerCase(Locale.ROOT);
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isValidHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static String buildGithubUrlOrNull(String github) {
        String clean = trimToEmpty(github);
        return TextUtils.isEmpty(clean) ? null : "https://github.com/" + clean;
    }

    private boolean isUiSafe() {
        return !mDestroyed && isAdded() && getContext() != null;
    }

    private static final class AggregatedMaintainer {
        final String maintainer;
        final String github;
        String paypal;
        final LinkedHashSet<String> devices = new LinkedHashSet<>();

        AggregatedMaintainer(String maintainer, String github, String paypal) {
            this.maintainer = maintainer;
            this.github = github;
            this.paypal = isValidHttpUrl(paypal) ? paypal : "";
        }
    }

    private static final class MaintainerInfo {
        final String maintainer;
        final String summary;
        final String github;
        final String clickUrl;

        MaintainerInfo(String maintainer, String summary, String github, String clickUrl) {
            this.maintainer = maintainer;
            this.summary = summary;
            this.github = github;
            this.clickUrl = clickUrl;
        }
    }

    private static final class FetchResult {
        final List<MaintainerInfo> maintainers;
        final String treeEtag;
        final String treeLastModified;
        final boolean notModified;

        FetchResult(List<MaintainerInfo> maintainers, String treeEtag, String treeLastModified) {
            this.maintainers = maintainers;
            this.treeEtag = treeEtag;
            this.treeLastModified = treeLastModified;
            this.notModified = false;
        }

        private FetchResult(boolean notModified) {
            this.maintainers = Collections.emptyList();
            this.treeEtag = "";
            this.treeLastModified = "";
            this.notModified = notModified;
        }

        static FetchResult notModified() {
            return new FetchResult(true);
        }
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_about);
}
