/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.statusbar;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

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
import org.evolution.settings.preferences.ExpressiveDialogs;
import org.evolution.settings.preferences.SystemSettingSeekBarPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.preferences.colorpicker.SystemSettingColorPickerPreference;
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
    private static final String KEY_ICONS_CATEGORY = "status_bar_icons_category";
    private static final String QUICK_PULLDOWN = "qs_quick_pulldown";
    private static final String STATUS_BAR_CLOCK_STYLE = "status_bar_clock";
    private static final String STATUS_BAR_CARRIER_KEY = "status_bar_carrier_key";
    private static final String CARRIER_NAME = "lockscreen_show_carrier";
    private static final String CUSTOM_CARRIER_LABEL = "lockscreen_show_custom_carrier_text";
    private static final String LOGO_ENABLED = "status_bar_logo";
    private static final String LOGO_POSITION = "status_bar_logo_position";
    private static final String LOGO_COLOR = "status_bar_logo_color";
    private static final String LOGO_COLOR_PICKER = "status_bar_logo_color_picker";
    private static final String LOGO_CUSTOM_STYLE = "status_bar_logo_style";
    private static final String LOGO_CUSTOM_IMAGE = "status_bar_logo_custom_image";

    private static final String KEY_CLOCK_CHIP_GRADIENT_START_COLOR =
            "statusbar_clock_chip_gradient_start_color";
    private static final String KEY_CLOCK_CHIP_GRADIENT_END_COLOR =
            "statusbar_clock_chip_gradient_end_color";
    private static final String KEY_CLOCK_CHIP_GRADIENT_ANGLE =
            "statusbar_clock_chip_gradient_angle";
    private static final String KEY_CLOCK_CHIP_GRADIENT_MASK_TEXT =
            "statusbar_clock_chip_gradient_mask_text";

    private static final int CLOCK_CHIP_STYLE_CUSTOM_GRADIENT = 13;
    private static final int LOGO_COLOR_MODE_CUSTOM = 2;

    private static final int CARRIER_MODE_NEVER = 0;

    private static final int LOGO_CUSTOM_IMAGE_REQUEST = 3001;

    private static final int PULLDOWN_DIR_NONE = 0;
    private static final int PULLDOWN_DIR_RIGHT = 1;
    private static final int PULLDOWN_DIR_LEFT = 2;
    private static final int PULLDOWN_DIR_ALWAYS = 3;

    private LineageSystemSettingListPreference mQuickPulldown;
    private LineageSystemSettingListPreference mStatusBarClock;
    private PreferenceCategory mIconsCategory;
    private SystemSettingSwitchPreference mBluetoothBatteryStatus;
    private SystemSettingSwitchPreference mLogo;
    private Preference mLogoPosition;
    private SystemSettingListPreference mLogoColor;
    private SystemSettingColorPickerPreference mLogoColorPicker;
    private int mLogoColorMode;
    private Preference mLogoCustomImage;
    private Preference mLogoStyle;

    private Preference mClockChip;
    private SystemSettingColorPickerPreference mClockChipGradientStartColor;
    private SystemSettingColorPickerPreference mClockChipGradientEndColor;
    private SystemSettingSeekBarPreference mClockChipGradientAngle;
    private Preference mClockChipGradientMaskText;

    private SystemSettingListPreference mCarrierMode;
    private Preference mCustomCarrierTextPref;
    private String mCustomCarrierText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_status_bar);

        ContentResolver resolver = getActivity().getContentResolver();
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

        if (!DeviceUtils.deviceSupportsBluetooth(context)) {
            mIconsCategory.removePreference(mBluetoothBatteryStatus);
        }

        mClockChip = findPreference(KEY_CLOCK_CHIP);
        updateClockChipSummary();

        mClockChipGradientStartColor = (SystemSettingColorPickerPreference)
                findPreference(KEY_CLOCK_CHIP_GRADIENT_START_COLOR);
        mClockChipGradientEndColor = (SystemSettingColorPickerPreference)
                findPreference(KEY_CLOCK_CHIP_GRADIENT_END_COLOR);

        mClockChipGradientAngle = (SystemSettingSeekBarPreference)
                findPreference(KEY_CLOCK_CHIP_GRADIENT_ANGLE);
        mClockChipGradientAngle.setOnPreferenceChangeListener(this);

        mClockChipGradientMaskText = findPreference(KEY_CLOCK_CHIP_GRADIENT_MASK_TEXT);
        mClockChipGradientMaskText.setOnPreferenceChangeListener(this);

        int currentChipStyle = Settings.System.getIntForUser(
                resolver,
                Settings.System.STATUSBAR_CLOCK_CHIP,
                0,
                UserHandle.USER_CURRENT);
        updateClockChipGradientPrefsVisibility(currentChipStyle);

        if (!TelephonyUtils.isVoiceCapable(getContext())) {
            Preference carrierCategory = findPreference(STATUS_BAR_CARRIER_KEY);
            if (carrierCategory != null) {
                prefScreen.removePreference(carrierCategory);
            }
        } else {
            mCarrierMode = (SystemSettingListPreference) findPreference(CARRIER_NAME);
            mCarrierMode.setOnPreferenceChangeListener(this);

            mCustomCarrierTextPref = findPreference(CUSTOM_CARRIER_LABEL);
            updateCustomCarrierTextSummary();

            int carrierMode = mCarrierMode.getIntValue(1);
            updateCustomCarrierTextPrefVisibility(carrierMode);
        }

        mLogo = (SystemSettingSwitchPreference) findPreference(LOGO_ENABLED);
        mLogo.setOnPreferenceChangeListener(this);

        mLogoPosition = findPreference(LOGO_POSITION);

        mLogoColor = (SystemSettingListPreference) findPreference(LOGO_COLOR);
        int logoColor = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_LOGO_COLOR, 0, UserHandle.USER_CURRENT);
        mLogoColor.setValue(String.valueOf(logoColor));
        mLogoColor.setSummary(mLogoColor.getEntry());
        mLogoColor.setOnPreferenceChangeListener(this);
        mLogoColorPicker = (SystemSettingColorPickerPreference) findPreference(LOGO_COLOR_PICKER);
        updateColorPrefs(logoColor);

        mLogoStyle = findPreference(LOGO_CUSTOM_STYLE);
        if (mLogoStyle != null) {
            mLogoStyle.setOnPreferenceChangeListener(this);
        }

        mLogoCustomImage = findPreference(LOGO_CUSTOM_IMAGE);

        updateLogoPrefsVisibility(mLogo.isChecked());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        ContentResolver resolver = getActivity().getContentResolver();
        if (preference == mQuickPulldown) {
            Integer value = parseIntegerValue(newValue);
            if (value == null) {
                return false;
            }
            updateQuickPulldownSummary(value);
            return true;
        } else if (preference == mLogoColor) {
            Integer logoColor = parseIntegerValue(newValue);
            if (logoColor == null || !(newValue instanceof String)) {
                return false;
            }
            int index = mLogoColor.findIndexOfValue((String) newValue);
            if (index < 0) {
                return false;
            }
            Settings.System.putIntForUser(resolver,
                    Settings.System.STATUS_BAR_LOGO_COLOR, logoColor, UserHandle.USER_CURRENT);
            mLogoColor.setSummary(mLogoColor.getEntries()[index]);
            updateColorPrefs(logoColor);
            return true;
        } else if (preference.getKey() != null
                && preference.getKey().equals(LOGO_CUSTOM_STYLE)) {
            if (!(newValue instanceof Integer)) {
                return false;
            }
            updateCustomImagePrefVisibility((Integer) newValue);
            return true;
        } else if (preference == mLogo) {
            boolean enabled = (boolean) newValue;
            updateLogoPrefsVisibility(enabled);
            return true;
        } else if (preference == mClockChip) {
            if (!(newValue instanceof Integer)) {
                return false;
            }
            int style = (Integer) newValue;
            updateClockChipGradientPrefsVisibility(style);
            updateClockChipSummary(style);
            return true;
        } else if (preference == mClockChipGradientAngle) {
            int angle = (int) newValue;
            Settings.System.putIntForUser(
                    resolver,
                    Settings.System.STATUSBAR_CLOCK_CHIP_GRADIENT_ANGLE,
                    angle,
                    UserHandle.USER_CURRENT);
            return true;
        } else if (preference == mClockChipGradientMaskText) {
            Integer value = parseIntegerValue(newValue);
            if (value == null) {
                return false;
            }
            Settings.System.putIntForUser(
                    resolver,
                    KEY_CLOCK_CHIP_GRADIENT_MASK_TEXT,
                    value,
                    UserHandle.USER_CURRENT);
            return true;
        } else if (preference == mCarrierMode) {
            Integer value = parseIntegerValue(newValue);
            if (value == null) {
                return false;
            }
            updateCustomCarrierTextPrefVisibility(value);
            return true;
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
                            R.string.quick_settings_header_image_error,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void updateLogoPrefsVisibility(boolean enabled) {
        if (mLogoPosition != null) mLogoPosition.setVisible(enabled);
        if (mLogoStyle != null) mLogoStyle.setVisible(enabled);
        if (mLogoColor != null) mLogoColor.setVisible(enabled);
        if (mLogoColorPicker != null) {
            mLogoColorPicker.setVisible(enabled && mLogoColorMode == LOGO_COLOR_MODE_CUSTOM);
        }
        updateCustomImagePrefVisibility();
    }

    private void updateCustomImagePrefVisibility() {
        int currentStyle = Settings.System.getIntForUser(
                requireContext().getContentResolver(),
                Settings.System.STATUS_BAR_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);
        updateCustomImagePrefVisibility(currentStyle);
    }

    private void updateCustomImagePrefVisibility(int currentStyle) {
        if (mLogoCustomImage == null) return;
        if (mLogo != null && !mLogo.isChecked()) {
            mLogoCustomImage.setVisible(false);
            return;
        }
        boolean isCustom = currentStyle == getCustomLogoStyleIndex();
        mLogoCustomImage.setVisible(isCustom);
        if (isCustom) {
            String path = Settings.System.getStringForUser(
                    requireContext().getContentResolver(),
                    Settings.System.STATUS_BAR_LOGO_CUSTOM_IMAGE_URI,
                    UserHandle.USER_CURRENT);
            updateCustomImagePrefSummary(path);
        }
    }

    private int getCustomLogoStyleIndex() {
        String[] drawables = getResources().getStringArray(R.array.statusbar_logo_drawables);
        for (int i = 0; i < drawables.length; i++) {
            if ("custom_image".equals(drawables[i])) {
                return i;
            }
        }
        return -1;
    }

    private void updateCustomImagePrefSummary(String path) {
        if (mLogoCustomImage == null) return;
        mLogoCustomImage.setSummary(
                path != null && !path.isEmpty()
                        ? path
                        : getString(R.string.status_bar_logo_custom_image_pick_summary));
    }

    private void updateColorPrefs(int logoColor) {
        mLogoColorMode = logoColor;
        // Only show the custom color while the custom logo color mode is selected.
        if (mLogoColorPicker != null) {
            mLogoColorPicker.setVisible(
                    mLogo != null && mLogo.isChecked() && logoColor == LOGO_COLOR_MODE_CUSTOM);
        }
    }

    private static Integer parseIntegerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateClockChipGradientPrefsVisibility(int chipStyle) {
        boolean isGradient = (chipStyle == CLOCK_CHIP_STYLE_CUSTOM_GRADIENT);
        if (mClockChipGradientStartColor != null) {
            mClockChipGradientStartColor.setVisible(isGradient);
        }
        if (mClockChipGradientEndColor != null) {
            mClockChipGradientEndColor.setVisible(isGradient);
        }
        if (mClockChipGradientAngle != null) {
            mClockChipGradientAngle.setVisible(isGradient);
        }
        if (mClockChipGradientMaskText != null) {
            mClockChipGradientMaskText.setVisible(isGradient);
        }
    }

    private void updateClockChipSummary() {
        int index = Settings.System.getIntForUser(
                requireContext().getContentResolver(),
                Settings.System.STATUSBAR_CLOCK_CHIP,
                0, UserHandle.USER_CURRENT);
        updateClockChipSummary(index);
    }

    private void updateClockChipSummary(int index) {
        if (mClockChip == null) return;
        String[] labels = getResources().getStringArray(R.array.statusbar_clock_chip_labels);
        if (index == 0 || labels.length == 0) {
            mClockChip.setSummary(R.string.gesture_setting_off);
            return;
        }
        String label = (index >= 0 && index < labels.length) ? labels[index] : labels[0];
        mClockChip.setSummary(getString(R.string.gesture_setting_on) + " / " + label);
    }

    private void updateCustomCarrierTextPrefVisibility(int carrierMode) {
        if (mCustomCarrierTextPref != null) {
            mCustomCarrierTextPref.setVisible(carrierMode != CARRIER_MODE_NEVER);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (CUSTOM_CARRIER_LABEL.equals(preference.getKey())) {
            final ContentResolver resolver = getActivity().getContentResolver();

            ExpressiveDialogs.showTextInputDialog(
                    getActivity(),
                    getString(R.string.custom_carrier_label_title),
                    getString(R.string.custom_carrier_label_dialog_message),
                    TextUtils.isEmpty(mCustomCarrierText) ? "" : mCustomCarrierText,
                    getString(android.R.string.ok),
                    getString(android.R.string.cancel),
                    true,
                    value -> {
                        Settings.System.putStringForUser(resolver,
                                CUSTOM_CARRIER_LABEL, value, UserHandle.USER_CURRENT);
                        updateCustomCarrierTextSummary();
                    });
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
