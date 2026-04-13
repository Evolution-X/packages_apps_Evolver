/*
 * Copyright (C) 2019-2025 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.provider.Settings;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;

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
import org.evolution.settings.preferences.SystemPropertySwitchPreference;
import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class Themes extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Themes";

    private static final String VELVET_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String VELVET_NEW_SEARCH_CLASS = VELVET_PACKAGE + ".OneSearchAimActivity";
    private static final String VELVET_ONESEARCH_COMPONENT = VELVET_PACKAGE + "/" + VELVET_NEW_SEARCH_CLASS;

    private static final String KEY_LOCK_SOUND = "lock_sound";
    private static final String KEY_UNLOCK_SOUND = "unlock_sound";
    private static final String KEY_ICONS_CATEGORY = "themes_icons_category";
    private static final String KEY_UDFPS_ICON = "udfps_icon";
    private static final String KEY_ANIMATIONS_CATEGORY = "themes_animations_category";
    private static final String KEY_UDFPS_ANIMATION = "udfps_animation";
    private static final String KEY_LAUNCHER_CATEGORY = "themes_launcher_category";
    private static final String KEY_LAUNCHER_SEARCH_BAR = "persist.sys.velvet.force_onesearch";

    private GlobalSettingListPreference mLockSound;
    private GlobalSettingListPreference mUnlockSound;
    private PreferenceCategory mLauncherCategory;
    private SystemPropertySwitchPreference mSearchBar;
    private PreferenceCategory mIconsCategory;
    private Preference mUdfpsIcon;
    private PreferenceCategory mAnimationsCategory;
    private Preference mUdfpsAnimation;
    private ThemeUtils mThemeUtils;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_themes);
        mThemeUtils = ThemeUtils.getInstance(getContext());

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mLockSound = (GlobalSettingListPreference) findPreference(KEY_LOCK_SOUND);
        mLockSound.setOnPreferenceChangeListener(this);
        mUnlockSound = (GlobalSettingListPreference) findPreference(KEY_UNLOCK_SOUND);
        mUnlockSound.setOnPreferenceChangeListener(this);
        mLauncherCategory = (PreferenceCategory) findPreference(KEY_LAUNCHER_CATEGORY);
        mSearchBar = (SystemPropertySwitchPreference) findPreference(KEY_LAUNCHER_SEARCH_BAR);
        mIconsCategory = (PreferenceCategory) findPreference(KEY_ICONS_CATEGORY);
        mUdfpsIcon = (Preference) findPreference(KEY_UDFPS_ICON);
        mAnimationsCategory = (PreferenceCategory) findPreference(KEY_ANIMATIONS_CATEGORY);
        mUdfpsAnimation = (Preference) findPreference(KEY_UDFPS_ANIMATION);

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

        if (mSearchBar != null) {
            mSearchBar.setChecked(isOneSearchAimActivityEnabled(context)
            && SystemProperties.getBoolean(KEY_LAUNCHER_SEARCH_BAR, false));
            mSearchBar.setOnPreferenceClickListener(pref -> {
                boolean enabled = mSearchBar.isChecked();
                DeviceUtils.setComponentEnabled(context, VELVET_ONESEARCH_COMPONENT, enabled);
                return false;
            });
        }
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
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    private static boolean isOneSearchAimActivityEnabled(Context context) {
        return DeviceUtils.isActivityEnabled(context, VELVET_ONESEARCH_COMPONENT);
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_themes) {

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);
                final Resources resources = context.getResources();

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
                return keys;
            }
        };
}
