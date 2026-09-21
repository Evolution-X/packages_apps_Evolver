/*
 * Copyright (C) 2010 Daniel Nilsson
 *               2013 Slimroms
 *               2015 The TeamEos Project
 *               2020-2021 crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences.colorpicker;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

/**
 * A preference that lets the user pick a color using the skydoves color picker.
 * The value is persisted as an ARGB int.
 */
public class ColorPickerPreference extends Preference {

    private static final String ANDROIDNS = "http://schemas.android.com/apk/res/android";
    private static final String SETTINGS_NS = "http://schemas.android.com/apk/res/com.android.settings";
    private static final float DISABLED_ALPHA = 0.38f;

    PreferenceViewHolder mView;
    LinearLayout mWidgetFrameView;
    private int mDefaultValue = Color.BLACK;
    private int mCurrentValue = mDefaultValue;
    private String mCurrentHexValue;
    private float mDensity = 0;
    private boolean mAlphaSliderEnabled = false;
    private boolean mShowReset;
    private boolean mShowPreview;
    private boolean mDividerAbove;
    private boolean mDividerBelow;
    private boolean mAutoSummary = true;

    public ColorPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ColorPickerPreference(Context context, AttributeSet attrs) {
        // Use the theme's preferenceStyle like every other preference, otherwise
        // the row falls back to the bare AndroidX layout and looks out of place.
        this(context, attrs, androidx.preference.R.attr.preferenceStyle);
    }

    public ColorPickerPreference(Context context) {
        this(context, null);
    }

