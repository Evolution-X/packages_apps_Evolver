/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.evolution.VibrationUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import lineageos.preference.SystemSettingMainSwitchPreference;

import java.io.File;

import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.preferences.WallpaperPreviewPreference;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class LockGlympsSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockGlympsSettings";

    private static final String KEY_PREVIEW = "lock_glymps_preview";
    private static final String KEY_ENABLE = "lock_glymps_enabled";
    private static final String KEY_SOURCE = "lock_glymps_source";
    private static final String KEY_WALLPAPER_TARGET = "lock_glymps_wallpaper_target";
    private static final String KEY_CHANGE_ON = "lock_glymps_change_on";
    private static final String KEY_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String KEY_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String KEY_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String KEY_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String KEY_CLEAR_CACHE = "lock_glymps_clear_cache";
    private static final String KEY_FOLDER_INFO = "lock_glymps_folder_info";

    private static final String STORAGE_FOLDER = "Glymps";

    private WallpaperPreviewPreference mPreviewPreference;
    private SystemSettingMainSwitchPreference mEnablePreference;
    private SystemSettingListPreference mSourcePreference;
    private SystemSettingListPreference mWallpaperTargetPreference;
    private SystemSettingListPreference mChangeOnPreference;
    private SystemSettingListPreference mTimerIntervalPreference;
    private SystemSettingSwitchPreference mWifiOnlyPreference;
    private SystemSettingListPreference mCacheSizePreference;
    private Preference mCustomUrlsPreference;
    private Preference mClearCachePreference;
    private Preference mFolderInfoPreference;

    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.lock_glymps_settings);

        Context context = getActivity();
        if (context == null) return;

        mPreviewPreference = findPreference(KEY_PREVIEW);

        mEnablePreference = findPreference(KEY_ENABLE);
        if (mEnablePreference != null) {
            mEnablePreference.setOnPreferenceChangeListener(this);
        }

        mSourcePreference = findPreference(KEY_SOURCE);
        if (mSourcePreference != null) {
            mSourcePreference.setOnPreferenceChangeListener(this);
            String currentSource = mSourcePreference.getValue();
            if (currentSource != null) {
                updateSourceDependentPrefs(currentSource);
            }
        }

        mWallpaperTargetPreference = findPreference(KEY_WALLPAPER_TARGET);
        if (mWallpaperTargetPreference != null) {
            mWallpaperTargetPreference.setOnPreferenceChangeListener(this);
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
            updateFolderInfo();
            mFolderInfoPreference.setOnPreferenceClickListener(pref -> {
                showFolderInfo();
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

        } else if (KEY_SOURCE.equals(key)) {
            updateSourceDependentPrefs((String) newValue);
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
        }

        notifyServiceToRefresh(context);
        return true;
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

    private void updateTimerVisibility(String changeOnValue) {
        if (mTimerIntervalPreference != null) {
            boolean showTimer = "2".equals(changeOnValue);
            mTimerIntervalPreference.setVisible(showTimer);
        }
    }

    private void updateSourceDependentPrefs(String sourceValue) {
        boolean isOnlineSource = "0".equals(sourceValue) || "1".equals(sourceValue);
        boolean isCustomUrls = "1".equals(sourceValue);
        boolean isLocalFolder = "2".equals(sourceValue);

        if (mWifiOnlyPreference != null) {
            mWifiOnlyPreference.setVisible(isOnlineSource);
        }

        if (mCacheSizePreference != null) {
            mCacheSizePreference.setVisible(isOnlineSource);
        }

        if (mCustomUrlsPreference != null) {
            mCustomUrlsPreference.setVisible(isCustomUrls);
        }

        if (mFolderInfoPreference != null) {
            mFolderInfoPreference.setVisible(isLocalFolder);
            if (isLocalFolder) {
                updateFolderInfo();
            }
        }

        if (mClearCachePreference != null) {
            mClearCachePreference.setVisible(isOnlineSource);
        }
    }

    private void updateFolderInfo() {
        if (mFolderInfoPreference == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);

        if (!storageDir.exists()) {
            mFolderInfoPreference.setSummary("Folder not found. Tap to create.");
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                       lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = (files != null) ? files.length : 0;
            mFolderInfoPreference.setSummary(count + " wallpapers found in " + storageDir.getPath());
        }
    }

    private void showFolderInfo() {
        Context context = getActivity();
        if (context == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("Local Wallpaper Folder");

        if (!storageDir.exists()) {
            builder.setMessage("Folder does not exist yet.\n\nLocation: " + storageDir.getPath() +
                "\n\nWould you like to create it?");

            builder.setPositiveButton("Create Folder", (dialog, which) -> {
                if (storageDir.mkdirs()) {
                    android.widget.Toast.makeText(context,
                        "Folder created: " + storageDir.getPath(),
                        android.widget.Toast.LENGTH_LONG).show();
                    updateFolderInfo();
                } else {
                    android.widget.Toast.makeText(context,
                        "Failed to create folder",
                        android.widget.Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton("Cancel", null);
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                       lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = (files != null) ? files.length : 0;

            builder.setMessage("Folder location: " + storageDir.getPath() +
                "\n\nWallpapers found: " + count +
                "\n\nSupported formats: JPG, PNG, WEBP" +
                "\n\nPlace your wallpaper images in this folder and they will be used randomly.");

            builder.setPositiveButton("OK", null);
        }

        builder.show();
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

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("Custom Wallpaper URLs");
        builder.setMessage("Enter direct image URLs, one per line");

        final android.widget.EditText input = new android.widget.EditText(context);
        input.setText(urls != null ? urls.replace(",", "\n") : "");
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setHint("https://example.com/image1.jpg\nhttps://example.com/image2.png");

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String inputText = input.getText().toString();
            String[] lines = inputText.split("\n");
            StringBuilder sb = new StringBuilder();

            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(trimmed);
                }
            }

            Settings.System.putString(context.getContentResolver(),
                "lock_glymps_custom_urls", sb.toString());

            notifyServiceToRefresh(context);

            android.widget.Toast.makeText(context,
                "Custom URLs saved",
                android.widget.Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void clearCache() {
        Context context = getActivity();
        if (context == null) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("Clear Cache");
        builder.setMessage("This will delete all cached wallpapers and they will be re-downloaded. Continue?");

        builder.setPositiveButton("Clear", (dialog, which) -> {
            Intent intent = new Intent();
            intent.setClassName("com.android.systemui",
                "com.android.systemui.lockglymps.LockGlympsService");
            intent.setAction("CLEAR_CACHE");
            context.startService(intent);

            android.widget.Toast.makeText(context,
                "Cache cleared. New wallpapers will be downloaded.",
                android.widget.Toast.LENGTH_SHORT).show();
            
            SystemUtils.showSystemUiRestartDialog(context);
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
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
