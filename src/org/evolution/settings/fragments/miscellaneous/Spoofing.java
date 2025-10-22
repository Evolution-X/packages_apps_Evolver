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
import android.os.SystemProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.KeyProviderManager;
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
    private static final String SYS_GMS_SPOOF = "persist.sys.pp.gms";
    private static final String SYS_GMS_CERT_SPOOF = "persist.sys.pp.gmscertchain";
    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pp";
    private static final String SYS_GAMES_SPOOF = "persist.sys.pp.games";
    private static final String SYS_PHOTOS_SPOOF = "persist.sys.pp.photos";
    private static final String SYS_QSB_SPOOF = "persist.sys.pp.qsb";
    private static final String SYS_SNAPCHAT_SPOOF = "persist.sys.pp.snapchat";
    private static final String SYS_TENSOR_SPOOF = "persist.sys.pp.tensor";
    private static final String KEYBOX_DATA_KEY = "keybox_data_setting";

    private ActivityResultLauncher<Intent> mKeyboxFilePickerLauncher;
    private KeyboxDataPreference mKeyboxDataPreference;
    private Preference mPifJsonFilePreference;
    private Preference mUpdateJsonButton;
    private PreferenceCategory mSystemWideCategory;
    private SystemPropertySwitchPreference mDisableForceIntegrity;
    private SystemPropertySwitchPreference mGmsSpoof;
    private SystemPropertySwitchPreference mGoogleSpoof;
    private SystemPropertySwitchPreference mGamesSpoof;
    private SystemPropertySwitchPreference mPhotosSpoof;
    private SystemPropertySwitchPreference mQsbSpoof;
    private SystemPropertySwitchPreference mSnapchatSpoof;
    private SystemPropertySwitchPreference mTensorSpoof;

    private Handler mHandler;

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
        mGamesSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GAMES_SPOOF);
        mPhotosSpoof = (SystemPropertySwitchPreference) findPreference(SYS_PHOTOS_SPOOF);
        mGmsSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GMS_SPOOF);
        mGoogleSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GOOGLE_SPOOF);
        mPifJsonFilePreference = findPreference(KEY_PIF_JSON_FILE_PREFERENCE);
        mQsbSpoof = (SystemPropertySwitchPreference) findPreference(SYS_QSB_SPOOF);
        mSnapchatSpoof = (SystemPropertySwitchPreference) findPreference(SYS_SNAPCHAT_SPOOF);
        mTensorSpoof = (SystemPropertySwitchPreference) findPreference(SYS_TENSOR_SPOOF);
        mUpdateJsonButton = findPreference(KEY_UPDATE_JSON_BUTTON);

        String model = SystemProperties.get("ro.product.model");
        boolean isTensorDevice = model.matches("Pixel (6|7|8|9|10)[a-zA-Z ]*");
        boolean isPixelGmsEnabled = SystemProperties.getBoolean(SYS_GMS_SPOOF, true); // Default to Pixel GMS

        if (DeviceUtils.isCurrentlySupportedPixel()) {
            mGoogleSpoof.setDefaultValue(false);
            if (isMainlineTensorModel(model)) {
                mSystemWideCategory.removePreference(mGoogleSpoof);
            }
        }

        if (isTensorDevice) {
            mSystemWideCategory.removePreference(mTensorSpoof);
        }

        mGmsSpoof.setOnPreferenceChangeListener(this);
        mGoogleSpoof.setOnPreferenceChangeListener(this);
        mPhotosSpoof.setOnPreferenceChangeListener(this);
        mGamesSpoof.setOnPreferenceChangeListener(this);
        mQsbSpoof.setOnPreferenceChangeListener(this);
        mSnapchatSpoof.setOnPreferenceChangeListener(this);
        mTensorSpoof.setOnPreferenceChangeListener(this);

        mDisableForceIntegrity = findPreference(SYS_GMS_CERT_SPOOF);
        if (mDisableForceIntegrity != null) {
            mDisableForceIntegrity.setEnabled(KeyProviderManager.isKeyboxAvailable());
        }

        mKeyboxFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    Preference pref = findPreference(KEYBOX_DATA_KEY);
                    if (pref instanceof KeyboxDataPreference) {
                        ((KeyboxDataPreference) pref).handleFileSelected(uri);
                    }
                    if (mDisableForceIntegrity != null) {
                        mDisableForceIntegrity.setEnabled(KeyProviderManager.isKeyboxAvailable());
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

        Preference showPropertiesPref = findPreference("show_pif_properties");
        if (showPropertiesPref != null) {
            showPropertiesPref.setOnPreferenceClickListener(preference -> {
                showPropertiesDialog();
                return true;
            });
        }
    }

    private boolean isMainlineTensorModel(String model) {
        return model.matches("Pixel (8|9|10)[a-zA-Z ]*");
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

    /**
     * Kill packages that need to be restarted to pick up new PIF properties
     */
    private void killGMSPackages() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            String[] packages = {
                "com.google.android.apps.photos",
                "com.google.android.gms",
                "com.google.android.googlequicksearchbox",
                "com.android.vending",
                "com.snapchat.android"
            };
            for (String pkg : packages) {
                am.getClass()
                  .getMethod("forceStopPackage", String.class)
                  .invoke(am, pkg);
                Log.i(TAG, pkg + " process killed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill packages", e);
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

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mGmsSpoof
            || preference == mPhotosSpoof
            || preference == mQsbSpoof
            || preference == mSnapchatSpoof) {
            killGMSPackages();
            return true;
        }
        if (preference == mGoogleSpoof
            || preference == mGamesSpoof) {
            SystemRestartUtils.showSystemRestartDialog(getContext());
            return true;
        }
        if (preference == mTensorSpoof) {
            boolean enabled = (Boolean) newValue;
            SystemProperties.set(SYS_TENSOR_SPOOF, enabled ? "true" : "false");
            SystemRestartUtils.showSystemRestartDialog(getContext());
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
