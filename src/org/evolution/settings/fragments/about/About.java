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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.PixelPropsUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.deviceinfo.DeviceNameUtils;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.HttpCachePrefs;
import org.evolution.settings.utils.NetworkUtils;
import org.evolution.settings.utils.UrlUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
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

    private static final String PREFS_NAME = "about_ota_maintainers_cache";
    private static final String PREF_ENTRIES_JSON = "entries_json";
    private static final String PREF_LAST_UPDATED = "last_updated";
    private static final String PREF_PAYPAL_JSON = "paypal_json";

    // Key used by HttpCachePrefs for the OTA tree conditional request
    private static final String CACHE_KEY_TREE = "ota_tree";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private GestureDetector mLongPressDetector;
    private GithubAvatarLoader mAvatarLoader;
    private RecyclerView.OnItemTouchListener mLongPressTouchListener;
    private SharedPreferences mPrefs;
    private HttpCachePrefs mTreeCache;
    private volatile boolean mDestroyed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_about);

        if (PixelPropsUtils.isCustomForkBuild()) {
            if (getPreferenceScreen() != null) {
                getPreferenceScreen().removeAll();
            }
            return;
        }

        mPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        mTreeCache = new HttpCachePrefs(mPrefs, CACHE_KEY_TREE);
        mAvatarLoader = GithubAvatarLoader.getInstance();

        Map<String, String> avatarMap = new LinkedHashMap<>();
        avatarMap.put("about_founder_1", "joeyhuab");
        avatarMap.put("about_founder_2", "AnierinBliss");
        avatarMap.put("about_founder_3", "RealAkito");
        avatarMap.put("about_member_1",  "TechPanelGM");
        avatarMap.put("about_member_2",  "AidanWarner97");
        avatarMap.put("about_member_3",  "Onelots");
        avatarMap.put("about_member_4",  "manidweep");
        avatarMap.put("about_member_5",  "apelete");

        for (Map.Entry<String, String> entry : avatarMap.entrySet()) {
            Preference pref = findPreference(entry.getKey());
            if (pref != null) {
                mAvatarLoader.load(requireContext(), pref, entry.getValue());
            }
        }

        setupTeamMemberPreferences(readCachedGithubToPaypal());
        loadCurrentMaintainers();
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        if (!mExecutor.isShutdown()) {
            mExecutor.shutdown();
        }
        // Do NOT shut down GithubAvatarLoader — it is a shared singleton.
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return false;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupCategoryLongPressRefresh();
    }

    @Override
    public void onDestroyView() {
        RecyclerView list = getListView();
        if (list != null && mLongPressTouchListener != null) {
            list.removeOnItemTouchListener(mLongPressTouchListener);
        }
        mLongPressTouchListener = null;
        mLongPressDetector = null;
        super.onDestroyView();
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

        List<MaintainerInfo> cached = readCachedMaintainers();
        long cachedUpdatedAt = mPrefs.getLong(PREF_LAST_UPDATED, 0L);
        if (!cached.isEmpty()) {
            populateMaintainerCategory(cached, cachedUpdatedAt);
            setupTeamMemberPreferences(readCachedGithubToPaypal());
        }

        boolean hasCache = !cached.isEmpty();
        if (hasCache && !mTreeCache.isStale()) {
            return;
        }

        mExecutor.execute(() -> {
            FetchResult result = fetchMaintainersFromOtaWithConditionalTree();
            if (result == null) {
                mTreeCache.touchLastCheck();
                return;
            }

            if (result.notModified) {
                mTreeCache.touchLastCheck();
                return;
            }

            long updatedAt = System.currentTimeMillis();
            writeMaintainersCache(result.maintainers, result.githubToPaypal, updatedAt);
            mTreeCache.write(result.treeEtag, result.treeLastModified);

            final List<MaintainerInfo> finalMaintainers = result.maintainers;
            final Map<String, String> finalGithubToPaypal = result.githubToPaypal;
            mMainHandler.post(() -> {
                if (!isUiSafe()) return;
                populateMaintainerCategory(finalMaintainers, updatedAt);
                setupTeamMemberPreferences(finalGithubToPaypal);
            });
        });
    }

    private void setupTeamMemberPreferences(Map<String, String> githubToPaypal) {
        Map<String, String> hardcodedDonate = new LinkedHashMap<>();
        hardcodedDonate.put("joeyhuab",     "https://linktr.ee/joeyhuab");
        hardcodedDonate.put("anierinbliss", "https://www.paypal.me/AnierinB");
        hardcodedDonate.put("realakito",    "https://t.me/RealAkito");
        hardcodedDonate.put("aidanwarner97","https://linktr.ee/aidanlw");
        hardcodedDonate.put("manidweep",    "https://paypal.me/manidreddy1");
        hardcodedDonate.put("apelete",      "https://www.linkedin.com/posts/apelete_evolutionx-activity-7429791532091797504-ND3i");

        Map<String, String> teamGithubMap = new LinkedHashMap<>();
        teamGithubMap.put("about_founder_1", "joeyhuab");
        teamGithubMap.put("about_founder_2", "AnierinBliss");
        teamGithubMap.put("about_founder_3", "RealAkito");
        teamGithubMap.put("about_member_1",  "TechPanelGM");
        teamGithubMap.put("about_member_2",  "AidanWarner97");
        teamGithubMap.put("about_member_3",  "Onelots");
        teamGithubMap.put("about_member_4",  "manidweep");
        teamGithubMap.put("about_member_5",  "apelete");

        for (Map.Entry<String, String> entry : teamGithubMap.entrySet()) {
            Preference pref = findPreference(entry.getKey());
            if (pref == null) continue;

            String github = entry.getValue();
            String key    = github.toLowerCase(Locale.ROOT);

            String donateUrl = githubToPaypal.containsKey(key)
                    ? githubToPaypal.get(key)
                    : hardcodedDonate.get(key);

            final String githubUrl   = UrlUtils.buildGithubUrl(github);
            final boolean hasGithub  = githubUrl != null;
            final boolean hasDonate  = UrlUtils.isValidHttpUrl(donateUrl);
            final String finalDonate = hasDonate ? donateUrl : null;

            pref.setSelectable(hasGithub || hasDonate);

            if (!hasGithub && !hasDonate) continue;

            if (hasGithub && hasDonate) {
                final String name = pref.getTitle() != null
                        ? pref.getTitle().toString() : github;
                pref.setOnPreferenceClickListener(p -> {
                    showMaintainerLinkDialog(name, githubUrl, finalDonate);
                    return true;
                });
            } else {
                final String only = hasGithub ? githubUrl : finalDonate;
                pref.setOnPreferenceClickListener(p -> {
                    openUrl(only);
                    return true;
                });
            }
        }
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

        for (MaintainerInfo info : maintainers) {
            Preference pref = createMaintainerPreference(info);
            category.addPreference(pref);

            if (!TextUtils.isEmpty(info.github)) {
                mAvatarLoader.load(requireContext(), pref, info.github);
            }
        }
    }

    private Preference createMaintainerPreference(MaintainerInfo info) {
        Preference pref = new Preference(requireContext());
        pref.setKey(buildMaintainerPreferenceKey(info));
        pref.setTitle(info.maintainer);
        pref.setSummary(info.summary);

        final String githubUrl = UrlUtils.buildGithubUrl(info.github);
        final String donateUrl = UrlUtils.isValidHttpUrl(info.donateUrl) ? info.donateUrl : null;
        final boolean hasGithub = githubUrl != null;
        final boolean hasDonate = donateUrl != null;
        final boolean hasAny    = hasGithub || hasDonate;

        pref.setSelectable(hasAny);

        if (!hasAny) {
            pref.setOnPreferenceClickListener(null);
            return pref;
        }

        if (hasGithub && hasDonate) {
            pref.setOnPreferenceClickListener(p -> {
                showMaintainerLinkDialog(info.maintainer, githubUrl, donateUrl);
                return true;
            });
        } else {
            final String only = hasGithub ? githubUrl : donateUrl;
            pref.setOnPreferenceClickListener(p -> {
                openUrl(only);
                return true;
            });
        }

        return pref;
    }

    private void showMaintainerLinkDialog(String maintainerName, String githubUrl, String donateUrl) {
        if (!isUiSafe()) return;
        CharSequence[] items = {
            getString(R.string.maintainer_link_github),
            getString(R.string.maintainer_link_donate),
        };
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(maintainerName)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) openUrl(githubUrl);
                    else            openUrl(donateUrl);
                })
                .show();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Unable to open URL: " + url, e);
        }
    }

    private String buildMaintainerPreferenceKey(MaintainerInfo info) {
        String base = UrlUtils.trimToEmpty(info.maintainer) + "|" + UrlUtils.trimToEmpty(info.github);
        return "current_maintainer_" + Integer.toHexString(base.hashCode());
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
        try {
            NetworkUtils.FetchResult r = NetworkUtils.fetchWithStatus(
                    OTA_TREE_API,
                    mTreeCache.buildHeaders("application/vnd.github+json"));

            if (r.isNotModified()) {
                return FetchResult.notModified();
            }
            if (!r.isOk() || r.bytes == null) {
                Log.w(TAG, "Tree API failed: HTTP " + r.statusCode);
                return null;
            }

            String treeJson = r.bodyAsString();
            if (TextUtils.isEmpty(treeJson)) {
                Log.w(TAG, "Empty OTA tree response");
                return null;
            }

            JSONObject root = new JSONObject(treeJson);
            JSONArray tree = root.optJSONArray("tree");
            if (tree == null || tree.length() == 0) {
                return new FetchResult(Collections.emptyList(),
                        Collections.emptyMap(),
                        r.etag,
                        r.lastModified);
            }

            Map<String, AggregatedMaintainer> aggregate = new LinkedHashMap<>();

            for (int i = 0; i < tree.length(); i++) {
                JSONObject node = tree.optJSONObject(i);
                if (node == null) continue;

                String type = UrlUtils.trimToEmpty(node.optString("type", ""));
                String path = UrlUtils.trimToEmpty(node.optString("path", ""));
                if (!"blob".equals(type)) continue;
                if (!path.startsWith("builds/") || !path.endsWith(".json")) continue;

                String rawUrl = OTA_RAW_BASE + path;

                try {
                    String deviceJson = NetworkUtils.fetchString(rawUrl, null);
                    if (TextUtils.isEmpty(deviceJson)) continue;

                    JSONObject deviceRoot = new JSONObject(deviceJson);
                    JSONArray response = deviceRoot.optJSONArray("response");
                    if (response == null || response.length() == 0) continue;

                    JSONObject entry = response.optJSONObject(0);
                    if (entry == null) continue;
                    if (!entry.optBoolean("currently_maintained", false)) continue;

                    String maintainer = UrlUtils.trimToEmpty(entry.optString("maintainer", ""));
                    String oem        = UrlUtils.trimToEmpty(entry.optString("oem", ""));
                    String device     = UrlUtils.trimToEmpty(entry.optString("device", ""));
                    String github     = UrlUtils.trimToEmpty(entry.optString("github", ""));
                    String paypal     = UrlUtils.trimToEmpty(entry.optString("paypal", ""));

                    if (TextUtils.isEmpty(maintainer) || TextUtils.isEmpty(device)) continue;

                    String deviceLabel = formatDeviceLabel(oem, device);
                    String key = normalizeKey(maintainer, github);
                    AggregatedMaintainer bucket = aggregate.get(key);
                    if (bucket == null) {
                        bucket = new AggregatedMaintainer(maintainer, github, paypal);
                        aggregate.put(key, bucket);
                    } else if (TextUtils.isEmpty(bucket.paypal) && UrlUtils.isValidHttpUrl(paypal)) {
                        bucket.paypal = paypal;
                    }

                    bucket.devices.add(deviceLabel);
                } catch (Exception e) {
                    Log.d(TAG, "Failed parsing OTA JSON: " + path, e);
                }
            }

            List<MaintainerInfo> result = new ArrayList<>();
            ArrayList<String> devicesTemp = new ArrayList<>();

            for (AggregatedMaintainer m : aggregate.values()) {
                devicesTemp.clear();
                devicesTemp.addAll(m.devices);
                devicesTemp.sort(String.CASE_INSENSITIVE_ORDER);

                String summary   = TextUtils.join(", ", devicesTemp);
                String donateUrl = UrlUtils.isValidHttpUrl(m.paypal) ? m.paypal : null;
                String clickUrl  = donateUrl != null ? donateUrl : UrlUtils.buildGithubUrl(m.github);

                result.add(new MaintainerInfo(m.maintainer, summary, m.github, donateUrl, clickUrl));
            }

            result.sort(Comparator.comparing(info -> info.maintainer.toLowerCase(Locale.ROOT)));

            Map<String, String> githubToPaypal = new LinkedHashMap<>();
            for (AggregatedMaintainer m : aggregate.values()) {
                if (!UrlUtils.trimToEmpty(m.github).isEmpty() && UrlUtils.isValidHttpUrl(m.paypal)) {
                    githubToPaypal.put(m.github.toLowerCase(Locale.ROOT), m.paypal);
                }
            }

            return new FetchResult(result, githubToPaypal, r.etag, r.lastModified);

        } catch (Exception e) {
            Log.e(TAG, "Failed loading OTA maintainers", e);
            return null;
        }
    }

    private List<MaintainerInfo> readCachedMaintainers() {
        String json = UrlUtils.trimToEmpty(mPrefs.getString(PREF_ENTRIES_JSON, ""));
        if (json.isEmpty()) return Collections.emptyList();

        try {
            JSONArray arr = new JSONArray(json);
            List<MaintainerInfo> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;

                String maintainer = UrlUtils.trimToEmpty(obj.optString("maintainer", ""));
                String summary    = UrlUtils.trimToEmpty(obj.optString("summary", ""));
                String github     = UrlUtils.trimToEmpty(obj.optString("github", ""));
                String clickUrl   = UrlUtils.trimToEmpty(obj.optString("click_url", ""));

                if (maintainer.isEmpty()) continue;
                if (summary.isEmpty() && clickUrl.isEmpty()) continue;

                String cachedDonate = clickUrl.contains("github.com") ? null : clickUrl;
                list.add(new MaintainerInfo(maintainer, summary, github, cachedDonate, clickUrl));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse maintainer cache", e);
            return Collections.emptyList();
        }
    }

    private void writeMaintainersCache(List<MaintainerInfo> list,
            Map<String, String> githubToPaypal, long updatedAt) {
        JSONArray arr = new JSONArray();
        for (MaintainerInfo i : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("maintainer", i.maintainer);
                obj.put("summary",    i.summary);
                obj.put("github",     i.github);
                obj.put("click_url",  i.clickUrl);
            } catch (Exception ignored) {}
            arr.put(obj);
        }

        JSONObject paypalObj = new JSONObject();
        for (Map.Entry<String, String> e : githubToPaypal.entrySet()) {
            try { paypalObj.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }

        mPrefs.edit()
                .putString(PREF_ENTRIES_JSON, arr.toString())
                .putString(PREF_PAYPAL_JSON,  paypalObj.toString())
                .putLong(PREF_LAST_UPDATED,   updatedAt)
                .apply();
    }

    private Map<String, String> readCachedGithubToPaypal() {
        String json = UrlUtils.trimToEmpty(mPrefs.getString(PREF_PAYPAL_JSON, ""));
        if (json.isEmpty()) return Collections.emptyMap();
        try {
            JSONObject obj = new JSONObject(json);
            Map<String, String> map = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                map.put(key, obj.getString(key));
            }
            return map;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static String formatDeviceLabel(String oem, String device) {
        String cleanDevice = UrlUtils.trimToEmpty(device);
        if (cleanDevice.isEmpty()) return "";
        return DeviceNameUtils.prefixIfNeeded(oem, device);
    }

    private static String normalizeKey(String maintainer, String github) {
        return UrlUtils.trimToEmpty(maintainer).toLowerCase(Locale.ROOT) + "|"
                + UrlUtils.trimToEmpty(github).toLowerCase(Locale.ROOT);
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
            this.github     = github;
            this.paypal     = UrlUtils.isValidHttpUrl(paypal) ? paypal : "";
        }
    }

    private static final class MaintainerInfo {
        final String maintainer;
        final String summary;
        final String github;
        final String donateUrl;
        final String clickUrl;

        MaintainerInfo(String maintainer, String summary, String github,
                       String donateUrl, String clickUrl) {
            this.maintainer = maintainer;
            this.summary    = summary;
            this.github     = github;
            this.donateUrl  = donateUrl;
            this.clickUrl   = clickUrl;
        }
    }

    private static final class FetchResult {
        final List<MaintainerInfo> maintainers;
        final Map<String, String>  githubToPaypal;
        final String               treeEtag;
        final String               treeLastModified;
        final boolean              notModified;

        FetchResult(List<MaintainerInfo> maintainers, Map<String, String> githubToPaypal,
                    String treeEtag, String treeLastModified) {
            this.maintainers      = maintainers;
            this.githubToPaypal   = githubToPaypal;
            this.treeEtag         = treeEtag;
            this.treeLastModified = treeLastModified;
            this.notModified      = false;
        }

        private FetchResult(boolean notModified) {
            this.maintainers      = Collections.emptyList();
            this.githubToPaypal   = Collections.emptyMap();
            this.treeEtag         = "";
            this.treeLastModified = "";
            this.notModified      = notModified;
        }

        static FetchResult notModified() {
            return new FetchResult(true);
        }
    }

    private void setupCategoryLongPressRefresh() {
        RecyclerView list = getListView();
        if (list == null) return;

        mLongPressDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        handleCategoryLongPress(list, e);
                    }
                });

        mLongPressTouchListener = new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                if (mLongPressDetector != null) {
                    mLongPressDetector.onTouchEvent(e);
                }
                return false;
            }
        };

        list.addOnItemTouchListener(mLongPressTouchListener);
    }

    private void handleCategoryLongPress(RecyclerView list, MotionEvent e) {
        View child = list.findChildViewUnder(e.getX(), e.getY());
        if (child == null) return;

        int position = list.getChildAdapterPosition(child);
        if (position == RecyclerView.NO_POSITION) return;

        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!(adapter instanceof PreferenceGroupAdapter)) return;

        Preference pref = ((PreferenceGroupAdapter) adapter).getItem(position);
        if (pref == null || !TextUtils.equals(KEY_CURRENT_MAINTAINERS, pref.getKey())) return;

        forceRefreshMaintainers();
    }

    private void forceRefreshMaintainers() {
        mTreeCache.invalidate();
        loadCurrentMaintainers();
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_about);
}
