/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

import lineageos.providers.LineageSettings;

import static org.lineageos.internal.util.DeviceKeysConstants.Action;

public class ShakeGesturesController extends BasePreferenceController {

    public ShakeGesturesController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        boolean enabled = Settings.System.getInt(
                mContext.getContentResolver(),
                "shake_gestures_enabled", 0) == 1;

        if (!enabled) {
            return mContext.getString(com.android.settings.R.string.gesture_setting_off);
        }

        Action action = Action.fromSettings(
                mContext.getContentResolver(),
                LineageSettings.System.KEY_SHAKE_GESTURE_ACTION,
                Action.NOTHING);

        if (action == Action.NOTHING) {
            return mContext.getString(com.android.settings.R.string.gesture_setting_off);
        }

        String[] entries = mContext.getResources().getStringArray(
                com.android.settings.R.array.hardware_keys_action_entries);
        int index = action.ordinal();
        String actionLabel = (index >= 0 && index < entries.length) ? entries[index] : entries[0];

        return mContext.getString(com.android.settings.R.string.gesture_setting_on)
                + " / " + actionLabel;
    }
}
