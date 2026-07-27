/*
 * SPDX-FileCopyrightText: Evolution X
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

import org.evolution.settings.utils.PreferenceUtils;

@SearchIndexable
public class Spoofing extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Spoofing";

    private static final String KEY_FEATURES_CATEGORY = "spoofing_features_category";
    private static final String KEY_APP_SPECIFIC_CATEGORY = "spoofing_app_specific_category";
    private static final String PI_PHOTOS_SPOOF = "pi_photos_spoof";
    private static final String PI_SNAPCHAT_SPOOF = "pi_snapchat_spoof";
    private static final String KEY_TENSOR_TARGETS = "tensor_targets_settings";

    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String SNAPCHAT_PACKAGE = "com.snapchat.android";

    private PreferenceCategory mFeaturesCategory;
    private PreferenceCategory mAppSpecificCategory;
    private SwitchPreferenceCompat mPhotosSpoof;
    private SwitchPreferenceCompat mSnapchatSpoof;
    private Preference mTensorTargets;

    private Handler mHandler;
    private Runnable mPendingKill;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.spoofing);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());

        mFeaturesCategory = (PreferenceCategory) findPreference(KEY_FEATURES_CATEGORY);
        mAppSpecificCategory = (PreferenceCategory) findPreference(KEY_APP_SPECIFIC_CATEGORY);
        mPhotosSpoof = (SwitchPreferenceCompat) findPreference(PI_PHOTOS_SPOOF);
        mSnapchatSpoof = (SwitchPreferenceCompat) findPreference(PI_SNAPCHAT_SPOOF);
        mTensorTargets = findPreference(KEY_TENSOR_TARGETS);

        // Tensor targets: only relevant on non-Tensor devices
        if (mTensorTargets != null && PixelPropsUtils.isTensorPixelDevice()) {
            mFeaturesCategory.removePreference(mTensorTargets);
        }
        mPhotosSpoof = initAppSpoof(mPhotosSpoof, PHOTOS_PACKAGE);
        mSnapchatSpoof = initAppSpoof(mSnapchatSpoof, SNAPCHAT_PACKAGE);
    }

    /**
     * Checks whether {@code pkg} is installed. If not, removes {@code pref} from the
     * app-specific category and returns null. If installed, syncs the checked state from
     * Settings.Secure and registers the change listener, then returns the preference unchanged.
     */
    private SwitchPreferenceCompat initAppSpoof(SwitchPreferenceCompat pref, String pkg) {
        if (pref == null) return null;
        try {
            getContext().getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            mAppSpecificCategory.removePreference(pref);
            return null;
        }
        // SecureSettingSwitchPreference already syncs checked state from Settings.Secure
        pref.setOnPreferenceChangeListener(this);
        return pref;
    }

    private void scheduleKill(String pkg) {
        if (mPendingKill != null) {
            mHandler.removeCallbacks(mPendingKill);
        }
        Toast.makeText(getContext(), R.string.spoofing_applying_changes, Toast.LENGTH_SHORT).show();
        mPendingKill = () -> killIfRunning(pkg);
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

    // killGooglePackages() removed — pixel props kill logic moved to PixelPropsSettings fragment

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mSnapchatSpoof == null && mPhotosSpoof == null) return true;
        if (preference == mSnapchatSpoof) {
            scheduleKill(SNAPCHAT_PACKAGE);
        } else if (preference == mPhotosSpoof) {
            scheduleKill(PHOTOS_PACKAGE);
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null && mPendingKill != null) {
            mHandler.removeCallbacks(mPendingKill);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.spoofing);
}
