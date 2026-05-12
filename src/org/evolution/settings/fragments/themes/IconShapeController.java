/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.content.pm.PackageManager;

import com.android.internal.util.evolution.ThemeUtils;
import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

import static com.android.internal.util.evolution.ThemeUtils.ICON_SHAPE_KEY;

public class IconShapeController extends BasePreferenceController {

    private final ThemeUtils mThemeUtils;

    public IconShapeController(Context context, String preferenceKey) {
        super(context, preferenceKey);
        mThemeUtils = ThemeUtils.getInstance(context);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        String pkg = mThemeUtils.getOverlayInfos(ICON_SHAPE_KEY).stream()
                .filter(info -> info.isEnabled())
                .map(info -> info.packageName)
                .findFirst()
                .orElse("android");

        if ("android".equals(pkg)) {
            return mContext.getString(R.string.default_value);
        }

        try {
            return mContext.getPackageManager()
                    .getApplicationInfo(pkg, 0)
                    .loadLabel(mContext.getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
