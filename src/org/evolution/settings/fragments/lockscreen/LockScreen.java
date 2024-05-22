/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.SecureSettingSwitchPreference;

@SearchIndexable
public class LockScreen extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockScreen";

    private static final String KEY_FINGERPRINT_CATEGORY = "lock_screen_fingerprint_category";
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String KEY_SCREEN_OFF_UDFPS = "screen_off_udfps_enabled";
    private static final String KEY_AUTHENTICATION_SUCCESS = "fp_success_vibrate";
    private static final String KEY_AUTHENTICATION_ERROR = "fp_error_vibrate";
    private static final String KEY_MISCELLANEOUS_CATEGORY = "lock_screen_miscellaneous_category";
    private static final String KEY_POCKET_DETECTION = "pocket_judge";

    private PreferenceCategory mFingerprintCategory;
    private SecureSettingSwitchPreference mScreenOffUdfps;
    private PreferenceCategory mMiscellaneousCategory;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_lock_screen);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mFingerprintCategory = (PreferenceCategory) findPreference(KEY_FINGERPRINT_CATEGORY);
        mScreenOffUdfps = (SecureSettingSwitchPreference) findPreference(KEY_SCREEN_OFF_UDFPS);
        mMiscellaneousCategory = (PreferenceCategory) findPreference(KEY_MISCELLANEOUS_CATEGORY);

        FingerprintManager fingerprintManager = (FingerprintManager)
                getActivity().getSystemService(Context.FINGERPRINT_SERVICE);

        if (fingerprintManager == null || !fingerprintManager.isHardwareDetected()) {
            prefScreen.removePreference(mFingerprintCategory);
        } else {
            boolean screenOffUdfpsAvailable = resources.getBoolean(
                    com.android.internal.R.bool.config_supportScreenOffUdfps) ||
                    !TextUtils.isEmpty(resources.getString(
                            com.android.internal.R.string.config_dozeUdfpsLongPressSensorType));

            if (!screenOffUdfpsAvailable) {
                mFingerprintCategory.removePreference(mScreenOffUdfps);
            }
        }

        boolean pocketDetectionAvailable = resources.getBoolean(
                com.android.internal.R.bool.config_pocketModeSupported);
        if (!pocketDetectionAvailable) {
            prefScreen.removePreference(mMiscellaneousCategory);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_lock_screen) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

                FingerprintManager fingerprintManager = (FingerprintManager)
                    context.getSystemService(Context.FINGERPRINT_SERVICE);

                if (fingerprintManager == null || !fingerprintManager.isHardwareDetected()) {
                    keys.add(KEY_RIPPLE_EFFECT);
                    keys.add(KEY_SCREEN_OFF_UDFPS);
                    keys.add(KEY_AUTHENTICATION_SUCCESS);
                    keys.add(KEY_AUTHENTICATION_ERROR);
                } else {
                    boolean screenOffUdfpsAvailable = resources.getBoolean(
                        com.android.internal.R.bool.config_supportScreenOffUdfps) ||
                        !TextUtils.isEmpty(resources.getString(
                            com.android.internal.R.string.config_dozeUdfpsLongPressSensorType));
                    if (!screenOffUdfpsAvailable) {
                        keys.add(KEY_SCREEN_OFF_UDFPS);
                    }
                }
                boolean pocketDetectionAvailable = resources.getBoolean(
                        com.android.internal.R.bool.config_pocketModeSupported);
                if (!pocketDetectionAvailable) {
                    keys.add(KEY_POCKET_DETECTION);
                }
                return keys;
            }
        };
}
