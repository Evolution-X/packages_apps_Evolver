/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

/**
 * Preference with one-hour decrement and increment controls for screenshot retention.
 */
public class ScreenshotRetentionPreference extends Preference {

    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 7 * 24;

    private int mHours = MAX_HOURS;
    private boolean mInitialized;
    private TextView mValueView;
    private ImageButton mDecreaseButton;
    private ImageButton mIncreaseButton;

    public ScreenshotRetentionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ScreenshotRetentionPreference(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        setLayoutResource(R.layout.preference_screenshot_retention);
        setPreferenceDataStore(new SystemSettingsStore(context.getContentResolver()));
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mValueView = (TextView) holder.findViewById(R.id.screenshot_retention_value);
        mDecreaseButton = (ImageButton) holder.findViewById(
                R.id.screenshot_retention_decrease);
        mIncreaseButton = (ImageButton) holder.findViewById(
                R.id.screenshot_retention_increase);

        ensureInitialized();

        if (mDecreaseButton != null) {
            mDecreaseButton.setOnClickListener(v -> adjust(-1));
        }
        if (mIncreaseButton != null) {
            mIncreaseButton.setOnClickListener(v -> adjust(1));
        }
        updateValueText();
    }

    /**
     * Returns the current retention period in hours.
     */
    public int getHours() {
        ensureInitialized();
        return mHours;
    }

    private void ensureInitialized() {
        if (!mInitialized) {
            mHours = clamp(getPersistedInt(MAX_HOURS));
            mInitialized = true;
        }
    }

    private void adjust(int delta) {
        if (!isEnabled()) {
            return;
        }
        setHours(mHours + delta);
    }

    private void setHours(int hours) {
        final int newHours = clamp(hours);
        if (newHours == mHours) {
            return;
        }
        if (!callChangeListener(newHours)) {
            return;
        }
        mHours = newHours;
        persistInt(mHours);
        updateValueText();
    }

    private void updateValueText() {
        if (mValueView != null) {
            mValueView.setText(formatHours(mHours));
        }

        final boolean enabled = isEnabled();
        if (mDecreaseButton != null) {
            final boolean canDecrease = enabled && mHours > MIN_HOURS;
            mDecreaseButton.setEnabled(canDecrease);
            mDecreaseButton.setAlpha(canDecrease ? 1f : 0.38f);
        }
        if (mIncreaseButton != null) {
            final boolean canIncrease = enabled && mHours < MAX_HOURS;
            mIncreaseButton.setEnabled(canIncrease);
            mIncreaseButton.setAlpha(canIncrease ? 1f : 0.38f);
        }
    }

    private String formatHours(int hours) {
        final Resources res = getContext().getResources();
        if (hours < 24) {
            return res.getQuantityString(
                    R.plurals.screenshot_retention_hours, hours, hours);
        }

        final int days = hours / 24;
        final int remainingHours = hours % 24;
        final String dayText = res.getQuantityString(
                R.plurals.screenshot_retention_days, days, days);
        if (remainingHours == 0) {
            return dayText;
        }
        final String hourText = res.getQuantityString(
                R.plurals.screenshot_delete_after_hours, remainingHours, remainingHours);
        return dayText + " " + hourText;
    }

    private static int clamp(int hours) {
        return Math.max(MIN_HOURS, Math.min(MAX_HOURS, hours));
    }
}
