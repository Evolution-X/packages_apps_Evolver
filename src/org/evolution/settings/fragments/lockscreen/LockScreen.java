/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

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
import org.evolution.settings.utils.ImageUtils;

@SearchIndexable
public class LockScreen extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockScreen";

    private static final String KEY_FINGERPRINT_CATEGORY = "lock_screen_fingerprint_category";
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String KEY_SCREEN_OFF_UDFPS = "screen_off_udfps_enabled";
    private static final String KEY_AUTHENTICATION_SUCCESS = "fp_success_vibrate";
    private static final String KEY_AUTHENTICATION_ERROR = "fp_error_vibrate";
    private static final String CUSTOM_IMAGE_REQUEST_CODE_KEY = "lockscreen_custom_image";
    private static final int CUSTOM_IMAGE_REQUEST_CODE = 1001;

    private Preference mCustomImagePreference;
    private PreferenceCategory mFingerprintCategory;
    private SecureSettingSwitchPreference mScreenOffUdfps;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_lock_screen);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mCustomImagePreference = findPreference(CUSTOM_IMAGE_REQUEST_CODE_KEY);
        int clockStyle = Settings.Secure.getIntForUser(getContext().getContentResolver(), "clock_style", 0, UserHandle.USER_CURRENT);
        String imagePath = Settings.System.getString(getContext().getContentResolver(), "custom_aod_image_uri");
        if (imagePath != null && clockStyle > 0) {
            mCustomImagePreference.setSummary(imagePath);
            mCustomImagePreference.setEnabled(true);
        } else if (clockStyle == 0) {
            mCustomImagePreference.setSummary(getContext().getString(R.string.custom_aod_image_not_supported));
            mCustomImagePreference.setEnabled(false);
        }

        mFingerprintCategory = (PreferenceCategory) findPreference(KEY_FINGERPRINT_CATEGORY);
        mScreenOffUdfps = (SecureSettingSwitchPreference) findPreference(KEY_SCREEN_OFF_UDFPS);

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
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mCustomImagePreference) {
            try {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                startActivityForResult(intent, CUSTOM_IMAGE_REQUEST_CODE);
            } catch(Exception e) {
                Toast.makeText(getContext(), R.string.quick_settings_header_needs_gallery, Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == CUSTOM_IMAGE_REQUEST_CODE && resultCode == Activity.RESULT_OK && result != null) {
            Uri imgUri = result.getData();
            if (imgUri != null) {
                String savedImagePath = ImageUtils.saveImageToInternalStorage(getContext(), imgUri, "lockscreen_aod_image", "LOCKSCREEN_CUSTOM_AOD_IMAGE");
                if (savedImagePath != null) {
                    ContentResolver resolver = getContext().getContentResolver();
                    Settings.System.putStringForUser(resolver, "custom_aod_image_uri", savedImagePath, UserHandle.USER_CURRENT);
                    mCustomImagePreference.setSummary(savedImagePath);
                }
            }
        }
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
                return keys;
            }
        };
}
