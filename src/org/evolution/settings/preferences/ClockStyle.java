/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;
import com.android.settings.R;

public class ClockStyle extends RelativeLayout {

    private static final int[] CLOCK_VIEW_IDS = {
            R.id.keyguard_clock_style_default,
            R.id.keyguard_clock_style_oos,
            R.id.keyguard_clock_style_ios,
            R.id.keyguard_clock_style_cos,
            R.id.keyguard_clock_style_custom,
            R.id.keyguard_clock_style_custom1,
            R.id.keyguard_clock_style_custom2,
            R.id.keyguard_clock_style_custom3,
            R.id.keyguard_clock_style_miui,
            R.id.keyguard_clock_style_ide,
            R.id.keyguard_clock_style_lottie,
            R.id.keyguard_clock_style_lottie2,
            R.id.keyguard_clock_style_fluid,
            R.id.keyguard_clock_style_hyper,
            R.id.keyguard_clock_style_dual,
            R.id.keyguard_clock_style_stylish,
            R.id.keyguard_clock_style_sidebar,
            R.id.keyguard_clock_style_minimal,
            R.id.keyguard_clock_style_minimal2,
            R.id.keyguard_clock_style_minimal3
    };

    private static final int DEFAULT_STYLE = 0; //Disabled
    private static final String CLOCK_STYLE_KEY = "clock_style";

	private Context mContext;
	private View[] clockViews;
    private final MyContentObserver mContentObserver;

	public ClockStyle(Context context, AttributeSet attrs) {
		super(context, attrs);
		mContext = context;
        mContentObserver = new MyContentObserver(new Handler(Looper.getMainLooper()));
	}

	@Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        clockViews = new View[CLOCK_VIEW_IDS.length];
        for (int i = 0; i < CLOCK_VIEW_IDS.length; i++) {
            if (CLOCK_VIEW_IDS[i] != 0) {
                clockViews[i] = findViewById(CLOCK_VIEW_IDS[i]);
            } else {
                clockViews[i] = null;
            }
        }
        updateClockView();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContentObserver.observe();
        updateClockView();
    }

    @Override
    protected void onDetachedFromWindow() {
        mContentObserver.unobserve();
        super.onDetachedFromWindow();
    }

    private void updateClockView() {
        if (clockViews != null) {
            int clockStyle = Settings.System.getInt(mContext.getContentResolver(), CLOCK_STYLE_KEY, DEFAULT_STYLE);
            for (int i = 0; i < clockViews.length; i++) {
                if (clockViews[i] != null) {
                    clockViews[i].setVisibility(i == clockStyle ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    private class MyContentObserver extends ContentObserver {
        private boolean mRegistered;

        MyContentObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            if (mRegistered) return;
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(CLOCK_STYLE_KEY), false, this);
            mRegistered = true;
        }

        void unobserve() {
            if (!mRegistered) return;
            mContext.getContentResolver().unregisterContentObserver(this);
            mRegistered = false;
        }

        @Override
        public void onChange(boolean selfChange) {
            updateClockView();
        }
    }
}
