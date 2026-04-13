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
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;
import android.widget.Toast;
import android.provider.Settings;
import android.util.ArraySet;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.Collections;
import java.util.List;

import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_SYSTEM_WIDE_CATEGORY = "spoofing_system_wide_category";
    private static final String PI_PP_SPOOF = "pi_pp_spoof";
    private static final String PI_PHOTOS_SPOOF = "pi_photos_spoof";
    private static final String PI_SNAPCHAT_SPOOF = "pi_snapchat_spoof";
    private static final String PI_TENSOR_SPOOF = "pi_tensor_spoof";

    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String SNAPCHAT_PACKAGE = "com.snapchat.android";
    private static final String VENDING_PACKAGE = "com.android.vending";

    private static final ArraySet<String> MAINLINE_TENSOR = new ArraySet<>();
    private static final ArraySet<String> TENSOR = new ArraySet<>();
    private static final boolean IS_MAINLINE_TENSOR;
    private static final boolean IS_TENSOR;

    private PreferenceCategory mSystemWideCategory;
    private SwitchPreferenceCompat mGoogleSpoof;
    private SwitchPreferenceCompat mPhotosSpoof;
    private SwitchPreferenceCompat mSnapchatSpoof;
    private SwitchPreferenceCompat mTensorSpoof;

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
        addPreferencesFromResource(R.xml.spoofing);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();

        mSystemWideCategory = (PreferenceCategory) findPreference(KEY_SYSTEM_WIDE_CATEGORY);
        mPhotosSpoof = (SwitchPreferenceCompat) findPreference(PI_PHOTOS_SPOOF);
        mGoogleSpoof = (SwitchPreferenceCompat) findPreference(PI_PP_SPOOF);
        mSnapchatSpoof = (SwitchPreferenceCompat) findPreference(PI_SNAPCHAT_SPOOF);
        mTensorSpoof = (SwitchPreferenceCompat) findPreference(PI_TENSOR_SPOOF);

        boolean isPhotosEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
        boolean isSnapchatEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
        boolean isTensorEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.PI_TENSOR_SPOOF, 0) == 1;

        // Google spoof: hide entirely on mainline Tensor devices
        if (DeviceUtils.isCurrentlySupportedPixel() && IS_MAINLINE_TENSOR) {
            mSystemWideCategory.removePreference(mGoogleSpoof);
        } else {
            mGoogleSpoof.setOnPreferenceChangeListener(this);
        }

        // Tensor spoof: only relevant on non-Tensor devices
        if (mTensorSpoof != null) {
            if (!IS_TENSOR) {
                mTensorSpoof.setChecked(isTensorEnabled);
                mTensorSpoof.setOnPreferenceChangeListener(this);
            } else {
                mSystemWideCategory.removePreference(mTensorSpoof);
            }
        }

        mPhotosSpoof.setChecked(isPhotosEnabled);
        mPhotosSpoof.setOnPreferenceChangeListener(this);

        mSnapchatSpoof.setChecked(isSnapchatEnabled);
        mSnapchatSpoof.setOnPreferenceChangeListener(this);
    }

    private void killPackage(String pkg) {
        try {
            ActivityManager am = (ActivityManager)
                    getContext().getSystemService(Context.ACTIVITY_SERVICE);
            am.getClass()
                    .getMethod("forceStopPackage", String.class)
                    .invoke(am, pkg);
            Log.i(TAG, pkg + " process killed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill package: " + pkg, e);
        }
    }

    /**
     * Kill all Google packages (com.google.*) and Play Store to pick up new properties.
     * Note: com.android.vending is killed explicitly since it doesn't match the com.google prefix.
     */
    private void killGooglePackages() {
        try {
            PackageManager pm = getContext().getPackageManager();
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (app.packageName.startsWith("com.google")) {
                    killPackage(app.packageName);
                }
            }
            killPackage(VENDING_PACKAGE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill Google packages", e);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final ContentResolver resolver = getContext().getContentResolver();
        boolean enabled = (Boolean) newValue;

        if (preference == mSnapchatSpoof) {
            Settings.Secure.putInt(resolver, Settings.Secure.PI_SNAPCHAT_SPOOF, enabled ? 1 : 0);
            killPackage(SNAPCHAT_PACKAGE);
            return true;
        }
        if (preference == mPhotosSpoof) {
            Settings.Secure.putInt(resolver, Settings.Secure.PI_PHOTOS_SPOOF, enabled ? 1 : 0);
            killPackage(PHOTOS_PACKAGE);
            return true;
        }
        if (preference == mGoogleSpoof) {
            Settings.Secure.putInt(resolver, Settings.Secure.PI_PP_SPOOF, enabled ? 1 : 0);
            killGooglePackages();
            return true;
        }
        if (preference == mTensorSpoof) {
            Settings.Secure.putInt(resolver, Settings.Secure.PI_TENSOR_SPOOF, enabled ? 1 : 0);
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
                    return super.getNonIndexableKeys(context);
                }
            };
}