    public ColorPickerPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray ta, int index) {
        return ta.getInt(index, Color.BLACK);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        // when using PreferenceDataStore, restorePersistedValue is always true (see Preference
        // class for reference) so we load the persistent value with getPersistedInt if available
        // in the data store, and use defaultValue as fallback (onGetDefaultValue has been already
        // called and it loaded the android:defaultValue attr from our xml).
        if (defaultValue == null) {
            // no android:defaultValue: fall back to the value resolved in init()
            defaultValue = mDefaultValue;
        }
        mCurrentValue = getPersistedInt((Integer) defaultValue);
        onColorChanged(mCurrentValue);
    }

    private void init(Context context, AttributeSet attrs) {
        mDensity = getContext().getResources().getDisplayMetrics().density;
        if (attrs != null) {
            mAlphaSliderEnabled = attrs.getAttributeBooleanValue(null, "alphaSlider", false)
                    || attrs.getAttributeBooleanValue(SETTINGS_NS, "alphaSlider", false);
            // Some layouts declare the default with the settings: namespace instead of android:
            mDefaultValue = attrs.getAttributeIntValue(ANDROIDNS, "defaultValue",
                    attrs.getAttributeIntValue(SETTINGS_NS, "defaultValue", Color.BLACK));
            mShowReset = attrs.getAttributeBooleanValue(SETTINGS_NS, "showReset", true);
            mShowPreview = attrs.getAttributeBooleanValue(SETTINGS_NS, "showPreview", true);
            mDividerAbove = attrs.getAttributeBooleanValue(SETTINGS_NS, "dividerAbove", false);
            mDividerBelow = attrs.getAttributeBooleanValue(SETTINGS_NS, "dividerBelow", false);
        }
    }

    @Override
    public void onAttached() {
        super.onAttached();
        registerResultListener();
    }

    @Override
    public void onDetached() {
        FragmentActivity activity = getFragmentActivity();
        if (activity != null) {
            activity.getSupportFragmentManager().clearFragmentResultListener(getRequestKey());
        }
        super.onDetached();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder view) {
        mView = view;
        super.onBindViewHolder(view);
        view.setDividerAllowedAbove(mDividerAbove);
        view.setDividerAllowedBelow(mDividerBelow);

        view.itemView.setOnClickListener(v -> showDialog());
        mWidgetFrameView = ((LinearLayout) view.findViewById(android.R.id.widget_frame));
        mWidgetFrameView.setOrientation(LinearLayout.HORIZONTAL);
        mWidgetFrameView.setVisibility(View.VISIBLE);
        mWidgetFrameView.setMinimumWidth(0);
        mWidgetFrameView.setPadding(
                mWidgetFrameView.getPaddingLeft(),
                mWidgetFrameView.getPaddingTop(),
                (int) (mDensity * 8),
                mWidgetFrameView.getPaddingBottom());
        setDefaultButton();
        setPreviewColor();
    }

    /**
     * Restore a default value, not necessarily a color
     * For example: Set default value to -1 to remove a color filter
     *
     * @author Randall Rushing aka Bigrushdog
     */
    private void setDefaultButton() {
        if (!mShowReset || mView == null || mWidgetFrameView == null)
            return;

        // remove already created default button
        int count = mWidgetFrameView.getChildCount();
        if (count > 0) {
            View oldView = mWidgetFrameView.findViewWithTag("default");
            View spacer = mWidgetFrameView.findViewWithTag("spacer");
            if (oldView != null) {
                mWidgetFrameView.removeView(oldView);
            }
            if (spacer != null) {
                mWidgetFrameView.removeView(spacer);
            }
        }

        // Keep the reset button when disabled (dimmed, inert) so every color
        // preference looks the same regardless of state.
        ImageView defView = new ImageView(getContext());
        mWidgetFrameView.addView(defView);
        defView.setImageDrawable(getContext().getDrawable(R.drawable.ic_settings_backup_restore));
        defView.setContentDescription(getContext().getString(R.string.color_picker_reset));
        defView.setTag("default");
        defView.setEnabled(isEnabled());
        defView.setAlpha(isEnabled() ? 1f : DISABLED_ALPHA);
        defView.setOnClickListener(v -> {
            if (isEnabled()) onColorChanged(mDefaultValue);
        });
        // sorcery for a linear layout ugh
        View spacer = new View(getContext());
        spacer.setTag("spacer");
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int) (mDensity * 16),
                LayoutParams.MATCH_PARENT));
        mWidgetFrameView.addView(spacer);
    }

    private void setPreviewColor() {
        if (!mShowPreview || mView == null || mWidgetFrameView == null)
            return;

        // remove already create preview image
        int count = mWidgetFrameView.getChildCount();
        if (count > 0) {
            View preview = mWidgetFrameView.findViewWithTag("preview");
            if (preview != null) {
                mWidgetFrameView.removeView(preview);
            }
        }
        // Keep the swatch when disabled (dimmed) instead of dropping it.
        ImageView iView = new ImageView(getContext());
        mWidgetFrameView.addView(iView);
        iView.setAlpha(isEnabled() ? 1f : DISABLED_ALPHA);
        final int size = (int) getContext().getResources()
                .getDimension(R.dimen.oval_notification_size);
        final int imageColor = ((mCurrentValue & 0xF0F0F0) == 0xF0F0F0) ?
                (mCurrentValue - 0x101010) : mCurrentValue;
        iView.setImageDrawable(createOvalShape(size, 0xFF000000 + imageColor));
        iView.setTag("preview");
    }

    /**
     * Updates the swatch and summary to {@code color} without persisting it or
     * notifying the change listener. Use for syncing the UI with a value that is
     * stored elsewhere.
     */
    public void setColor(int color) {
        mCurrentValue = color;
        mCurrentHexValue = formatSummary(color);
        if (mAutoSummary) setSummary(mCurrentHexValue);
        setPreviewColor();
    }

    /** #RRGGBB, or #AARRGGBB when the alpha slider is enabled. */
    private String formatSummary(int color) {
        return mAlphaSliderEnabled
                ? String.format("#%08X", color)
                : String.format("#%06X", color & 0xFFFFFF);
    }

    public void onColorChanged(int color) {
        mCurrentValue = color;
        mCurrentHexValue = formatSummary(color);
        if (mAutoSummary) setSummary(mCurrentHexValue);
        setPreviewColor();
        persistInt(color);
        OnPreferenceChangeListener listener = getOnPreferenceChangeListener();
        if (listener != null) {
            listener.onPreferenceChange(this, color);
        }
    }

    protected void showDialog() {
        if (!isEnabled())
            return;

        FragmentActivity activity = getFragmentActivity();
        if (activity == null)
            return;

        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.findFragmentByTag(ColorPickerPrefDialogFragment.TAG) != null)
            return;

        registerResultListener();
        CharSequence title = getTitle();
        ColorPickerPrefDialogFragment.newInstance(getRequestKey(),
                title != null ? title.toString() : null, mCurrentValue, mAlphaSliderEnabled)
                .show(fm, ColorPickerPrefDialogFragment.TAG);
    }

    private void registerResultListener() {
        FragmentActivity activity = getFragmentActivity();
        if (activity == null)
            return;
        activity.getSupportFragmentManager().setFragmentResultListener(getRequestKey(), activity,
                (requestKey, result) -> onColorChanged(
                        result.getInt(ColorPickerPrefDialogFragment.RESULT_COLOR)));
    }

    private String getRequestKey() {
        return "color_picker_pref:" + getKey();
    }

    private FragmentActivity getFragmentActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof FragmentActivity) {
                return (FragmentActivity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * Toggle Alpha Slider visibility (by default it's disabled)
     *
     * @param enable
     */
    public void setAlphaSliderEnabled(boolean enable) {
        mAlphaSliderEnabled = enable;
    }

    public static String convertToRGB(int color) {
        return String.format("#%02x%02x%02x", Color.red(color), Color.green(color),
                Color.blue(color));
    }

    /**
     * For custom purposes. Not used by ColorPickerPreferrence
     *
     * set color preview value from outside
     * @author kufikugel
     */
    public void setNewPreviewColor(int color) {
        onColorChanged(color);
    }

    public void setDefaultValue(int value) {
        mDefaultValue = value;
    }

    /**
     * For custom purposes. Not used by ColorPickerPreferrence
     *
     * @param color
     * @author Unknown
     */
    public static String convertToARGB(int color) {
        return String.format("#%02x%02x%02x%02x", Color.alpha(color), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    /**
     * For custom purposes. Not used by ColorPickerPreferrence
     *
     * @param argb
     * @throws NumberFormatException
     * @author Unknown
     */
    public static int convertToColorInt(String argb) throws NumberFormatException {

        if (argb.startsWith("#")) {
            argb = argb.replace("#", "");
        }

        int alpha = -1, red = -1, green = -1, blue = -1;

        if (argb.length() == 8) {
            alpha = Integer.parseInt(argb.substring(0, 2), 16);
            red = Integer.parseInt(argb.substring(2, 4), 16);
            green = Integer.parseInt(argb.substring(4, 6), 16);
            blue = Integer.parseInt(argb.substring(6, 8), 16);
        }
        else if (argb.length() == 6) {
            alpha = 255;
            red = Integer.parseInt(argb.substring(0, 2), 16);
            green = Integer.parseInt(argb.substring(2, 4), 16);
            blue = Integer.parseInt(argb.substring(4, 6), 16);
        }

        return Color.argb(alpha, red, green, blue);
    }

    /**
     * Toggle Auto Summary (by default it's enabled)
     *
     * @param enable
     */
    public void setAutoSummaryEnabled(boolean enable) {
        mAutoSummary = enable;
    }

    private static ShapeDrawable createOvalShape(int size, int color) {
        ShapeDrawable shape = new ShapeDrawable(new OvalShape());
        shape.setIntrinsicHeight(size);
        shape.setIntrinsicWidth(size);
        shape.getPaint().setColor(color);
        return shape;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setPreviewColor();
        setDefaultButton();
    }
}
