/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;

import androidx.preference.PreferenceDataStore;

import com.android.settingslib.PrimarySwitchPreference;

public class SecureSettingPrimarySwitchPreference extends PrimarySwitchPreference {

    public SecureSettingPrimarySwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPreferenceDataStore(new DataStore());
    }

    public SecureSettingPrimarySwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPreferenceDataStore(new DataStore());
    }

    public SecureSettingPrimarySwitchPreference(Context context) {
        super(context);
        setPreferenceDataStore(new DataStore());
    }

    @Override
    public void onAttached() {
        super.onAttached();
        setChecked(Settings.Secure.getIntForUser(getContext().getContentResolver(),
                getKey(), 0, UserHandle.USER_CURRENT) != 0);
    }

    private class DataStore extends PreferenceDataStore {
        @Override
        public void putBoolean(String key, boolean value) {
            Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    key, value ? 1 : 0, UserHandle.USER_CURRENT);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                    key, defaultValue ? 1 : 0, UserHandle.USER_CURRENT) != 0;
        }
    }
}
