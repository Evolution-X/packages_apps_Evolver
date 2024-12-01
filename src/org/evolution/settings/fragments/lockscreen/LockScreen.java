/*
 * Copyright (C) 2019-2025 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
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
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.SecureSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SystemUtilsNew;
import org.evolution.settings.utils.TelephonyUtils;
// import org.evolution.settings.utils.ImageUtils;

@SearchIndexable
public class LockScreen extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockScreen";

    private static final String LOCKSCREEN_GESTURES_CATEGORY = "lockscreen_gestures_category";
    private static final String LOCKSCREEN_INTERFACE_CATEGORY = "lockscreen_interface_category";
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String KEY_SMARTSPACE = "lockscreen_smartspace_enabled";
    private static final String KEY_WEATHER = "lockscreen_weather_enabled";
    private static final String KEY_FP_SUCCESS = "fp_success_vibrate";
    private static final String KEY_FP_ERROR = "fp_error_vibrate";
    private static final String KEY_CARRIER_NAME = "lockscreen_show_carrier";
//    private static final String CUSTOM_IMAGE_REQUEST_CODE_KEY = "lockscreen_custom_image";
//    private static final int CUSTOM_IMAGE_REQUEST_CODE = 1001;

//    private Preference mCustomImagePreference;
    private Preference mRippleEffect;
    private SwitchPreferenceCompat mSmartspace;
    private SwitchPreferenceCompat mWeather;
    private SwitchPreferenceCompat mFpSuccessVib;
    private SwitchPreferenceCompat mFpErrorVib;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_lock_screen);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final Resources resources = context.getResources();

        PreferenceCategory gestCategory = (PreferenceCategory) findPreference(LOCKSCREEN_GESTURES_CATEGORY);

        mFpSuccessVib = findPreference(KEY_FP_SUCCESS);
        mFpErrorVib = findPreference(KEY_FP_ERROR);
        mRippleEffect = (Preference) findPreference(KEY_RIPPLE_EFFECT);

//        mCustomImagePreference = findPreference(CUSTOM_IMAGE_REQUEST_CODE_KEY);
//        int clockStyle = Settings.Secure.getIntForUser(getContext().getContentResolver(), "clock_style", 0, UserHandle.USER_CURRENT);
//        String imagePath = Settings.System.getString(getContext().getContentResolver(), "custom_aod_image_uri");
//        if (imagePath != null && clockStyle > 0) {
//            mCustomImagePreference.setSummary(imagePath);
//            mCustomImagePreference.setEnabled(true);
//        } else if (clockStyle == 0) {
//            mCustomImagePreference.setSummary(getContext().getString(R.string.custom_aod_image_not_supported));
//            mCustomImagePreference.setEnabled(false);
//        }

        boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
        if (!hasFingerprint) {
            gestCategory.removePreference(mRippleEffect);
        }

        boolean hapticAvailable = DeviceUtils.hasVibrator(context);
        if (!hasFingerprint || !hapticAvailable) {
            gestCategory.removePreference(mFpSuccessVib);
            gestCategory.removePreference(mFpErrorVib);
        }

        if (!TelephonyUtils.isVoiceCapable(context)) {
            PreferenceCategory intCategory = (PreferenceCategory) findPreference(LOCKSCREEN_INTERFACE_CATEGORY);
            SwitchPreferenceCompat carrierName = findPreference(KEY_CARRIER_NAME);
            intCategory.removePreference(carrierName);
        }

        mSmartspace = (SwitchPreferenceCompat) findPreference(KEY_SMARTSPACE);
        mSmartspace.setOnPreferenceChangeListener(this);

        mWeather = (SwitchPreferenceCompat) findPreference(KEY_WEATHER);
        mWeather.setOnPreferenceChangeListener(this);

        updateWeatherSettings();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mSmartspace) {
            mSmartspace.setChecked((Boolean)newValue);
            updateWeatherSettings();
            SystemUtilsNew.showSystemUiRestartDialog(getContext());
            return true;
        } else if (preference == mWeather) {
            mWeather.setChecked((Boolean)newValue);
            SystemUtilsNew.showSystemUiRestartDialog(getContext());
            return true;
        }
        return false;
    }

    private void updateWeatherSettings() {
        if (mWeather == null || mSmartspace == null) return;

        boolean weatherEnabled = OmniJawsClient.get().isOmniJawsEnabled(getContext());
        mWeather.setEnabled(!mSmartspace.isChecked() && weatherEnabled);
        mWeather.setSummary(!mSmartspace.isChecked() && weatherEnabled ? R.string.lockscreen_weather_summary :
            R.string.lockscreen_weather_enabled_info);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWeatherSettings();
    }

//    @Override
//    public boolean onPreferenceTreeClick(Preference preference) {
//        if (preference == mCustomImagePreference) {
//            try {
//                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
//                intent.setType("image/*");
//                startActivityForResult(intent, CUSTOM_IMAGE_REQUEST_CODE);
//            } catch(Exception e) {
//                Toast.makeText(getContext(), R.string.quick_settings_header_needs_gallery, Toast.LENGTH_LONG).show();
//            }
//            return true;
//        }
//        return super.onPreferenceTreeClick(preference);
//    }

//    @Override
//    public void onActivityResult(int requestCode, int resultCode, Intent result) {
//        super.onActivityResult(requestCode, resultCode, result);
//        if (requestCode == CUSTOM_IMAGE_REQUEST_CODE && resultCode == Activity.RESULT_OK && result != null) {
//            Uri imgUri = result.getData();
//            if (imgUri != null) {
//                String savedImagePath = ImageUtils.saveImageToInternalStorage(getContext(), imgUri, "lockscreen_aod_image", "LOCKSCREEN_CUSTOM_AOD_IMAGE");
//                if (savedImagePath != null) {
//                    ContentResolver resolver = getContext().getContentResolver();
//                    Settings.System.putStringForUser(resolver, "custom_aod_image_uri", savedImagePath, UserHandle.USER_CURRENT);
//                    mCustomImagePreference.setSummary(savedImagePath);
//                }
//            }
//        }
//    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_lock_screen) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);

                boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
                if (!hasFingerprint) {
                    keys.add(KEY_RIPPLE_EFFECT);
                }

                boolean hapticAvailable = DeviceUtils.hasVibrator(context);
                if (!hasFingerprint || !hapticAvailable) {
                    keys.add(KEY_FP_SUCCESS);
                    keys.add(KEY_FP_ERROR);
                }

                if (!TelephonyUtils.isVoiceCapable(context)) {
                    keys.add(KEY_CARRIER_NAME);
                }

                return keys;
            }
        };
}
