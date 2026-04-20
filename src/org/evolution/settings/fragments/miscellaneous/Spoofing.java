/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.PixelPropsUtils;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_SYSTEM_WIDE_CATEGORY = "spoofing_system_wide_category";
    private static final String KEY_APP_SPECIFIC_CATEGORY = "spoofing_app_specific_category";
    private static final String PI_PP_SPOOF = "pi_pp_spoof";
    private static final String PI_PHOTOS_SPOOF = "pi_photos_spoof";
    private static final String PI_SNAPCHAT_SPOOF = "pi_snapchat_spoof";
    private static final String PI_TENSOR_SPOOF = "pi_tensor_spoof";

    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String SNAPCHAT_PACKAGE = "com.snapchat.android";
    private static final String VENDING_PACKAGE = "com.android.vending";

    private PreferenceCategory mSystemWideCategory;
    private PreferenceCategory mAppSpecificCategory;
    private SwitchPreferenceCompat mGoogleSpoof;
    private SwitchPreferenceCompat mPhotosSpoof;
    private SwitchPreferenceCompat mSnapchatSpoof;
    private SwitchPreferenceCompat mTensorSpoof;

    private Handler mHandler;
    private Runnable mPendingKill;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.spoofing);

        if (PixelPropsUtils.isCustomForkBuild()) {
            if (getPreferenceScreen() != null) {
                getPreferenceScreen().removeAll();
            }
            return;
        }

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());

        mSystemWideCategory = (PreferenceCategory) findPreference(KEY_SYSTEM_WIDE_CATEGORY);
        mAppSpecificCategory = (PreferenceCategory) findPreference(KEY_APP_SPECIFIC_CATEGORY);
        mPhotosSpoof = (SwitchPreferenceCompat) findPreference(PI_PHOTOS_SPOOF);
        mGoogleSpoof = (SwitchPreferenceCompat) findPreference(PI_PP_SPOOF);
        mSnapchatSpoof = (SwitchPreferenceCompat) findPreference(PI_SNAPCHAT_SPOOF);
        mTensorSpoof = (SwitchPreferenceCompat) findPreference(PI_TENSOR_SPOOF);

        // Google spoof: hide entirely on mainline Tensor devices
        if (PixelPropsUtils.isMainlinePixelDevice()) {
            mSystemWideCategory.removePreference(mGoogleSpoof);
        } else {
            mGoogleSpoof.setOnPreferenceChangeListener(this);
        }

        // Tensor spoof: only relevant on non-Tensor devices
        if (mTensorSpoof != null) {
            if (!PixelPropsUtils.isTensorPixelDevice()) {
                boolean isTensorEnabled = Settings.Secure.getInt(resolver, Settings.Secure.PI_TENSOR_SPOOF, 0) == 1;
                mTensorSpoof.setChecked(isTensorEnabled);
                mTensorSpoof.setOnPreferenceChangeListener(this);
            } else {
                mSystemWideCategory.removePreference(mTensorSpoof);
            }
        }

        if (mPhotosSpoof != null) {
            try {
                getContext().getPackageManager().getPackageInfo(PHOTOS_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                mAppSpecificCategory.removePreference(mPhotosSpoof);
                mPhotosSpoof = null;
            }
        }

        if (mPhotosSpoof != null) {
            boolean isPhotosEnabled = Settings.Secure.getInt(resolver, Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
            mPhotosSpoof.setChecked(isPhotosEnabled);
            mPhotosSpoof.setOnPreferenceChangeListener(this);
        }

        if (mSnapchatSpoof != null) {
            try {
                getContext().getPackageManager().getPackageInfo(SNAPCHAT_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                mAppSpecificCategory.removePreference(mSnapchatSpoof);
                mSnapchatSpoof = null;
            }
        }

        if (mSnapchatSpoof != null) {
            boolean isSnapchatEnabled = Settings.Secure.getInt(resolver, Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
            mSnapchatSpoof.setChecked(isSnapchatEnabled);
            mSnapchatSpoof.setOnPreferenceChangeListener(this);
        }
    }

    private void scheduleKill(String pkg) {
        if (mPendingKill != null) {
            mHandler.removeCallbacks(mPendingKill);
        }
        Toast.makeText(getContext(), R.string.spoofing_applying_changes, Toast.LENGTH_SHORT).show();
        mPendingKill = () -> {
            if (pkg != null) {
                killIfRunning(pkg);
            } else {
                killGooglePackages();
            }
        };
        mHandler.postDelayed(mPendingKill, 500);
    }

    private void killIfRunning(String pkg) {
        try {
            ActivityManager am = (ActivityManager)
                    getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            for (ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
                if (proc.pkgList == null) continue;
                for (String p : proc.pkgList) {
                    if (pkg.equals(p)) {
                        am.forceStopPackage(pkg);
                        Log.d(TAG, "Killed: " + pkg);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Kill failed: " + pkg, e);
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
                    killIfRunning(app.packageName);
                }
            }
            killIfRunning(VENDING_PACKAGE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill Google packages", e);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final ContentResolver resolver = getContext().getContentResolver();
        boolean enabled = (Boolean) newValue;

        if (preference == mSnapchatSpoof) {
            int current = Settings.Secure.getInt(resolver, Settings.Secure.PI_SNAPCHAT_SPOOF, 0);
            if ((enabled ? 1 : 0) == current) return true;
            Settings.Secure.putInt(resolver, Settings.Secure.PI_SNAPCHAT_SPOOF, enabled ? 1 : 0);
            scheduleKill(SNAPCHAT_PACKAGE);
            return true;
        }

        if (preference == mPhotosSpoof) {
            int current = Settings.Secure.getInt(resolver, Settings.Secure.PI_PHOTOS_SPOOF, 1);
            if ((enabled ? 1 : 0) == current) return true;
            Settings.Secure.putInt(resolver, Settings.Secure.PI_PHOTOS_SPOOF, enabled ? 1 : 0);
            scheduleKill(PHOTOS_PACKAGE);
            return true;
        }

        if (preference == mGoogleSpoof) {
            int current = Settings.Secure.getInt(resolver, Settings.Secure.PI_PP_SPOOF, 1);
            if ((enabled ? 1 : 0) == current) return true;
            Settings.Secure.putInt(resolver, Settings.Secure.PI_PP_SPOOF, enabled ? 1 : 0);
            scheduleKill(null);
            return true;
        }

        if (preference == mTensorSpoof) {
            int current = Settings.Secure.getInt(resolver, Settings.Secure.PI_TENSOR_SPOOF, 0);
            if ((enabled ? 1 : 0) == current) return true;
            Settings.Secure.putInt(resolver, Settings.Secure.PI_TENSOR_SPOOF, enabled ? 1 : 0);
            scheduleKill(null);
            Toast.makeText(getContext(),
                    enabled ? R.string.spoofing_tensor_enabled : R.string.spoofing_tensor_disabled,
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null && mPendingKill != null) {
            mHandler.removeCallbacks(mPendingKill);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.spoofing);
}
