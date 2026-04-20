/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.Context;

import com.android.internal.util.evolution.PixelPropsUtils;
import com.android.settings.core.BasePreferenceController;

import org.evolution.settings.utils.BootAnimationUtils;

public class BootAnimationController extends BasePreferenceController {

    public BootAnimationController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        // Hide this preference entirely on genuine Pixel devices.
        // BootAnimationUtils.isPixelDevice() delegates to
        // PixelPropsUtils.isSupportedPixelDevice() which checks
        // ro.product.model against SUPPORTED_PIXEL_PATTERN and
        // ro.soc.manufacturer == "Google".
        if (PixelPropsUtils.isCustomForkBuild()) {
            return UNSUPPORTED_ON_DEVICE;
        }

        return BootAnimationUtils.isPixelDevice()
                ? UNSUPPORTED_ON_DEVICE
                : AVAILABLE;
    }
}
