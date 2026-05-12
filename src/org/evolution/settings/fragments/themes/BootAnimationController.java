/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.Context;

import com.android.internal.util.evolution.PixelPropsUtils;
import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

import org.evolution.settings.utils.BootAnimationUtils;

public class BootAnimationController extends BasePreferenceController {

    public BootAnimationController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        // Custom fork builds spoof Pixel props, so check this first
        // to avoid incorrectly hiding the preference on non-Pixel fork devices.
        if (PixelPropsUtils.isCustomForkBuild()) {
            return UNSUPPORTED_ON_DEVICE;
        }
        if (BootAnimationUtils.isBootAnimationSelectorDisabled()) {
            return UNSUPPORTED_ON_DEVICE;
        }
        // Hide on genuine Pixel devices where boot animation overrides have no effect.
        // Delegates to PixelPropsUtils which checks ro.product.model and ro.soc.manufacturer.
        return BootAnimationUtils.isPixelDevice()
                ? UNSUPPORTED_ON_DEVICE
                : AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        int index = BootAnimationUtils.getBootAnimStyle();
        String[] entries = mContext.getResources().getStringArray(
                R.array.themes_boot_animation_entries);
        if (index >= 0 && index < entries.length) {
            return entries[index];
        }
        return mContext.getString(R.string.boot_animation_style_unknown);
    }
}
