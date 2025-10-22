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
package org.evolution.settings.fragments.themes;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.MediaUtils;

import java.io.File;
import java.util.List;

import com.android.internal.util.evolution.VibrationUtils;

@SearchIndexable
public class VideoWallpaperFragment extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "VideoWallpaperFragment";
    private static final String PREFS_NAME = "video_wallpaper_prefs";

    private static final String KEY_PICK_VIDEO = "pick_video_wallpaper";
    private static final String KEY_ENABLE_VIDEO_WALLPAPER = "enable_video_wallpaper";
    private static final String KEY_CLEAR_VIDEO_WALLPAPER = "clear_video_wallpaper";
    private static final String KEY_CURRENT_WALLPAPER = "current_video_wallpaper";
    private static final String KEY_PLAYBACK_SPEED = "playback_speed";
    private static final String KEY_PAUSE_ON_LOCK = "pause_on_lock";

    private Preference mPickVideoPref;
    private SwitchPreferenceCompat mEnableWallpaperPref;
    private Preference mClearWallpaperPref;
    private Preference mCurrentWallpaperPref;
    private ListPreference mPlaybackSpeedPref;
    private SwitchPreferenceCompat mPauseOnLockPref;

    private ActivityResultLauncher<Intent> mVideoPickerLauncher;
    private SharedPreferences mPrefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.video_wallpaper_settings);

        final Context context = getContext();
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initPreferences();
        setupVideoPickerLauncher();
        setupPreferenceListeners();
        loadPreferenceValues();
        updatePreferences();
    }

    private void initPreferences() {
        mPickVideoPref = findPreference(KEY_PICK_VIDEO);
        mEnableWallpaperPref = findPreference(KEY_ENABLE_VIDEO_WALLPAPER);
        mClearWallpaperPref = findPreference(KEY_CLEAR_VIDEO_WALLPAPER);
        mCurrentWallpaperPref = findPreference(KEY_CURRENT_WALLPAPER);
        mPlaybackSpeedPref = findPreference(KEY_PLAYBACK_SPEED);
        mPauseOnLockPref = findPreference(KEY_PAUSE_ON_LOCK);
    }

    private void loadPreferenceValues() {
        if (mPlaybackSpeedPref != null) {
            String speed = mPrefs.getString(KEY_PLAYBACK_SPEED, "1.0");
            mPlaybackSpeedPref.setValue(speed);
            Log.d(TAG, "Loaded playback speed: " + speed);
        }

        if (mPauseOnLockPref != null) {
            boolean pauseOnLock = mPrefs.getBoolean(KEY_PAUSE_ON_LOCK, true);
            mPauseOnLockPref.setChecked(pauseOnLock);
            Log.d(TAG, "Loaded pause on lock: " + pauseOnLock);
        }
    }

    private void setupVideoPickerLauncher() {
        mVideoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            handleVideoSelection(uri);
                        }
                    }
                }
        );
    }

    private void setupPreferenceListeners() {
        if (mPickVideoPref != null) {
            mPickVideoPref.setOnPreferenceClickListener(preference -> {
                openVideoPicker();
                return true;
            });
        }

        if (mEnableWallpaperPref != null) {
            mEnableWallpaperPref.setOnPreferenceChangeListener(this);
        }

        if (mClearWallpaperPref != null) {
            mClearWallpaperPref.setOnPreferenceClickListener(preference -> {
                confirmClearWallpaper();
                return true;
            });
        }

        if (mPlaybackSpeedPref != null) {
            mPlaybackSpeedPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String speed = (String) newValue;
                mPrefs.edit().putString(KEY_PLAYBACK_SPEED, speed).apply();
                Log.d(TAG, "Playback speed saved: " + speed);
                Toast.makeText(getContext(), 
                        "Playback speed changed to " + speed + "x", 
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        if (mPauseOnLockPref != null) {
            mPauseOnLockPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean pauseOnLock = (Boolean) newValue;
                mPrefs.edit().putBoolean(KEY_PAUSE_ON_LOCK, pauseOnLock).apply();
                Log.d(TAG, "Pause on lock saved: " + pauseOnLock);
                Toast.makeText(getContext(), 
                        pauseOnLock ? "Will pause when locked" : "Will continue when locked", 
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimeTypes = {"video/mp4", "image/gif", "image/webp"};
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        try {
            mVideoPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening video picker", e);
            Toast.makeText(getContext(), R.string.video_wallpaper_picker_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleVideoSelection(Uri videoUri) {
        Toast.makeText(getContext(), R.string.video_wallpaper_processing, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String savedPath = MediaUtils.saveMediaToWallpaperStorage(getContext(), videoUri);
            requireActivity().runOnUiThread(() -> {
                if (savedPath != null) {
                    Log.d(TAG, "Video saved at: " + savedPath);
                    Toast.makeText(getContext(), R.string.video_wallpaper_saved, Toast.LENGTH_SHORT).show();
                    updatePreferences();
                    if (mEnableWallpaperPref != null) {
                        enableVideoWallpaper();
                    }
                } else {
                    Toast.makeText(getContext(), R.string.video_wallpaper_save_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void enableVideoWallpaper() {
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(getContext(), VideoWallpaperService.class));
            startActivity(intent);
            Toast.makeText(getContext(), R.string.video_wallpaper_apply_prompt, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Error enabling video wallpaper", e);
            Toast.makeText(getContext(), R.string.video_wallpaper_enable_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearWallpaper() {
        new AlertDialog.Builder(getContext())
                .setTitle("Clear video wallpaper?")
                .setMessage("This will remove the current video wallpaper")
                .setPositiveButton("Clear", (dialog, which) -> clearVideoWallpaper())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearVideoWallpaper() {
        File file = MediaUtils.getCurrentWallpaperFile(getContext());
        if (file != null && file.exists() && MediaUtils.deleteCurrentWallpaper(getContext())) {
            Toast.makeText(getContext(), R.string.video_wallpaper_cleared, Toast.LENGTH_SHORT).show();
            if (mEnableWallpaperPref != null) {
                mEnableWallpaperPref.setChecked(false);
            }
            updatePreferences();
        } else {
            Toast.makeText(getContext(), R.string.video_wallpaper_clear_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePreferences() {
        File file = MediaUtils.getCurrentWallpaperFile(getContext());
        boolean hasWallpaper = file != null && file.exists();
        if (mCurrentWallpaperPref != null) {
            if (hasWallpaper) {
                String name = file.getName();
                String size = formatFileSize(file.length());
                mCurrentWallpaperPref.setSummary(
                        getString(R.string.video_wallpaper_current_summary_dynamic, name, size));
                mCurrentWallpaperPref.setVisible(true);
            } else {
                mCurrentWallpaperPref.setSummary(R.string.video_wallpaper_current_none);
                mCurrentWallpaperPref.setVisible(false);
            }
        }

        if (mClearWallpaperPref != null) {
            mClearWallpaperPref.setEnabled(hasWallpaper);
        }

        if (mEnableWallpaperPref != null) {
            mEnableWallpaperPref.setEnabled(hasWallpaper);
            if (!hasWallpaper) {
                mEnableWallpaperPref.setChecked(false);
            }
        }

        if (mPlaybackSpeedPref != null) {
            mPlaybackSpeedPref.setEnabled(hasWallpaper);
        }
        if (mPauseOnLockPref != null) {
            mPauseOnLockPref.setEnabled(hasWallpaper);
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mEnableWallpaperPref) {
            boolean enable = (Boolean) newValue;
            if (enable) {
                File file = MediaUtils.getCurrentWallpaperFile(getContext());
                if (file != null && file.exists()) {
                    enableVideoWallpaper();
                    return true;
                } else {
                    Toast.makeText(getContext(), R.string.video_wallpaper_select_first, 
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            } else {
                Intent intent = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(Intent.createChooser(intent, 
                        getString(R.string.video_wallpaper_select_wallpaper)));
                return true;
            }
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
    public void onResume() {
        super.onResume();
        loadPreferenceValues();
        updatePreferences();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.video_wallpaper_settings) {
                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    return keys;
                }
            };
}
