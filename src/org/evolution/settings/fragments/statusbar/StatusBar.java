/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.Preference.OnPreferenceChangeListener;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import lineageos.preference.LineageSystemSettingListPreference;

import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class StatusBar extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "StatusBar";

    private static final String QUICK_PULLDOWN_KEY = "qs_quick_pulldown";

    private static final String ICONS_CATEGORY_KEY = "status_bar_icons_category";
    private static final String BLUETOOTH_BATTERY_STATUS_KEY = "bluetooth_show_battery";
    private static final String DATA_DISABLED_ICON_KEY = "data_disabled_icon";
    private static final String SHOW_4G_ICON_KEY = "show_fourg_icon";

    private static final int PULLDOWN_DIR_NONE = 0;
    private static final int PULLDOWN_DIR_RIGHT = 1;
    private static final int PULLDOWN_DIR_LEFT = 2;
    private static final int PULLDOWN_DIR_BOTH = 3;

    private LineageSystemSettingListPreference mQuickPulldown;

    private PreferenceCategory mIconsCategory;
    private Preference mBluetoothBatteryStatus;
    private Preference mDataDisabledIcon;
    private Preference mShow4gIcon;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_status_bar);
        Context mContext = getActivity().getApplicationContext();

        mQuickPulldown =
                (LineageSystemSettingListPreference) findPreference(QUICK_PULLDOWN_KEY);
        mQuickPulldown.setOnPreferenceChangeListener(this);
        updateQuickPulldownSummary(mQuickPulldown.getIntValue(0));

        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            mQuickPulldown.setEntries(R.array.status_bar_quick_pull_down_entries_rtl);
            mQuickPulldown.setEntryValues(R.array.status_bar_quick_pull_down_values_rtl);
        }

        mIconsCategory = (PreferenceCategory) findPreference(ICONS_CATEGORY_KEY);
        mBluetoothBatteryStatus = (Preference) findPreference(BLUETOOTH_BATTERY_STATUS_KEY);
        mDataDisabledIcon = (Preference) findPreference(DATA_DISABLED_ICON_KEY);
        mShow4gIcon = (Preference) findPreference(SHOW_4G_ICON_KEY);

        if (!DeviceUtils.deviceSupportsBluetooth(mContext)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
        }

        if (!DeviceUtils.deviceSupportsMobileData(mContext)) {
            mIconsCategory.removePreference(mDataDisabledIcon);
            mIconsCategory.removePreference(mShow4gIcon);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mQuickPulldown) {
            int value = Integer.parseInt((String) newValue);
            updateQuickPulldownSummary(value);
            return true;
        }
        return false;
    }

    private void updateQuickPulldownSummary(int value) {
        String summary = "";
        switch (value) {
            case PULLDOWN_DIR_NONE:
                summary = getResources().getString(
                    R.string.status_bar_quick_pull_down_off);
                break;
            case PULLDOWN_DIR_RIGHT:
            case PULLDOWN_DIR_LEFT:
            case PULLDOWN_DIR_BOTH:
                summary = getResources().getString(
                    R.string.status_bar_quick_pull_down_summary,
                    getResources().getString(
                        value == PULLDOWN_DIR_RIGHT
                            ? R.string.status_bar_quick_pull_down_right
                            : value == PULLDOWN_DIR_LEFT
                                ? R.string.status_bar_quick_pull_down_left
                                : R.string.status_bar_quick_pull_down_both
                    )
                );
                break;
        }
        mQuickPulldown.setSummary(summary);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_status_bar);
}
