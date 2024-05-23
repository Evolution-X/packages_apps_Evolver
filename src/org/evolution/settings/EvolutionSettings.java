/*
 * Copyright (C) 2019-2024 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.DeviceUtils;

@SearchIndexable
public class EvolutionSettings extends DashboardFragment {

    private static final String TAG = "EvolutionSettings";

    private static final String KEY_BUTTONS_PREF = "buttons";

    private Preference mButtonsPref;

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.evolution_settings;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources resources = context.getResources();

        mButtonsPref = (Preference) findPreference(KEY_BUTTONS_PREF);

        if (DeviceUtils.isEdgeToEdgeEnabled(context)) {
            prefScreen.removePreference(mButtonsPref);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings);
}
