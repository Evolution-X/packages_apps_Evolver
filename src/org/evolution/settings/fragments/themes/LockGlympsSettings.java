/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * Copyright (C) 2026 Evolution X contributors
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

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.evolution.VibrationUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import lineageos.preference.SystemSettingMainSwitchPreference;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.preferences.WallpaperPreviewPreference;

@SearchIndexable
public class LockGlympsSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockGlympsSettings";
    private static final String GLYMPS_CONTROL_PACKAGE = "com.android.systemui";
    private static final String GLYMPS_CONTROL_RECEIVER =
            "com.android.systemui.lockglymps.LockGlympsControlReceiver";
    private static final String ACTION_ENABLE =
            "com.android.systemui.lockglymps.action.ENABLE";
    private static final String ACTION_DISABLE =
            "com.android.systemui.lockglymps.action.DISABLE";
    private static final String ACTION_REFRESH_SETTINGS =
            "com.android.systemui.lockglymps.action.REFRESH_SETTINGS";
    private static final String ACTION_CLEAR_CACHE =
            "com.android.systemui.lockglymps.action.CLEAR_CACHE";
    private static final long SERVICE_REFRESH_DELAY_MS = 150L;
    private static final int MAX_CUSTOM_URLS = 100;
    private static final int MAX_CUSTOM_URL_LENGTH = 4096;
    private static final int MAX_API_KEY_LENGTH = 512;

    private static final String KEY_PREVIEW = "lock_glymps_preview";
    private static final String KEY_ENABLE = "lock_glymps_enabled";

    // New multi-source registry. Keep KEY_LEGACY_SOURCE synchronized until
    // SystemUI migrations no longer need the original 0/1/2 source selector.
    private static final String KEY_LEGACY_SOURCE = "lock_glymps_source";
    private static final String KEY_PROVIDERS = "lock_glymps_providers";
    private static final String KEY_CATEGORIES = "lock_glymps_categories";
    private static final String KEY_PROVIDER_STRATEGY = "lock_glymps_provider_strategy";
    private static final String KEY_API_KEYS = "lock_glymps_api_keys";

    private static final String KEY_WALLPAPER_TARGET = "lock_glymps_wallpaper_target";
    private static final String KEY_CHANGE_ON = "lock_glymps_change_on";
    private static final String KEY_TIMER_INTERVAL = "lock_glymps_timer_interval";
    private static final String KEY_WIFI_ONLY = "lock_glymps_wifi_only";
    private static final String KEY_CACHE_SIZE = "lock_glymps_cache_size";
    private static final String KEY_CUSTOM_URLS = "lock_glymps_custom_urls";
    private static final String KEY_CLEAR_CACHE = "lock_glymps_clear_cache";
    private static final String KEY_FOLDER_INFO = "lock_glymps_folder_info";

    private static final String KEY_PEXELS_API_KEY = "lock_glymps_pexels_api_key";
    private static final String KEY_UNSPLASH_API_KEY = "lock_glymps_unsplash_api_key";
    private static final String KEY_PIXABAY_API_KEY = "lock_glymps_pixabay_api_key";

    private static final String DEFAULT_PROVIDERS = "wallhaven";
    private static final String DEFAULT_CATEGORIES = "nature,amoled,space";

    private static final String PROVIDER_WALLHAVEN = "wallhaven";
    private static final String PROVIDER_PICSUM = "picsum";
    private static final String PROVIDER_PEXELS = "pexels";
    private static final String PROVIDER_UNSPLASH = "unsplash";
    private static final String PROVIDER_PIXABAY = "pixabay";
    private static final String PROVIDER_CUSTOM_URLS = "custom_urls";
    private static final String PROVIDER_LOCAL_FOLDER = "local_folder";

    private static final String STORAGE_FOLDER = "Glymps";

    private WallpaperPreviewPreference mPreviewPreference;
    private SystemSettingMainSwitchPreference mEnablePreference;
    private Preference mProvidersPreference;
    private Preference mCategoriesPreference;
    private Preference mApiKeysPreference;
    private SystemSettingListPreference mProviderStrategyPreference;
    private SystemSettingListPreference mWallpaperTargetPreference;
    private SystemSettingListPreference mChangeOnPreference;
    private SystemSettingListPreference mTimerIntervalPreference;
    private SystemSettingSwitchPreference mWifiOnlyPreference;
    private SystemSettingListPreference mCacheSizePreference;
    private Preference mCustomUrlsPreference;
    private Preference mClearCachePreference;
    private Preference mFolderInfoPreference;

    private Handler mHandler;
    private Runnable mPendingServiceRefresh;

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

        mProvidersPreference = findPreference(KEY_PROVIDERS);
        if (mProvidersPreference != null) {
            mProvidersPreference.setOnPreferenceClickListener(pref -> {
                showMultiSelectDialog(
                        KEY_PROVIDERS,
                        R.array.lock_glymps_provider_entries,
                        R.array.lock_glymps_provider_values,
                        R.string.lock_glymps_select_providers_title,
                        DEFAULT_PROVIDERS,
                        pref,
                        true);
                return true;
            });
        }

        mCategoriesPreference = findPreference(KEY_CATEGORIES);
        if (mCategoriesPreference != null) {
            mCategoriesPreference.setOnPreferenceClickListener(pref -> {
                showMultiSelectDialog(
                        KEY_CATEGORIES,
                        R.array.lock_glymps_category_entries,
                        R.array.lock_glymps_category_values,
                        R.string.lock_glymps_select_categories_title,
                        DEFAULT_CATEGORIES,
                        pref,
                        false);
                return true;
            });
        }

        mProviderStrategyPreference = findPreference(KEY_PROVIDER_STRATEGY);
        if (mProviderStrategyPreference != null) {
            mProviderStrategyPreference.setOnPreferenceChangeListener(this);
        }

        mApiKeysPreference = findPreference(KEY_API_KEYS);
        if (mApiKeysPreference != null) {
            mApiKeysPreference.setOnPreferenceClickListener(pref -> {
                showApiKeysDialog();
                return true;
            });
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
        if (mWifiOnlyPreference != null) {
            mWifiOnlyPreference.setOnPreferenceChangeListener(this);
        }

        mCacheSizePreference = findPreference(KEY_CACHE_SIZE);
        if (mCacheSizePreference != null) {
            mCacheSizePreference.setOnPreferenceChangeListener(this);
        }

        String[] refreshKeys = new String[] {
                "lock_glymps_min_resolution",
                "lock_glymps_orientation",
                "lock_glymps_tone",
                "lock_glymps_order",
                "lock_glymps_sfw_only",
                "lock_glymps_no_repeat"
        };
        for (String refreshKey : refreshKeys) {
            Preference refreshPreference = findPreference(refreshKey);
            if (refreshPreference != null) {
                refreshPreference.setOnPreferenceChangeListener(this);
            }
        }

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

        updateMultiSelectSummary(
                mProvidersPreference,
                KEY_PROVIDERS,
                DEFAULT_PROVIDERS,
                R.array.lock_glymps_provider_entries,
                R.array.lock_glymps_provider_values);
        updateMultiSelectSummary(
                mCategoriesPreference,
                KEY_CATEGORIES,
                DEFAULT_CATEGORIES,
                R.array.lock_glymps_category_entries,
                R.array.lock_glymps_category_values);
        updateProviderDependentPrefs();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Context context = getActivity();
        if (context == null) return false;

        String key = preference.getKey();

        if (KEY_ENABLE.equals(key)) {
            boolean enabled = (Boolean) newValue;

            // Persist first so SystemUI reads the new state during Service.onCreate().
            // The preference framework will persist the same value again after this
            // listener returns true.
            boolean persisted = Settings.System.putInt(
                    context.getContentResolver(),
                    KEY_ENABLE,
                    enabled ? 1 : 0);
            if (!persisted) {
                Log.e(TAG, "Unable to persist Wallpaper Glymps enabled state");
                Toast.makeText(
                        context,
                        R.string.lock_glymps_service_error,
                        Toast.LENGTH_SHORT).show();
                return false;
            }

            if (!sendGlympsCommand(context, enabled ? ACTION_ENABLE : ACTION_DISABLE)) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        KEY_ENABLE,
                        enabled ? 0 : 1);
                Toast.makeText(
                        context,
                        R.string.lock_glymps_service_error,
                        Toast.LENGTH_SHORT).show();
                return false;
            }

            if (enabled) {
                scheduleServiceRefresh(context);
            }
            return true;
        }

        if (KEY_WALLPAPER_TARGET.equals(key)) {
            scheduleServiceRefresh(context);
            schedulePreviewRefresh();
            return true;
        }

        if (KEY_CHANGE_ON.equals(key)) {
            updateTimerVisibility((String) newValue);
            scheduleServiceRefresh(context);
            return true;
        }

        // Preference listeners run before the preference framework commits the new
        // value. Defer the refresh slightly so SystemUI never races and reloads
        // the previous setting.
        scheduleServiceRefresh(context);
        return true;
    }

    private void showMultiSelectDialog(
            String settingKey,
            int entriesRes,
            int valuesRes,
            int titleRes,
            String defaultCsv,
            Preference summaryPreference,
            boolean providers) {
        Context context = getActivity();
        if (context == null) return;

        CharSequence[] entries = getResources().getTextArray(entriesRes);
        String[] values = getResources().getStringArray(valuesRes);
        Set<String> selected = readCsvSetting(settingKey, defaultCsv);
        boolean[] checked = new boolean[values.length];

        for (int i = 0; i < values.length; i++) {
            checked[i] = selected.contains(values[i]);
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setMultiChoiceItems(entries, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    LinkedHashSet<String> result = new LinkedHashSet<>();
                    for (int i = 0; i < values.length; i++) {
                        if (checked[i]) result.add(values[i]);
                    }

                    if (result.isEmpty()) {
                        Toast.makeText(context, R.string.lock_glymps_selection_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    boolean stored = Settings.System.putString(
                            context.getContentResolver(),
                            settingKey,
                            joinCsv(result));
                    if (!stored) {
                        Log.e(TAG, "Unable to persist Glymps multi-select setting: " + settingKey);
                        Toast.makeText(
                                context,
                                R.string.lock_glymps_service_error,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (providers) {
                        syncLegacySource(result);
                        updateProviderDependentPrefs();
                    }

                    updateMultiSelectSummary(
                            summaryPreference,
                            settingKey,
                            defaultCsv,
                            entriesRes,
                            valuesRes);
                    scheduleServiceRefresh(context);
                    schedulePreviewRefresh();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private Set<String> readCsvSetting(String key, String defaultValue) {
        Context context = getActivity();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (context == null) return values;

        String stored = Settings.System.getString(context.getContentResolver(), key);
        String csv = (stored == null || stored.trim().isEmpty()) ? defaultValue : stored;
        if (csv == null) return values;

        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values;
    }

    private String joinCsv(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private void updateMultiSelectSummary(
            Preference preference,
            String settingKey,
            String defaultCsv,
            int entriesRes,
            int valuesRes) {
        if (preference == null) return;

        Set<String> selected = readCsvSetting(settingKey, defaultCsv);
        CharSequence[] entries = getResources().getTextArray(entriesRes);
        String[] values = getResources().getStringArray(valuesRes);

        if (selected.size() <= 3) {
            StringBuilder labels = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (!selected.contains(values[i])) continue;
                if (labels.length() > 0) labels.append(" • ");
                labels.append(entries[i]);
            }
            if (labels.length() > 0) {
                preference.setSummary(labels);
                return;
            }
        }

        preference.setSummary(getString(R.string.lock_glymps_selected_count, selected.size()));
    }

    private void syncLegacySource(Set<String> providers) {
        Context context = getActivity();
        if (context == null) return;

        int legacySource = 0;
        if (providers.size() == 1 && providers.contains(PROVIDER_CUSTOM_URLS)) {
            legacySource = 1;
        } else if (providers.size() == 1 && providers.contains(PROVIDER_LOCAL_FOLDER)) {
            legacySource = 2;
        }

        Settings.System.putInt(context.getContentResolver(), KEY_LEGACY_SOURCE, legacySource);
    }

    private void updateProviderDependentPrefs() {
        Set<String> providers = readCsvSetting(KEY_PROVIDERS, DEFAULT_PROVIDERS);

        boolean hasCustomUrls = providers.contains(PROVIDER_CUSTOM_URLS);
        boolean hasLocalFolder = providers.contains(PROVIDER_LOCAL_FOLDER);
        boolean needsApiKey = providers.contains(PROVIDER_PEXELS)
                || providers.contains(PROVIDER_UNSPLASH)
                || providers.contains(PROVIDER_PIXABAY);
        boolean hasOnlineProvider = providers.contains(PROVIDER_WALLHAVEN)
                || providers.contains(PROVIDER_PICSUM)
                || providers.contains(PROVIDER_PEXELS)
                || providers.contains(PROVIDER_UNSPLASH)
                || providers.contains(PROVIDER_PIXABAY)
                || hasCustomUrls;

        if (mCustomUrlsPreference != null) {
            mCustomUrlsPreference.setVisible(hasCustomUrls);
        }

        if (mFolderInfoPreference != null) {
            mFolderInfoPreference.setVisible(hasLocalFolder);
            if (hasLocalFolder) updateFolderInfo();
        }

        if (mApiKeysPreference != null) {
            mApiKeysPreference.setVisible(needsApiKey);
        }

        if (mWifiOnlyPreference != null) {
            mWifiOnlyPreference.setVisible(hasOnlineProvider);
        }

        if (mCacheSizePreference != null) {
            mCacheSizePreference.setVisible(hasOnlineProvider);
        }

        if (mClearCachePreference != null) {
            mClearCachePreference.setVisible(hasOnlineProvider);
        }
    }

    private void showApiKeysDialog() {
        Context context = getActivity();
        if (context == null) return;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        EditText pexels = createApiKeyField(
                context,
                R.string.lock_glymps_pexels_key_hint,
                Settings.Secure.getString(context.getContentResolver(), KEY_PEXELS_API_KEY));
        EditText unsplash = createApiKeyField(
                context,
                R.string.lock_glymps_unsplash_key_hint,
                Settings.Secure.getString(context.getContentResolver(), KEY_UNSPLASH_API_KEY));
        EditText pixabay = createApiKeyField(
                context,
                R.string.lock_glymps_pixabay_key_hint,
                Settings.Secure.getString(context.getContentResolver(), KEY_PIXABAY_API_KEY));

        container.addView(pexels);
        container.addView(unsplash);
        container.addView(pixabay);

        new AlertDialog.Builder(context)
                .setTitle(R.string.lock_glymps_api_keys_dialog_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String oldPexels = Settings.Secure.getString(
                            context.getContentResolver(), KEY_PEXELS_API_KEY);
                    String oldUnsplash = Settings.Secure.getString(
                            context.getContentResolver(), KEY_UNSPLASH_API_KEY);
                    String oldPixabay = Settings.Secure.getString(
                            context.getContentResolver(), KEY_PIXABAY_API_KEY);

                    String newPexels = sanitizeApiKey(pexels.getText().toString());
                    String newUnsplash = sanitizeApiKey(unsplash.getText().toString());
                    String newPixabay = sanitizeApiKey(pixabay.getText().toString());

                    boolean pexelsStored = Settings.Secure.putString(
                            context.getContentResolver(), KEY_PEXELS_API_KEY, newPexels);
                    boolean unsplashStored = Settings.Secure.putString(
                            context.getContentResolver(), KEY_UNSPLASH_API_KEY, newUnsplash);
                    boolean pixabayStored = Settings.Secure.putString(
                            context.getContentResolver(), KEY_PIXABAY_API_KEY, newPixabay);

                    if (!pexelsStored || !unsplashStored || !pixabayStored) {
                        Log.e(TAG, "Unable to persist all Glymps API keys; rolling back");
                        Settings.Secure.putString(
                                context.getContentResolver(), KEY_PEXELS_API_KEY, oldPexels);
                        Settings.Secure.putString(
                                context.getContentResolver(), KEY_UNSPLASH_API_KEY, oldUnsplash);
                        Settings.Secure.putString(
                                context.getContentResolver(), KEY_PIXABAY_API_KEY, oldPixabay);
                        Toast.makeText(
                                context,
                                R.string.lock_glymps_service_error,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    scheduleServiceRefresh(context);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText createApiKeyField(Context context, int hintRes, String value) {
        EditText field = new EditText(context);
        field.setHint(hintRes);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (value != null) field.setText(value);
        return field;
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
            mTimerIntervalPreference.setVisible("2".equals(changeOnValue));
        }
    }

    private void updateFolderInfo() {
        if (mFolderInfoPreference == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);

        if (!storageDir.exists()) {
            mFolderInfoPreference.setSummary("Folder not found. Tap to create.");
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = files != null ? files.length : 0;
            mFolderInfoPreference.setSummary(
                    count + " wallpapers found in " + storageDir.getPath());
        }
    }

    private void showFolderInfo() {
        Context context = getActivity();
        if (context == null) return;

        File storageDir = new File(Environment.getExternalStorageDirectory(), STORAGE_FOLDER);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Local Wallpaper Folder");

        if (!storageDir.exists()) {
            builder.setMessage("Folder does not exist yet.\n\nLocation: " + storageDir.getPath()
                    + "\n\nWould you like to create it?");

            builder.setPositiveButton("Create Folder", (dialog, which) -> {
                if (storageDir.mkdirs()) {
                    Toast.makeText(context,
                            "Folder created: " + storageDir.getPath(),
                            Toast.LENGTH_LONG).show();
                    updateFolderInfo();
                } else {
                    Toast.makeText(context,
                            "Failed to create folder",
                            Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton(android.R.string.cancel, null);
        } else {
            File[] files = storageDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp");
            });

            int count = files != null ? files.length : 0;

            builder.setMessage("Folder location: " + storageDir.getPath()
                    + "\n\nWallpapers found: " + count
                    + "\n\nSupported formats: JPG, PNG, WEBP"
                    + "\n\nPlace your wallpaper images in this folder and they will be used randomly.");

            builder.setPositiveButton(android.R.string.ok, null);
        }

        builder.show();
    }

    private void scheduleServiceRefresh(Context context) {
        if (context == null) return;

        Context appContext = context.getApplicationContext();
        if (mHandler == null) {
            notifyServiceToRefresh(appContext);
            return;
        }

        if (mPendingServiceRefresh != null) {
            mHandler.removeCallbacks(mPendingServiceRefresh);
        }

        mPendingServiceRefresh = () -> {
            notifyServiceToRefresh(appContext);
            mPendingServiceRefresh = null;
        };
        mHandler.postDelayed(mPendingServiceRefresh, SERVICE_REFRESH_DELAY_MS);
    }

    private void notifyServiceToRefresh(Context context) {
        sendGlympsCommand(context, ACTION_REFRESH_SETTINGS);
    }

    private boolean sendGlympsCommand(Context context, String action) {
        if (context == null || action == null) return false;

        ComponentName component = new ComponentName(
                GLYMPS_CONTROL_PACKAGE,
                GLYMPS_CONTROL_RECEIVER);

        try {
            context.getPackageManager().getReceiverInfo(component, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Wallpaper Glymps control receiver is unavailable", e);
            return false;
        }

        Intent commandIntent = new Intent(action);
        commandIntent.setComponent(component);

        try {
            context.sendBroadcast(commandIntent);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to send Wallpaper Glymps command: " + action, e);
            return false;
        }
    }

    private void showCustomUrlsDialog() {
        Context context = getActivity();
        if (context == null) return;

        String urls = readAndMigrateCustomUrls(context);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Custom Wallpaper URLs");
        builder.setMessage("Enter direct HTTP or HTTPS image URLs, one per line");

        final EditText input = new EditText(context);
        input.setText(formatCustomUrlsForEditor(urls));
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setHint("https://example.com/image1.jpg\nhttps://example.com/image2.png");

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        builder.setView(input);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String[] lines = input.getText().toString().split("\\r?\\n");
            StringBuilder result = new StringBuilder();
            int invalidCount = 0;
            int acceptedCount = 0;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (acceptedCount >= MAX_CUSTOM_URLS
                        || trimmed.length() > MAX_CUSTOM_URL_LENGTH
                        || !isValidHttpUrl(trimmed)) {
                    invalidCount++;
                    continue;
                }

                if (result.length() > 0) result.append('\n');
                result.append(trimmed);
                acceptedCount++;
            }

            boolean stored = Settings.Secure.putString(
                    context.getContentResolver(),
                    KEY_CUSTOM_URLS,
                    result.toString());
            if (!stored) {
                Log.e(TAG, "Unable to persist custom Wallpaper Glymps URLs");
                Toast.makeText(
                        context,
                        R.string.lock_glymps_service_error,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (invalidCount > 0) {
                Toast.makeText(
                        context,
                        getString(R.string.lock_glymps_invalid_urls_ignored, invalidCount),
                        Toast.LENGTH_LONG).show();
            }

            scheduleServiceRefresh(context);
        });

        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private String formatCustomUrlsForEditor(String storedUrls) {
        if (storedUrls == null || storedUrls.trim().isEmpty()) {
            return "";
        }

        // New format is newline-delimited. For the old comma-delimited format,
        // split only when a comma is followed by another HTTP(S) URL so commas
        // inside a valid URL remain untouched.
        if (storedUrls.indexOf('\n') >= 0) {
            return storedUrls;
        }
        return storedUrls.replaceAll(",(?=\\s*https?://)", "\n");
    }

    private String readAndMigrateCustomUrls(Context context) {
        String secure = Settings.Secure.getString(
                context.getContentResolver(), KEY_CUSTOM_URLS);
        if (secure != null) {
            return secure;
        }

        String legacy = Settings.System.getString(
                context.getContentResolver(), KEY_CUSTOM_URLS);
        String migrated = legacy == null ? "" : legacy;

        // Custom network endpoints are security-sensitive. Migrate them out of
        // Settings.System (which third-party apps can be allowed to modify) into
        // Settings.Secure, then remove the legacy value.
        try {
            boolean stored = Settings.Secure.putString(
                    context.getContentResolver(), KEY_CUSTOM_URLS, migrated);
            if (stored) {
                Settings.System.putString(
                        context.getContentResolver(), KEY_CUSTOM_URLS, null);
            } else {
                Log.w(TAG, "Unable to migrate custom wallpaper URLs to Settings.Secure");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to migrate custom wallpaper URLs", e);
        }
        return migrated;
    }

    private boolean isValidHttpUrl(String value) {
        if (value == null || value.length() > MAX_CUSTOM_URL_LENGTH) {
            return false;
        }

        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();

        return host != null
                && !host.isEmpty()
                && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
    }

    private String sanitizeApiKey(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= MAX_API_KEY_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_API_KEY_LENGTH);
    }

    private void clearCache() {
        Context context = getActivity();
        if (context == null) return;

        new AlertDialog.Builder(context)
                .setTitle("Clear Cache")
                .setMessage("This will delete all cached wallpapers and they will be re-downloaded. Continue?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    if (!sendGlympsCommand(context, ACTION_CLEAR_CACHE)) {
                        Toast.makeText(
                                context,
                                R.string.lock_glymps_service_error,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Toast.makeText(context,
                            "Cache cleared. New wallpapers will be downloaded.",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            if (mPendingServiceRefresh != null) {
                mHandler.removeCallbacks(mPendingServiceRefresh);
                mPendingServiceRefresh = null;
            }
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
