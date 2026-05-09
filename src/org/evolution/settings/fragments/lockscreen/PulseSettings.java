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

    private SecureSettingListPreference mPulseRenderer;
    private SecureSettingListPreference mPulseColor;
    private SecureSettingColorPickerPreference mPulseCustomColor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.pulse_settings);

        mPulseRenderer = (SecureSettingListPreference) findPreference(KEY_PULSE_RENDERER);
        mPulseColor = (SecureSettingListPreference) findPreference(KEY_PULSE_COLOR);
        mPulseCustomColor = (SecureSettingColorPickerPreference) findPreference(KEY_PULSE_CUSTOM_COLOR);

        if (mPulseRenderer != null) {
            mPulseRenderer.setOnPreferenceChangeListener(this);
            String currentRenderer = Settings.Secure.getStringForUser(
                    getContentResolver(),
                    Settings.Secure.PULSE_RENDERER,
                    UserHandle.USER_CURRENT);
            updatePreferenceVisibility(currentRenderer, getCurrentColorMode());
        }

        if (mPulseColor != null) {
            mPulseColor.setOnPreferenceChangeListener(this);
            updatePreferenceVisibility(getCurrentRenderer(), getCurrentColorMode());
        }

        boolean hapticAvailable = DeviceUtils.hasVibrator(getContext());
        if (!hapticAvailable) {
            getPreferenceScreen().removePreference(findPreference(KEY_PULSE_BASS_HAPTICS));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mPulseRenderer) {
            String value = (String) newValue;
            updatePreferenceVisibility(value, getCurrentColorMode());
            return true;
        } else if (preference == mPulseColor) {
            String value = (String) newValue;
            updatePreferenceVisibility(getCurrentRenderer(), value);
            return true;
        }
        return false;
    }

    private void updatePreferenceVisibility(String rendererValue, String colorValue) {
        if (mPulseColor != null && mPulseCustomColor != null) {
            boolean isMatrix = "matrix".equals(rendererValue);
            boolean isCustomColor = "custom".equals(colorValue);
            mPulseColor.setVisible(!isMatrix);
            mPulseCustomColor.setVisible(!isMatrix && isCustomColor);
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

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.pulse_settings);
}
