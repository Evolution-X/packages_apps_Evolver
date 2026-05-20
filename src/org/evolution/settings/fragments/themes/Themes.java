/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.SystemRestartUtils;
import com.android.internal.util.evolution.ThemeUtils;
import com.android.internal.util.evolution.Utils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.fragments.themes.IconShapeController;
import org.evolution.settings.preferences.SoundPickerPreference;
import org.evolution.settings.preferences.SystemPropertyListPreference;
import org.evolution.settings.preferences.SystemPropertySwitchPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;

@SearchIndexable
public class Themes extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Themes";

    private static final String VELVET_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String VELVET_NEW_SEARCH_CLASS = VELVET_PACKAGE + ".OneSearchAimActivity";
    private static final String VELVET_ONESEARCH_COMPONENT = VELVET_PACKAGE + "/" + VELVET_NEW_SEARCH_CLASS;

    private static final String KEY_ANIMATIONS_CATEGORY = "themes_visual_effects_category";
    private static final String KEY_BOOT_ANIMATION = "boot_animation";
    private static final String KEY_EMOJI_STYLE = "persist.sys.ax_emoji_style";
    private static final String KEY_ICON_SHAPE = "android.theme.customization.adaptive_icon_shape";
    private static final String KEY_ICONS_CATEGORY = "themes_icons_category";
    private static final String KEY_LAUNCHER_CATEGORY = "themes_launcher_category";
    private static final String KEY_LAUNCHER_SEARCH_BAR = "persist.sys.velvet.force_onesearch";
    private static final String KEY_LOCK_SOUND = "lock_sound";
    private static final String KEY_NAVBAR_ICONS = "android.theme.customization.navbar";
    private static final String KEY_UDFPS_ANIMATION = "udfps_animation";
    private static final String KEY_UDFPS_ICON = "udfps_icon";
    private static final String KEY_UNLOCK_SOUND = "unlock_sound";

    private Preference mNavbarIcons;
    private Preference mUdfpsAnimation;
    private Preference mUdfpsIcon;
    private PreferenceCategory mAnimationsCategory;
    private PreferenceCategory mIconsCategory;
    private PreferenceCategory mLauncherCategory;
    private SoundPickerPreference mLockSound;
    private SoundPickerPreference mUnlockSound;
    private SystemPropertyListPreference mEmojiStyle;
    private SystemPropertySwitchPreference mSearchBar;
    private ThemeUtils mThemeUtils;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_themes);
        mThemeUtils = ThemeUtils.getInstance(getContext());

        final Context context = getContext();
        final PreferenceScreen prefScreen = getPreferenceScreen();

        mLockSound = findPreference(KEY_LOCK_SOUND);
        if (mLockSound != null) {
            mLockSound.entries = getResources().getTextArray(R.array.themes_lock_unlock_sounds_entries);
            mLockSound.entryValues = getResources().getTextArray(R.array.themes_lock_sounds_values);
            mLockSound.settingKey = "lock_sound";
            mLockSound.defaultValue = "/product/media/audio/ui/Lock.ogg";
            mLockSound.applyConfig();
        }

        mUnlockSound = findPreference(KEY_UNLOCK_SOUND);
        if (mUnlockSound != null) {
            mUnlockSound.entries = getResources().getTextArray(R.array.themes_lock_unlock_sounds_entries);
            mUnlockSound.entryValues = getResources().getTextArray(R.array.themes_unlock_sounds_values);
            mUnlockSound.settingKey = "unlock_sound";
            mUnlockSound.defaultValue = "/product/media/audio/ui/Unlock.ogg";
            mUnlockSound.applyConfig();
        }

        mLauncherCategory = findPreference(KEY_LAUNCHER_CATEGORY);
        mSearchBar = findPreference(KEY_LAUNCHER_SEARCH_BAR);
        mIconsCategory = findPreference(KEY_ICONS_CATEGORY);
        mNavbarIcons = findPreference(KEY_NAVBAR_ICONS);
        mUdfpsIcon = findPreference(KEY_UDFPS_ICON);
        mAnimationsCategory = findPreference(KEY_ANIMATIONS_CATEGORY);
        mUdfpsAnimation = findPreference(KEY_UDFPS_ANIMATION);
        mEmojiStyle = findPreference(KEY_EMOJI_STYLE);

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

        if (!Utils.isPackageInstalled(context, "com.google.android.apps.nexuslauncher")) {
            prefScreen.removePreference(mLauncherCategory);
        }

        if (mNavbarIcons != null && isGestureNavigationEnabled(context)) {
            mIconsCategory.removePreference(mNavbarIcons);
        }

        if (mSearchBar != null) {
            mSearchBar.setChecked(isOneSearchAimActivityEnabled(context)
                    && SystemProperties.getBoolean(KEY_LAUNCHER_SEARCH_BAR, false));
            mSearchBar.setOnPreferenceClickListener(pref -> {
                DeviceUtils.setComponentEnabled(context, VELVET_ONESEARCH_COMPONENT,
                        mSearchBar.isChecked());
                return false;
            });
        }

        if (mEmojiStyle != null) {
            mEmojiStyle.setOnPreferenceChangeListener(this);
        }

        updateBootAnimationSummary();
        updateIconShapeSummary();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (KEY_EMOJI_STYLE.equals(preference.getKey())) {
            SystemRestartUtils.showSystemRestartDialog(getActivity());
            return true;
        }
        return false;
    }

    private void updateBootAnimationSummary() {
        Preference pref = findPreference(KEY_BOOT_ANIMATION);
        if (pref == null) return;
        pref.setSummary(new BootAnimationController(getContext(), KEY_BOOT_ANIMATION).getSummary());
    }

    private void updateIconShapeSummary() {
        Preference pref = findPreference(KEY_ICON_SHAPE);
        if (pref == null) return;
        pref.setSummary(new IconShapeController(getContext(), KEY_ICON_SHAPE).getSummary());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBootAnimationSummary();
        updateIconShapeSummary();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    private static boolean isOneSearchAimActivityEnabled(Context context) {
        return DeviceUtils.isActivityEnabled(context, VELVET_ONESEARCH_COMPONENT);
    }

    private static boolean isGestureNavigationEnabled(Context context) {
        return Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.NAVIGATION_MODE, 0, UserHandle.USER_CURRENT) == 2;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_themes) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);

                    FingerprintManager fingerprintManager = (FingerprintManager)
                            context.getSystemService(Context.FINGERPRINT_SERVICE);

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

                    if (isGestureNavigationEnabled(context)) {
                        keys.add(KEY_NAVBAR_ICONS);
                    }

                    return keys;
                }
            };
}
