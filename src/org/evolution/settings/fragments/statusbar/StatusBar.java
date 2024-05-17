/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import lineageos.preference.LineageSystemSettingListPreference;

import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class StatusBar extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "StatusBar";

    private static final String KEY_QUICK_PULLDOWN = "qs_quick_pulldown";
    private static final String KEY_ICONS_CATEGORY = "status_bar_icons_category";
    private static final String KEY_BATTERY_STYLE = "status_bar_battery_style";
    private static final String KEY_BATTERY_PERCENT = "status_bar_show_battery_percent";
    private static final String KEY_BATTERY_TEXT_CHARGING = "status_bar_battery_text_charging";
    private static final String KEY_DATA_DISABLED_ICON = "data_disabled_icon";
    private static final String KEY_BLUETOOTH_BATTERY_STATUS = "bluetooth_show_battery";
    private static final String KEY_FOUR_G_ICON = "show_fourg_icon";

    private static final int PULLDOWN_DIR_NONE = 0;
    private static final int PULLDOWN_DIR_RIGHT = 1;
    private static final int PULLDOWN_DIR_LEFT = 2;
    private static final int PULLDOWN_DIR_BOTH = 3;
    private static final int BATTERY_STYLE_PORTRAIT = 0;
    private static final int BATTERY_STYLE_TEXT = 4;
    private static final int BATTERY_STYLE_HIDDEN = 5;

    private LineageSystemSettingListPreference mQuickPulldown;
    private PreferenceCategory mIconsCategory;
    private SystemSettingListPreference mBatteryPercent;
    private SystemSettingListPreference mBatteryStyle;
    private SystemSettingSwitchPreference mBatteryTextCharging;
    private SystemSettingSwitchPreference mDataDisabledIcon;
    private SystemSettingSwitchPreference mFourgIcon;
    private SystemSettingSwitchPreference mBluetoothBatteryStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_status_bar);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mQuickPulldown =
                (LineageSystemSettingListPreference) findPreference(KEY_QUICK_PULLDOWN);
        mQuickPulldown.setOnPreferenceChangeListener(this);
        updateQuickPulldownSummary(mQuickPulldown.getIntValue(0));

        mIconsCategory = (PreferenceCategory) findPreference(KEY_ICONS_CATEGORY);
        mBatteryStyle = (SystemSettingListPreference) findPreference(KEY_BATTERY_STYLE);
        mBatteryPercent = (SystemSettingListPreference) findPreference(KEY_BATTERY_PERCENT);
        mBatteryTextCharging = (SystemSettingSwitchPreference) findPreference(KEY_BATTERY_TEXT_CHARGING);
        mBluetoothBatteryStatus = (SystemSettingSwitchPreference) findPreference(KEY_BLUETOOTH_BATTERY_STATUS);
        mDataDisabledIcon = (SystemSettingSwitchPreference) findPreference(KEY_DATA_DISABLED_ICON);
        mFourgIcon = (SystemSettingSwitchPreference) findPreference(KEY_FOUR_G_ICON);
        mBluetoothBatteryStatus = (SystemSettingSwitchPreference) findPreference(KEY_BLUETOOTH_BATTERY_STATUS);

        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            mQuickPulldown.setEntries(R.array.status_bar_quick_pull_down_entries_rtl);
            mQuickPulldown.setEntryValues(R.array.status_bar_quick_pull_down_values_rtl);
        }

        int batterystyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);
        int batterypercent = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT);

        mBatteryStyle.setOnPreferenceChangeListener(this);

        mBatteryPercent.setEnabled(
                batterystyle != BATTERY_STYLE_TEXT && batterystyle != BATTERY_STYLE_HIDDEN);
        mBatteryPercent.setOnPreferenceChangeListener(this);

        mBatteryTextCharging.setEnabled(batterystyle == BATTERY_STYLE_HIDDEN ||
                (batterystyle != BATTERY_STYLE_TEXT && batterypercent != 2));

        if (!DeviceUtils.deviceSupportsMobileData(context)) {
            mIconsCategory.removePreference(mDataDisabledIcon);
            mIconsCategory.removePreference(mFourgIcon);
        }

        if (!DeviceUtils.deviceSupportsBluetooth(context)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mQuickPulldown) {
            int value = Integer.parseInt((String) newValue);
            updateQuickPulldownSummary(value);
            return true;
        } else if (preference == mBatteryStyle) {
            int value = Integer.parseInt((String) newValue);
            int batterypercent = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT);
            mBatteryPercent.setEnabled(
                    value != BATTERY_STYLE_TEXT && value != BATTERY_STYLE_HIDDEN);
            mBatteryTextCharging.setEnabled(value == BATTERY_STYLE_HIDDEN ||
                    (value != BATTERY_STYLE_TEXT && batterypercent != 2));
            return true;
        } else if (preference == mBatteryPercent) {
            int value = Integer.parseInt((String) newValue);
            int batterystyle = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUS_BAR_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);
            mBatteryTextCharging.setEnabled(batterystyle == BATTERY_STYLE_HIDDEN ||
                    (batterystyle != BATTERY_STYLE_TEXT && value != 2));
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
        new BaseSearchIndexProvider(R.xml.evolution_settings_status_bar) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

                if (!DeviceUtils.deviceSupportsMobileData(context)) {
                    keys.add(KEY_DATA_DISABLED_ICON);
                    keys.add(KEY_FOUR_G_ICON);
                }
                if (!DeviceUtils.deviceSupportsBluetooth(context)) {
                    keys.add(KEY_BLUETOOTH_BATTERY_STATUS);
                }
                return keys;
            }
        };
}
