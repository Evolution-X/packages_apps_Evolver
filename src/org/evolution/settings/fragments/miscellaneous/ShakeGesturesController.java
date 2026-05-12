/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.content.Context;
import android.os.UserHandle;

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
        Action action = Action.fromSettings(
                mContext.getContentResolver(),
                LineageSettings.System.KEY_SHAKE_GESTURE_ACTION,
                Action.NOTHING);
        String[] entries = mContext.getResources().getStringArray(
                R.array.hardware_keys_action_entries);
        int index = action.ordinal();
        if (index >= 0 && index < entries.length) {
            return entries[index];
        }
        return entries[0];
    }
}
