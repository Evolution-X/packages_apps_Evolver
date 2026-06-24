/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.quicksettings;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import lineageos.providers.LineageSettings;

import org.evolution.settings.preferences.SecureSettingListPreference;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class QuickSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "QuickSettings";

    private static final String QS_BRIGHTNESS_CATEGORY = "qs_brightness_slider_category";
//    private static final String QS_LAYOUT_CATEGORY = "qs_layout_category";

    private static final String KEY_BRIGHTNESS_SLIDER_HAPTIC = "qs_brightness_slider_haptic";
    private static final String KEY_BRIGHTNESS_SLIDER_POSITION = "qs_brightness_slider_position";
    private static final String KEY_COMPACT_MEDIA_PLAYER_ENABLED = "qs_compact_media_player_mode";
    private static final String KEY_MEDIA_WAVEFORM_SEEKBAR = "media_waveform_seekbar";
    private static final String KEY_QS_HEADER_CLOCK_STYLE = "qs_header_clock_style";
//    private static final String KEY_QS_PANEL_STYLE = "qs_panel_style";
//    private static final String KEY_QS_SHOW_MEDIA_PLAYER = "qs_show_media_player";
//    private static final String KEY_QS_TILE_ALTERNATE_COLOR = "qs_tile_alternate_color";
//    private static final String KEY_QS_TILE_HAPTIC = "qs_tile_haptic";
//    private static final String KEY_QS_TILE_ICON_SHAPE = "qs_tile_icon_shape";
//    private static final String KEY_QS_TILE_LABEL_HIDE = "qs_tile_label_hide";
//    private static final String KEY_QS_TILE_SHAPE = "qs_tile_shape";
//    private static final String KEY_QS_WIDGET_IOS_MUSIC = "qs_widget_ios_music";
//    private static final String KEY_QS_WIDGET_PANEL = "qs_widget_panel";
//    private static final String KEY_QS_WIDGET_SLIDER_CORNER = "qs_widget_slider_corner";
    private static final String KEY_SHOW_AUTO_BRIGHTNESS = "qs_show_auto_brightness";
//    private static final String KEY_SHOW_VOLUME_SLIDER = "qs_show_volume_slider";
//    private static final String KEY_SHOW_RINGER_MODE = "qs_show_ringer_mode";
    private static final String KEY_SHOW_BRIGHTNESS_SLIDER = "qs_show_brightness_slider";
//    private static final String KEY_SINGLE_QS_TONE_ENABLED = "single_qs_tone_enabled";

    private ListPreference mBrightnessSliderPosition;
//    private ListPreference mQsPanelStyle;
    private ListPreference mShowBrightnessSlider;
//    private ListPreference mVolumeSliderMode;
//    private Preference mQsTileIconShape;
//    private Preference mQsTileShape;
//    private SecureSettingListPreference mQsShowMediaPlayer;
    private SwitchPreferenceCompat mBrightnessSliderHaptic;
//    private SwitchPreferenceCompat mQsTileAlternateColor;
//    private SwitchPreferenceCompat mQsTileHaptic;
//    private SwitchPreferenceCompat mQsTileLabelHide;
    private SwitchPreferenceCompat mShowAutoBrightness;
//    private SwitchPreferenceCompat mShowRingerMode;
    private SystemSettingListPreference mQsHeaderClockStyle;
    private SystemSettingSwitchPreference mCompactMediaPlayer;
    private SystemSettingSwitchPreference mMediaWaveformSeekBar;
//    private SystemSettingSwitchPreference mQsWidgetIosMusic;
//    private SystemSettingSwitchPreference mQsWidgetPanel;
//    private SystemSettingSwitchPreference mQsWidgetSliderCorner;
//    private SystemSettingSwitchPreference mSingleQsToneEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_quick_settings);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();

        final PreferenceCategory brightnessCategory = findPreference(QS_BRIGHTNESS_CATEGORY);
//        final PreferenceCategory tileCategory = findPreference(QS_LAYOUT_CATEGORY);

        mMediaWaveformSeekBar = (SystemSettingSwitchPreference) findPreference(KEY_MEDIA_WAVEFORM_SEEKBAR);
        if (mMediaWaveformSeekBar != null) {
            mMediaWaveformSeekBar.setOnPreferenceChangeListener(this);
        }

        mShowBrightnessSlider = findPreference(KEY_SHOW_BRIGHTNESS_SLIDER);
        mShowBrightnessSlider.setOnPreferenceChangeListener(this);
        boolean showSlider = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER, 1, UserHandle.USER_CURRENT) > 0;

//        mVolumeSliderMode = findPreference(KEY_SHOW_VOLUME_SLIDER);
//        mVolumeSliderMode.setEnabled(showSlider);

        mBrightnessSliderPosition = findPreference(KEY_BRIGHTNESS_SLIDER_POSITION);
        mBrightnessSliderPosition.setEnabled(showSlider);

        mBrightnessSliderHaptic = findPreference(KEY_BRIGHTNESS_SLIDER_HAPTIC);
