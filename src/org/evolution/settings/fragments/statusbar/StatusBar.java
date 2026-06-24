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
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

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

import org.evolution.settings.fragments.statusbar.ClockChipController;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.preferences.colorpicker.ColorPickerPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.StatusBarLogoImageUtils;
import org.evolution.settings.utils.SystemUtils;
import org.evolution.settings.utils.TelephonyUtils;

@SearchIndexable
public class StatusBar extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "StatusBar";

    private static final String KEY_BLUETOOTH_BATTERY_STATUS = "bluetooth_show_battery";
    private static final String KEY_CLOCK_CHIP = "statusbar_clock_chip";
//    private static final String KEY_COLORED_ICONS = "statusbar_colored_icons";
    private static final String KEY_ICONS_CATEGORY = "status_bar_icons_category";
    private static final String QUICK_PULLDOWN = "qs_quick_pulldown";
    private static final String STATUS_BAR_CLOCK_STYLE = "status_bar_clock";
    private static final String STATUS_BAR_CARRIER_KEY = "status_bar_carrier_key";
    private static final String CARRIER_NAME = "lockscreen_show_carrier";
    private static final String CUSTOM_CARRIER_LABEL = "lockscreen_show_custom_carrier_text";
    private static final String LOGO_COLOR = "status_bar_logo_color";
    private static final String LOGO_COLOR_PICKER = "status_bar_logo_color_picker";
    private static final String LOGO_CUSTOM_STYLE = "status_bar_logo_style";
    private static final String LOGO_CUSTOM_IMAGE = "status_bar_logo_custom_image";

    private static final int LOGO_STYLE_CUSTOM = 33;
    private static final int LOGO_CUSTOM_IMAGE_REQUEST = 3001;

    private static final int PULLDOWN_DIR_NONE = 0;
    private static final int PULLDOWN_DIR_RIGHT = 1;
    private static final int PULLDOWN_DIR_LEFT = 2;
    private static final int PULLDOWN_DIR_ALWAYS = 3;

    private LineageSystemSettingListPreference mQuickPulldown;
    private LineageSystemSettingListPreference mStatusBarClock;
    private PreferenceCategory mIconsCategory;
    private SystemSettingSwitchPreference mBluetoothBatteryStatus;
//    private SystemSettingSwitchPreference mColoredIcons;
    private SystemSettingListPreference mLogoColor;
    private ColorPickerPreference mLogoColorPicker;
    private Preference mLogoCustomImage;
    private Preference mLogoStyle;

    private Preference mCustomCarrierTextPref;
    private String mCustomCarrierText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_status_bar);

        final Context context = getContext();
        final PreferenceScreen prefScreen = getPreferenceScreen();

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

        mQuickPulldown =
                (LineageSystemSettingListPreference) findPreference(QUICK_PULLDOWN);
        mQuickPulldown.setOnPreferenceChangeListener(this);
        updateQuickPulldownSummary(mQuickPulldown.getIntValue(0));

        // Adjust status bar preferences for RTL
        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            mQuickPulldown.setEntries(R.array.status_bar_quick_qs_pulldown_entries_rtl);
            mQuickPulldown.setEntryValues(R.array.status_bar_quick_qs_pulldown_values_rtl);
        }

        mIconsCategory = findPreference(KEY_ICONS_CATEGORY);
        mBluetoothBatteryStatus = findPreference(KEY_BLUETOOTH_BATTERY_STATUS);
