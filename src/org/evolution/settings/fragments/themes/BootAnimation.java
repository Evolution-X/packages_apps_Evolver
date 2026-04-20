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
import android.os.SystemProperties;
import android.provider.SearchIndexableResource;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;

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
import org.evolution.settings.utils.BootAnimationUtils;

@SearchIndexable
public class BootAnimation extends SettingsPreferenceFragment
        implements OnPreferenceChangeListener {

    private static final String BOOTANIMATION_STYLE_KEY = "persist.sys.bootanimation_style";
    private static final String TAG = "BootAnimationSettings";
    private static final int REQUEST_CODE_PICK_ZIP = 1001;
    private static final String CUSTOM_BOOTANIMATION_FILE =
            "/data/misc/bootanim/bootanimation.zip";

    private ListPreference mBootAnimationStyle;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.boot_animation);

        // On genuine Pixel devices the boot animation service ignores the
        // system property we set, so the entire preference screen is useless.
        // Hide everything and return early.
        if (BootAnimationUtils.isPixelDevice()) {
            getPreferenceScreen().removeAll();
            return;
        }

        mBootAnimationStyle = findPreference(BOOTANIMATION_STYLE_KEY);
        if (mBootAnimationStyle != null) {
            mBootAnimationStyle.setOnPreferenceChangeListener(this);
            int currentStyle = SystemProperties.getInt(BOOTANIMATION_STYLE_KEY, 0);
            mBootAnimationStyle.setValue(String.valueOf(currentStyle));
            updateBootAnimationPreview();
        }
    }

    // -----------------------------------------------------------------------
    // Preference change
    // -----------------------------------------------------------------------

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference != mBootAnimationStyle) return false;

        int style = Integer.parseInt((String) newValue);

        if (style == BootAnimationUtils.STYLE_CUSTOM) {
            // Don't update the prop yet — wait for the user to pick a file
            launchFilePicker();
            return false;
        }

        // For all built-in styles, just flip the system property.
        // The boot animation service reads it directly from BOOT_ANIMATION_FILES[style].
        // No file copy needed.
        SystemProperties.set(BOOTANIMATION_STYLE_KEY, String.valueOf(style));
        updateBootAnimationPreview();
        return true;
    }

    // -----------------------------------------------------------------------
    // File picker — custom boot animation
    // -----------------------------------------------------------------------

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_CODE_PICK_ZIP);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_ZIP
                && resultCode == Activity.RESULT_OK
                && data != null) {
            Uri uri = data.getData();
            if (uri != null) handleSelectedFile(uri);
        }
    }

    private void handleSelectedFile(Uri uri) {
        try {
            InputStream inputStream =
                    requireContext().getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for URI: " + uri);
                return;
            }

            File dest = new File(CUSTOM_BOOTANIMATION_FILE);
            dest.getParentFile().mkdirs();

            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = inputStream.read(buf)) > 0) out.write(buf, 0, len);
            }
            inputStream.close();

            // Allow the bootanim service (runs as 'graphics') to read the file
            dest.setReadable(true, false);

            SystemProperties.set(BOOTANIMATION_STYLE_KEY,
                    String.valueOf(BootAnimationUtils.STYLE_CUSTOM));
            mBootAnimationStyle.setValue(
                    String.valueOf(BootAnimationUtils.STYLE_CUSTOM));
            updateBootAnimationPreview();

            Toast.makeText(getContext(),
                    R.string.boot_animation_applied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error copying custom boot animation", e);
        }
    }

    // -----------------------------------------------------------------------
    // Preview helper
    // -----------------------------------------------------------------------

    private void updateBootAnimationPreview() {
        BootAnimationPreviewPreference preview = findPreference("boot_animation_preview");
        if (preview != null) preview.loadBootAnimationPreview();
    }

    // -----------------------------------------------------------------------
    // Boilerplate
    // -----------------------------------------------------------------------

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    public static final SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(
                        Context context, boolean enabled) {
                    ArrayList<SearchIndexableResource> result = new ArrayList<>();
                    SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.boot_animation;
                    result.add(sir);
                    return result;
                }
            };
}