//        mQsTileHaptic = findPreference(KEY_QS_TILE_HAPTIC);
        if (DeviceUtils.hasVibrator(context)) {
            mBrightnessSliderHaptic.setOnPreferenceChangeListener(this);
            mBrightnessSliderHaptic.setEnabled(showSlider);
        } else {
            brightnessCategory.removePreference(mBrightnessSliderHaptic);
//            tileCategory.removePreference(mQsTileHaptic);
        }

        mShowAutoBrightness = findPreference(KEY_SHOW_AUTO_BRIGHTNESS);
        if (context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available)) {
            mShowAutoBrightness.setEnabled(showSlider);
        } else {
            brightnessCategory.removePreference(mShowAutoBrightness);
        }

//        mShowRingerMode = findPreference(KEY_SHOW_RINGER_MODE);
//        mShowRingerMode.setEnabled(showSlider);

        mCompactMediaPlayer = findPreference(KEY_COMPACT_MEDIA_PLAYER_ENABLED);
        mCompactMediaPlayer.setOnPreferenceChangeListener(this);

//        mQsShowMediaPlayer = findPreference(KEY_QS_SHOW_MEDIA_PLAYER);
//        mQsShowMediaPlayer.setOnPreferenceChangeListener(this);

//        mQsWidgetPanel = findPreference(KEY_QS_WIDGET_PANEL);
//        mQsWidgetPanel.setOnPreferenceChangeListener(this);
//        mQsWidgetIosMusic = findPreference(KEY_QS_WIDGET_IOS_MUSIC);
//        mQsWidgetSliderCorner = findPreference(KEY_QS_WIDGET_SLIDER_CORNER);
//        updateWidgetPanelDependencies();

        mQsHeaderClockStyle = (SystemSettingListPreference) findPreference(KEY_QS_HEADER_CLOCK_STYLE);
        if (mQsHeaderClockStyle != null) {
            mQsHeaderClockStyle.setOnPreferenceChangeListener(this);
        }

//        mSingleQsToneEnabled = findPreference(KEY_SINGLE_QS_TONE_ENABLED);
//        mSingleQsToneEnabled.setOnPreferenceChangeListener(this);

//        mQsTileAlternateColor = findPreference(KEY_QS_TILE_ALTERNATE_COLOR);
//        mQsTileAlternateColor.setOnPreferenceChangeListener(this);

//        mQsPanelStyle = findPreference(KEY_QS_PANEL_STYLE);
//        mQsPanelStyle.setOnPreferenceChangeListener(this);
//        mQsTileShape = findPreference(KEY_QS_TILE_SHAPE);
//        mQsTileIconShape = findPreference(KEY_QS_TILE_ICON_SHAPE);
//        mQsTileLabelHide = findPreference(KEY_QS_TILE_LABEL_HIDE);
//        updatePanelStylePrefs(Settings.System.getIntForUser(resolver,
//                Settings.System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT));
    }

//    private void updateWidgetPanelDependencies() {
//        boolean enabled = Settings.System.getInt(
//                getContext().getContentResolver(), KEY_QS_WIDGET_PANEL, 0) == 1;

//        mQsWidgetIosMusic.setVisible(enabled);
//        mQsWidgetSliderCorner.setVisible(enabled);
//        mQsShowMediaPlayer.setVisible(!enabled);
//    }

//    private void updatePanelStylePrefs(int panelStyle) {
//        boolean isClassic = panelStyle == 1;
//        mQsTileShape.setVisible(!isClassic);
//        mQsTileIconShape.setVisible(isClassic);
//        mQsTileLabelHide.setVisible(isClassic);
//    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mShowBrightnessSlider) {
            int value = Integer.parseInt((String) newValue);
            mBrightnessSliderPosition.setEnabled(value > 0);
            if (mBrightnessSliderHaptic != null)
                mBrightnessSliderHaptic.setEnabled(value > 0);
            if (mShowAutoBrightness != null)
                mShowAutoBrightness.setEnabled(value > 0);
            return true;
        } else if (preference == mCompactMediaPlayer
                || preference == mBrightnessSliderHaptic
                || preference == mMediaWaveformSeekBar) {
            SystemUtils.showSystemUiRestartDialog(getActivity());
            return true;
        } else if (preference == mQsHeaderClockStyle) {
            String newVal = newValue.toString();
            String oldVal = mQsHeaderClockStyle.getValue();
            if ("0".equals(newVal) != "0".equals(oldVal)) {
                SystemUtils.showSystemUiRestartDialog(getActivity());
            }
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_quick_settings) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    final ContentResolver resolver = context.getContentResolver();

                    if (!context.getResources().getBoolean(
                            com.android.internal.R.bool.config_automatic_brightness_available)) {
                        keys.add(KEY_SHOW_AUTO_BRIGHTNESS);
                    }

                    if (!DeviceUtils.hasVibrator(context)) {
                        keys.add(KEY_BRIGHTNESS_SLIDER_HAPTIC);
//                        keys.add(KEY_QS_TILE_HAPTIC);
                    }

//                    if (Settings.System.getInt(resolver, KEY_QS_WIDGET_PANEL, 0) == 1) {
//                        keys.add(KEY_QS_SHOW_MEDIA_PLAYER);
//                    } else {
//                        keys.add(KEY_QS_WIDGET_IOS_MUSIC);
//                        keys.add(KEY_QS_WIDGET_SLIDER_CORNER);
//                    }

                    return keys;
                }
            };
}
