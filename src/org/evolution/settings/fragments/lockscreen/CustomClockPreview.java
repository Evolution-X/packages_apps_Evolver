/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 * Copyright (C) 2024-25 Project Infinity X
 * Copyright (C) 2026 crDroid Android Project
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
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.Preference.OnPreferenceChangeListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.evolution.ThemeUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.widget.LayoutPreference;

import com.android.settings.core.SubSettingLauncher;
import org.evolution.settings.preferences.SecureSettingListPreference;
import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.SystemUtils;

import java.util.ArrayList;
import java.util.List;


import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class CustomClockPreview extends SettingsPreferenceFragment implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "CustomClockPreview";
    private static final String PREF_FIRST_TIME = "first_time_clock_face_access";

    private static final String KEY_CLOCK_PREVIEW = "clock_preview";
    private static final String KEY_CLOCK_COLOR = ClockColorPickerDialogFragment.KEY_CLOCK_COLOR;
    private static final String KEY_GRADIENT_ENABLED = "lock_screen_custom_clock_gradient_enabled";
    private static final String KEY_GRADIENT_COLOR_START = "lock_screen_custom_clock_gradient_color_start";
    private static final String KEY_GRADIENT_COLOR_END = "lock_screen_custom_clock_gradient_color_end";
    private static final String KEY_CLOCK_FONT = "lock_clock_font";

    private static final String KEY_CLOCK_COLOR_MODE = "lock_screen_custom_clock_color_mode";

    private ViewPager viewPager;
    private ClockPagerAdapter pagerAdapter;
    private ExtendedFloatingActionButton applyFab;
    private View highlightGuide;
    private TextView clockNameTextView;

    private LayoutPreference clockPreviewPref;
    private Preference mClockFontPref;
    private Preference mClockColorPref;
    private SecureSettingListPreference mClockColorModePref;
    private Preference mGradientColorStartPref;
    private Preference mGradientColorEndPref;

    List<Preference> persistentPrefs = new ArrayList<>();
    List<Preference> customOnlyPrefs = new ArrayList<>();

    private int mClockPosition = 0;

    private ThemeUtils mThemeUtils;
    private Handler mHandler = new Handler();

    private static final int[] CLOCK_LAYOUTS = ClockUtils.INSTANCE.getCLOCK_LAYOUTS();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActivity().setTitle(getString(R.string.lockscreen_custom_clock_style_title));
        mThemeUtils = ThemeUtils.getInstance(getActivity());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.lockscreen_clock_preview_settings);

        clockPreviewPref = findPreference(KEY_CLOCK_PREVIEW);
        mClockFontPref = findPreference(KEY_CLOCK_FONT);
        mClockColorPref = findPreference(KEY_CLOCK_COLOR);
        mClockColorModePref = findPreference(KEY_CLOCK_COLOR_MODE);

        persistentPrefs.add(clockPreviewPref);
        persistentPrefs.add(mClockFontPref);

        customOnlyPrefs.addAll(PreferenceUtils.getAllPreferences(getPreferenceScreen()));
        customOnlyPrefs.removeIf(persistentPrefs::contains);

        if (mClockColorModePref != null) {
            mClockColorModePref.setOnPreferenceChangeListener(this);
            updateClockPrefs(mClockColorModePref, mClockColorModePref.getValue());
        }

    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mClockColorModePref) {
            updateClockPrefs(mClockColorModePref, newValue);
        }
        return true;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupClockPreview();
        setupClockColorPref();
        setupGradientPrefs();
    }

    private void updateClockPrefs(Preference pref, Object value) {
        switch (pref) {
            case SecureSettingListPreference p -> {
                if (p == mClockColorModePref && mClockColorModePref != null) {
                    boolean isCustomColor = value != null && ((String) value).equals("custom");
                    mClockColorPref.setVisible(isCustomColor);
                }
            }
            case LayoutPreference lp -> {
                if (lp == clockPreviewPref) {
                    boolean isDefault = (mClockPosition == 0);
                    if (isDefault) {
                        for (Preference p : customOnlyPrefs) {
                            p.setVisible(false);
                        }
                    } else {
                        for (Preference p : customOnlyPrefs) {
                            if (p == mClockColorPref) {
                                p.setVisible(mClockColorModePref.getValue().equals("custom"));
                            } else {
                                p.setVisible(true);
                            }
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private void updateClockPrefs(Preference pref) {
        updateClockPrefs(pref, null);
    }

    private void setupClockColorPref() {
        if (mClockColorPref == null) return;

        updateClockColorSummary();

        mClockColorPref.setOnPreferenceClickListener(pref -> {
            ClockColorPickerDialogFragment dialog = ClockColorPickerDialogFragment.newInstance();
            dialog.setOnColorAppliedListener(() -> { updateClockColorSummary(); return kotlin.Unit.INSTANCE; });
            dialog.show(getChildFragmentManager(), ClockColorPickerDialogFragment.TAG);
            return true;
        });
    }

    private void setupGradientPrefs() {
        mGradientColorStartPref = findPreference(KEY_GRADIENT_COLOR_START);
        mGradientColorEndPref = findPreference(KEY_GRADIENT_COLOR_END);

        updateGradientColorSummary(mGradientColorStartPref, KEY_GRADIENT_COLOR_START, 0xFF00E5FF);
        updateGradientColorSummary(mGradientColorEndPref, KEY_GRADIENT_COLOR_END, 0xFFFF2DAA);

        if (mGradientColorStartPref != null) {
            mGradientColorStartPref.setOnPreferenceClickListener(pref -> {
                showGradientColorDialog(KEY_GRADIENT_COLOR_START, 0xFF00E5FF,
                        getString(R.string.lockscreen_custom_clock_gradient_start_title),
                        mGradientColorStartPref);
                return true;
            });
        }
        if (mGradientColorEndPref != null) {
            mGradientColorEndPref.setOnPreferenceClickListener(pref -> {
                showGradientColorDialog(KEY_GRADIENT_COLOR_END, 0xFFFF2DAA,
                        getString(R.string.lockscreen_custom_clock_gradient_end_title),
                        mGradientColorEndPref);
                return true;
            });
        }
    }

    private void showGradientColorDialog(String key, int defaultArgb, String title, Preference target) {
        ClockColorPickerDialogFragment dialog =
                ClockColorPickerDialogFragment.newInstance(key, defaultArgb, title);
        dialog.setOnColorAppliedListener(() -> {
            updateGradientColorSummary(target, key, defaultArgb);
            return kotlin.Unit.INSTANCE;
        });
        dialog.show(getChildFragmentManager(), ClockColorPickerDialogFragment.TAG);
    }

    private void updateGradientColorSummary(Preference pref, String key, int defaultArgb) {
        if (pref == null || getContext() == null) return;
        int argb = Settings.Secure.getIntForUser(
                getContext().getContentResolver(), key, defaultArgb, UserHandle.USER_CURRENT);
        pref.setSummary(String.format("#%06X", argb & 0x00FFFFFF));
    }

    private void updateClockColorSummary() {
        if (mClockColorPref == null || getContext() == null) return;
        int argb = Settings.Secure.getIntForUser(
                getContext().getContentResolver(),
                KEY_CLOCK_COLOR,
                ClockColorPickerDialogFragment.DEFAULT_COLOR_ARGB,
                UserHandle.USER_CURRENT);
        mClockColorPref.setSummary(String.format("#%06X", argb & 0x00FFFFFF));
    }

    private void setupClockPreview() {
        if (clockPreviewPref == null) return;

        clockNameTextView = clockPreviewPref.findViewById(R.id.clock_name);
        viewPager = clockPreviewPref.findViewById(R.id.view_pager);
        applyFab = clockPreviewPref.findViewById(R.id.apply_extended_fab);
        highlightGuide = clockPreviewPref.findViewById(R.id.highlight_guide);

        pagerAdapter = new ClockPagerAdapter();
        viewPager.setAdapter(pagerAdapter);
        viewPager.setClipChildren(true);
        viewPager.setClipToPadding(true);

        mClockPosition = Settings.Secure.getIntForUser(
                getContext().getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT);
        if (mClockPosition < 0 || mClockPosition >= CLOCK_LAYOUTS.length) {
            mClockPosition = 0;
            Settings.Secure.putIntForUser(
                    getContext().getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT);
        }
        viewPager.setCurrentItem(mClockPosition);

        applyFab.setOnClickListener(v -> {
            Context ctx = getContext();
            int currentClock = Settings.Secure.getIntForUser(
                    ctx.getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, 0, UserHandle.USER_CURRENT);
            if (mClockPosition != currentClock) {
                Settings.Secure.putIntForUser(
                        ctx.getContentResolver(), Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE, mClockPosition, UserHandle.USER_CURRENT);
                updateClockOverlays(mClockPosition);
                SystemUtils.restartSystemUI(ctx);
            }
        });

        View browseButton = clockPreviewPref.findViewById(R.id.browse_clocks_button);
        if (browseButton != null) {
            browseButton.setOnClickListener(v -> {
                new SubSettingLauncher(getContext())
                    .setDestination(ClockPickerFragment.class.getName())
                    .setTitleRes(R.string.lockscreen_custom_clock_style_title)
                    .setSourceMetricsCategory(getMetricsCategory())
                    .launch();
            });
        }

        if (isFirstTime()) {
            highlightGuide.setVisibility(View.VISIBLE);
            highlightGuide.setOnClickListener(v -> {
                highlightGuide.setVisibility(View.GONE);
                disableHighlight();
            });
        } else {
            highlightGuide.setVisibility(View.GONE);
        }

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrollStateChanged(int state) {}
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            @Override
            public void onPageSelected(int position) {
                mClockPosition = position;
                if (viewPager != null) {
                    viewPager.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
                updateClockName(position);
                updateClockPrefs(clockPreviewPref);
            }
        });

        updateClockName(mClockPosition);
        updateClockPrefs(clockPreviewPref);
    }

    private void updateClockName(int position) {
        String[] clockNames = ClockUtils.INSTANCE.getClockNames();
        if (clockNameTextView != null && position >= 0 && position < clockNames.length) {
            clockNameTextView.setText(clockNames[position]);
        }
    }

    private void updateClockOverlays(int clockStyle) {
        mThemeUtils.setOverlayEnabled(
                "android.theme.customization.smartspace",
                clockStyle != 0 ? "com.android.systemui.hide.smartspace" : "com.android.systemui",
                "com.android.systemui");
    }

    private boolean isFirstTime() {
        return Settings.System.getIntForUser(
                getContext().getContentResolver(), PREF_FIRST_TIME, 1, UserHandle.USER_CURRENT) != 0;
    }

    private void disableHighlight() {
        Settings.System.putIntForUser(
                getContext().getContentResolver(), PREF_FIRST_TIME, 0, UserHandle.USER_CURRENT);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateClockName(mClockPosition);
        updateClockPrefs(clockPreviewPref);
        updateClockColorSummary();
        updateGradientColorSummary(mGradientColorStartPref, KEY_GRADIENT_COLOR_START, 0xFF00E5FF);
        updateGradientColorSummary(mGradientColorEndPref, KEY_GRADIENT_COLOR_END, 0xFFFF2DAA);
    }

    private class ClockPagerAdapter extends PagerAdapter {
        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            LayoutInflater inflater = LayoutInflater.from(getActivity());
            View layout = inflater.inflate(CLOCK_LAYOUTS[position], container, false);
            container.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Override
        public int getCount() {
            return CLOCK_LAYOUTS.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateClockName(mClockPosition);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }
}
