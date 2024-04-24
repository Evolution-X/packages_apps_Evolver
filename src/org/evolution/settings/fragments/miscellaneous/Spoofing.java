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

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_SYSTEM_WIDE_CATEGORY = "spoofing_system_wide_category";
    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pixelprops";
    private static final String SYS_GAMEPROP_SPOOF = "persist.sys.pixelprops.games";
    private static final String SYS_GPHOTOS_SPOOF = "persist.sys.pixelprops.gphotos";
    private static final String SYS_QSB_SPOOF = "persist.sys.pixelprops.qsb";
    private static final String SYS_SNAP_SPOOF = "persist.sys.pixelprops.snap";
    private static final String SYS_VENDING_SPOOF = "persist.sys.pixelprops.vending";
    private static final String SYS_ENABLE_TENSOR_FEATURES = "persist.sys.features.tensor";

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
    }

    private boolean isMainlineTensorModel(String model) {
        return model.matches("Pixel [8-9][a-zA-Z ]*");
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
