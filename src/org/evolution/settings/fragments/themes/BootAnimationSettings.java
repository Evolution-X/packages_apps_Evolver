/*
 * Copyright (C) 2024 risingOS
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
package org.evolution.settings.fragments.themes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageView;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.Indexable;
import com.android.settingslib.search.SearchIndexable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.evolution.settings.preferences.BootAnimationPreviewPreference;

@SearchIndexable
public class BootAnimationSettings extends SettingsPreferenceFragment implements OnPreferenceChangeListener {

    private static final String BOOTANIMATION_STYLE_KEY = "persist.sys.bootanimation_style";
    private static final String TAG = "BootAnimationSettings";
    private static final int REQUEST_CODE_PICK_ZIP = 1001;
    private static final String CUSTOM_BOOTANIMATION_FILE = "/data/misc/bootanim/bootanimation.zip";

    private ListPreference mBootAnimationStyle;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.evolution_settings_bootanimation);

        mBootAnimationStyle = (ListPreference) findPreference(BOOTANIMATION_STYLE_KEY);
        if (mBootAnimationStyle != null) {
            mBootAnimationStyle.setOnPreferenceChangeListener(this);

            // Set the current value from the system property
            int currentStyle = SystemProperties.getInt(BOOTANIMATION_STYLE_KEY, 0);
            mBootAnimationStyle.setValue(String.valueOf(currentStyle));
            updateBootAnimationPreview();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mBootAnimationStyle) {
            int style = Integer.parseInt((String) newValue);
            if (style == 5) { // Custom option selected
                launchFilePicker();
                return false; // Return false to prevent immediate property update
            } else {
                SystemProperties.set(BOOTANIMATION_STYLE_KEY, (String) newValue);
                updateBootAnimationPreview();
                return true;
            }
        }
        return false;
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_CODE_PICK_ZIP);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_ZIP && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                handleSelectedFile(uri);
            }
        }
    }

    private void handleSelectedFile(Uri uri) {
        try {
            // Copy the selected file to the custom bootanimation location
            InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
            File customBootAnimation = new File(CUSTOM_BOOTANIMATION_FILE);
            // Ensure the directory exists
            customBootAnimation.getParentFile().mkdirs();
            try (OutputStream outputStream = new FileOutputStream(customBootAnimation)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            inputStream.close();
            // Update system property to use custom bootanimation
            SystemProperties.set(BOOTANIMATION_STYLE_KEY, "5"); // Custom option value
            updateBootAnimationPreview();
            // Force the preference to update to the custom option
            mBootAnimationStyle.setValue("5"); // Set to the custom option
        } catch (Exception e) {
            Log.e(TAG, "Error copying custom bootanimation", e);
        }
    }

    private void updateBootAnimationPreview() {
        BootAnimationPreviewPreference previewPreference = (BootAnimationPreviewPreference) findPreference("bootanimation_preview");
        if (previewPreference != null) {
            previewPreference.loadBootAnimationPreview();
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    /**
     * For search
     */
    public static final SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                                                                            boolean enabled) {
                    ArrayList<SearchIndexableResource> result =
                            new ArrayList<>();

                    SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.evolution_settings_bootanimation;
                    result.add(sir);
                    return result;
                }

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    return keys;
                }
            };
}
