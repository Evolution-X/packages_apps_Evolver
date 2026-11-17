/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.evolution.VibrationUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import lineageos.preference.SystemSettingMainSwitchPreference;

import org.evolution.settings.preferences.ExpressiveDialogs;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.preferences.WallpaperPreviewPreference;
import org.evolution.settings.utils.SystemUtils;

import org.json.JSONObject;

import java.io.File;

/**
 * Wallpaper Glymps settings (Evolver).
 *
 * v2 structure: one wallpaper source per target (lock / home), a taste and
 * filters section (topics, Wallhaven categories and dominant color, Wikimedia
 * Commons collection), "apply now", and an "about this wallpaper" entry that
 * reads the current wallpaper metadata written by LockGlympsService into
 * Settings.System lock_glymps_current. All v1 behaviors (custom URL dialog,
 * local folder dialog, timer visibility, cache management) are preserved.
 */
@SearchIndexable
public class LockGlympsSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockGlympsSettings";

    private static final String KEY_PREVIEW = "lock_glymps_preview";
    private static final String KEY_ENABLE = "lock_glymps_enabled";
    private static final String KEY_WALLPAPER_TARGET = "lock_glymps_wallpaper_target";
    private static final String KEY_PROVIDER_LOCK = "lock_glymps_provider_lock";
    private static final String KEY_PROVIDER_HOME = "lock_glymps_provider_home";
    private static final String KEY_TASTE = "lock_glymps_taste";
    private static final String KEY_CURRENT = "lock_glymps_current";
    private static final String KEY_CHANGE_ON = "lock_glymps_change_on";
    private static final String KEY_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String KEY_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String KEY_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String KEY_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String KEY_CLEAR_CACHE = "lock_glymps_clear_cache";
    private static final String KEY_APPLY_NOW = "lock_glymps_apply_now";
    private static final String KEY_FOLDER_INFO = "lock_glymps_folder_info";
    // UI-only taste keys (persisted inside the JSON blob, not Settings.System).
    private static final String KEY_TASTE_LOCK_TOPICS = "lock_glymps_taste_lock_topics";
    private static final String KEY_TASTE_LOCK_CATEGORIES = "lock_glymps_taste_lock_categories";
    private static final String KEY_TASTE_LOCK_COLOR = "lock_glymps_taste_lock_color";
    private static final String KEY_TASTE_LOCK_PRESET = "lock_glymps_taste_lock_preset";
    private static final String KEY_TASTE_HOME_TOPICS = "lock_glymps_taste_home_topics";
    private static final String KEY_TASTE_HOME_CATEGORIES = "lock_glymps_taste_home_categories";
    private static final String KEY_TASTE_HOME_COLOR = "lock_glymps_taste_home_color";
    private static final String KEY_TASTE_HOME_PRESET = "lock_glymps_taste_home_preset";

    private static final String STORAGE_FOLDER = "Glymps";
    private static final String JSON_LOCK = "lock";
    private static final String JSON_HOME = "home";
    private static final String KEY_LEGACY_SOURCE = "lock_glymps_source";

    private WallpaperPreviewPreference mPreviewPreference;
    private SystemSettingMainSwitchPreference mEnablePreference;
    private SystemSettingListPreference mWallpaperTargetPreference;
    private SystemSettingListPreference mProviderLockPreference;
    private SystemSettingListPreference mProviderHomePreference;
    private SystemSettingListPreference mChangeOnPreference;
    private SystemSettingListPreference mTimerIntervalPreference;
    private SystemSettingSwitchPreference mWifiOnlyPreference;
    private SystemSettingListPreference mCacheSizePreference;
    private Preference mCustomUrlsPreference;
    private Preference mClearCachePreference;
    private Preference mApplyNowPreference;
    private Preference mFolderInfoPreference;
    private Preference mCurrentInfoPreference;
    private Preference mTasteLockTopics;
    private ListPreference mTasteLockCategories;
    private ListPreference mTasteLockColor;
    private ListPreference mTasteLockPreset;
    private Preference mTasteHomeTopics;
    private ListPreference mTasteHomeCategories;
    private ListPreference mTasteHomeColor;
    private ListPreference mTasteHomePreset;

    private JSONObject mTaste;
    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getActivity();
        if (context == null) return;

        // Persist the migrated v1 source BEFORE the preference lists inflate,
        // so their default persisting cannot override a v1 user's choice.
        migrateLegacySource(context);
        addPreferencesFromResource(R.xml.lock_glymps_settings);

        mTaste = loadTaste(context);

        mPreviewPreference = findPreference(KEY_PREVIEW);

        mEnablePreference = findPreference(KEY_ENABLE);
        if (mEnablePreference != null) {
            mEnablePreference.setOnPreferenceChangeListener(this);
        }

        mWallpaperTargetPreference = findPreference(KEY_WALLPAPER_TARGET);
        if (mWallpaperTargetPreference != null) {
            mWallpaperTargetPreference.setOnPreferenceChangeListener(this);
        }

        mProviderLockPreference = findPreference(KEY_PROVIDER_LOCK);
        if (mProviderLockPreference != null) {
            mProviderLockPreference.setOnPreferenceChangeListener(this);
        }

        mProviderHomePreference = findPreference(KEY_PROVIDER_HOME);
        if (mProviderHomePreference != null) {
            mProviderHomePreference.setOnPreferenceChangeListener(this);
        }

        mChangeOnPreference = findPreference(KEY_CHANGE_ON);
        if (mChangeOnPreference != null) {
            mChangeOnPreference.setOnPreferenceChangeListener(this);
            String currentMode = mChangeOnPreference.getValue();
            if (currentMode != null) {
                updateTimerVisibility(currentMode);
            }
        }

        mTimerIntervalPreference = findPreference(KEY_TIMER_INTERVAL);
        if (mTimerIntervalPreference != null) {
            mTimerIntervalPreference.setOnPreferenceChangeListener(this);
        }

        mWifiOnlyPreference = findPreference(KEY_WIFI_ONLY);
        mCacheSizePreference = findPreference(KEY_CACHE_SIZE);

        mCustomUrlsPreference = findPreference(KEY_CUSTOM_URLS);
        if (mCustomUrlsPreference != null) {
            mCustomUrlsPreference.setOnPreferenceClickListener(pref -> {
                showCustomUrlsDialog();
                return true;
            });
        }

        mFolderInfoPreference = findPreference(KEY_FOLDER_INFO);
        if (mFolderInfoPreference != null) {
            mFolderInfoPreference.setOnPreferenceClickListener(pref -> {
                showFolderInfo();
                return true;
            });
        }

        mApplyNowPreference = findPreference(KEY_APPLY_NOW);
        if (mApplyNowPreference != null) {
            mApplyNowPreference.setOnPreferenceClickListener(pref -> {
                applyNow(getActivity());
                return true;
            });
        }

        mClearCachePreference = findPreference(KEY_CLEAR_CACHE);
        if (mClearCachePreference != null) {
            mClearCachePreference.setOnPreferenceClickListener(pref -> {
                clearCache();
                return true;
            });
        }

        mCurrentInfoPreference = findPreference(KEY_CURRENT);
        if (mCurrentInfoPreference != null) {
            mCurrentInfoPreference.setOnPreferenceClickListener(pref -> {
                showCurrentInfoDialog();
                return true;
            });
        }

        mTasteLockTopics = findPreference(KEY_TASTE_LOCK_TOPICS);
        if (mTasteLockTopics != null) {
            mTasteLockTopics.setOnPreferenceClickListener(pref -> {
                showTopicsDialog(JSON_LOCK);
                return true;
            });
        }

        mTasteLockCategories = bindTasteList(KEY_TASTE_LOCK_CATEGORIES, JSON_LOCK, "categories");
        mTasteLockColor = bindTasteList(KEY_TASTE_LOCK_COLOR, JSON_LOCK, "color");
        mTasteLockPreset = bindTasteList(KEY_TASTE_LOCK_PRESET, JSON_LOCK, "preset");

        mTasteHomeTopics = findPreference(KEY_TASTE_HOME_TOPICS);
        if (mTasteHomeTopics != null) {
            mTasteHomeTopics.setOnPreferenceClickListener(pref -> {
                showTopicsDialog(JSON_HOME);
                return true;
            });
        }

        mTasteHomeCategories = bindTasteList(KEY_TASTE_HOME_CATEGORIES, JSON_HOME, "categories");
        mTasteHomeColor = bindTasteList(KEY_TASTE_HOME_COLOR, JSON_HOME, "color");
        mTasteHomePreset = bindTasteList(KEY_TASTE_HOME_PRESET, JSON_HOME, "preset");

        updateProviderDependentPrefs();
        updateTasteSummaries();
        updateCurrentInfo();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Context context = getActivity();
        if (context == null) return false;

        String key = preference.getKey();

        if (KEY_ENABLE.equals(key)) {
            boolean enabled = (Boolean) newValue;

            Intent serviceIntent = new Intent();
            serviceIntent.setClassName("com.android.systemui",
                "com.android.systemui.lockglymps.LockGlympsService");

            if (enabled) {
                context.startService(serviceIntent);
            } else {
                context.stopService(serviceIntent);
            }

            SystemUtils.showSystemUiRestartDialog(context);
            return true;

        } else if (KEY_PROVIDER_LOCK.equals(key) || KEY_PROVIDER_HOME.equals(key)) {
            updateProviderDependentPrefs();
            notifyServiceToRefresh(context);
            SystemUtils.showSystemUiRestartDialog(context);
            return true;

        } else if (KEY_WALLPAPER_TARGET.equals(key)) {
            notifyServiceToRefresh(context);
            schedulePreviewRefresh();
            SystemUtils.showSystemUiRestartDialog(context);
            return true;

        } else if (KEY_CHANGE_ON.equals(key)) {
            updateTimerVisibility((String) newValue);
            notifyServiceToRefresh(context);
            SystemUtils.showSystemUiRestartDialog(context);
            return true;

        } else if (KEY_TIMER_INTERVAL.equals(key)) {
            notifyServiceToRefresh(context);
            return true;

        } else if (KEY_TASTE_LOCK_CATEGORIES.equals(key)) {
            setTasteValue(JSON_LOCK, "categories", (String) newValue);
            return true;

        } else if (KEY_TASTE_LOCK_COLOR.equals(key)) {
            setTasteValue(JSON_LOCK, "color", (String) newValue);
            return true;

        } else if (KEY_TASTE_LOCK_PRESET.equals(key)) {
            setTasteValue(JSON_LOCK, "preset", (String) newValue);
            return true;

        } else if (KEY_TASTE_HOME_CATEGORIES.equals(key)) {
            setTasteValue(JSON_HOME, "categories", (String) newValue);
            return true;

        } else if (KEY_TASTE_HOME_COLOR.equals(key)) {
            setTasteValue(JSON_HOME, "color", (String) newValue);
            return true;

        } else if (KEY_TASTE_HOME_PRESET.equals(key)) {
            setTasteValue(JSON_HOME, "preset", (String) newValue);
            return true;
        }

        notifyServiceToRefresh(context);
        return true;
    }

    private ListPreference bindTasteList(String key, String target, String field) {
        ListPreference preference = findPreference(key);
        if (preference != null) {
            preference.setPersistent(false);
            preference.setOnPreferenceChangeListener(this);
            String value = tasteString(target, field);
            // Only apply values that exist in the entry values; otherwise the
            // XML default (110 / "" / quality) stays in charge.
            if (preference.findIndexOfValue(value) >= 0) {
                preference.setValue(value);
            }
        }
        return preference;
    }

    private void setTasteValue(String target, String field, String value) {
        Context context = getActivity();
        if (context == null) return;
        JSONObject taste = tasteFor(target);
        try {
            taste.put(field, value == null ? "" : value);
        } catch (Exception e) {
            // ignored
        }
        saveTaste(context);
        updateTasteSummaries();
    }

    // ---- taste JSON helpers (shape: {"lock":{...},"home":{...}}) ----

    private JSONObject loadTaste(Context context) {
        try {
            String json = Settings.System.getString(context.getContentResolver(), KEY_TASTE);
            if (json != null && !json.isEmpty()) {
                return new JSONObject(json);
            }
        } catch (Exception e) {
            // fall through to a fresh object
        }
        return new JSONObject();
    }

    private void saveTaste(Context context) {
        if (mTaste == null) {
            mTaste = new JSONObject();
        }
        try {
            Settings.System.putString(context.getContentResolver(), KEY_TASTE, mTaste.toString());
        } catch (Exception e) {
            // ignored
        }
        notifyServiceToRefresh(context);
    }

    private JSONObject tasteFor(String target) {
        if (mTaste == null) {
            mTaste = new JSONObject();
        }
        JSONObject taste = mTaste.optJSONObject(target);
        if (taste == null) {
            taste = new JSONObject();
            try {
                mTaste.put(target, taste);
            } catch (Exception e) {
                // ignored
            }
        }
        return taste;
    }

    private String tasteString(String target, String field) {
        JSONObject taste = mTaste == null ? null : mTaste.optJSONObject(target);
        return taste == null ? "" : taste.optString(field, "");
    }

    private void updateTasteSummaries() {
        Context context = getActivity();
        if (context == null) return;

        String unset = getString(R.string.lock_glymps_taste_unset);
        if (mTasteLockTopics != null) {
            String topics = tasteString(JSON_LOCK, "topics");
            mTasteLockTopics.setSummary(topics.isEmpty() ? unset : topics);
        }
        if (mTasteHomeTopics != null) {
            String topics = tasteString(JSON_HOME, "topics");
            mTasteHomeTopics.setSummary(topics.isEmpty() ? unset : topics);
        }
    }

    private void showTopicsDialog(String target) {
        Context context = getActivity();
        if (context == null) return;

        String current = tasteString(target, "topics");

        ExpressiveDialogs.showTextInputDialog(
            context,
            getString(R.string.lock_glymps_taste_dialog_title),
            getString(R.string.lock_glymps_taste_dialog_message),
            current.replace(",", " "),
            getString(R.string.menu_save),
            getString(R.string.menu_cancel),
            true,
            false,
            5,
            10,
            getString(R.string.lock_glymps_taste_hint),
            inputText -> {
                Context ctx = getActivity();
                if (ctx == null) return;
                StringBuilder sb = new StringBuilder();
                for (String token : inputText.split("[,\\s]+")) {
                    String trimmed = token.trim();
                    if (trimmed.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(trimmed);
                }
                JSONObject taste = tasteFor(target);
                try {
                    taste.put("topics", sb.toString());
                } catch (Exception e) {
                    // ignored
                }
                saveTaste(ctx);
                updateTasteSummaries();
                android.widget.Toast.makeText(ctx,
                    R.string.lock_glymps_taste_saved,
                    android.widget.Toast.LENGTH_SHORT).show();
            });
    }

    // ---- provider-dependent visibility ----

    /**
     * Maps the v1 source (0=picsum, 1=custom URLs, 2=local folder) into the
     * per-target provider keys before the lists inflate. The engine keeps the
     * same mapping for users who never open this screen.
     */
    private void migrateLegacySource(Context context) {
        try {
            String lock = Settings.System.getString(
                    context.getContentResolver(), KEY_PROVIDER_LOCK);
            String home = Settings.System.getString(
                    context.getContentResolver(), KEY_PROVIDER_HOME);
            if (lock != null && home != null) {
                return; // both targets chosen already
            }
            int source = Settings.System.getInt(
                    context.getContentResolver(), KEY_LEGACY_SOURCE, 0);
            if (source == 0) {
                return; // v1 source 0 is the same picsum default
            }
            String provider = source == 1 ? "custom" : "local";
            if (lock == null) {
                Settings.System.putString(
                        context.getContentResolver(), KEY_PROVIDER_LOCK, provider);
            }
            if (home == null) {
                Settings.System.putString(
                        context.getContentResolver(), KEY_PROVIDER_HOME, provider);
            }
        } catch (Exception e) {
            // ignored — the engine's migrateProvider() covers this case too
        }
    }

    private String providerOf(String key) {
        SystemSettingListPreference preference =
                KEY_PROVIDER_LOCK.equals(key) ? mProviderLockPreference
                : mProviderHomePreference;
        if (preference != null && preference.getValue() != null) {
            return preference.getValue();
        }
        Context context = getActivity();
        if (context != null) {
            String stored = Settings.System.getString(
                    context.getContentResolver(), key);
            if (stored != null && !stored.isEmpty()) {
                return stored;
            }
        }
        return "picsum";
    }

    private static boolean isNetworkProvider(String id) {
        return "picsum".equals(id) || "wallhaven".equals(id)
                || "commons".equals(id) || "openverse".equals(id)
                || "custom".equals(id);
    }

    private void updateProviderDependentPrefs() {
        String lock = providerOf(KEY_PROVIDER_LOCK);
        String home = providerOf(KEY_PROVIDER_HOME);
        boolean anyNetwork = isNetworkProvider(lock) || isNetworkProvider(home);
        boolean anyCustom = "custom".equals(lock) || "custom".equals(home);
        boolean anyLocal = "local".equals(lock) || "local".equals(home);

        if (mWifiOnlyPreference != null) {
            mWifiOnlyPreference.setVisible(anyNetwork);
        }
        if (mCacheSizePreference != null) {
            mCacheSizePreference.setVisible(anyNetwork);
        }
        if (mClearCachePreference != null) {
            mClearCachePreference.setVisible(anyNetwork);
        }
        if (mCustomUrlsPreference != null) {
            mCustomUrlsPreference.setVisible(anyCustom);
        }
        if (mFolderInfoPreference != null) {
            mFolderInfoPreference.setVisible(anyLocal);
            if (anyLocal) {
                updateFolderInfo();
            }
        }

        boolean lockTaste = isTasteSource(lock);
        boolean homeTaste = isTasteSource(home);
        setVisible(mTasteLockTopics, lockTaste);
        setVisible(mTasteLockCategories, "wallhaven".equals(lock));
        setVisible(mTasteLockColor, "wallhaven".equals(lock));
        setVisible(mTasteLockPreset, "commons".equals(lock));
        setVisible(mTasteHomeTopics, homeTaste);
        setVisible(mTasteHomeCategories, "wallhaven".equals(home));
        setVisible(mTasteHomeColor, "wallhaven".equals(home));
        setVisible(mTasteHomePreset, "commons".equals(home));
    }

    private static boolean isTasteSource(String id) {
        return "wallhaven".equals(id) || "commons".equals(id) || "openverse".equals(id);
    }

    private static void setVisible(Preference preference, boolean visible) {
        if (preference != null) {
            preference.setVisible(visible);
        }
    }

    private void updateTimerVisibility(String changeOnValue) {
        if (mTimerIntervalPreference != null) {
            boolean showTimer = "2".equals(changeOnValue);
            mTimerIntervalPreference.setVisible(showTimer);
        }
    }

    private void schedulePreviewRefresh() {
        if (mHandler != null && mPreviewPreference != null) {
            mHandler.postDelayed(() -> {
                if (mPreviewPreference != null) {
                    mPreviewPreference.refreshPreviews();
                }
            }, 1500);
        }
    }

    // ---- current wallpaper info (Settings.System lock_glymps_current) ----

    private void updateCurrentInfo() {
        if (mCurrentInfoPreference == null) return;
        Context context = getActivity();
        if (context == null) return;

        JSONObject current = readCurrent(context);
        if (current == null) {
            mCurrentInfoPreference.setSummary(
                    getString(R.string.lock_glymps_current_summary_empty));
            return;
        }
        String provider = providerName(current.optString("provider"));
        String attribution = current.optString("attribution");
        if (attribution.isEmpty()) {
            mCurrentInfoPreference.setSummary(
                    getString(R.string.lock_glymps_current_summary_empty));
        } else {
            mCurrentInfoPreference.setSummary(
                    getString(R.string.lock_glymps_current_summary_format, provider, attribution));
        }
    }

    private JSONObject readCurrent(Context context) {
        try {
            String json = Settings.System.getString(
                    context.getContentResolver(), KEY_CURRENT);
            if (json != null && !json.isEmpty()) {
                JSONObject current = new JSONObject(json);
                if (current.optString("provider").isEmpty()
                        && current.optString("attribution").isEmpty()) {
                    return null;
                }
                return current;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    private String providerName(String id) {
        if (id == null || id.isEmpty() || "unknown".equals(id)) {
            return id == null ? "" : id;
        }
        String[] values = getResources().getStringArray(R.array.lock_glymps_provider_values);
        String[] entries = getResources().getStringArray(R.array.lock_glymps_provider_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(id)) {
                return i < entries.length ? entries[i] : id;
            }
        }
        return id;
    }

    private void showCurrentInfoDialog() {
        Context context = getActivity();
        if (context == null) return;

        JSONObject current = readCurrent(context);
        if (current == null) {
            ExpressiveDialogs.showConfirmDialog(
                context,
                getString(R.string.lock_glymps_current_title),
                getString(R.string.lock_glymps_current_summary_empty),
                getString(android.R.string.ok),
                null,
                true,
                null);
            return;
        }

        String provider = providerName(current.optString("provider"));
        String attribution = current.optString("attribution");
        String license = current.optString("license");
        String sourcePage = current.optString("sourcePage");
        long time = current.optLong("time", 0);
        String applied = time > 0
                ? DateUtils.getRelativeTimeSpanString(time).toString()
                : getString(R.string.lock_glymps_current_summary_empty);

        String detail = getString(R.string.lock_glymps_current_detail_format,
                provider,
                attribution.isEmpty() ? provider : attribution,
                license.isEmpty() ? provider : license,
                applied);

        if (sourcePage != null && !sourcePage.isEmpty()) {
            ExpressiveDialogs.showConfirmDialog(
                context,
                getString(R.string.lock_glymps_current_title),
                detail,
                getString(R.string.lock_glymps_current_open),
                getString(R.string.menu_cancel),
                true,
                () -> {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(sourcePage)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(context,
                            R.string.lock_glymps_current_no_page,
                            android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
        } else {
            ExpressiveDialogs.showConfirmDialog(
                context,
                getString(R.string.lock_glymps_current_title),
                detail,
                getString(android.R.string.ok),
                null,
                true,
                null);
        }
    }

    // ---- actions ----

    private void applyNow(Context context) {
        if (context == null) return;
        Intent intent = new Intent();
        intent.setClassName("com.android.systemui",
            "com.android.systemui.lockglymps.LockGlympsService");
        intent.setAction("APPLY_NOW");
        context.startService(intent);

        android.widget.Toast.makeText(context,
            R.string.lock_glymps_applying,
            android.widget.Toast.LENGTH_SHORT).show();
        schedulePreviewRefresh();
        if (mHandler != null) {
            mHandler.postDelayed(this::updateCurrentInfo, 3000);
        }
    }

    private void notifyServiceToRefresh(Context context) {
        Intent serviceIntent = new Intent();
        serviceIntent.setClassName("com.android.systemui",
            "com.android.systemui.lockglymps.LockGlympsService");
        serviceIntent.setAction("REFRESH_SETTINGS");
        context.startService(serviceIntent);
    }

    private void showCustomUrlsDialog() {
        Context context = getActivity();
        if (context == null) return;

        String urls = Settings.System.getString(context.getContentResolver(),
            "lock_glymps_custom_urls");

        ExpressiveDialogs.showTextInputDialog(
            context,
            getString(R.string.lock_glymps_custom_urls_dialog_title),
            getString(R.string.lock_glymps_custom_urls_dialog_message),
            urls != null ? urls.replace(",", "\n") : "",
            getString(R.string.menu_save),
            getString(R.string.menu_cancel),
            true,
            false,
            5,
            10,
            getString(R.string.lock_glymps_custom_urls_hint),
            inputText -> {
                Context ctx = getActivity();
                if (ctx == null) return;
                StringBuilder sb = new StringBuilder();

                for (String line : inputText.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(trimmed);
                    }
                }

                Settings.System.putString(ctx.getContentResolver(),
                    "lock_glymps_custom_urls", sb.toString());

                notifyServiceToRefresh(ctx);

                android.widget.Toast.makeText(ctx,
                    R.string.lock_glymps_custom_urls_saved,
                    android.widget.Toast.LENGTH_SHORT).show();
            });
    }

    private void showFolderInfo() {
        Context context = getActivity();
        if (context == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);
        String title = getString(R.string.lock_glymps_folder_dialog_title);

        if (!storageDir.exists()) {
            ExpressiveDialogs.showConfirmDialog(
                context,
                title,
                getString(R.string.lock_glymps_folder_missing_message, storageDir.getPath()),
                getString(R.string.lock_glymps_folder_create),
                getString(R.string.menu_cancel),
                true,
                () -> {
                    if (storageDir.mkdirs()) {
                        android.widget.Toast.makeText(context,
                            getString(R.string.lock_glymps_folder_created, storageDir.getPath()),
                            android.widget.Toast.LENGTH_LONG).show();
                        updateFolderInfo();
                    } else {
                        android.widget.Toast.makeText(context,
                            R.string.lock_glymps_folder_create_failed,
                            android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                       lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = (files != null) ? files.length : 0;

            ExpressiveDialogs.showConfirmDialog(
                context,
                title,
                getString(R.string.lock_glymps_folder_info_message, storageDir.getPath(), count),
                getString(android.R.string.ok),
                null,
                true,
                null);
        }
    }

    private void updateFolderInfo() {
        if (mFolderInfoPreference == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);

        if (!storageDir.exists()) {
            mFolderInfoPreference.setSummary(
                    getString(R.string.lock_glymps_folder_missing_short));
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                       lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = (files != null) ? files.length : 0;
            mFolderInfoPreference.setSummary(
                    getString(R.string.lock_glymps_folder_count_format, count,
                            storageDir.getPath()));
        }
    }

    private void clearCache() {
        Context context = getActivity();
        if (context == null) return;

        ExpressiveDialogs.showConfirmDialog(
            context,
            getString(R.string.lock_glymps_clear_cache_dialog_title),
            getString(R.string.lock_glymps_clear_cache_dialog_message),
            getString(R.string.lock_glymps_clear_cache_confirm),
            getString(R.string.menu_cancel),
            true,
            () -> {
                Intent intent = new Intent();
                intent.setClassName("com.android.systemui",
                    "com.android.systemui.lockglymps.LockGlympsService");
                intent.setAction("CLEAR_CACHE");
                context.startService(intent);

                android.widget.Toast.makeText(context,
                    R.string.lock_glymps_cache_cleared,
                    android.widget.Toast.LENGTH_SHORT).show();

                SystemUtils.showSystemUiRestartDialog(context);
            });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference != null && preference.getKey() != null) {
            VibrationUtils.triggerVibration(getContext(), 3);
        }
        return super.onPreferenceTreeClick(preference);
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.lock_glymps_settings);
}
