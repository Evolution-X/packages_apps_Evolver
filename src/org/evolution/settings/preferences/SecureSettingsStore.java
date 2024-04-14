/*
 * Copyright (C) 2016-2018 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.ContentResolver;
import android.preference.PreferenceDataStore;
import android.os.UserHandle;
import android.provider.Settings;

public class SecureSettingsStore extends androidx.preference.PreferenceDataStore
        implements PreferenceDataStore {

    private ContentResolver mContentResolver;

    public SecureSettingsStore(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return Settings.Secure.getIntForUser(mContentResolver, key, defValue ? 1 : 0, UserHandle.USER_CURRENT) != 0;
    }

    public float getFloat(String key, float defValue) {
        return Settings.Secure.getFloatForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public int getInt(String key, int defValue) {
        return Settings.Secure.getIntForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public long getLong(String key, long defValue) {
        return Settings.Secure.getLongForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public String getString(String key, String defValue) {
        String result = Settings.Secure.getString(mContentResolver, key);
        return result == null ? defValue : result;
    }

    public void putBoolean(String key, boolean value) {
        putInt(key, value ? 1 : 0);
    }

    public void putFloat(String key, float value) {
        Settings.Secure.putFloatForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putInt(String key, int value) {
        Settings.Secure.putIntForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putLong(String key, long value) {
        Settings.Secure.putLongForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putString(String key, String value) {
        Settings.Secure.putString(mContentResolver, key, value);
    }
}
