/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.preferences;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.internal.widget.QsHeaderImageView;
import com.android.settings.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QsHeaderImagePreviewPreference extends Preference {
    private static final String TAG = "QsHeaderPreview";
    private static final String[] OBSERVED_SETTINGS = {
            Settings.System.STATUS_BAR_CUSTOM_HEADER,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
            Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refreshPreview();
        }
    };
    private ExecutorService mExecutor;
    private boolean mAttached;
    private boolean mResumed;
    private boolean mLoading;
    private boolean mSliderTracking;
    private int mLoadGeneration;
    private String mImageSource;
    private Drawable mImage;
    private QsHeaderImageView mPreview;
    private FrameLayout mFrame;
    private View mFade;
    private View mControls;
    private TextView mEmpty;
    private TextView mZoomLabel;
    private TextView mHorizontalLabel;
    private TextView mVerticalLabel;
    private SeekBar mZoom;
    private SeekBar mHorizontal;
    private SeekBar mVertical;
    private final View.OnLayoutChangeListener mLayoutListener =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (r - l != oldR - oldL) updatePreviewSize();
            };
    private final View.OnAttachStateChangeListener mViewAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    updateAnimation();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    stopAnimation();
                }
            };
    private ScaleGestureDetector mScaleDetector;
    private float mLastX;
    private float mLastY;
    private int mPointerId = -1;

    public QsHeaderImagePreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_qs_header_image_preview);
        setSelectable(false);
        setPersistent(false);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        mAttached = true;
        mExecutor = Executors.newSingleThreadExecutor();
        for (String key : OBSERVED_SETTINGS) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mObserver, UserHandle.USER_CURRENT);
        }
        refreshPreview();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        releasePreview();
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
        mPreview = (QsHeaderImageView) holder.findViewById(R.id.qs_header_preview_image);
        mPreview.setSettingsObserverEnabled(false);
        mPreview.addOnAttachStateChangeListener(mViewAttachListener);
        mFrame = (FrameLayout) holder.findViewById(R.id.qs_header_preview_frame);
        mFade = holder.findViewById(R.id.qs_header_preview_fade);
        mControls = holder.findViewById(R.id.qs_header_preview_controls);
        mEmpty = (TextView) holder.findViewById(R.id.qs_header_preview_empty);
        mZoomLabel = (TextView) holder.findViewById(R.id.qs_header_preview_zoom_label);
        mHorizontalLabel = (TextView) holder.findViewById(R.id.qs_header_preview_horizontal_label);
        mVerticalLabel = (TextView) holder.findViewById(R.id.qs_header_preview_vertical_label);
        mZoom = (SeekBar) holder.findViewById(R.id.qs_header_preview_zoom);
        mHorizontal = (SeekBar) holder.findViewById(R.id.qs_header_preview_horizontal);
        mVertical = (SeekBar) holder.findViewById(R.id.qs_header_preview_vertical);
        bindSlider(mZoom, Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM, 100);
        bindSlider(mHorizontal, Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X, 0);
        bindSlider(mVertical, Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y, 0);
        holder.findViewById(R.id.qs_header_preview_reset).setOnClickListener(v -> resetFraming());
        mFrame.removeOnLayoutChangeListener(mLayoutListener);
        mFrame.addOnLayoutChangeListener(mLayoutListener);
        mScaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        mPreview.zoomImage(detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        updateSliderValues();
                        return true;
                    }
                });
        mPreview.setOnTouchListener((v, event) -> handlePreviewTouch(event));
        showImage();
        refreshPreview();
    }

    private void bindSlider(SeekBar slider, String key, int offset) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || !isEnabled() || mImage == null) return;
                int value = progress + offset;
                float zoom = mPreview.getImageZoom();
                float x = mPreview.getImagePositionX();
                float y = mPreview.getImagePositionY();
                if (Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM.equals(key)) zoom = value / 100f;
                if (Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X.equals(key)) x = value / 1000f;
                if (Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y.equals(key)) y = value / 1000f;
                mPreview.setImageFraming(zoom, x, y);
                updateSliderValues();
                if (!mHandler.hasCallbacks(mSaveFraming)) {
                    mHandler.postDelayed(mSaveFraming, 80);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSliderTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mSliderTracking = false;
                saveFraming();
            }
        });
    }

    private boolean handlePreviewTouch(MotionEvent event) {
        if (!isEnabled() || mImage == null) return false;
        mPreview.getParent().requestDisallowInterceptTouchEvent(true);
        mScaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPointerId = event.getPointerId(0);
                mLastX = event.getX();
                mLastY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(mPointerId);
                if (index >= 0) {
                    float x = event.getX(index);
                    float y = event.getY(index);
                    if (!mScaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        mPreview.panImage(x - mLastX, y - mLastY);
                        updateSliderValues();
                    }
                    mLastX = x;
                    mLastY = y;
                }
                if (!mHandler.hasCallbacks(mSaveFraming)) {
                    mHandler.postDelayed(mSaveFraming, 80);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == mPointerId) {
                    int next = event.getActionIndex() == 0 ? 1 : 0;
                    mPointerId = event.getPointerId(next);
                    mLastX = event.getX(next);
                    mLastY = event.getY(next);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mPointerId = -1;
                saveFraming();
                mPreview.getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) mPreview.performClick();
                break;
        }
        return true;
    }

    private final Runnable mSaveFraming = this::saveFraming;

    private void saveFraming() {
        mHandler.removeCallbacks(mSaveFraming);
        if (mPreview == null) return;
        putSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM,
                Math.round(mPreview.getImageZoom() * 100));
        putSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X,
                Math.round(mPreview.getImagePositionX() * 1000));
        putSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y,
                Math.round(mPreview.getImagePositionY() * 1000));
    }

    private int getSetting(String key, int fallback) {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                key, fallback, UserHandle.USER_CURRENT);
    }

    private void putSetting(String key, int value) {
        if (getSetting(key, -1) != value) {
            Settings.System.putIntForUser(getContext().getContentResolver(), key, value,
                    UserHandle.USER_CURRENT);
        }
    }

    private void resetFraming() {
        mPreview.setImageFraming(1f, 0.5f, 0.5f);
        updateSliderValues();
        saveFraming();
    }

    public void refreshPreview() {
        if (!mAttached) return;
        updateAnimation();
        if (mPreview != null && mPointerId == -1 && !mSliderTracking
                && !mHandler.hasCallbacks(mSaveFraming)) {
            mPreview.setImageFraming(getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_ZOOM, 100) / 100f,
                    getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_X, 500) / 1000f,
                    getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_POSITION_Y, 500) / 1000f);
            updateSliderValues();
        }
        updatePreviewSize();
        if (mPreview != null) {
            mPreview.setImageAlpha(255 - getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 0));
        }
        String provider = Settings.System.getStringForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER, UserHandle.USER_CURRENT);
        boolean file = "file".equals(provider);
        String source = (file || "static".equals(provider))
                ? Settings.System.getStringForUser(getContext().getContentResolver(),
                        file ? Settings.System.STATUS_BAR_FILE_HEADER_IMAGE
                                : Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
                        UserHandle.USER_CURRENT) : null;
        String identity = provider + ":" + source;
        if (identity.equals(mImageSource)) return;
        mImageSource = identity;
        int generation = ++mLoadGeneration;
        stopAnimation();
        mImage = null;
        mLoading = source != null && !source.isEmpty();
        showImage();
        if (!mLoading) return;
        mExecutor.execute(() -> {
            Drawable image = loadImage(source, file);
            mHandler.post(() -> {
                if (!mAttached || generation != mLoadGeneration) return;
                mLoading = false;
                mImage = image;
                showImage();
            });
        });
    }

    private Drawable loadImage(String source, boolean file) {
        try {
            if (file) {
                Drawable image = ImageDecoder.decodeDrawable(ImageDecoder.createSource(
                                getContext().getContentResolver(), Uri.parse(source)),
                        (decoder, info, src) -> {
                            int limit = 2048;
                            int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            if (longest > limit) {
                                decoder.setTargetSampleSize((longest + limit - 1) / limit);
                            }
                        });
                if (image instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) image).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                }
                return image;
            }
            String[] parts = source.split("/", 2);
            if (parts.length != 2) return null;
            Resources resources = getContext().getPackageManager().getResourcesForApplication(parts[0]);
            return resources.getDrawable(resources.getIdentifier(parts[1], "drawable", parts[0]), null);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Unable to load header preview", e);
            return null;
        }
    }

    private void showImage() {
        if (mPreview == null) return;
        mPreview.setImageDrawable(mImage);
        mEmpty.setVisibility(mImage == null ? View.VISIBLE : View.GONE);
        mControls.setVisibility(mImage == null ? View.GONE : View.VISIBLE);
        mFade.setVisibility(mImage == null ? View.GONE : View.VISIBLE);
        updateAnimation();
    }

    private void updateAnimation() {
        if (mImage instanceof Animatable) {
            Animatable animation = (Animatable) mImage;
            if (mAttached && mResumed && mPreview != null && mPreview.isAttachedToWindow()
                    && mPreview.getWindowVisibility() == View.VISIBLE
                    && getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER, 0) == 1) {
                if (!animation.isRunning()) animation.start();
            } else {
                animation.stop();
            }
        }
    }

    private void updateSliderValues() {
        if (mPreview == null) return;
        int zoom = Math.round(mPreview.getImageZoom() * 100);
        int x = Math.round(mPreview.getImagePositionX() * 100);
        int y = Math.round(mPreview.getImagePositionY() * 100);
        mZoom.setProgress(zoom - 100);
        mHorizontal.setProgress(Math.round(mPreview.getImagePositionX() * 1000));
        mVertical.setProgress(Math.round(mPreview.getImagePositionY() * 1000));
        mZoomLabel.setText(getContext().getString(R.string.qs_header_preview_zoom_value, zoom));
        mHorizontalLabel.setText(getContext().getString(R.string.qs_header_preview_horizontal_value, x));
        mVerticalLabel.setText(getContext().getString(R.string.qs_header_preview_vertical_value, y));
    }

    private void updatePreviewSize() {
        if (mFrame == null || mFrame.getWidth() == 0) return;
        float density = getContext().getResources().getDisplayMetrics().density;
        int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
        if (screenWidth <= 0) return;
        float ratio = mFrame.getWidth() / (float) screenWidth;
        int height = Math.round(getSetting(Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT, 142)
                * density * ratio);
        ViewGroup.LayoutParams frameParams = mFrame.getLayoutParams();
        if (frameParams.height != height) {
            frameParams.height = height;
            mFrame.setLayoutParams(frameParams);
        }
        ViewGroup.LayoutParams imageParams = mPreview.getLayoutParams();
        int width = Math.round((screenWidth + 100 * density) * ratio);
        if (imageParams.width != width) {
            imageParams.width = width;
            mPreview.setLayoutParams(imageParams);
        }
        ViewGroup.LayoutParams fadeParams = mFade.getLayoutParams();
        int fadeHeight = Math.round(height * 0.555f);
        if (fadeParams.height != fadeHeight) {
            fadeParams.height = fadeHeight;
            mFade.setLayoutParams(fadeParams);
        }
    }

    public void pausePreview() {
        mResumed = false;
        if (mHandler.hasCallbacks(mSaveFraming)) saveFraming();
        mPointerId = -1;
        mSliderTracking = false;
        stopAnimation();
    }

    public void resumePreview() {
        mResumed = true;
        if (mImage == null && !mLoading) mImageSource = null;
        refreshPreview();
        showImage();
    }

    public void releasePreview() {
        if (mHandler.hasCallbacks(mSaveFraming)) saveFraming();
        mPointerId = -1;
        mSliderTracking = false;
        stopAnimation();
        if (mPreview != null) {
            mPreview.removeOnAttachStateChangeListener(mViewAttachListener);
            mPreview.setOnTouchListener(null);
            mPreview.setImageDrawable(null);
        }
        if (mFrame != null) mFrame.removeOnLayoutChangeListener(mLayoutListener);
        if (mZoom != null) mZoom.setOnSeekBarChangeListener(null);
        if (mHorizontal != null) mHorizontal.setOnSeekBarChangeListener(null);
        if (mVertical != null) mVertical.setOnSeekBarChangeListener(null);
        mPreview = null;
        mFrame = null;
        mFade = null;
        mControls = null;
        mEmpty = null;
        mZoomLabel = null;
        mHorizontalLabel = null;
        mVerticalLabel = null;
        mZoom = null;
        mHorizontal = null;
        mVertical = null;
        mScaleDetector = null;
    }

    private void stopAnimation() {
        if (mImage instanceof Animatable) ((Animatable) mImage).stop();
    }

    @Override
    public void onDetached() {
        pausePreview();
        releasePreview();
        mAttached = false;
        ++mLoadGeneration;
        getContext().getContentResolver().unregisterContentObserver(mObserver);
        mHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) mExecutor.shutdownNow();
        mExecutor = null;
        mLoading = false;
        mImage = null;
        mImageSource = null;
        mPreview = null;
        mPointerId = -1;
        super.onDetached();
    }
}
