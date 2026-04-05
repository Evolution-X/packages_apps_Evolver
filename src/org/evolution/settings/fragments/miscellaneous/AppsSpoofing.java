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

import org.evolution.settings.preferences.SystemPropertySwitchPreference;
import org.evolution.settings.utils.DeviceUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@SearchIndexable
public class AppsSpoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "AppsSpoofing";

    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pp";
    private static final String SYS_PHOTOS_SPOOF = "persist.sys.pp.photos";
    private static final String SYS_TENSOR_SPOOF = "persist.sys.pp.tensor";

    private static final String GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String VENDING_PACKAGE = "com.android.vending";

    private static final ArraySet<String> MAINLINE_TENSOR = new ArraySet<>();
    private static final ArraySet<String> TENSOR = new ArraySet<>();
    private static final boolean IS_MAINLINE_TENSOR;
    private static final boolean IS_TENSOR;

    private SystemPropertySwitchPreference mGoogleSpoof;
    private SystemPropertySwitchPreference mPhotosSpoof;
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
        addPreferencesFromResource(R.xml.apps_spoofing);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mPhotosSpoof = (SystemPropertySwitchPreference) findPreference(SYS_PHOTOS_SPOOF);
        mGoogleSpoof = (SystemPropertySwitchPreference) findPreference(SYS_GOOGLE_SPOOF);
        mTensorSpoof = (SystemPropertySwitchPreference) findPreference(SYS_TENSOR_SPOOF);

        if (DeviceUtils.isCurrentlySupportedPixel()) {
            mGoogleSpoof.setDefaultValue(false);
            if (IS_MAINLINE_TENSOR) {
                prefScreen.removePreference(mGoogleSpoof);
            }
        }

        if (IS_TENSOR) {
            prefScreen.removePreference(mTensorSpoof);
        }

        mGoogleSpoof.setOnPreferenceChangeListener(this);
        mPhotosSpoof.setOnPreferenceChangeListener(this);
        mTensorSpoof.setOnPreferenceChangeListener(this);
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

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mPhotosSpoof) {
            killPackage(GOOGLE_PHOTOS_PACKAGE);
            return true;
        }
        if (preference == mGoogleSpoof) {
            killGooglePackages()
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
        new BaseSearchIndexProvider(R.xml.apps_spoofing) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

                return keys;
            }
        };
}
