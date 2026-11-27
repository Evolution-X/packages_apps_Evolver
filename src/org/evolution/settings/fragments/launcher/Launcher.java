/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.launcher;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

@SearchIndexable
public class Launcher extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_HIDE_PAGE_INDICATOR = "launcher_hide_page_indicator";
    private static final String KEY_PAGE_INDICATOR_COLOR = "launcher_page_indicator_color";
    private static final String KEY_FOLDER_OPACITY = "launcher_folder_opacity";
    private static final String KEY_DOCK_SPACING = "launcher_dock_spacing";
    private static final String KEY_HIDE_SCROLLBAR = "launcher_hide_scrollbar";
    private static final String KEY_HOME_CATEGORY = "launcher_home_category";
    private static final String KEY_FOLDERS_CATEGORY = "launcher_folders_category";
    private static final String KEY_DOCK_CATEGORY = "launcher_dock_category";
    private static final String KEY_DRAWER_CATEGORY = "launcher_drawer_category";
    private static final String KEY_OVERLAYS_UNAVAILABLE = "launcher_overlays_unavailable";
    private static final String KEY_RESTART_NOTE = "launcher_restart_note";

    private static final String[] PAGE_COLOR_OVERLAYS = {
            LauncherOverlayController.OVERLAY_PAGE_INDICATOR_DARK,
            LauncherOverlayController.OVERLAY_PAGE_INDICATOR_LIGHT,
    };
    private static final String[] PAGE_COLOR_VALUES = {"dark", "light"};

    private static final String[] FOLDER_OPACITY_OVERLAYS = {
            LauncherOverlayController.OVERLAY_FOLDER_OPACITY_75,
            LauncherOverlayController.OVERLAY_FOLDER_OPACITY_50,
            LauncherOverlayController.OVERLAY_FOLDER_OPACITY_0,
    };
    private static final String[] FOLDER_OPACITY_VALUES = {"75", "50", "0"};

    private static final String[] DOCK_SPACING_OVERLAYS = {
            LauncherOverlayController.OVERLAY_DOCK_COMPACT,
            LauncherOverlayController.OVERLAY_DOCK_WIDE,
    };
    private static final String[] DOCK_SPACING_VALUES = {"compact", "wide"};

    private LauncherOverlayController mController;

    private SwitchPreferenceCompat mHidePageIndicator;
    private ListPreference mPageIndicatorColor;
    private ListPreference mFolderOpacity;
    private ListPreference mDockSpacing;
    private SwitchPreferenceCompat mHideScrollbar;

    private boolean mSyncing;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_launcher);

        Context context = getContext();
        if (context == null) {
            return;
        }
        mController = new LauncherOverlayController(context.getApplicationContext());

        mHidePageIndicator = findPreference(KEY_HIDE_PAGE_INDICATOR);
        mPageIndicatorColor = findPreference(KEY_PAGE_INDICATOR_COLOR);
        mFolderOpacity = findPreference(KEY_FOLDER_OPACITY);
        mDockSpacing = findPreference(KEY_DOCK_SPACING);
        mHideScrollbar = findPreference(KEY_HIDE_SCROLLBAR);

        if (mHidePageIndicator == null || mPageIndicatorColor == null
                || mFolderOpacity == null || mDockSpacing == null
                || mHideScrollbar == null) {
            return;
        }

        if (!mController.areAllOverlaysAvailable()) {
            hideUnavailableOptions();
            return;
        }

        mHidePageIndicator.setOnPreferenceChangeListener(this);
        mPageIndicatorColor.setOnPreferenceChangeListener(this);
        mFolderOpacity.setOnPreferenceChangeListener(this);
        mDockSpacing.setOnPreferenceChangeListener(this);
        mHideScrollbar.setOnPreferenceChangeListener(this);

        syncPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncPreferences();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mSyncing || mController == null
                || !mController.areAllOverlaysAvailable()) {
            return false;
        }

        boolean success;
        if (preference == mHidePageIndicator) {
            success = mController.setEnabled(
                    LauncherOverlayController.OVERLAY_HIDE_PAGE_INDICATOR,
                    Boolean.TRUE.equals(newValue));
        } else if (preference == mPageIndicatorColor) {
            success = mController.select(PAGE_COLOR_OVERLAYS,
                    overlayForValue((String) newValue, PAGE_COLOR_VALUES,
                            PAGE_COLOR_OVERLAYS, null));
        } else if (preference == mFolderOpacity) {
            success = mController.select(FOLDER_OPACITY_OVERLAYS,
                    overlayForValue((String) newValue, FOLDER_OPACITY_VALUES,
                            FOLDER_OPACITY_OVERLAYS, null));
        } else if (preference == mDockSpacing) {
            success = mController.select(DOCK_SPACING_OVERLAYS,
                    overlayForValue((String) newValue, DOCK_SPACING_VALUES,
                            DOCK_SPACING_OVERLAYS, null));
        } else if (preference == mHideScrollbar) {
            success = mController.setEnabled(
                    LauncherOverlayController.OVERLAY_HIDE_SCROLLBAR,
                    Boolean.TRUE.equals(newValue));
        } else {
            return false;
        }

        if (!success) {
            Toast.makeText(getContext(), R.string.launcher_apply_failed,
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        if (!mController.restartLauncher()) {
            Toast.makeText(getContext(), R.string.launcher_restart_failed,
                    Toast.LENGTH_SHORT).show();
        }

        // onPreferenceChange runs before the preference stores the new value.
        if (preference instanceof ListPreference) {
            ((ListPreference) preference).setValue((String) newValue);
        } else if (preference instanceof SwitchPreferenceCompat) {
            ((SwitchPreferenceCompat) preference).setChecked(Boolean.TRUE.equals(newValue));
        }
        updateSummaries();
        return true;
    }

    private void syncPreferences() {
        if (mController == null || mHidePageIndicator == null
                || !mController.areAllOverlaysAvailable()) {
            return;
        }

        mSyncing = true;
        mHidePageIndicator.setChecked(mController.isEnabled(
                LauncherOverlayController.OVERLAY_HIDE_PAGE_INDICATOR));
        mHideScrollbar.setChecked(mController.isEnabled(
                LauncherOverlayController.OVERLAY_HIDE_SCROLLBAR));

        mPageIndicatorColor.setValue(mController.getSelected(
                PAGE_COLOR_OVERLAYS, PAGE_COLOR_VALUES, "default"));
        mFolderOpacity.setValue(mController.getSelected(
                FOLDER_OPACITY_OVERLAYS, FOLDER_OPACITY_VALUES, "100"));
        mDockSpacing.setValue(mController.getSelected(
                DOCK_SPACING_OVERLAYS, DOCK_SPACING_VALUES, "default"));
        mSyncing = false;

        updateSummaries();
    }

    private void updateSummaries() {
        if (mHidePageIndicator == null) {
            return;
        }

        mHidePageIndicator.setSummary(mHidePageIndicator.isChecked()
                ? R.string.launcher_hide_page_indicator_on_summary
                : R.string.launcher_hide_page_indicator_off_summary);
        setListSummary(mPageIndicatorColor);
        if (mFolderOpacity != null) {
            mFolderOpacity.setSummary(getString(R.string.launcher_folder_opacity_summary,
                    mFolderOpacity.getEntry()));
        }
        setListSummary(mDockSpacing);
    }

    private void setListSummary(ListPreference preference) {
        if (preference != null && preference.getEntry() != null) {
            preference.setSummary(preference.getEntry());
        }
    }

    private String overlayForValue(String value, String[] values, String[] overlays,
            String fallback) {
        if (value != null) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return overlays[i];
                }
            }
        }
        return fallback;
    }

    private void hideUnavailableOptions() {
        final String[] keys = {
                KEY_HIDE_PAGE_INDICATOR,
                KEY_PAGE_INDICATOR_COLOR,
                KEY_FOLDER_OPACITY,
                KEY_DOCK_SPACING,
                KEY_HIDE_SCROLLBAR,
                KEY_HOME_CATEGORY,
                KEY_FOLDERS_CATEGORY,
                KEY_DOCK_CATEGORY,
                KEY_DRAWER_CATEGORY,
                KEY_RESTART_NOTE,
        };
        for (String key : keys) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setVisible(false);
            }
        }

        Preference unavailable = findPreference(KEY_OVERLAYS_UNAVAILABLE);
        if (unavailable != null) {
            unavailable.setVisible(true);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_launcher) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    LauncherOverlayController controller =
                            new LauncherOverlayController(context.getApplicationContext());
                    if (!controller.areAllOverlaysAvailable()) {
                        keys.add(KEY_HIDE_PAGE_INDICATOR);
                        keys.add(KEY_PAGE_INDICATOR_COLOR);
                        keys.add(KEY_FOLDER_OPACITY);
                        keys.add(KEY_DOCK_SPACING);
                        keys.add(KEY_HIDE_SCROLLBAR);
                    }
                    return keys;
                }
            };
}
