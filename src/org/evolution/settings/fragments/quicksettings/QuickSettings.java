/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.quicksettings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import lineageos.preference.LineageSecureSettingSwitchPreference;
import lineageos.providers.LineageSettings;

import org.evolution.settings.preferences.SecureSettingListPreference;
import org.evolution.settings.preferences.SecureSettingSwitchPreference;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSeekBarPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class QuickSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "QuickSettings";

    private static final String QS_BRIGHTNESS_CATEGORY = "qs_brightness_slider_category";
    private static final String QS_LAYOUT_CATEGORY = "qs_layout_category";
//    private static final String KEY_BATTERY_PERCENT = "qs_show_battery_percent";
//    private static final String KEY_BATTERY_STYLE = "qs_battery_style";
    private static final String KEY_BRIGHTNESS_SLIDER_POSITION = "qs_brightness_slider_position";
    private static final String KEY_BRIGHTNESS_SLIDER_HAPTIC = "qs_brightness_slider_haptic";
//    private static final String KEY_INTERFACE_CATEGORY = "quick_settings_interface_category";
    private static final String KEY_COMPACT_MEDIA_PLAYER_ENABLED = "qs_compact_media_player_mode";
    private static final String KEY_MEDIA_WAVEFORM_SEEKBAR = "media_waveform_seekbar";
    private static final String KEY_MEDIA_SQUIGGLE_ANIMATION = "media_squiggle_animation";
    private static final String KEY_SHOW_BRIGHTNESS_SLIDER = "qs_show_brightness_slider";
    private static final String KEY_SHOW_AUTO_BRIGHTNESS = "qs_show_auto_brightness";
//    private static final String KEY_TILE_ANIM_STYLE = "qs_tile_animation_style";
//    private static final String KEY_TILE_ANIM_DURATION = "qs_tile_animation_duration";
//    private static final String KEY_TILE_ANIM_INTERPOLATOR = "qs_tile_animation_interpolator";
    private static final String KEY_QS_TILE_HAPTIC = "qs_tile_haptic";
    private static final String KEY_QS_PANEL_STYLE = "qs_panel_style";
    private static final String KEY_QS_TILE_SHAPE = "qs_tile_shape";
    private static final String KEY_QS_TILE_ICON_SHAPE = "qs_tile_icon_shape";
    private static final String KEY_QS_TILE_LABEL_HIDE = "qs_tile_label_hide";
    private static final String KEY_QS_SHOW_MEDIA_PLAYER = "qs_show_media_player";
    private static final String KEY_SINGLE_QS_TONE_ENABLED = "single_qs_tone_enabled";

//    private static final int BATTERY_STYLE_PORTRAIT = 0;
//    private static final int BATTERY_STYLE_TEXT = 4;
//    private static final int BATTERY_STYLE_HIDDEN = 5;

    private PreferenceCategory mInterfaceCategory;
    private ListPreference mShowBrightnessSlider;
    private ListPreference mBrightnessSliderPosition;
    private SystemSettingSwitchPreference mCompactMediaPlayer;
    private SwitchPreferenceCompat mBrightnessSliderHaptic;
    private SwitchPreferenceCompat mShowAutoBrightness;
    private SwitchPreferenceCompat mQsTileHaptic;
    private ListPreference mQsPanelStyle;
    private Preference mQsTileShape;
    private Preference mQsTileIconShape;
    private SwitchPreferenceCompat mQsTileLabelHide;
    private SystemSettingSwitchPreference mMediaWaveformSeekBar;
    private SecureSettingSwitchPreference mMediaSquiggleAnimation;
    private SecureSettingSwitchPreference mQsShowMediaPlayer;
    private SystemSettingSwitchPreference mSingleQsToneEnabled;
//    private SystemSettingListPreference mBatteryStyle;
//    private SystemSettingListPreference mBatteryPercent;
//    private SystemSettingListPreference mTileAnimationInterpolator;
//    private SystemSettingListPreference mTileAnimationStyle;
//    private SystemSettingSeekBarPreference mTileAnimationDuration;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_quick_settings);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources res = context.getResources();

        PreferenceCategory brightnessCategory = (PreferenceCategory) findPreference(QS_BRIGHTNESS_CATEGORY);
        PreferenceCategory tileCategory = (PreferenceCategory) findPreference(QS_LAYOUT_CATEGORY);

