/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.app.Activity;
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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.SystemRestartUtils;
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

import org.evolution.settings.preferences.SystemPropertySwitchPreference;
import org.evolution.settings.utils.DeviceUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_SYSTEM_WIDE_CATEGORY = "spoofing_system_wide_category";
    private static final String KEY_GAME_PROPS_JSON_FILE_PREFERENCE = "game_props_json_file_preference";
    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pphooks.enable";
    private static final String SYS_GAMEPROP_SPOOF = "persist.sys.gameprops.enabled";
    private static final String SYS_GPHOTOS_SPOOF = "persist.sys.gphooks.enable";
    private static final String SYS_QSB_SPOOF = "persist.sys.qsb.enable";
    private static final String SYS_SNAP_SPOOF = "persist.sys.snap.enable";
    private static final String SYS_VENDING_SPOOF = "persist.sys.vending.enable";
    private static final String SYS_ENABLE_TENSOR_FEATURES = "persist.sys.features.tensor";

    private Preference mGamePropsJsonFilePreference;
    private Preference mUpdateJsonButton;
    private PreferenceCategory mSystemWideCategory;
    private SystemPropertySwitchPreference mGoogleSpoof;
    private SystemPropertySwitchPreference mGamePropsSpoof;
    private SystemPropertySwitchPreference mGphotosSpoof;
    private SystemPropertySwitchPreference mQsbSpoof;
    private SystemPropertySwitchPreference mSnapSpoof;
    private SystemPropertySwitchPreference mVendingSpoof;
    private SystemPropertySwitchPreference mTensorFeaturesToggle;

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
        mGamePropsSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GAMEPROP_SPOOF);
        mGphotosSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GPHOTOS_SPOOF);
        mGoogleSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GOOGLE_SPOOF);
        mGamePropsJsonFilePreference = findPreference(KEY_GAME_PROPS_JSON_FILE_PREFERENCE);
        mQsbSpoof = (SystemPropertySwitchPreference) findPreference(SYS_QSB_SPOOF);
        mSnapSpoof = (SystemPropertySwitchPreference) findPreference(SYS_SNAP_SPOOF);
        mVendingSpoof = (SystemPropertySwitchPreference) findPreference(SYS_VENDING_SPOOF);
        mTensorFeaturesToggle = (SystemPropertySwitchPreference) findPreference(SYS_ENABLE_TENSOR_FEATURES);

        String model = SystemProperties.get("ro.product.model");
        boolean isTensorDevice = model.matches("Pixel [6-9][a-zA-Z ]*");

        if (DeviceUtils.isCurrentlySupportedPixel()) {
            mGoogleSpoof.setDefaultValue(false);
            if (isMainlineTensorModel(model)) {
                mSystemWideCategory.removePreference(mGoogleSpoof);
            }
        }

        if (isTensorDevice) {
            mSystemWideCategory.removePreference(mTensorFeaturesToggle);
        }

        mGoogleSpoof.setOnPreferenceChangeListener(this);
        mGphotosSpoof.setOnPreferenceChangeListener(this);
        mGamePropsSpoof.setOnPreferenceChangeListener(this);
        mQsbSpoof.setOnPreferenceChangeListener(this);
        mSnapSpoof.setOnPreferenceChangeListener(this);
        mVendingSpoof.setOnPreferenceChangeListener(this);
        mTensorFeaturesToggle.setOnPreferenceChangeListener(this);

        mGamePropsJsonFilePreference.setOnPreferenceClickListener(preference -> {
            openFileSelector(10001);
            return true;
        });
    }

    private boolean isMainlineTensorModel(String model) {
        return model.matches("Pixel [8-9][a-zA-Z ]*");
    }

    private void openFileSelector(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (requestCode == 10001) {
                    loadGameSpoofingJson(uri);
                }
            }
        }
    }

    private void loadGameSpoofingJson(Uri uri) {
        Log.d(TAG, "Loading Game Props JSON from URI: " + uri.toString());
        try (InputStream inputStream = getActivity().getContentResolver().openInputStream(uri)) {
            if (inputStream != null) {
                String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Log.d(TAG, "Game Props JSON data: " + json);
                JSONObject jsonObject = new JSONObject(json);
                for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.startsWith("PACKAGES_") && !key.endsWith("_DEVICE")) {
                        String deviceKey = key + "_DEVICE";
                        if (jsonObject.has(deviceKey)) {
                            JSONObject deviceProps = jsonObject.getJSONObject(deviceKey);
                            JSONArray packages = jsonObject.getJSONArray(key);
                            for (int i = 0; i < packages.length(); i++) {
                                String packageName = packages.getString(i);
                                Log.d(TAG, "Spoofing package: " + packageName);
                                setGameProps(packageName, deviceProps);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading Game Props JSON or setting properties", e);
        }
        mHandler.postDelayed(() -> {
            SystemRestartUtils.showSystemRestartDialog(getContext());
        }, 1250);
    }

    private void setGameProps(String packageName, JSONObject deviceProps) {
        try {
            for (Iterator<String> it = deviceProps.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = deviceProps.getString(key);
                String systemPropertyKey = "persist.sys.gameprops." + packageName + "." + key;
                SystemProperties.set(systemPropertyKey, value);
                Log.d(TAG, "Set system property: " + systemPropertyKey + " = " + value);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing device properties", e);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mGoogleSpoof
            || preference == mGphotosSpoof
            || preference == mGamePropsSpoof
            || preference == mQsbSpoof
            || preference == mSnapSpoof
            || preference == mVendingSpoof) {
            SystemRestartUtils.showSystemRestartDialog(getContext());
            return true;
        }
        if (preference == mTensorFeaturesToggle) {
            boolean enabled = (Boolean) newValue;
            SystemProperties.set(SYS_ENABLE_TENSOR_FEATURES, enabled ? "true" : "false");
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
