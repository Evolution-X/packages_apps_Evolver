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
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.provider.Settings;
import android.util.ArraySet;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.SystemRestartUtils;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.evolution.settings.preferences.KeyboxDataPreference;
import org.evolution.settings.preferences.SystemPropertySwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SpoofingUtils;

import org.json.JSONArray;
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
    private static final String SYS_GMS_SPOOF = "persist.sys.pp.gms";
    private static final String SYS_VENDING_SPOOF = "persist.sys.pp.vending";
    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pp";
    private static final String SYS_PHOTOS_SPOOF = "persist.sys.pp.photos";
    private static final String SYS_SNAPCHAT_SPOOF = "persist.sys.pp.snapchat";
    private static final String SYS_TENSOR_SPOOF = "persist.sys.pp.tensor";
    private static final String KEYBOX_DATA_KEY = "keybox_data_setting";

    private static final String GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String SNAPCHAT_PACKAGE = "com.snapchat.android";
    private static final String VENDING_PACKAGE = "com.android.vending";

    private static final ArraySet<String> MAINLINE_TENSOR = new ArraySet<>();
    private static final ArraySet<String> TENSOR = new ArraySet<>();
    private static final boolean IS_MAINLINE_TENSOR;
    private static final boolean IS_TENSOR;

    private ActivityResultLauncher<Intent> mKeyboxFilePickerLauncher;
    private KeyboxDataPreference mKeyboxDataPreference;
    private Preference mPifJsonFilePreference;
    private Preference mUpdateJsonButton;
    private Preference mRandomPropertiesButton;
    private PreferenceCategory mSystemWideCategory;
    private SystemPropertySwitchPreference mGmsSpoof;
    private SystemPropertySwitchPreference mVendingSpoof;
    private SystemPropertySwitchPreference mGoogleSpoof;
    private SystemPropertySwitchPreference mPhotosSpoof;
    private SystemPropertySwitchPreference mSnapchatSpoof;
    private SystemPropertySwitchPreference mTensorSpoof;

    private Handler mHandler;

    static {

        Collections.addAll(MAINLINE_TENSOR,
                "stallion","blazer","frankel","mustang","comet","komodo","caiman","tokay",
                "akita","husky","shiba"
        );

        Collections.addAll(TENSOR,
                "stallion","blazer","frankel","mustang","tegu","comet","komodo","caiman","tokay",
                "akita","husky","shiba","felix","tangorpro","lynx","cheetah","panther",
                "bluejay","oriole","raven"
        );

        final String device = SystemProperties.get("ro.evolution.device");
        IS_MAINLINE_TENSOR = MAINLINE_TENSOR.contains(device);
        IS_TENSOR = TENSOR.contains(device);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();
        addPreferencesFromResource(R.xml.spoofing);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mSystemWideCategory = (PreferenceCategory) findPreference(KEY_SYSTEM_WIDE_CATEGORY);
        mPhotosSpoof = (SystemPropertySwitchPreference) findPreference(SYS_PHOTOS_SPOOF);
        mGmsSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GMS_SPOOF);
        mVendingSpoof = (SystemPropertySwitchPreference) findPreference(SYS_VENDING_SPOOF);
        mGoogleSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GOOGLE_SPOOF);
        mPifJsonFilePreference = findPreference(KEY_PIF_JSON_FILE_PREFERENCE);
        mSnapchatSpoof = (SystemPropertySwitchPreference) findPreference(SYS_SNAPCHAT_SPOOF);
        mTensorSpoof = (SystemPropertySwitchPreference) findPreference(SYS_TENSOR_SPOOF);
        mUpdateJsonButton = findPreference(KEY_UPDATE_JSON_BUTTON);
        mRandomPropertiesButton = findPreference(KEY_RANDOM_PROPERTIES_BUTTON);

        boolean isPixelGmsEnabled = SystemProperties.getBoolean(SYS_GMS_SPOOF, true);

        if (DeviceUtils.isCurrentlySupportedPixel()) {
            mGoogleSpoof.setDefaultValue(false);
            if (IS_MAINLINE_TENSOR) {
                mSystemWideCategory.removePreference(mGoogleSpoof);
            }
        }

        if (IS_TENSOR) {
            mSystemWideCategory.removePreference(mTensorSpoof);
        }

        mGmsSpoof.setOnPreferenceChangeListener(this);
        mVendingSpoof.setOnPreferenceChangeListener(this);
        mGoogleSpoof.setOnPreferenceChangeListener(this);
        mPhotosSpoof.setOnPreferenceChangeListener(this);
        mSnapchatSpoof.setOnPreferenceChangeListener(this);
        mTensorSpoof.setOnPreferenceChangeListener(this);

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

        mPifJsonFilePreference.setOnPreferenceClickListener(preference -> {
            openFileSelector(10001);
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

    private void openFileSelector(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        startActivityForResult(intent, requestCode);
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
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (requestCode == 10001) {
                    loadPifJson(uri);
                }
            }
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
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            am.getClass()
                .getMethod("forceStopPackage", String.class)
                .invoke(am, pkg);
                Log.i(TAG, pkg + " process killed");  
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill package", e);
        }
    }

    private void killVending() {
        killPackage(VENDING_PACKAGE);
    }

    /**
     * Kill packages that need to be restarted to pick up new PIF properties
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
     * Kill all Google packages (com.google.*) so they can pick up new properties
     */
    private void killGooglePackages() {
        try {
            PackageManager pm = getContext().getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);

            for (ApplicationInfo app : apps) {
                String pkg = app.packageName;

                if (pkg.startsWith("com.google")) {
                    killPackage(pkg);
                }
            }

            // Keep explicit Play Store kill (extra safety)
            killVending();

        } catch (Exception e) {
            Log.e(TAG, "Failed to kill Google packages", e);
        }
    }

    private void updatePropertiesFromUrl(String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                try (InputStream inputStream = urlConnection.getInputStream()) {
                    String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    Log.d(TAG, "Downloaded JSON data: " + json);
                    JSONObject jsonObject = new JSONObject(json);
                    String spoofedModel = jsonObject.optString("MODEL", "Unknown model");
                    for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                        String key = it.next();
                        String value = jsonObject.getString(key);
                        Log.d(TAG, "Setting property: persist.sys.pihooks_" + key + " = " + value);
                        SystemProperties.set("persist.sys.pihooks_" + key, value);
                    }
                    mHandler.post(() -> {
                        String toastMessage = getString(R.string.toast_spoofing_success, spoofedModel);
                        Toast.makeText(getContext(), toastMessage, Toast.LENGTH_LONG).show();
                        killGMSPackages();
                    });

                } finally {
                    urlConnection.disconnect();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error downloading JSON or setting properties", e);
                mHandler.post(() -> {
                    Toast.makeText(getContext(), R.string.toast_spoofing_failure, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void loadPifJson(Uri uri) {
        Log.d(TAG, "Loading PIF JSON from URI: " + uri.toString());
        try (InputStream inputStream = getActivity().getContentResolver().openInputStream(uri)) {
            if (inputStream != null) {
                String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Log.d(TAG, "PIF JSON data: " + json);
                JSONObject jsonObject = new JSONObject(json);
                for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                    String key = it.next();
                    String value = jsonObject.getString(key);
                    Log.d(TAG, "Setting PIF property: persist.sys.pihooks_" + key + " = " + value);
                    SystemProperties.set("persist.sys.pihooks_" + key, value);
                }
                killGMSPackages();
                Toast.makeText(getContext(), "PIF JSON loaded and packages refreshed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading PIF JSON or setting properties", e);
            Toast.makeText(getContext(), "Error loading PIF JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private void getRandomFingerprint() {
       final AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle("Please wait")
            .setMessage("Fetching PIF properties...")
            .setCancelable(false) 
            .setView(new ProgressBar(requireContext())) 
            .create();
            dialog.show();

        new Thread (() -> {
            try {
                Map<String, String> newValues = SpoofingUtils.getRandomFingerprint(SystemProperties.get("persist.sys.pihooks_DEVICE"));
                String spoofedModel = newValues.get("MODEL");
                for (Map.Entry<String, String> entry : newValues.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    Log.d(TAG, "Setting PIF property: persist.sys.pihooks_" + key + " = " + value);
                    SystemProperties.set("persist.sys.pihooks_" + key, value);
                }
                mHandler.post(() -> {
                        String toastMessage = getString(R.string.toast_spoofing_success, spoofedModel);
                        Toast.makeText(getContext(), toastMessage, Toast.LENGTH_LONG).show();
                        killGMSPackages();
                });
            } catch (Exception e)  {
                Log.e(TAG, "Error fetching PIF properties!", e);
                Toast.makeText(getContext(), "Error fetching PIF properties!", Toast.LENGTH_SHORT).show();
            } finally {
                new Handler(Looper.getMainLooper()).post(dialog::dismiss);
            }
        }).start();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mGmsSpoof) {
            killGMSPackages();
            return true;
        }
        if (preference == mVendingSpoof) {
            killVending();
            return true;
        }
        if (preference == mSnapchatSpoof) {
            killPackage(SNAPCHAT_PACKAGE);
            return true;
        }
        if (preference == mPhotosSpoof) {
            killPackage(GOOGLE_PHOTOS_PACKAGE);
            return true;
        }
        if (preference == mGoogleSpoof) {
            SystemRestartUtils.showSystemRestartDialog(getContext());
            return true;
        }
        if (preference == mTensorSpoof) {
            boolean enabled = (Boolean) newValue;
            SystemProperties.set(SYS_TENSOR_SPOOF, enabled ? "true" : "false");
            // Restart all Google apps to pick up new feature flags
            killGooglePackages();
            Toast.makeText(getContext(),
                    enabled ? "Tensor features enabled" : "Tensor features disabled",
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
        new BaseSearchIndexProvider(R.xml.spoofing) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

                return keys;
            }
        };
}