//        mBatteryStyle = (SystemSettingListPreference) findPreference(KEY_BATTERY_STYLE);
//        mBatteryPercent = (SystemSettingListPreference) findPreference(KEY_BATTERY_PERCENT);

//        int batterystyle = Settings.System.getIntForUser(resolver,
//                Settings.System.QS_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);

//        mBatteryStyle.setOnPreferenceChangeListener(this);

//        mBatteryPercent.setEnabled(
//                batterystyle != BATTERY_STYLE_TEXT && batterystyle != BATTERY_STYLE_HIDDEN);

        mCompactMediaPlayer = findPreference(KEY_COMPACT_MEDIA_PLAYER_ENABLED);
        mCompactMediaPlayer.setOnPreferenceChangeListener(this);

        mQsShowMediaPlayer = (SecureSettingSwitchPreference) findPreference(KEY_QS_SHOW_MEDIA_PLAYER);
        if (mQsShowMediaPlayer != null) {
            mQsShowMediaPlayer.setOnPreferenceChangeListener(this);
        }

        mSingleQsToneEnabled = (SystemSettingSwitchPreference) findPreference(KEY_SINGLE_QS_TONE_ENABLED);
        if (mSingleQsToneEnabled != null) {
            mSingleQsToneEnabled.setOnPreferenceChangeListener(this);
        }

        mMediaWaveformSeekBar = (SystemSettingSwitchPreference) findPreference(KEY_MEDIA_WAVEFORM_SEEKBAR);
        if (mMediaWaveformSeekBar != null) {
            mMediaWaveformSeekBar.setOnPreferenceChangeListener(this);
        }

        mMediaSquiggleAnimation = (SecureSettingSwitchPreference) findPreference(KEY_MEDIA_SQUIGGLE_ANIMATION);
        if (mMediaSquiggleAnimation != null) {
            mMediaSquiggleAnimation.setOnPreferenceChangeListener(this);
        }

        mShowBrightnessSlider = findPreference(KEY_SHOW_BRIGHTNESS_SLIDER);
        mShowBrightnessSlider.setOnPreferenceChangeListener(this);
        boolean showSlider = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER, 1, UserHandle.USER_CURRENT) > 0;

        mBrightnessSliderPosition = findPreference(KEY_BRIGHTNESS_SLIDER_POSITION);
        mBrightnessSliderPosition.setEnabled(showSlider);

        mBrightnessSliderHaptic = findPreference(KEY_BRIGHTNESS_SLIDER_HAPTIC);
        mBrightnessSliderHaptic.setOnPreferenceChangeListener(this);
        mQsTileHaptic = findPreference(KEY_QS_TILE_HAPTIC);
        boolean hapticAvailable = DeviceUtils.hasVibrator(context);

        if (hapticAvailable) {
            mBrightnessSliderHaptic.setEnabled(showSlider);
        } else {
            brightnessCategory.removePreference(mBrightnessSliderHaptic);
            tileCategory.removePreference(mQsTileHaptic);
        }

        mShowAutoBrightness = findPreference(KEY_SHOW_AUTO_BRIGHTNESS);
        boolean automaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        if (automaticAvailable) {
            mShowAutoBrightness.setEnabled(showSlider);
        } else {
            brightnessCategory.removePreference(mShowAutoBrightness);
        }

//        mTileAnimationStyle = (SystemSettingListPreference) findPreference(KEY_TILE_ANIM_STYLE);
//        mTileAnimationDuration = (SystemSettingSeekBarPreference) findPreference(KEY_TILE_ANIM_DURATION);
//        mTileAnimationInterpolator = (SystemSettingListPreference) findPreference(KEY_TILE_ANIM_INTERPOLATOR);
//        mTileAnimationStyle.setOnPreferenceChangeListener(this);

