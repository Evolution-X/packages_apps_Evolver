/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.UserHandle;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.ThemeUtils;
import com.android.internal.util.evolution.Utils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.GlobalSettingListPreference;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SystemRestartUtils;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class Themes extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Themes";

    private static final String KEY_LOCK_SOUND = "lock_sound";
    private static final String KEY_UNLOCK_SOUND = "unlock_sound";
    private static final String KEY_ICONS_CATEGORY = "themes_icons_category";
    private static final String KEY_NAVBAR_ICON = "android.theme.customization.navbar";
    private static final String KEY_SIGNAL_ICON = "android.theme.customization.signal_icon";
    private static final String KEY_UDFPS_ICON = "udfps_icon";
    private static final String KEY_ANIMATIONS_CATEGORY = "themes_animations_category";
    private static final String KEY_UDFPS_ANIMATION = "udfps_animation";
    private static final String KEY_BOOT_ANIMATION = "boot_animation";
    private static final String KEY_PGB_STYLE = "progress_bar_style";
    private static final String KEY_NOTIF_STYLE = "notification_style";
    private static final String KEY_POWERMENU_STYLE = "powermenu_style";
    private static final String KEY_LAUNCHER_CATEGORY = "themes_launcher_category";

    private static final String[] POWER_MENU_OVERLAYS = {
            "com.android.theme.powermenu.cyberpunk",
            "com.android.theme.powermenu.duoline",
            "com.android.theme.powermenu.ios",
            "com.android.theme.powermenu.layers"
    };

    private static final String[] NOTIF_OVERLAYS = {
            "com.android.theme.notification.cyberpunk",
            "com.android.theme.notification.duoline",
            "com.android.theme.notification.fluid",
            "com.android.theme.notification.ios",
            "com.android.theme.notification.layers"
    };

    private static final String[] PROGRESS_BAR_OVERLAYS = {
            "com.android.theme.progressbar.blocky_thumb",
            "com.android.theme.progressbar.minimal_thumb",
            "com.android.theme.progressbar.outline_thumb",
            "com.android.theme.progressbar.shishu"
    };

    private GlobalSettingListPreference mLockSound;
    private GlobalSettingListPreference mUnlockSound;
    private PreferenceCategory mLauncherCategory;
    private PreferenceCategory mIconsCategory;
    private Preference mNavbarIcon;
    private Preference mSignalIcon;
    private Preference mUdfpsIcon;
    private PreferenceCategory mAnimationsCategory;
    private Preference mBootAnimation;
    private Preference mUdfpsAnimation;
    private SystemSettingListPreference mNotificationStylePref;
    private SystemSettingListPreference mPowerMenuStylePref;
    private SystemSettingListPreference mProgressBarPref;
    private ThemeUtils mThemeUtils;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_themes);
        mThemeUtils = ThemeUtils.getInstance(getActivity());

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mLockSound = (GlobalSettingListPreference) findPreference(KEY_LOCK_SOUND);
        mLockSound.setOnPreferenceChangeListener(this);
        mUnlockSound = (GlobalSettingListPreference) findPreference(KEY_UNLOCK_SOUND);
        mUnlockSound.setOnPreferenceChangeListener(this);
        mLauncherCategory = (PreferenceCategory) findPreference(KEY_LAUNCHER_CATEGORY);
        mIconsCategory = (PreferenceCategory) findPreference(KEY_ICONS_CATEGORY);
        mNavbarIcon = (Preference) findPreference(KEY_NAVBAR_ICON);
        mSignalIcon = (Preference) findPreference(KEY_SIGNAL_ICON);
        mUdfpsIcon = (Preference) findPreference(KEY_UDFPS_ICON);
        mAnimationsCategory = (PreferenceCategory) findPreference(KEY_ANIMATIONS_CATEGORY);
        mBootAnimation = (Preference) findPreference(KEY_BOOT_ANIMATION);
        mUdfpsAnimation = (Preference) findPreference(KEY_UDFPS_ANIMATION);

        if (DeviceUtils.isCurrentlySupportedPixel()) {
            mAnimationsCategory.removePreference(mBootAnimation);
        }

        if (!DeviceUtils.deviceSupportsMobileData(context)) {
            mIconsCategory.removePreference(mSignalIcon);
        }

        if (DeviceUtils.isEdgeToEdgeEnabled(context)) {
            mIconsCategory.removePreference(mNavbarIcon);
        }

        FingerprintManager fingerprintManager = (FingerprintManager)
                getActivity().getSystemService(Context.FINGERPRINT_SERVICE);

        if (fingerprintManager == null || !fingerprintManager.isHardwareDetected()) {
            mIconsCategory.removePreference(mUdfpsIcon);
            mAnimationsCategory.removePreference(mUdfpsAnimation);
        } else {
            if (!Utils.isPackageInstalled(context, "org.evolution.udfps.icons")) {
                mIconsCategory.removePreference(mUdfpsIcon);
            }
            if (!Utils.isPackageInstalled(context, "org.evolution.udfps.animations")) {
                mAnimationsCategory.removePreference(mUdfpsAnimation);
            }
        }

        mProgressBarPref = findPreference(KEY_PGB_STYLE);
        mProgressBarPref.setOnPreferenceChangeListener(this);

        mNotificationStylePref = findPreference(KEY_NOTIF_STYLE);
        mNotificationStylePref.setOnPreferenceChangeListener(this);

        mPowerMenuStylePref = findPreference(KEY_POWERMENU_STYLE);
        mPowerMenuStylePref.setOnPreferenceChangeListener(this);

        if (!Utils.isPackageInstalled(context, "com.google.android.apps.nexuslauncher")) {
            prefScreen.removePreference(mLauncherCategory);
        }
    }

    private void updateStyle(String key, String category, String target,
            int defaultValue, String[] overlayPackages, boolean restartSystemUI) {
        final int style = Settings.System.getIntForUser(
                getContext().getContentResolver(),
                key,
                defaultValue,
                UserHandle.USER_CURRENT
        );
        if (mThemeUtils == null) {
            mThemeUtils = ThemeUtils.getInstance(getContext());
        }
        mThemeUtils.setOverlayEnabled(category, target, target);
        if (style > 0 && style <= overlayPackages.length) {
            mThemeUtils.setOverlayEnabled(category, overlayPackages[style - 1], target);
        }
        if (restartSystemUI) {
            SystemRestartUtils.restartSystemUI(getContext());
        }
    }

    private void updatePowerMenuStyle() {
        updateStyle(KEY_POWERMENU_STYLE, "android.theme.customization.powermenu", "com.android.systemui", 0, POWER_MENU_OVERLAYS, false);
    }

    private void updateNotifStyle() {
        updateStyle(KEY_NOTIF_STYLE, "android.theme.customization.notification", "com.android.systemui", 0, NOTIF_OVERLAYS, true);
    }

    private void updateProgressBarStyle() {
        updateStyle(KEY_PGB_STYLE, "android.theme.customization.progress_bar", "android", 0, PROGRESS_BAR_OVERLAYS, false);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        // Ensure newValue is a valid integer before parsing
        int value = 0;
        if (newValue instanceof String) {
            try {
                value = Integer.parseInt((String) newValue);
            } catch (NumberFormatException e) {
                // Handle the case where newValue is not an integer (like a file path)
                if (preference == mLockSound || preference == mUnlockSound) {
                    SystemUtils.showSystemUiRestartDialog(context);
                    return true;
                }
                return false;
            }
        }
        if (preference == mProgressBarPref) {
            Settings.System.putIntForUser(getActivity().getContentResolver(),
                    KEY_PGB_STYLE, value, UserHandle.USER_CURRENT);
            updateProgressBarStyle();
            return true;
        } else if (preference == mNotificationStylePref) {
            Settings.System.putIntForUser(getActivity().getContentResolver(),
                    KEY_NOTIF_STYLE, value, UserHandle.USER_CURRENT);
            updateNotifStyle();
            return true;
        } else if (preference == mPowerMenuStylePref) {
            Settings.System.putIntForUser(getActivity().getContentResolver(),
                    KEY_POWERMENU_STYLE, value, UserHandle.USER_CURRENT);
            updatePowerMenuStyle();
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_themes) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

                FingerprintManager fingerprintManager = (FingerprintManager)
                        context.getSystemService(Context.FINGERPRINT_SERVICE);

                if (DeviceUtils.isCurrentlySupportedPixel()) {
                    keys.add(KEY_BOOT_ANIMATION);
                }

                if (!DeviceUtils.deviceSupportsMobileData(context)) {
                    keys.add(KEY_SIGNAL_ICON);
                }

                if (!Utils.isPackageInstalled(context, "com.google.android.apps.nexuslauncher")) {
                    keys.add(KEY_LAUNCHER_CATEGORY);
                }

                if (fingerprintManager == null || !fingerprintManager.isHardwareDetected()) {
                    keys.add(KEY_UDFPS_ICON);
                    keys.add(KEY_UDFPS_ANIMATION);
                } else {
                    if (!Utils.isPackageInstalled(context, "org.evolution.udfps.icons")) {
                        keys.add(KEY_UDFPS_ICON);
                    }
                    if (!Utils.isPackageInstalled(context, "org.evolution.udfps.animations")) {
                        keys.add(KEY_UDFPS_ANIMATION);
                    }
                }
                return keys;
            }
        };
}
