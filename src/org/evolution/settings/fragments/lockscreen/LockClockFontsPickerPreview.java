/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 * Copyright (C) 2024-2025 Project Infinity X
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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import org.evolution.settings.utils.SystemRestartUtils;

import com.android.internal.util.android.ThemeUtils;
import org.evolution.settings.fragments.themes.fonts.FontArrayAdapter;
import org.evolution.settings.fragments.themes.fonts.FontManager;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;

public class LockClockFontsPickerPreview extends SettingsPreferenceFragment {

    private static final String TAG = "LockClockFontsPickerPreview";
    private static final String PREF_FIRST_TIME = "first_time_clock_face_access";

    private ViewPager viewPager;
    private ClockPagerAdapter pagerAdapter;
    private FontManager fontManager;
    private ExtendedFloatingActionButton applyFab;
    private View highlightGuide;
    private TextView clockNameTextView;

    private int mCurrentFontPosition = -1;
    private int mClockPosition = 0;

    private ThemeUtils mThemeUtils;
    private Handler mHandler = new Handler();

    private final static int[] mCenterClocks = {2, 3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16};

    private static final int[] CLOCK_LAYOUTS = {
            R.layout.keyguard_clock_default,
            R.layout.keyguard_clock_oos,
            R.layout.keyguard_clock_center,
            R.layout.keyguard_clock_simple,
            R.layout.keyguard_clock_miui,
            R.layout.keyguard_clock_ide,
            R.layout.keyguard_clock_moto,
            R.layout.keyguard_clock_stylish,
            R.layout.keyguard_clock_stylish2,
            R.layout.keyguard_clock_stylish3,
            R.layout.keyguard_clock_stylish4,
            R.layout.keyguard_clock_stylish5,
            R.layout.keyguard_clock_stylish6,
            R.layout.keyguard_clock_stylish7,
            R.layout.keyguard_clock_stylish8,
            R.layout.keyguard_clock_stylish9,
            R.layout.keyguard_clock_stylish10
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fontManager = new FontManager(getActivity(), true);
        getActivity().setTitle(getActivity().getString(R.string.theme_customization_lock_clock_title));
        mThemeUtils = ThemeUtils.getInstance(getActivity());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.lockscreen_font_picker_preview, container, false);
        clockNameTextView = rootView.findViewById(R.id.clock_name);

        viewPager = rootView.findViewById(R.id.view_pager);
        pagerAdapter = new ClockPagerAdapter();
        viewPager.setAdapter(pagerAdapter);
        mClockPosition = Settings.Secure.getIntForUser(getContext().getContentResolver(), "clock_style", 0, UserHandle.USER_CURRENT);
        if (mClockPosition < 0 || mClockPosition >= CLOCK_LAYOUTS.length) {
            mClockPosition = 0;
            Settings.Secure.putIntForUser(getContext().getContentResolver(), "clock_style", 0, UserHandle.USER_CURRENT);
        }
        viewPager.setCurrentItem(mClockPosition);

