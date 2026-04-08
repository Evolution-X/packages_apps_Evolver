/*
 * Copyright (C) 2019-2025 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.provider.Settings;
import android.util.ArraySet;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.evolution.settings.preferences.KeyboxDataPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SpoofingUtils;

import org.json.JSONException;
import org.json.JSONObject;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_PIF_JSON_FILE_PREFERENCE = "pif_json_file_preference";
    private static final String KEY_SYSTEM_WIDE_CATEGORY = "spoofing_system_wide_category";
    private static final String KEY_UPDATE_JSON_BUTTON = "update_pif_json";
    private static final String KEY_RANDOM_PROPERTIES_BUTTON = "update_pif_auto_random";
    private static final String PI_ENABLE_SPOOF = "pi_enable_spoof";
    private static final String PI_VENDING_SPOOF = "pi_vending_spoof";
    private static final String PI_PP_SPOOF = "pi_pp_spoof";
    private static final String PI_PHOTOS_SPOOF = "pi_photos_spoof";
    private static final String PI_SNAPCHAT_SPOOF = "pi_snapchat_spoof";
    private static final String PI_TENSOR_SPOOF = "pi_tensor_spoof";
    private static final String KEYBOX_DATA_KEY = "keybox_data_setting";

    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String SNAPCHAT_PACKAGE = "com.snapchat.android";
    private static final String VENDING_PACKAGE = "com.android.vending";

    private static final int MAX_PROP_VALUE_LENGTH = 91;

    private static final int NETWORK_TIMEOUT_MS = 10_000;

    private static final ArraySet<String> MAINLINE_TENSOR = new ArraySet<>(Arrays.asList(
            "stallion", "blazer", "frankel", "mustang", "comet", "komodo", "caiman", "tokay",
            "akita", "husky", "shiba"
    ));

    private static final ArraySet<String> TENSOR = new ArraySet<>(Arrays.asList(
            "stallion", "blazer", "frankel", "mustang", "tegu", "comet", "komodo", "caiman",
            "tokay", "akita", "husky", "shiba", "felix", "tangorpro", "lynx", "cheetah",
            "panther", "bluejay", "oriole", "raven"
    ));

    static {
        if (!TENSOR.containsAll(MAINLINE_TENSOR)) {
            throw new IllegalStateException(
                    "MAINLINE_TENSOR contains devices not present in TENSOR. "
                    + "Please update the TENSOR set.");
        }
    }

    private static final boolean IS_MAINLINE_TENSOR;
    private static final boolean IS_TENSOR;

    static {
        final String device = SystemProperties.get("ro.evolution.device", "");
        if (device.isEmpty()) {
            Log.w(TAG, "ro.evolution.device is empty; Tensor spoofing controls will be hidden.");
        }
        IS_MAINLINE_TENSOR = MAINLINE_TENSOR.contains(device);
        IS_TENSOR = TENSOR.contains(device);
    }

    private ExecutorService mExecutor;

    private ActivityResultLauncher<Intent> mKeyboxFilePickerLauncher;
    private ActivityResultLauncher<Intent> mJsonFilePickerLauncher;
    private KeyboxDataPreference mKeyboxDataPreference;
    private Preference mPifJsonFilePreference;
    private Preference mUpdateJsonButton;
    private Preference mRandomPropertiesButton;
    private PreferenceCategory mSystemWideCategory;
    private SwitchPreferenceCompat mGmsSpoof;
    private SwitchPreferenceCompat mVendingSpoof;
    private SwitchPreferenceCompat mGoogleSpoof;
    private SwitchPreferenceCompat mPhotosSpoof;
    private SwitchPreferenceCompat mSnapchatSpoof;
    private SwitchPreferenceCompat mTensorSpoof;

    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
        addPreferencesFromResource(R.xml.spoofing);

        final Context context = getContext();
        if (context == null) return;

        final ContentResolver resolver = context.getContentResolver();

        mSystemWideCategory = (PreferenceCategory) findPreference(KEY_SYSTEM_WIDE_CATEGORY);
        mPhotosSpoof = (SwitchPreferenceCompat) findPreference(PI_PHOTOS_SPOOF);
        mGmsSpoof = (SwitchPreferenceCompat) findPreference(PI_ENABLE_SPOOF);
        mVendingSpoof = (SwitchPreferenceCompat) findPreference(PI_VENDING_SPOOF);
        mGoogleSpoof = (SwitchPreferenceCompat) findPreference(PI_PP_SPOOF);
        mPifJsonFilePreference = findPreference(KEY_PIF_JSON_FILE_PREFERENCE);
        mSnapchatSpoof = (SwitchPreferenceCompat) findPreference(PI_SNAPCHAT_SPOOF);
        mTensorSpoof = (SwitchPreferenceCompat) findPreference(PI_TENSOR_SPOOF);
        mUpdateJsonButton = findPreference(KEY_UPDATE_JSON_BUTTON);
        mRandomPropertiesButton = findPreference(KEY_RANDOM_PROPERTIES_BUTTON);

        boolean isPixelGmsEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_ENABLE_SPOOF, 1) == 1;
        boolean isTensorEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_TENSOR_SPOOF, 0) == 1;
        boolean isVendingEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_VENDING_SPOOF, 0) == 1;
        boolean isPhotosEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
        boolean isSnapchatEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
        boolean isGoogleSpoofEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_PP_SPOOF, 0) == 1;

        if (mGoogleSpoof != null) {
            if (DeviceUtils.isCurrentlySupportedPixel()) {
                mGoogleSpoof.setDefaultValue(false);
                if (IS_MAINLINE_TENSOR) {
                    mSystemWideCategory.removePreference(mGoogleSpoof);
                }
            }
            mGoogleSpoof.setChecked(isGoogleSpoofEnabled);
            mGoogleSpoof.setOnPreferenceChangeListener(this);
        }

        if (mTensorSpoof != null) {
            if (!IS_TENSOR) {
                mTensorSpoof.setChecked(isTensorEnabled);
                mTensorSpoof.setOnPreferenceChangeListener(this);
            } else {
                mSystemWideCategory.removePreference(mTensorSpoof);
            }
        }

        mGmsSpoof.setChecked(isPixelGmsEnabled);
        mGmsSpoof.setOnPreferenceChangeListener(this);
        mVendingSpoof.setChecked(isVendingEnabled);
        mVendingSpoof.setOnPreferenceChangeListener(this);
        mPhotosSpoof.setChecked(isPhotosEnabled);
        mPhotosSpoof.setOnPreferenceChangeListener(this);
        mSnapchatSpoof.setChecked(isSnapchatEnabled);
        mSnapchatSpoof.setOnPreferenceChangeListener(this);

        mKeyboxFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    Preference pref = findPreference(KEYBOX_DATA_KEY);
                    if (pref instanceof KeyboxDataPreference) {
                        ((KeyboxDataPreference) pref).handleFileSelected(uri);
                    }
                }
            }
        );

        mJsonFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        loadPifJson(uri);
                    }
                }
            }
        );

        mPifJsonFilePreference.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/json");
            mJsonFilePickerLauncher.launch(intent);
            return true;
        });

        mUpdateJsonButton.setOnPreferenceClickListener(preference -> {
            updatePropertiesFromUrl("https://raw.githubusercontent.com/Evolution-X/.github/refs/heads/main/profile/pif.json");
            return true;
        });

        mRandomPropertiesButton.setOnPreferenceClickListener(preference -> {
            getRandomFingerprint();
            return true;
        });

        Preference showPropertiesPref = findPreference("show_pif_properties");
        if (showPropertiesPref != null) {
            showPropertiesPref.setOnPreferenceClickListener(preference -> {
                showPropertiesDialog();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mKeyboxDataPreference = findPreference(KEYBOX_DATA_KEY);
        if (mKeyboxDataPreference != null) {
            mKeyboxDataPreference.setFilePickerLauncher(mKeyboxFilePickerLauncher);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdownNow();
        }
    }

    private void showPropertiesDialog() {
        StringBuilder properties = new StringBuilder();
        try {
            JSONObject jsonObject = new JSONObject();
            String[] keys = {
                "persist.sys.pihooks_ID",
                "persist.sys.pihooks_BRAND",
                "persist.sys.pihooks_DEVICE",
                "persist.sys.pihooks_FINGERPRINT",
                "persist.sys.pihooks_MANUFACTURER",
                "persist.sys.pihooks_MODEL",
                "persist.sys.pihooks_PRODUCT",
                "persist.sys.pihooks_SECURITY_PATCH",
                "persist.sys.pihooks_DEVICE_INITIAL_SDK_INT",
                "persist.sys.pihooks_RELEASE",
                "persist.sys.pihooks_SDK_INT"
            };
            for (String key : keys) {
                String value = SystemProperties.get(key, null);
                if (value != null) {
                    String buildKey = key.replace("persist.sys.pihooks_", "");
                    jsonObject.put(buildKey, value);
                }
            }
            properties.append(jsonObject.toString(4));
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON from properties", e);
            properties.append(getString(R.string.error_loading_properties));
        }
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.show_pif_properties_title)
            .setMessage(properties.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void killPackage(String pkg) {
        try {
            Context context = getContext();
            if (context == null) return;

            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            am.forceStopPackage(pkg);
            Log.i(TAG, pkg + " process killed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill package: " + pkg, e);
        }
    }

    private void killVending() {
        killPackage(VENDING_PACKAGE);
    }

    /**
     * Kill packages that need to be restarted to pick up new PIF properties.
     */
    private void killGMSPackages() {
        String[] packages = {
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox"
        };
        for (String pkg : packages) {
            killPackage(pkg);
        }
        killVending();
    }

    /**
     * Kill a curated set of Google packages so they pick up new spoofed properties.
     *
     * Fix #10: Replaced the broad "kill all com.google.*" approach with an explicit
     * allowlist to avoid disrupting unrelated system services (e.g. TTS, IME, etc).
     * Fix #11: Removed the redundant trailing killVending() call — the vending package
     * is already included in the list below.
     */
    private void killGooglePackages() {
        String[] packages = {
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.photos",
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.youtube",
            "com.google.android.gm",
            "com.google.android.maps",
            VENDING_PACKAGE
        };
        for (String pkg : packages) {
            killPackage(pkg);
        }
    }

    /**
     * Fix #6: Sanitize a property value before passing it to SystemProperties.set().
     * Returns null if the value is blank or suspiciously long, so callers can skip it.
     */
    private static String sanitizePropertyValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_PROP_VALUE_LENGTH) {
            Log.w(TAG, "Property value exceeds max length (" + MAX_PROP_VALUE_LENGTH
                    + "), skipping: " + value);
            return null;
        }
        return value;
    }

    private void updatePropertiesFromUrl(String urlString) {
        mExecutor.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setConnectTimeout(NETWORK_TIMEOUT_MS);
                urlConnection.setReadTimeout(NETWORK_TIMEOUT_MS);
                try (InputStream inputStream = urlConnection.getInputStream()) {
                    String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    Log.d(TAG, "Downloaded JSON data: " + json);
                    JSONObject jsonObject = new JSONObject(json);
                    String spoofedModel = jsonObject.optString("MODEL", "Unknown model");
                    for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                        String key = it.next();
                        String raw = jsonObject.getString(key);
                        String value = sanitizePropertyValue(raw);
                        if (value == null) continue;
                        Log.d(TAG, "Setting property: persist.sys.pihooks_" + key + " = " + value);
                        SystemProperties.set("persist.sys.pihooks_" + key, value);
                    }
                    mHandler.post(() -> {
                        Context ctx = getContext();
                        if (ctx == null) return;
                        String toastMessage = ctx.getString(R.string.toast_spoofing_success, spoofedModel);
                        Toast.makeText(ctx, toastMessage, Toast.LENGTH_LONG).show();
                        killGMSPackages();
                    });
                } finally {
                    urlConnection.disconnect();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error downloading JSON or setting properties", e);
                mHandler.post(() -> {
                    Context ctx = getContext();
                    if (ctx == null) return;
                    Toast.makeText(ctx, R.string.toast_spoofing_failure, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadPifJson(Uri uri) {
        Log.d(TAG, "Loading PIF JSON from URI: " + uri.toString());
        Context context = getContext();
        if (context == null) return;
        mExecutor.execute(() -> {
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream != null) {
                    String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    Log.d(TAG, "PIF JSON data: " + json);
                    JSONObject jsonObject = new JSONObject(json);
                    for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                        String key = it.next();
                        String raw = jsonObject.getString(key);
                        String value = sanitizePropertyValue(raw);
                        if (value == null) continue;
                        Log.d(TAG, "Setting PIF property: persist.sys.pihooks_" + key + " = " + value);
                        SystemProperties.set("persist.sys.pihooks_" + key, value);
                    }
                    mHandler.post(() -> {
                        Context ctx = getContext();
                        if (ctx == null) return;
                        killGMSPackages();
                        Toast.makeText(ctx, R.string.toast_pif_json_loaded, Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading PIF JSON or setting properties", e);
                mHandler.post(() -> {
                    Context ctx = getContext();
                    if (ctx == null) return;
                    Toast.makeText(ctx, R.string.toast_pif_json_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void getRandomFingerprint() {
        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_random_dialog_title)
            .setMessage(R.string.pif_random_dialog_message)
            .setCancelable(false)
            .setView(new ProgressBar(requireContext()))
            .create();
        dialog.show();

        mExecutor.execute(() -> {
            try {
                Map<String, String> newValues = SpoofingUtils.getRandomFingerprint(
                        SystemProperties.get("persist.sys.pihooks_DEVICE"));
                String spoofedModel = newValues.get("MODEL");
                for (Map.Entry<String, String> entry : newValues.entrySet()) {
                    String key = entry.getKey();
                    String value = sanitizePropertyValue(entry.getValue());
                    if (value == null) continue;
                    Log.d(TAG, "Setting PIF property: persist.sys.pihooks_" + key + " = " + value);
                    SystemProperties.set("persist.sys.pihooks_" + key, value);
                }
                mHandler.post(() -> {
                    Context ctx = getContext();
                    if (ctx == null) return;
                    String toastMessage = ctx.getString(R.string.toast_spoofing_success, spoofedModel);
                    Toast.makeText(ctx, toastMessage, Toast.LENGTH_LONG).show();
                    killGMSPackages();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching PIF properties!", e);
                mHandler.post(() -> {
                    Context ctx = getContext();
                    if (ctx == null) return;
                    Toast.makeText(ctx, R.string.toast_pif_fetch_error, Toast.LENGTH_SHORT).show();
                });
            } finally {
                mHandler.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                });
            }
        });
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        if (context == null) return false;
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mGmsSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_ENABLE_SPOOF,
                    enabled ? 1 : 0);
            killGMSPackages();
            return true;
        }
        if (preference == mVendingSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_VENDING_SPOOF,
                    enabled ? 1 : 0);
            killVending();
            return true;
        }
        if (preference == mSnapchatSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_SNAPCHAT_SPOOF,
                    enabled ? 1 : 0);
            killPackage(SNAPCHAT_PACKAGE);
            return true;
        }
        if (preference == mPhotosSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_PHOTOS_SPOOF,
                    enabled ? 1 : 0);
            killPackage(PHOTOS_PACKAGE);
            return true;
        }
        if (preference == mGoogleSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_PP_SPOOF,
                    enabled ? 1 : 0);
            killGooglePackages();
            return true;
        }
        if (preference == mTensorSpoof) {
            boolean enabled = (Boolean) newValue;
            Settings.Secure.putInt(resolver,
                    Settings.Secure.PI_TENSOR_SPOOF,
                    enabled ? 1 : 0);
            killGooglePackages();
            Toast.makeText(context,
                    enabled ? getString(R.string.tensor_enabled) : getString(R.string.tensor_disabled),
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.spoofing);
}
