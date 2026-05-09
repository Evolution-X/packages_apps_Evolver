/*
 * Copyright (C) 2016-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import com.android.internal.util.evolution.VibrationUtils;

@SearchIndexable
public class MediaArtSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_MEDIA_ART_FILTER = "ls_media_art_filter";
    private static final String KEY_PIXEL_SIZE = "ls_media_art_pixel_size";
    private static final int FILTER_PIXELATION = 7;

    private ListPreference mMediaArtFilter;
    private Preference mPixelSize;

    private ContentObserver mMediaFilterObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            updatePixelSizeVisibility();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.media_art_settings);

        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();

        mMediaArtFilter = (ListPreference) findPreference(KEY_MEDIA_ART_FILTER);
        mPixelSize = findPreference(KEY_PIXEL_SIZE);

        if (mMediaArtFilter != null) {
            mMediaArtFilter.setOnPreferenceChangeListener(this);
        }

        updatePixelSizeVisibility();

        if (resolver != null) {
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_FILTER),
                false,
                mMediaFilterObserver,
                UserHandle.USER_CURRENT
            );
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePixelSizeVisibility();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Context context = getContext();
        if (context != null && context.getContentResolver() != null) {
            context.getContentResolver().unregisterContentObserver(mMediaFilterObserver);
        }
    }

    private void updatePixelSizeVisibility() {
        if (mPixelSize == null) return;

        Context context = getContext();
        if (context == null) return;

        final ContentResolver resolver = context.getContentResolver();
        int currentFilter = Settings.System.getIntForUser(
            resolver,
            Settings.System.LS_MEDIA_ART_FILTER,
            0,
            UserHandle.USER_CURRENT
        );

        mPixelSize.setVisible(currentFilter == FILTER_PIXELATION);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mMediaArtFilter) {
            int filterValue = Integer.parseInt((String) newValue);
            if (mPixelSize != null) {
                mPixelSize.setVisible(filterValue == FILTER_PIXELATION);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference != null && preference.getKey() != null) {
            VibrationUtils.triggerVibration(getContext(), 3);
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.media_art_settings);
}
