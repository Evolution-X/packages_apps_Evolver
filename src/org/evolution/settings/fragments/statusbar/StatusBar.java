/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import lineageos.preference.LineageSystemSettingListPreference;

import org.evolution.settings.fragments.statusbar.ClockChipController;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.SystemUtils;
import org.evolution.settings.utils.TelephonyUtils;

@SearchIndexable
public class StatusBar extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "StatusBar";

    private static final String KEY_BLUETOOTH_BATTERY_STATUS = "bluetooth_show_battery";
    private static final String KEY_CLOCK_CHIP = "statusbar_clock_chip";
    private static final String KEY_COLORED_ICONS = "statusbar_colored_icons";
    private static final String KEY_ICONS_CATEGORY = "status_bar_icons_category";
    private static final String KEY_QUICK_PULLDOWN = "qs_quick_pulldown";
    private static final String STATUS_BAR_CLOCK_STYLE = "status_bar_clock";
    private static final String STATUS_BAR_CARRIER_KEY = "status_bar_carrier_key";
    private static final String CARRIER_NAME = "lockscreen_show_carrier";
    private static final String CUSTOM_CARRIER_LABEL = "lockscreen_show_custom_carrier_text";

    private static final int PULLDOWN_DIR_NONE = 0;
    private static final int PULLDOWN_DIR_RIGHT = 1;
    private static final int PULLDOWN_DIR_LEFT = 2;
    private static final int PULLDOWN_DIR_BOTH = 3;

    private LineageSystemSettingListPreference mQuickPulldown;
    private LineageSystemSettingListPreference mStatusBarClock;
    private PreferenceCategory mIconsCategory;
    private SystemSettingSwitchPreference mBluetoothBatteryStatus;
    private SystemSettingSwitchPreference mColoredIcons;

    private Preference mCustomCarrierTextPref;
    private String mCustomCarrierText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_status_bar);

        final Context context = getContext();

        mStatusBarClock = findPreference(STATUS_BAR_CLOCK_STYLE);

        // Adjust status bar preferences for RTL
        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            if (DeviceUtils.hasCenteredCutout(context)) {
                mStatusBarClock.setEntries(R.array.status_bar_clock_position_entries_notch_rtl);
                mStatusBarClock.setEntryValues(R.array.status_bar_clock_position_values_notch_rtl);
            } else {
                mStatusBarClock.setEntries(R.array.status_bar_clock_position_entries_rtl);
                mStatusBarClock.setEntryValues(R.array.status_bar_clock_position_values_rtl);
            }
        } else if (DeviceUtils.hasCenteredCutout(context)) {
            mStatusBarClock.setEntries(R.array.status_bar_clock_position_entries_notch);
            mStatusBarClock.setEntryValues(R.array.status_bar_clock_position_values_notch);
        }

        mQuickPulldown = findPreference(KEY_QUICK_PULLDOWN);
        mQuickPulldown.setOnPreferenceChangeListener(this);
        updateQuickPulldownSummary(mQuickPulldown.getIntValue(0));

        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            mQuickPulldown.setEntries(R.array.status_bar_quick_pull_down_entries_rtl);
            mQuickPulldown.setEntryValues(R.array.status_bar_quick_pull_down_values_rtl);
        }

        mIconsCategory = findPreference(KEY_ICONS_CATEGORY);
        mBluetoothBatteryStatus = findPreference(KEY_BLUETOOTH_BATTERY_STATUS);
        mColoredIcons = findPreference(KEY_COLORED_ICONS);
        mColoredIcons.setOnPreferenceChangeListener(this);

        if (!DeviceUtils.deviceSupportsBluetooth(context)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
        }

        updateClockChipSummary();

        if (!TelephonyUtils.isVoiceCapable(mContext)) {
            Preference carrierCategory = findPreference(STATUS_BAR_CARRIER_KEY);
            if (carrierCategory != null) {
                prefScreen.removePreference(carrierCategory);
            }
        } else {
            mCustomCarrierTextPref = findPreference(CUSTOM_CARRIER_LABEL);
            updateCustomCarrierTextSummary();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mQuickPulldown) {
            updateQuickPulldownSummary(Integer.parseInt((String) newValue));
            return true;
        } else if (preference == mColoredIcons) {
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (CUSTOM_CARRIER_LABEL.equals(preference.getKey())) {
            final ContentResolver resolver = getActivity().getContentResolver();

            AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
            alert.setTitle(R.string.custom_carrier_label_title);
            alert.setMessage(R.string.custom_carrier_label_dialog_message);

            LinearLayout container = new LinearLayout(getActivity());
            container.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(55, 20, 55, 20);

            final EditText input = new EditText(getActivity());
            input.setText(TextUtils.isEmpty(mCustomCarrierText) ? "" : mCustomCarrierText);
            input.setSelection(input.getText().length());
            input.setLayoutParams(lp);
            input.setGravity(Gravity.START | Gravity.TOP);
            container.addView(input);
            alert.setView(container);

            alert.setPositiveButton(getString(android.R.string.ok),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String value = input.getText().toString();
                            Settings.System.putStringForUser(resolver,
                                    CUSTOM_CARRIER_LABEL, value, UserHandle.USER_CURRENT);
                            updateCustomCarrierTextSummary();
                        }
                    });
            alert.setNegativeButton(getString(android.R.string.cancel), null);
            alert.show();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void updateCustomCarrierTextSummary() {
        if (mCustomCarrierTextPref == null) return;
        mCustomCarrierText = Settings.System.getStringForUser(
                getActivity().getContentResolver(),
                CUSTOM_CARRIER_LABEL, UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(mCustomCarrierText)) {
            mCustomCarrierTextPref.setSummary(R.string.custom_carrier_label_summary);
        } else {
            mCustomCarrierTextPref.setSummary(mCustomCarrierText);
        }
    }

    private void updateClockChipSummary() {
        Preference pref = findPreference(KEY_CLOCK_CHIP);
        if (pref == null) return;
        pref.setSummary(new ClockChipController(getContext(), KEY_CLOCK_CHIP).getSummary());
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
    public void onResume() {
        super.onResume();
        updateClockChipSummary();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
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

                    if (!DeviceUtils.deviceSupportsBluetooth(context)) {
                        keys.add(KEY_BLUETOOTH_BATTERY_STATUS);
                    }

                    if (!TelephonyUtils.isVoiceCapable(context)) {
                        keys.add(CARRIER_NAME);
                        keys.add(CUSTOM_CARRIER_LABEL);
                    }

                    return keys;
                }
            };
}
