/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settings.core.BasePreferenceController;

public class CustomClockController extends BasePreferenceController {

    private static final String[] CLOCK_NAMES = {
        "Default Clock",
        "OnePlus Clock",
        "OnePlus Clock 2",
        "Center Clock",
        "Simple Clock",
        "MIUI Clock",
        "IDE Clock",
        "Moto Clock",
        "Stylish Clock",
        "Stylish Clock 2",
        "Stylish Clock 3",
        "Stylish Clock 4",
        "Stylish Clock 5",
        "Stylish Clock 6",
        "Stylish Clock 7",
        "Stylish Clock 8",
        "Stylish Clock 9",
        "Stylish Clock 10",
        "Text Clock",
        "LifeStyle Clock",
        "Android 9 Vibe",
        "NothingOS 1 Clock",
        "NothingOS 2 Clock",
        "Stacked Clock",
        "X Factor",
        "Simple Analog",
        "Block",
        "Bubble",
        "Label Clock",
        "iOS Clock",
        "Taden Clock",
        "Mont Clock",
        "Encode Clock",
        "NOS Clock 3",
        "Anci Outline",
        "Anci Ovalium",
        "Anci Rectangle",
        "Anci Wallet",
        "Anci Clavicula",
        "Anci KLN",
        "Anci Miring",
        "Anci Scapula",
        "Anci Sternum",
        "Spark Circle",
        "Spark List",
        "HyperOS Clock",
        "iOS 2",
        "iOS 3",
        "iOS 4",
        "iOS 5",
        "iOS 6",
        "iOS 7",
        "iOS 8",
        "iOS 9",
        "iOS 10",
        "iOS 11",
        "iOS 12",
        "Big 1",
        "Big 2",
        "Big 3",
        "Sweet",
        "Pixel",
        "Samurai",
        "Gateway",
    };

    public CustomClockController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        int index = Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                0, UserHandle.USER_CURRENT);
        if (index >= 0 && index < CLOCK_NAMES.length) {
            return CLOCK_NAMES[index];
        }
        return CLOCK_NAMES[0];
    }
}
