/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import org.evolution.settings.preferences.SecureSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;

import java.util.Collections;
import java.util.List;

public class AppsSpoofing extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "AppsSpoofing";

    private static final String PACKAGE_PHOTOS = "com.google.android.apps.photos";
    private static final String PACKAGE_SNAPCHAT = "com.snapchat.android";

    private static final String[] GOOGLE_PACKAGES = {
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.googlequicksearchbox",
            "com.android.vending"
    };

    private static final ArraySet<String> TENSOR = new ArraySet<>();
    private static final boolean IS_TENSOR;

    static {
        Collections.addAll(TENSOR,
                "stallion", "blazer", "frankel", "mustang", "tegu", "comet", "komodo", "caiman", "tokay",
                "akita", "husky", "shiba", "felix", "tangorpro", "lynx", "cheetah", "panther",
                "bluejay", "oriole", "raven"
        );

        final String device = SystemProperties.get("ro.evolution.device");
        IS_TENSOR = TENSOR.contains(device);
    }

    private SecureSettingSwitchPreference mGoogleSpoof;
    private SecureSettingSwitchPreference mPhotosSpoof;
    private SecureSettingSwitchPreference mTensorSpoof;
    private SecureSettingSwitchPreference mSnapchatSpoof;

    private Handler mHandler;
    private Runnable mPendingKill;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler(Looper.getMainLooper());
        addPreferencesFromResource(R.xml.apps_spoofing);

        final Context context = getContext();
        final PreferenceScreen screen = getPreferenceScreen();

        mGoogleSpoof = findPreference("pi_pp_spoof");
        mPhotosSpoof = findPreference("pi_photos_spoof");
        mTensorSpoof = findPreference("pi_tensor_spoof");
        mSnapchatSpoof = findPreference("pi_snapchat_spoof");

        // Hide Tensor toggle on Tensor devices
        if (IS_TENSOR && mTensorSpoof != null) {
            screen.removePreference(mTensorSpoof);
        }

        // Hide Snapchat toggle if app is not installed
        if (mSnapchatSpoof != null) {
            try {
                context.getPackageManager().getPackageInfo(PACKAGE_SNAPCHAT, 0);
            } catch (PackageManager.NameNotFoundException e) {
                screen.removePreference(mSnapchatSpoof);
            }
        }

        // On a real Pixel device, Google spoof is irrelevant — disable and uncheck it.
        // Set listener to null before setChecked to avoid triggering a spurious kill on startup.
        if (DeviceUtils.isCurrentlySupportedPixel() && mGoogleSpoof != null) {
            mGoogleSpoof.setOnPreferenceChangeListener(null);
            mGoogleSpoof.setChecked(false);
            mGoogleSpoof.setEnabled(false);
        }

        // Register listeners — skip mGoogleSpoof if it was disabled above
        if (mGoogleSpoof != null && mGoogleSpoof.isEnabled()) {
            mGoogleSpoof.setOnPreferenceChangeListener(this);
        }
        if (mPhotosSpoof != null) mPhotosSpoof.setOnPreferenceChangeListener(this);
        if (mTensorSpoof != null) mTensorSpoof.setOnPreferenceChangeListener(this);
        if (mSnapchatSpoof != null) mSnapchatSpoof.setOnPreferenceChangeListener(this);
    }

    // -----------------------------
    // Smart kill logic (only if running)
    // -----------------------------

    /**
     * Debounced kill for a single package. Pass null to kill all GOOGLE_PACKAGES.
     * Tensor spoof passes null because it affects the full Google stack (GMS, GSF,
     * Play Store, Quick Search) — same target set as Google spoof.
     */
    private void scheduleKill(String pkg) {
        if (mPendingKill != null) {
            mHandler.removeCallbacks(mPendingKill);
        }

        mPendingKill = () -> {
            if (pkg != null) {
                killIfRunning(pkg);
            } else {
                for (String p : GOOGLE_PACKAGES) {
                    killIfRunning(p);
                }
            }
        };

        mHandler.postDelayed(mPendingKill, 500);
    }

    /**
     * Force-stops a package only if it is currently running.
     *
     * NOTE: getRunningAppProcesses() returns only the caller's own processes on Android 8+
     * for unprivileged apps. This fragment runs inside the privileged Settings process,
     * so the full process list is available here. If this class is ever moved outside of
     * system Settings, the running check will silently miss external processes and
     * forceStopPackage will still be called regardless.
     */
    private void killIfRunning(String pkg) {
        try {
            final Context context = getContext();
            if (context == null) return;

            if (context.checkSelfPermission("android.permission.FORCE_STOP_PACKAGES")
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "FORCE_STOP_PACKAGES not granted, skipping kill of " + pkg);
                return;
            }

            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (am == null) return;

            List<ActivityManager.RunningAppProcessInfo> processes =
                    am.getRunningAppProcesses();

            if (processes == null) return;

            for (ActivityManager.RunningAppProcessInfo proc : processes) {
                if (proc.pkgList == null) continue;

                for (String p : proc.pkgList) {
                    if (pkg.equals(p)) {
                        am.forceStopPackage(pkg);
                        Log.i(TAG, "Killed " + pkg);
                        return;
                    }
                }
            }

            Log.d(TAG, pkg + " not running, skip kill");

        } catch (Exception e) {
            Log.e(TAG, "Failed to kill " + pkg, e);
        }
    }

    // -----------------------------
    // Preference handling
    // -----------------------------
    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        boolean enabled = (Boolean) newValue;

        if (pref == mPhotosSpoof) {
            scheduleKill(PACKAGE_PHOTOS);
            toast(getString(enabled
                    ? R.string.spoof_enabled
                    : R.string.spoof_disabled, getString(R.string.spoof_photos_label)));
            return true;
        }

        if (pref == mSnapchatSpoof) {
            scheduleKill(PACKAGE_SNAPCHAT);
            toast(getString(enabled
                    ? R.string.spoof_enabled
                    : R.string.spoof_disabled, getString(R.string.spoof_snapchat_label)));
            return true;
        }

        if (pref == mTensorSpoof) {
            scheduleKill(null);
            toast(getString(enabled
                    ? R.string.spoof_enabled
                    : R.string.spoof_disabled, getString(R.string.spoof_tensor_label)));
            return true;
        }

        if (pref == mGoogleSpoof) {
            scheduleKill(null);
            toast(getString(R.string.spoof_pixel_props_updated));
            return true;
        }

        return false;
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }
}