        TextView fontMessage = rootView.findViewById(R.id.font_message);
        List<String> fontPackageNames = fontManager.getAllFontPackages();
        TextView fontSelector = rootView.findViewById(R.id.font_selector);
        int backgroundColor = ContextCompat.getColor(getContext(), 
                isNightMode() ? R.color.font_drop_down_bg_dark : R.color.font_drop_down_bg_light);
        fontSelector.setTextColor(ContextCompat.getColor(getContext(), isNightMode() 
                ? R.color.font_drop_down_bg_light 
                : R.color.font_drop_down_bg_dark));
        fontSelector.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));

        fontSelector.setOnClickListener(v -> {
            View popupView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_font_selector, null);
            PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);

            ListView fontListView = popupView.findViewById(R.id.font_list_view);
            FontArrayAdapter fontAdapter = new FontArrayAdapter(
                    getActivity(),
                    android.R.layout.simple_list_item_1,
                    fontPackageNames,
                    fontManager,
                    isNightMode()
            );
            fontListView.setAdapter(fontAdapter);

            fontListView.setOnItemClickListener((parent, view, position, id) -> {
                mCurrentFontPosition = position;
                String fontPackage = fontPackageNames.get(mCurrentFontPosition);
                applyFontToAllPreviews(fontPackage);
                fontSelector.setText(fontManager.getLabel(getContext(), fontPackage));
                popupWindow.dismiss();
            });

            popupView.setBackgroundResource(R.drawable.custom_background);
            Drawable backgroundDrawable = popupView.getBackground();
            if (backgroundDrawable != null) {
                backgroundDrawable.setColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP);
            }
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.showAsDropDown(v, 0, 10);
        });

        if (isStaticClockStyle(mClockPosition)) {
            fontMessage.setVisibility(View.VISIBLE);
        } else {
            fontMessage.setVisibility(View.GONE);
        }

        String currentFontPackage = fontManager.getCurrentFontPackage();
        mCurrentFontPosition = fontPackageNames.indexOf(currentFontPackage);
        if (mCurrentFontPosition != -1) {
            if (!isStaticClockStyle(mClockPosition)) {
                String fontPackage = fontPackageNames.get(mCurrentFontPosition);
                fontSelector.setText(fontManager.getLabel(getContext(), fontPackage));
                applyFontToAllPreviews(fontPackage);
            }
        }

        applyFab = rootView.findViewById(R.id.apply_extended_fab);
        setupApplyButton(fontPackageNames);

        highlightGuide = rootView.findViewById(R.id.highlight_guide);
        if (isFirstTime()) {
            highlightGuide.setVisibility(View.VISIBLE);
            highlightGuide.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    highlightGuide.setVisibility(View.GONE);
                    disableHighlight();
                }
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

                if (isStaticClockStyle(mClockPosition)) {
                    fontMessage.setVisibility(View.VISIBLE);
                } else {
                    fontMessage.setVisibility(View.GONE);
                    if (mCurrentFontPosition >= 0 && mCurrentFontPosition < fontPackageNames.size()) {
                        String fontPackage = fontPackageNames.get(mCurrentFontPosition);
                        applyFontToAllPreviews(fontPackage);
                    }
                }
            }
        });
        return rootView;
    }

    private void updateClockName(int position) {
        String[] clockNames = {
            "Default Clock",
            "OnePlus Clock",
            "IOS Clock",
            "Simple Clock",
            "MIUI Clock",
            "IDE Clock",
            "Moto Clock",
            "Stylish Clock",
            "Stylish Clock 2",
            "Stylish Clock 3",
            "Stylish Clock 4",
            "Stylish Clock 5",
            "Stylish Clock 6",
            "Stylish Clock 7",
            "Stylish Clock 8",
            "Stylish Clock 9",
            "Stylish Clock 10"
        };
        if (clockNameTextView != null && position >= 0 && position < clockNames.length) {
            clockNameTextView.setText(clockNames[position]);
        }
    }

    private void setupApplyButton(List<String> fontPackageNames) {
        applyFab.setOnClickListener(new View.OnClickListener() {
            private long lastClickTime = 0;

            @Override
            public void onClick(View view) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 2000) {
                    return;
                }
                lastClickTime = currentTime;

                if (mCurrentFontPosition >= 0 && mCurrentFontPosition < fontPackageNames.size()) {
                    String fontPackage = fontPackageNames.get(mCurrentFontPosition);

                    if (!isStaticClockStyle(mClockPosition)) {
                        applyFontToAllPreviews(fontPackage);
                        fontManager.enableFontPackage(mCurrentFontPosition);
                    }
                }

                Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    "clock_style", mClockPosition, UserHandle.USER_CURRENT);
                Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    "lock_screen_custom_clock_face", 0, UserHandle.USER_CURRENT);

                applyChangesAndRestart();
            }
        });
    }

    private void applyChangesAndRestart() {
        if (applyFab != null) {
            applyFab.setEnabled(false);
            applyFab.setText("Applying...");
        }

        updateClockOverlays(mClockPosition);

        final Context appContext = getActivity() != null ? getActivity().getApplicationContext() : null;
        final Context fragmentContext = getContext();

        if (appContext != null && fragmentContext != null && isAdded() && getActivity() != null && !getActivity().isFinishing()) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        SystemRestartUtils.restartSystemUI(appContext);
                        showSuccessMessage();

                    } catch (Exception e) {
                        if (isAdded() && getContext() != null && getActivity() != null && !getActivity().isFinishing()) {
                            try {
                                SystemRestartUtils.restartSystemUI(getContext());
                                showSuccessMessage();
                            } catch (Exception ex) {
                                showFailureMessage();
                            }
                        } else {
                            showFailureMessage();
                        }
                    }
                }
            }, 1000);
        } else {
            showFailureMessage();
        }
    }

    private void showSuccessMessage() {
        if (getActivity() != null && !getActivity().isFinishing()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (getContext() != null) {
                        Toast.makeText(getContext(),
                            "Settings applied successfully!",
                            Toast.LENGTH_SHORT).show();
                    }
                    if (applyFab != null) {
                        applyFab.setEnabled(true);
                        applyFab.setText("Apply");
                    }
                }
            });
        }
    }

    private void showFailureMessage() {
        if (getActivity() != null && !getActivity().isFinishing()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (applyFab != null) {
                        applyFab.setEnabled(true);
                        applyFab.setText("Apply");
                    }
                    if (getContext() != null) {
                        Toast.makeText(getContext(),
                            "Settings saved. Please restart SystemUI manually if changes don't appear.",
                            Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private boolean isNightMode() {
        int nightModeFlags = getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateClockOverlays(int clockStyle) {
        mThemeUtils.setOverlayEnabled(
                "android.theme.customization.hideclock",
                clockStyle != 0 ? "com.android.systemui.clocks.hideclock" : "android",
                "android");
        mThemeUtils.setOverlayEnabled(
                "android.theme.customization.smartspace",
                clockStyle != 0 ? "com.android.systemui.hide.smartspace" : "com.android.systemui",
                "com.android.systemui");
        mThemeUtils.setOverlayEnabled(
                "android.theme.customization.smartspace_offset",
                clockStyle != 0 && isCenterClock(clockStyle)
                        ? "com.android.systemui.smartspace_offset.smartspace"
                        : "com.android.systemui",
                "com.android.systemui");
    }

    private boolean isCenterClock(int clockStyle) {
        for (int centerClock : mCenterClocks) {
            if (centerClock == clockStyle) {
                return true;
            }
        }
        return false;
    }

    private boolean isStaticClockStyle(int clockStyle) {
        if (clockStyle < 0 || clockStyle >= CLOCK_LAYOUTS.length) {
            return false;
        }
        return false;
    }

    private boolean shouldScaleDown(int position) {
        int layoutId = CLOCK_LAYOUTS[position];
        return layoutId == R.layout.keyguard_clock_stylish
               || layoutId == R.layout.keyguard_clock_stylish2
               || layoutId == R.layout.keyguard_clock_stylish3
               || layoutId == R.layout.keyguard_clock_stylish4
               || layoutId == R.layout.keyguard_clock_stylish5
               || layoutId == R.layout.keyguard_clock_stylish6
               || layoutId == R.layout.keyguard_clock_stylish7
               || layoutId == R.layout.keyguard_clock_stylish8
               || layoutId == R.layout.keyguard_clock_stylish9
               || layoutId == R.layout.keyguard_clock_stylish10;
    }

    private boolean isFirstTime() {
        return Settings.System.getIntForUser(
            getContext().getContentResolver(), PREF_FIRST_TIME, 1, UserHandle.USER_CURRENT) != 0;
    }

    private void disableHighlight() {
        Settings.System.putIntForUser(getContext().getContentResolver(), PREF_FIRST_TIME, 0, UserHandle.USER_CURRENT);
    }

    private class ClockPagerAdapter extends PagerAdapter {
        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            LayoutInflater inflater = LayoutInflater.from(getActivity());
            View layout = inflater.inflate(CLOCK_LAYOUTS[position], container, false);

            if (!isStaticClockStyle(position) && mCurrentFontPosition >= 0) {
                String fontPackage = fontManager.getAllFontPackages().get(mCurrentFontPosition);
                applyFontToPreview(fontPackage, layout, position);
            }

            int bottomPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                150,
                getResources().getDisplayMetrics()
            );
            layout.setPadding(
                layout.getPaddingLeft(),
                layout.getPaddingTop(),
                layout.getPaddingRight(),
                bottomPadding
            );

            if (shouldScaleDown(position)) {
                float scaleFactor = 0.70f;
                if (position == 0
                        || position == 1
                        || position == 2
                        || position == 5
                        || position == 6
                        || position == 7
                        || position == 14) {
                    scaleFactor = 0.35f;
                }
                layout.setScaleX(scaleFactor);
                layout.setScaleY(scaleFactor);
            }

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

    private void applyFontToAllPreviews(String font) {
        Typeface typeface = fontManager.getTypeface(getContext(), font);
        int childCount = viewPager.getChildCount();
        if (typeface != null) {
            for (int i = 0; i < childCount; i++) {
                View currentLayout = viewPager.getChildAt(i);
                int currentPosition = viewPager.getCurrentItem();
                if (currentLayout != null) {
                    if (!isStaticClockStyle(currentPosition)) {
                        updateAllTextViews(currentLayout, typeface);
                    }
                }
            }
        }
    }

    private void applyFontToPreview(String font, View layout, int position) {
        if (isStaticClockStyle(position)) {
            return;
        }
        Typeface typeface = fontManager.getTypeface(getContext(), font);
        if (typeface != null) {
            updateAllTextViews(layout, typeface);
        }
    }

    private void updateAllTextViews(View view, Typeface typeface) {
        if (view instanceof TextView || view instanceof TextClock) {
            ((TextView) view).setTypeface(typeface);
        } else if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                updateAllTextViews(child, typeface);
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateClockName(mClockPosition);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateClockName(mClockPosition);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler = null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN;
    }
}
