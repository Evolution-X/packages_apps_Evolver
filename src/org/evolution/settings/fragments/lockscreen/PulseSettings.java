/*
 * Copyright (C) 2016-2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.preferences.SecureSettingListPreference;
import org.evolution.settings.preferences.colorpicker.SecureSettingColorPickerPreference;
import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class PulseSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_PULSE_BASS_HAPTICS = "pulse_bass_haptics";
    private static final String KEY_PULSE_RENDERER = "pulse_renderer";
    private static final String KEY_PULSE_COLOR = "pulse_color";
    private static final String KEY_PULSE_CUSTOM_COLOR = "pulse_custom_color";
    private static final String KEY_PULSE_CAPTURE_MODE = "pulse_capture_mode";
    private static final String KEY_PULSE_ROUND_OUTPUT = "pulse_rounded_bars";

    private SecureSettingListPreference mPulseRenderer;
    private SecureSettingListPreference mPulseColor;
    private SecureSettingListPreference mPulseBassHaptics;
    private SecureSettingListPreference mPulseCaptureMode;
    private SecureSettingColorPickerPreference mPulseCustomColor;
    private SwitchPreferenceCompat mPulseRoundOutput;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.pulse_settings);

        mPulseRenderer = (SecureSettingListPreference) findPreference(KEY_PULSE_RENDERER);
        mPulseColor = (SecureSettingListPreference) findPreference(KEY_PULSE_COLOR);
        mPulseCustomColor = (SecureSettingColorPickerPreference) findPreference(KEY_PULSE_CUSTOM_COLOR);
        mPulseCaptureMode = (SecureSettingListPreference) findPreference(KEY_PULSE_CAPTURE_MODE);
        mPulseBassHaptics = (SecureSettingListPreference) findPreference(KEY_PULSE_BASS_HAPTICS);
        mPulseRoundOutput = (SwitchPreferenceCompat) findPreference(KEY_PULSE_ROUND_OUTPUT);

        if (mPulseRenderer != null) {
            mPulseRenderer.setOnPreferenceChangeListener(this);
            String currentRenderer = Settings.Secure.getStringForUser(
                    getContentResolver(),
                    Settings.Secure.PULSE_RENDERER,
                    UserHandle.USER_CURRENT);
            updatePreferenceVisibility(currentRenderer, getCurrentColorMode(), getCurrentCaptureMode());
        }

        if (mPulseColor != null) {
            mPulseColor.setOnPreferenceChangeListener(this);
            updatePreferenceVisibility(getCurrentRenderer(), getCurrentColorMode(), getCurrentCaptureMode());
        }

        boolean hapticAvailable = DeviceUtils.hasVibrator(getContext());
        if (!hapticAvailable) {
            mPulseBassHaptics.setVisible(false);
        }

        if (mPulseCaptureMode != null) {
            mPulseCaptureMode.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mPulseRenderer) {
            String value = (String) newValue;
            updatePreferenceVisibility(value, getCurrentColorMode(), getCurrentCaptureMode());
            return true;
        } else if (preference == mPulseColor) {
            String value = (String) newValue;
            updatePreferenceVisibility(getCurrentRenderer(), value, getCurrentCaptureMode());
            return true;
        } else if (preference == mPulseCaptureMode) {
            String value = (String) newValue; 
            updatePreferenceVisibility(getCurrentRenderer(), getCurrentColorMode(), value);
            return true;
        }
        return false;
    }

    private void updatePreferenceVisibility(String rendererValue, String colorValue, String captureModeValue) {
        if (captureModeValue != null) {
            setBassHapticPreference(!captureModeValue.equals("1"));  
        }

        if (rendererValue != null) {
            boolean supportsColoring = false;
            boolean supportsRounding = false;
            switch (rendererValue) {
                case "minimal", "solid" -> {
                    supportsRounding = true;
                    supportsColoring = true;
                }
                case "fading", "matrix", "neon", "particle", "sparkle", "waveform" -> {
                    supportsColoring = true;
                }
            }
            mPulseRoundOutput.setVisible(supportsRounding);
            mPulseColor.setVisible(supportsColoring);
            mPulseCustomColor.setVisible(supportsColoring && "custom".equals(colorValue));
        }
    }

    private String getCurrentRenderer() {
        return Settings.Secure.getStringForUser(
                getContentResolver(),
                Settings.Secure.PULSE_RENDERER,
                UserHandle.USER_CURRENT);
    }

    private String getCurrentColorMode() {
        return Settings.Secure.getStringForUser(
                getContentResolver(),
                Settings.Secure.PULSE_COLOR,
                UserHandle.USER_CURRENT);
    }

    private String getCurrentCaptureMode() {
        return Settings.Secure.getStringForUser(
                getContentResolver(),
                Settings.Secure.PULSE_CAPTURE_MODE,
                UserHandle.USER_CURRENT);
    }

    private void setBassHapticPreference(boolean enabled) {
        mPulseBassHaptics.setEnabled(enabled);
        if (enabled) {
            mPulseBassHaptics.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        } else {
            mPulseBassHaptics.setSummaryProvider(null);
            mPulseBassHaptics.setSummary(R.string.pulse_bass_haptics_disabled_amplitude);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.pulse_settings);
}
