/*
 * Copyright (C) 2019-2025 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.LinkedHashMap;
import java.util.Map;

@SearchIndexable
public class About extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "About";

    private GithubAvatarLoader mAvatarLoader;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_about);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();

        // Load GitHub avatars for team members and founders
        mAvatarLoader = new GithubAvatarLoader();

        Map<String, String> avatarMap = new LinkedHashMap<>();

        // Founders
        avatarMap.put("about_founder_1",          "joeyhuab");
        avatarMap.put("about_founder_2",          "AnierinBliss");
        avatarMap.put("about_founder_3",          "RealAkito");

        // Team members
        avatarMap.put("about_member_1",           "TechPanelGM");
        avatarMap.put("about_member_2",           "AidanWarner97");
        avatarMap.put("about_member_3",           "Onelots");
        avatarMap.put("about_member_4",           "manidweep");

        // Remembering
        avatarMap.put("about_member_5",           "apelete");

        for (Map.Entry<String, String> entry : avatarMap.entrySet()) {
            Preference pref = findPreference(entry.getKey());
            if (pref != null) {
                mAvatarLoader.load(context, pref, entry.getValue());
            }
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider(R.xml.evolution_settings_about);
}