//        mColoredIcons = findPreference(KEY_COLORED_ICONS);
//        mColoredIcons.setOnPreferenceChangeListener(this);

        if (!DeviceUtils.deviceSupportsBluetooth(context)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
        }

        updateClockChipSummary();

        if (!TelephonyUtils.isVoiceCapable(getContext())) {
            Preference carrierCategory = findPreference(STATUS_BAR_CARRIER_KEY);
            if (carrierCategory != null) {
                prefScreen.removePreference(carrierCategory);
            }
        } else {
            mCustomCarrierTextPref = findPreference(CUSTOM_CARRIER_LABEL);
            updateCustomCarrierTextSummary();
        }

        mLogoColor = (SystemSettingListPreference) findPreference(LOGO_COLOR);
        int logoColor = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_LOGO_COLOR, 0, UserHandle.USER_CURRENT);
        mLogoColor.setValue(String.valueOf(logoColor));
        mLogoColor.setSummary(mLogoColor.getEntry());
        mLogoColor.setOnPreferenceChangeListener(this);
        mLogoColorPicker = (ColorPickerPreference) findPreference(LOGO_COLOR_PICKER);
        int logoColorPicker = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_LOGO_COLOR_PICKER, 0xFFFFFFFF);
        mLogoColorPicker.setNewPreviewColor(logoColorPicker);
        String logoColorPickerHex = String.format("#%08x", (0xFFFFFFFF & logoColorPicker));
        if (logoColorPickerHex.equals("#ffffffff")) {
            mLogoColorPicker.setSummary(R.string.default_string);
        } else {
            mLogoColorPicker.setSummary(logoColorPickerHex);
        }
        mLogoColorPicker.setOnPreferenceChangeListener(this);
        updateColorPrefs(logoColor);

        mLogoStyle = findPreference(LOGO_CUSTOM_STYLE);
        if (mLogoStyle != null) {
            mLogoStyle.setOnPreferenceChangeListener(this);
        }

        mLogoCustomImage = findPreference(LOGO_CUSTOM_IMAGE);
        updateCustomImagePrefVisibility();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mQuickPulldown) {
            int value = Integer.parseInt((String) newValue);
            updateQuickPulldownSummary(value);
            return true;
        } else if (preference == mLogoColor) {
            int logoColor = Integer.valueOf((String) newValue);
            int index = mLogoColor.findIndexOfValue((String) newValue);
            Settings.System.putIntForUser(resolver,
                    Settings.System.STATUS_BAR_LOGO_COLOR, logoColor, UserHandle.USER_CURRENT);
            mLogoColor.setSummary(mLogoColor.getEntries()[index]);
            updateColorPrefs(logoColor);
            return true;
        } else if (preference.getKey() != null
                && preference.getKey().equals(LOGO_CUSTOM_STYLE)) {
            updateCustomImagePrefVisibility();
            return true;
        } else if (preference == mLogoColorPicker) {
            String hex = ColorPickerPreference.convertToARGB(
                    Integer.valueOf(String.valueOf(newValue)));
            if (hex.equals("#ffffffff")) {
                preference.setSummary(R.string.default_string);
            } else {
                preference.setSummary(hex);
            }
            int intHex = ColorPickerPreference.convertToColorInt(hex);
            Settings.System.putInt(resolver,
                    Settings.System.STATUS_BAR_LOGO_COLOR_PICKER, intHex);
            return true;
//        } else if (preference == mColoredIcons) {
//            SystemUtils.showSystemUiRestartDialog(getContext());
//            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOGO_CUSTOM_IMAGE_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri imgUri = data.getData();
            if (imgUri != null) {
                String savedPath = StatusBarLogoImageUtils.saveLogoImage(
                        getActivity(), imgUri);
                if (savedPath != null) {
                    Settings.System.putStringForUser(
                            getActivity().getContentResolver(),
                            Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI,
                            savedPath,
                            UserHandle.USER_CURRENT);
                    updateCustomImagePrefSummary(savedPath);
                } else {
                    Toast.makeText(getContext(),
                            R.string.qs_header_image_error,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void updateCustomImagePrefVisibility() {
        if (mLogoCustomImage == null) return;
        int currentStyle = Settings.System.getIntForUser(
                getActivity().getContentResolver(),
                Settings.System.STATUS_BAR_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);
        mLogoCustomImage.setVisible(currentStyle == LOGO_STYLE_CUSTOM);
        if (currentStyle == LOGO_STYLE_CUSTOM) {
            String path = Settings.System.getStringForUser(
                    getActivity().getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI,
                    UserHandle.USER_CURRENT);
            updateCustomImagePrefSummary(path);
        }
    }

    private void updateCustomImagePrefSummary(String path) {
        if (mLogoCustomImage == null) return;
        mLogoCustomImage.setSummary(
                path != null && !path.isEmpty()
                        ? path
                        : getString(R.string.status_bar_logo_custom_image_pick_summary));
    }

    private void updateColorPrefs(int logoColor) {
        if (mLogoColor != null) {
            mLogoColorPicker.setEnabled(logoColor == 2);
        }
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
        if (preference == mLogoCustomImage) {
            try {
                Intent intent = new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                startActivityForResult(intent, LOGO_CUSTOM_IMAGE_REQUEST);
            } catch (Exception e) {
                Toast.makeText(getContext(),
                        R.string.quick_settings_header_needs_gallery,
                        Toast.LENGTH_LONG).show();
            }
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
        String summary="";
        switch (value) {
            case PULLDOWN_DIR_NONE:
                summary = getResources().getString(
                    R.string.status_bar_quick_qs_pulldown_off);
                break;
            case PULLDOWN_DIR_ALWAYS:
                summary = getResources().getString(
                    R.string.status_bar_quick_qs_pulldown_always);
                break;
            case PULLDOWN_DIR_LEFT:
            case PULLDOWN_DIR_RIGHT:
                summary = getResources().getString(
                    R.string.status_bar_quick_qs_pulldown_summary,
                    getResources().getString(value == PULLDOWN_DIR_LEFT
                        ? R.string.status_bar_quick_qs_pulldown_summary_left
                        : R.string.status_bar_quick_qs_pulldown_summary_right));
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
