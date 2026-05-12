/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.slider.Slider;

public class HuePickerPreference extends CustomSeekBarPreference {

    private static final int TRACK_HEIGHT_DP = 48;
    private static final int TRACK_CORNER_DP = 24;

    private int mHue = 218;

    public HuePickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HuePickerPreference(Context context) {
        super(context, null);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final Slider slider = (Slider) holder.findViewById(
                com.android.settingslib.widget.preference.slider.R.id.slider);
        if (slider == null) return;

        // Hide plus/minus buttons and label frame
        View iconStartFrame = holder.itemView.findViewById(
                com.android.settingslib.widget.preference.slider.R.id.icon_start_frame);
        View iconEndFrame = holder.itemView.findViewById(
                com.android.settingslib.widget.preference.slider.R.id.icon_end_frame);
        View labelFrame = holder.itemView.findViewById(
                com.android.settingslib.widget.preference.slider.R.id.label_frame);
        if (iconStartFrame != null) iconStartFrame.setVisibility(View.GONE);
        if (iconEndFrame != null) iconEndFrame.setVisibility(View.GONE);
        if (labelFrame != null) labelFrame.setVisibility(View.GONE);

        // Make slider's own track transparent — gradient view sits behind it
        slider.setTrackHeight(dpToPx(TRACK_HEIGHT_DP));
        slider.setTrackTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        slider.setTrackActiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        slider.setTrackInactiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));

        // Tint thumb to current hue
        updateThumbColor(slider, mHue);

        // Inject gradient track behind slider
        injectHueTrack(slider);

        slider.addOnChangeListener((s, value, fromUser) -> {
            mHue = (int) value;
            updateThumbColor(s, mHue);
        });
    }

    private void updateThumbColor(Slider slider, int hue) {
        int color = hsvToColor(hue);
        slider.setThumbTintList(ColorStateList.valueOf(color));
        slider.setHaloTintList(ColorStateList.valueOf(
                Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))));
    }

    private void injectHueTrack(Slider slider) {
        ViewGroup parent = (ViewGroup) slider.getParent();
        if (parent == null) return;
        if (parent.findViewWithTag("hue_track") != null) return;

        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, buildHueColors());
        gradient.setCornerRadius(dpToPx(TRACK_CORNER_DP));

        View track = new View(slider.getContext());
        track.setTag("hue_track");
        track.setBackground(gradient);

        int sliderIndex = parent.indexOfChild(slider);

        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(TRACK_HEIGHT_DP));
            lp.leftMargin = dpToPx(20);
            lp.rightMargin = dpToPx(20);
            lp.gravity = Gravity.CENTER_VERTICAL;
            ((FrameLayout) parent).addView(track, sliderIndex, lp);
        } else {
            parent.addView(track, sliderIndex,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dpToPx(TRACK_HEIGHT_DP)));
        }
    }

    public void setHue(int hue) {
        mHue = hue;
        setValue(hue);
    }

    public int getHue() {
        return mHue;
    }

    public static int hsvToColor(int hue) {
        float[] hsv = {hue, 0.8f, 0.9f};
        return Color.HSVToColor(hsv);
    }

    private int[] buildHueColors() {
        int steps = 48;
        int[] colors = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float[] hsv = {(float) i / steps * 360f, 0.85f, 0.95f};
            colors[i] = Color.HSVToColor(hsv);
        }
        return colors;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }
}
