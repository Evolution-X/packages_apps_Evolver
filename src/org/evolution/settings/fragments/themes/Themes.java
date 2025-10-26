/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.ThemeUtils;
import com.android.internal.util.evolution.Utils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.GlobalSettingListPreference;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.ExternalFontInstaller;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class Themes extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Themes";

    private static final String KEY_LOCK_SOUND = "lock_sound";
    private static final String KEY_UNLOCK_SOUND = "unlock_sound";
    private static final String KEY_ICONS_CATEGORY = "themes_icons_category";
    private static final String KEY_SIGNAL_ICON = "android.theme.customization.signal_icon";
    private static final String KEY_UDFPS_ICON = "udfps_icon";
    private static final String KEY_ANIMATIONS_CATEGORY = "themes_animations_category";
    private static final String KEY_UDFPS_ANIMATION = "udfps_animation";
    private static final String KEY_PGB_STYLE = "progress_bar_style";
    private static final String KEY_NOTIFICATION_STYLE = "notification_style";
    private static final String KEY_POWERMENU_STYLE = "powermenu_style";
    private static final String KEY_LAUNCHER_CATEGORY = "themes_launcher_category";
    private static final String KEY_FONT_MODE = "font_mode";
    private static final String KEY_PREBUILT_FONTS = "android.theme.customization.fonts";
    private static final String KEY_CUSTOM_FONT_PICKER = "custom_font_picker";
    private static final String KEY_CUSTOM_FONT_INFO = "custom_font_info";
    private static final String KEY_RESET_CUSTOM_FONT = "reset_custom_font";
    private static final String KEY_REBOOT_FOR_FONT = "reboot_for_font";

    private static final int REQUEST_PICK_FONT = 1001;

    private static final String[] POWERMENU_OVERLAYS = {
            "com.android.theme.powermenu.cyberpunk",
            "com.android.theme.powermenu.duoline",
            "com.android.theme.powermenu.fluid",
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

    private ExternalFontInstaller mFontInstaller;
    private GlobalSettingListPreference mLockSound;
    private GlobalSettingListPreference mUnlockSound;
    private PreferenceCategory mLauncherCategory;
    private PreferenceCategory mIconsCategory;
    private Preference mSignalIcon;
    private Preference mUdfpsIcon;
    private PreferenceCategory mAnimationsCategory;
    private Preference mUdfpsAnimation;
    private SystemSettingListPreference mNotificationStylePref;
    private SystemSettingListPreference mPowerMenuStylePref;
    private SystemSettingListPreference mProgressBarPref;
    private SystemSettingListPreference mFontModePref;
    private Preference mPrebuiltFontsPref;
    private Preference mCustomFontPickerPref;
    private Preference mCustomFontInfoPref;
    private Preference mResetCustomFontPref;
    private Preference mRebootForFontPref;
    private ThemeUtils mThemeUtils;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_themes);
        mThemeUtils = new ThemeUtils(getContext());
        mFontInstaller = new ExternalFontInstaller(getActivity());

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
        mSignalIcon = (Preference) findPreference(KEY_SIGNAL_ICON);
        mUdfpsIcon = (Preference) findPreference(KEY_UDFPS_ICON);
        mAnimationsCategory = (PreferenceCategory) findPreference(KEY_ANIMATIONS_CATEGORY);
        mUdfpsAnimation = (Preference) findPreference(KEY_UDFPS_ANIMATION);

        if (!DeviceUtils.deviceSupportsMobileData(context)) {
            mIconsCategory.removePreference(mSignalIcon);
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

        mNotificationStylePref = findPreference(KEY_NOTIFICATION_STYLE);
        mNotificationStylePref.setOnPreferenceChangeListener(this);

        mPowerMenuStylePref = findPreference(KEY_POWERMENU_STYLE);
        mPowerMenuStylePref.setOnPreferenceChangeListener(this);

        mFontModePref = findPreference(KEY_FONT_MODE);
        mFontModePref.setOnPreferenceChangeListener(this);

        mPrebuiltFontsPref = findPreference(KEY_PREBUILT_FONTS);

        mCustomFontPickerPref = findPreference(KEY_CUSTOM_FONT_PICKER);
        mCustomFontPickerPref.setOnPreferenceClickListener(preference -> {
            pickCustomFont();
            return true;
        });

        mCustomFontInfoPref = findPreference(KEY_CUSTOM_FONT_INFO);
        mResetCustomFontPref = findPreference(KEY_RESET_CUSTOM_FONT);
        mResetCustomFontPref.setOnPreferenceClickListener(preference -> {
            resetCustomFont();
            return true;
        });

        mRebootForFontPref = findPreference(KEY_REBOOT_FOR_FONT);
        mRebootForFontPref.setOnPreferenceClickListener(preference -> {
            showRebootDialog();
            return true;
        });

        updateFontPreferences();

        if (!Utils.isPackageInstalled(context, "com.google.android.apps.nexuslauncher")) {
            prefScreen.removePreference(mLauncherCategory);
        }
    }

    private void updateFontPreferences() {
        final String fontMode = Settings.System.getStringForUser(
                getContext().getContentResolver(),
                KEY_FONT_MODE,
                UserHandle.USER_CURRENT
        );
        final String customFontName = Settings.Secure.getStringForUser(
                getContext().getContentResolver(),
                "custom_font_name",
                UserHandle.USER_CURRENT
        );

        final boolean isCustomMode = "custom".equals(fontMode);
        final boolean hasCustomFont = customFontName != null && !customFontName.isEmpty();

        mPrebuiltFontsPref.setVisible(!isCustomMode);
        mCustomFontPickerPref.setVisible(isCustomMode);
        mCustomFontInfoPref.setVisible(isCustomMode && hasCustomFont);
        mResetCustomFontPref.setVisible(isCustomMode && hasCustomFont);
        mRebootForFontPref.setVisible(isCustomMode && hasCustomFont);

        if (hasCustomFont) {
            mCustomFontInfoPref.setSummary(getString(R.string.custom_font_installed_summary, customFontName));
        }
    }

    private void pickCustomFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQUEST_PICK_FONT);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FONT && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri fontUri = data.getData();
                installCustomFont(fontUri);
            }
        }
    }

    private void installCustomFont(Uri fontUri) {
        new Thread(() -> {
            String postScriptName = mFontInstaller.installFontFromUri(getContext(), fontUri);
            if (postScriptName != null) {
                Settings.Secure.putStringForUser(
                        getContext().getContentResolver(),
                        "custom_font_name",
                        postScriptName,
                        UserHandle.USER_CURRENT
                );
                getActivity().runOnUiThread(() -> {
                    updateFontPreferences();
                    Toast.makeText(getContext(), R.string.custom_font_installed_success, Toast.LENGTH_SHORT).show();
                });
            } else {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), R.string.custom_font_install_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void resetCustomFont() {
        mFontInstaller.resetFontUpdates(getContext());
        Settings.Secure.putStringForUser(
                getContext().getContentResolver(),
                "custom_font_name",
                "",
                UserHandle.USER_CURRENT
        );

        if (mThemeUtils != null) {
            mThemeUtils.setOverlayEnabled(
                    "android.theme.customization.font",
                    "com.android.theme.font.SpaceGrotesk",
                    "android"
            );
        }

        updateFontPreferences();
        Toast.makeText(getContext(), R.string.custom_font_reset_success, Toast.LENGTH_SHORT).show();
    }

    private void showRebootDialog() {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.reboot_required_title)
                .setMessage(R.string.reboot_required_message)
                .setPositiveButton(R.string.reboot_device, (dialog, which) -> {
                    ExternalFontInstaller.rebootDevice(getContext());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateStyle(String key, String category, String target,
            int defaultValue, String[] overlayPackages, int style) {
        mThemeUtils.setOverlayEnabled(category, target, target);
        if (style > 0 && style <= overlayPackages.length) {
            mThemeUtils.setOverlayEnabled(category, overlayPackages[style - 1], target);
        }
    }

    private void updatePowermenuStyle(int style) {
        updateStyle(KEY_POWERMENU_STYLE, "android.theme.customization.powermenu", "com.android.systemui", 0, POWERMENU_OVERLAYS, style);
    }

    private void updateNotifStyle(int style) {
        updateStyle(KEY_NOTIFICATION_STYLE, "android.theme.customization.notification", "com.android.systemui", 0, NOTIF_OVERLAYS, style);
    }

    private void updateProgressBarStyle(int style) {
        updateStyle(KEY_PGB_STYLE, "android.theme.customization.progress_bar", "android", 0, PROGRESS_BAR_OVERLAYS, style);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mFontModePref) {
            String fontMode = (String) newValue;
            Settings.System.putStringForUser(
                    resolver,
                    KEY_FONT_MODE,
                    fontMode,
                    UserHandle.USER_CURRENT
            );
            if ("prebuilt".equals(fontMode)) {
                String customFontName = Settings.Secure.getStringForUser(
                        resolver,
                        "custom_font_name",
                        UserHandle.USER_CURRENT
                );
                if (customFontName != null && !customFontName.isEmpty()) {
                    resetCustomFont();
                }
            }
            updateFontPreferences();
            return true;
        }
        int value = 0;
        if (newValue instanceof String) {
            try {
                value = Integer.parseInt((String) newValue);
            } catch (NumberFormatException e) {
                if (preference == mLockSound || preference == mUnlockSound) {
                    SystemUtils.showSystemUiRestartDialog(context);
                    return true;
                }
                return false;
            }
        }
        if (preference == mProgressBarPref) {
            int value2 = Integer.parseInt((String) newValue);
            updateProgressBarStyle(value2);
            return true;
        } else if (preference == mNotificationStylePref) {
            int value2 = Integer.parseInt((String) newValue);
            updateNotifStyle(value2);
            return true;
        } else if (preference == mPowerMenuStylePref) {
            int value2 = Integer.parseInt((String) newValue);
            updatePowermenuStyle(value2);
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
