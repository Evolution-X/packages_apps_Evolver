/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settings.core.BasePreferenceController;

public class ClockChipController extends BasePreferenceController {

    public ClockChipController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        int index = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.STATUSBAR_CLOCK_CHIP,
                0, UserHandle.USER_CURRENT);
        String[] labels = mContext.getResources().getStringArray(
                com.android.settings.R.array.statusbar_clock_chip_labels);
        if (index >= 0 && index < labels.length) {
            return labels[index];
        }
        return labels[0];
    }
}
