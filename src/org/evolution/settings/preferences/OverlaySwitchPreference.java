/*
 * Copyright (C) 2022 Yet Another AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.evolution.settings.preferences;

import static android.os.UserHandle.CURRENT;
import static android.os.UserHandle.USER_CURRENT;

import android.content.Context;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;

import java.lang.SecurityException;
import java.util.List;

import lineageos.preference.SelfRemovingSwitchPreference;

public class OverlaySwitchPreference extends SelfRemovingSwitchPreference {

    private final static String TAG = "OverlaySwitchPreference";
    private final static String SETTINGSNS = "http://schemas.android.com/apk/res-auto";
    private static final String DKEY = "dkey";
    private static final String DKEY_NIGHT_ONLY = "dkeyNightOnly";

    private final String mDisableKey;
    private final boolean mDKeyNightOnly;
    private final OverlayManager mOverlayManager;

    public OverlaySwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mDisableKey = attrs.getAttributeValue(SETTINGSNS, DKEY);
        mDKeyNightOnly = attrs.getAttributeBooleanValue(SETTINGSNS, DKEY_NIGHT_ONLY, false);
        mOverlayManager = context.getSystemService(OverlayManager.class);
    }

    public OverlaySwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisableKey = attrs.getAttributeValue(SETTINGSNS, DKEY);
        mDKeyNightOnly = attrs.getAttributeBooleanValue(SETTINGSNS, DKEY_NIGHT_ONLY, false);
        mOverlayManager = context.getSystemService(OverlayManager.class);
    }

    public OverlaySwitchPreference(Context context) {
        this(context, null);
    }

    @Override
    protected boolean isPersisted() {
        return true;
    }

    @Override
    protected boolean getBoolean(String key, boolean defaultValue) {
        if (mOverlayManager == null) return false;
        OverlayInfo info = null;
        info = mOverlayManager.getOverlayInfo(getOverlayID(getKey()), CURRENT);
        if (info != null) return info.isEnabled();
        return false;
    }

    @Override
    protected void putBoolean(String key, boolean value) {
        if (mOverlayManager == null) return;
        OverlayManagerTransaction.Builder transaction = new OverlayManagerTransaction.Builder();
        transaction.setEnabled(getOverlayID(getKey()), value, USER_CURRENT);
        if (mDisableKey != null && !mDisableKey.isEmpty()) {
            if (mDKeyNightOnly) {
                final boolean isNight = (getContext().getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                if (isNight)
                    transaction.setEnabled(getOverlayID(mDisableKey), !value, USER_CURRENT);
                else // always enabled in day
                    transaction.setEnabled(getOverlayID(mDisableKey), true, USER_CURRENT);
            } else {
                transaction.setEnabled(getOverlayID(mDisableKey), !value, USER_CURRENT);
            }
        }
        try {
            mOverlayManager.commit(transaction.build());
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Failed setting overlay(s), future logs will point the reason");
            e.printStackTrace();
            return;
        }
    }

    private OverlayIdentifier getOverlayID(String name) {
        if (mOverlayManager == null) return null;
        try {
            if (name.contains(":")) {
                // specific overlay name in a package
                final String[] value = name.split(":");
                final String pkgName = value[0];
                final String overlayName = value[1];
                final List<OverlayInfo> infos =
                        mOverlayManager.getOverlayInfosForTarget(pkgName, CURRENT);
                for (OverlayInfo info : infos) {
                    if (overlayName.equals(info.getOverlayName()))
                        return info.getOverlayIdentifier();
                }
                Log.e(TAG, "No overlay found for " + name);
                return null;
            }
            // package with only one overlay
            OverlayInfo info = mOverlayManager.getOverlayInfo(name, CURRENT);
            if (info != null) {
                return info.getOverlayIdentifier();
            } else {
                Log.e(TAG, "No overlay info found for " + name);
                return null;
            }
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Error retrieving overlay ID for " + name, e);
            return null;
        }
    }
}
