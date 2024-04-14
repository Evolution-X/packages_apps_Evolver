/*
 * Copyright (C) 2016-2022 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.ContentResolver;
import android.preference.PreferenceDataStore;
import android.os.UserHandle;

import lineageos.providers.LineageSettings;


public class LineageSecureSettingsStore extends androidx.preference.PreferenceDataStore
        implements PreferenceDataStore {

    private ContentResolver mContentResolver;

    public LineageSecureSettingsStore(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return LineageSettings.Secure.getIntForUser(mContentResolver, key, defValue ? 1 : 0, UserHandle.USER_CURRENT) != 0;
    }

    public float getFloat(String key, float defValue) {
        return LineageSettings.Secure.getFloatForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public int getInt(String key, int defValue) {
        return LineageSettings.Secure.getIntForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public long getLong(String key, long defValue) {
        return LineageSettings.Secure.getLongForUser(mContentResolver, key, defValue, UserHandle.USER_CURRENT);
    }

    public String getString(String key, String defValue) {
        String result = LineageSettings.Secure.getString(mContentResolver, key);
        return result == null ? defValue : result;
    }

    public void putBoolean(String key, boolean value) {
        putInt(key, value ? 1 : 0);
    }

    public void putFloat(String key, float value) {
        LineageSettings.Secure.putFloatForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putInt(String key, int value) {
        LineageSettings.Secure.putIntForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putLong(String key, long value) {
        LineageSettings.Secure.putLongForUser(mContentResolver, key, value, UserHandle.USER_CURRENT);
    }

    public void putString(String key, String value) {
        LineageSettings.Secure.putString(mContentResolver, key, value);
    }
}
