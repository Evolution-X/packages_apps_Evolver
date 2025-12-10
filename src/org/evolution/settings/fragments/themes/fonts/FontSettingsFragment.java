/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes.fonts;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.ThemeUtils;
import com.android.internal.util.evolution.VibrationUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.preferences.SystemSettingListPreference;
import org.evolution.settings.utils.ExternalFontInstaller;

@SearchIndexable
public class FontSettingsFragment extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "FontSettingsFragment";

    private static final String KEY_FONT_MODE = "font_mode";
    private static final String KEY_PREBUILT_FONTS = "android.theme.customization.fonts";
    private static final String KEY_CUSTOM_FONT_PICKER = "custom_font_picker";
    private static final String KEY_GITHUB_FONT_PICKER = "github_font_picker";
    private static final String KEY_CUSTOM_FONT_INFO = "custom_font_info";
    private static final String KEY_RESET_CUSTOM_FONT = "reset_custom_font";
    private static final String KEY_REBOOT_FOR_FONT = "reboot_for_font";

    private static final int REQUEST_PICK_FONT = 1001;

    private SystemSettingListPreference mFontModePref;
    private Preference mPrebuiltFontsPref;
    private Preference mCustomFontPickerPref;
    private Preference mGithubFontPickerPref;
    private Preference mCustomFontInfoPref;
    private Preference mResetCustomFontPref;
    private Preference mRebootForFontPref;
    private ThemeUtils mThemeUtils;
    private ExternalFontInstaller mFontInstaller;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_fonts);

        mThemeUtils = ThemeUtils.getInstance(getActivity());
        mFontInstaller = new ExternalFontInstaller(getActivity());

        final Context context = getContext();
        final PreferenceScreen prefScreen = getPreferenceScreen();

        mFontModePref = findPreference(KEY_FONT_MODE);
        mFontModePref.setOnPreferenceChangeListener(this);

        mPrebuiltFontsPref = findPreference(KEY_PREBUILT_FONTS);

        mCustomFontPickerPref = findPreference(KEY_CUSTOM_FONT_PICKER);
        mCustomFontPickerPref.setOnPreferenceClickListener(preference -> {
            showFontPreviewDialog();
            return true;
        });

        mGithubFontPickerPref = findPreference(KEY_GITHUB_FONT_PICKER);
        mGithubFontPickerPref.setOnPreferenceClickListener(preference -> {
            openGithubFontPickerDialog();
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

        final boolean isPrebuiltMode = "prebuilt".equals(fontMode);
        final boolean isCustomMode = "custom".equals(fontMode);
        final boolean isGithubMode = "github".equals(fontMode);
        final boolean hasCustomFont = customFontName != null && !customFontName.isEmpty();

        mPrebuiltFontsPref.setVisible(isPrebuiltMode);
        mCustomFontPickerPref.setVisible(isCustomMode);
        mGithubFontPickerPref.setVisible(isGithubMode);
        mCustomFontInfoPref.setVisible((isCustomMode || isGithubMode) && hasCustomFont);
        mResetCustomFontPref.setVisible((isCustomMode || isGithubMode) && hasCustomFont);
        mRebootForFontPref.setVisible((isCustomMode || isGithubMode) && hasCustomFont);

        if (hasCustomFont) {
            mCustomFontInfoPref.setSummary(getString(R.string.custom_font_installed_summary, customFontName));
        }
    }

    private void showFontPreviewDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQUEST_PICK_FONT);
    }

    private void openGithubFontPickerDialog() {
        GithubFontPickerDialog dialog = new GithubFontPickerDialog(getActivity());
        dialog.setOnFontSelectedListener(fontName -> {
            updateFontPreferences();
        });
        dialog.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FONT && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri fontUri = data.getData();
                showFontPreviewAndInstall(fontUri);
            }
        }
    }

    private void showFontPreviewAndInstall(Uri fontUri) {
        FontPreviewDialog dialog = new FontPreviewDialog(getContext(), fontUri);
        dialog.setOnFontInstallListener(new FontPreviewDialog.OnFontInstallListener() {
            @Override
            public void onInstall(Uri uri) {
                installCustomFont(uri);
            }

            @Override
            public void onCancel() {
            }
        });
        dialog.show();
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
                    "com.android.theme.font.googlesansflex",
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

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference != null && preference.getKey() != null) {
            VibrationUtils.triggerVibration(getContext(), 3);
        }
        return super.onPreferenceTreeClick(preference);
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

        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_fonts);
}
