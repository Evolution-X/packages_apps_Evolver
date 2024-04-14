/*
 * Copyright (C) 2016-2018 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.ContentResolver;
import android.preference.PreferenceDataStore;
import android.provider.Settings;

public class GlobalSettingsStore extends androidx.preference.PreferenceDataStore
        implements PreferenceDataStore {

    private ContentResolver mContentResolver;

    public GlobalSettingsStore(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return getInt(key, defValue ? 1 : 0) != 0;
    }

    public float getFloat(String key, float defValue) {
        return Settings.Global.getFloat(mContentResolver, key, defValue);
    }

    public int getInt(String key, int defValue) {
        return Settings.Global.getInt(mContentResolver, key, defValue);
    }

    public long getLong(String key, long defValue) {
        return Settings.Global.getLong(mContentResolver, key, defValue);
    }

    public String getString(String key, String defValue) {
        String result = Settings.Global.getString(mContentResolver, key);
        return result == null ? defValue : result;
    }

    public void putBoolean(String key, boolean value) {
        putInt(key, value ? 1 : 0);
    }

    public void putFloat(String key, float value) {
        Settings.Global.putFloat(mContentResolver, key, value);
    }

    public void putInt(String key, int value) {
        Settings.Global.putInt(mContentResolver, key, value);
    }

    public void putLong(String key, long value) {
        Settings.Global.putLong(mContentResolver, key, value);
    }

    public void putString(String key, String value) {
        Settings.Global.putString(mContentResolver, key, value);
    }
}
