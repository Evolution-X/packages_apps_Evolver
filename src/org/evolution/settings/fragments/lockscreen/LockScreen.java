/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.OmniJawsClient;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.SecureSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class LockScreen extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockScreen";

    private static final String KEY_FP_ERROR = "fp_error_vibrate";
    private static final String KEY_FP_SUCCESS = "fp_success_vibrate";
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String KEY_SMARTSPACE = "lockscreen_smartspace_enabled";
    private static final String KEY_WEATHER = "lockscreen_weather_enabled";
    private static final String LOCKSCREEN_GESTURES_CATEGORY = "lockscreen_gestures_category";

    private Preference mRippleEffect;
    private SwitchPreferenceCompat mFpErrorVib;
    private SwitchPreferenceCompat mFpSuccessVib;
    private SwitchPreferenceCompat mSmartspace;
    private SwitchPreferenceCompat mWeather;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_lock_screen);

        final Context context = getContext();

        final PreferenceCategory gestCategory = findPreference(LOCKSCREEN_GESTURES_CATEGORY);

        mFpSuccessVib = findPreference(KEY_FP_SUCCESS);
        mFpErrorVib = findPreference(KEY_FP_ERROR);
        mRippleEffect = findPreference(KEY_RIPPLE_EFFECT);

        boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
        if (!hasFingerprint) {
            gestCategory.removePreference(mRippleEffect);
        }

        if (!hasFingerprint || !DeviceUtils.hasVibrator(context)) {
            gestCategory.removePreference(mFpSuccessVib);
            gestCategory.removePreference(mFpErrorVib);
        }

        mSmartspace = findPreference(KEY_SMARTSPACE);
        mSmartspace.setOnPreferenceChangeListener(this);

        mWeather = findPreference(KEY_WEATHER);
        mWeather.setOnPreferenceChangeListener(this);

        updateWeatherSettings();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mSmartspace) {
            updateWeatherSettings();
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        } else if (preference == mWeather) {
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        }
        return false;
    }

    private void updateWeatherSettings() {
        boolean weatherEnabled = OmniJawsClient.get().isOmniJawsEnabled(getContext());
        mWeather.setEnabled(!mSmartspace.isChecked() && weatherEnabled);
        mWeather.setSummary(!mSmartspace.isChecked() && weatherEnabled
                ? R.string.lockscreen_weather_summary
                : R.string.lockscreen_weather_enabled_info);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWeatherSettings();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
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

                    boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
                    if (!hasFingerprint) {
                        keys.add(KEY_RIPPLE_EFFECT);
                    }

                    if (!hasFingerprint || !DeviceUtils.hasVibrator(context)) {
                        keys.add(KEY_FP_SUCCESS);
                        keys.add(KEY_FP_ERROR);
                    }

                    return keys;
                }
            };
}