//        int tileAnimationStyle = Settings.System.getIntForUser(getContentResolver(),
//                Settings.System.QS_TILE_ANIMATION_STYLE, 0, UserHandle.USER_CURRENT);
//        updateTileAnimStyle(tileAnimationStyle);

        mQsPanelStyle = findPreference(KEY_QS_PANEL_STYLE);
        mQsPanelStyle.setOnPreferenceChangeListener(this);
        mQsTileShape = findPreference(KEY_QS_TILE_SHAPE);
        mQsTileIconShape = findPreference(KEY_QS_TILE_ICON_SHAPE);
        mQsTileLabelHide = findPreference(KEY_QS_TILE_LABEL_HIDE);

        int panelStyle = Settings.System.getIntForUser(resolver,
                Settings.System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT);
        updatePanelStylePrefs(panelStyle);

        updateSquiggleAnimationVisibility();
    }

    private void updatePanelStylePrefs(int panelStyle) {
        boolean isClassic = panelStyle == 1;

        if (mQsTileShape != null) {
            mQsTileShape.setVisible(!isClassic);
        }
        if (mQsTileIconShape != null) {
            mQsTileIconShape.setVisible(isClassic);
        }
        if (mQsTileLabelHide != null) {
            mQsTileLabelHide.setVisible(isClassic);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mShowBrightnessSlider) {
            int value = Integer.parseInt((String) newValue);
            mBrightnessSliderPosition.setEnabled(value > 0);
            if (mBrightnessSliderHaptic != null)
                mBrightnessSliderHaptic.setEnabled(value > 0);
            if (mShowAutoBrightness != null)
                mShowAutoBrightness.setEnabled(value > 0);
            return true;
        } else if (preference == mQsPanelStyle) {
            int value = Integer.parseInt((String) newValue);
            updatePanelStylePrefs(value);
            return true;
        } else if (preference == mCompactMediaPlayer
            || preference == mBrightnessSliderHaptic) {
            SystemUtils.showSystemUiRestartDialog(context);
            return true;
        } else if (preference == mQsShowMediaPlayer) {
            SystemUtils.showSystemUiRestartDialog(getActivity());
            return true;
        } else if (preference == mSingleQsToneEnabled) {
            SystemUtils.showSystemUiRestartDialog(getActivity());
            return true;
        } else if (preference == mMediaWaveformSeekBar) {
            updateSquiggleAnimationVisibility((Boolean) newValue);
            SystemUtils.showSystemUiRestartDialog(getActivity());
            return true;
        } else if (preference == mMediaSquiggleAnimation) {
            SystemUtils.showSystemUiRestartDialog(getActivity());
            return true;
//        } else if (preference == mBatteryStyle) {
//            int value = Integer.parseInt((String) newValue);
//            mBatteryPercent.setEnabled(
//                    value != BATTERY_STYLE_TEXT && value != BATTERY_STYLE_HIDDEN);
//            return true;
//        } else if (preference == mTileAnimationStyle) {
//            int value = Integer.parseInt((String) newValue);
//            updateTileAnimStyle(value);
//            return true;
        }
        return false;
    }

    private void updateSquiggleAnimationVisibility() {
        updateSquiggleAnimationVisibility(null);
    }

    private void updateSquiggleAnimationVisibility(Boolean newValue) {
        if (mMediaSquiggleAnimation == null) return;

        final ContentResolver resolver = getActivity().getContentResolver();
        boolean waveformEnabled;

        if (newValue != null) {
            waveformEnabled = newValue;
        } else {
            waveformEnabled = Settings.System.getInt(resolver,
                Settings.System.MEDIA_WAVEFORM_SEEKBAR, 0) == 1;
        }

        mMediaSquiggleAnimation.setVisible(!waveformEnabled);
        mMediaSquiggleAnimation.setEnabled(!waveformEnabled);
    }

//    private void updateTileAnimStyle(int tileAnimationStyle) {
//        mTileAnimationDuration.setEnabled(tileAnimationStyle != 0);
//        mTileAnimationInterpolator.setEnabled(tileAnimationStyle != 0);
//    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_quick_settings) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources res = context.getResources();
                final ContentResolver resolver = context.getContentResolver();

                boolean automaticAvailable = res.getBoolean(
                        com.android.internal.R.bool.config_automatic_brightness_available);
                if (!automaticAvailable) {
                    keys.add(KEY_SHOW_AUTO_BRIGHTNESS);
                }

                boolean hapticAvailable = DeviceUtils.hasVibrator(context);
                if (!hapticAvailable) {
                    keys.add(KEY_BRIGHTNESS_SLIDER_HAPTIC);
                    keys.add(KEY_QS_TILE_HAPTIC);
                }

                boolean waveformEnabled = Settings.System.getInt(resolver,
                    Settings.System.MEDIA_WAVEFORM_SEEKBAR, 0) == 1;
                if (waveformEnabled) {
                    keys.add(KEY_MEDIA_SQUIGGLE_ANIMATION);
                }

                return keys;
            }
        };
}
