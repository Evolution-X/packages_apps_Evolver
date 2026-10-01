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
package org.evolution.settings.preferences;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.android.settings.R;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WallpaperPreviewPreference extends Preference {

    private static final String TAG = "WallpaperPreviewPreference";
    private static final String GLYMPS_CONTROL_PACKAGE = "com.android.systemui";
    private static final String GLYMPS_CONTROL_RECEIVER =
            "com.android.systemui.lockglymps.LockGlympsControlReceiver";
    private static final String ACTION_APPLY_NOW =
            "com.android.systemui.lockglymps.action.APPLY_NOW";

    private ImageView mLockPreview;
    private ImageView mHomePreview;
    private MaterialButton mApplyButton;

    private ExecutorService mExecutor;
    private Handler mHandler;
    private WallpaperManager mWallpaperManager;

    private Bitmap mLockWallpaper;
    private Bitmap mHomeWallpaper;
    private boolean mAttached;
    private int mPreviewGeneration;

    public WallpaperPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_wallpaper_preview);
        mExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mWallpaperManager = WallpaperManager.getInstance(context);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        mAttached = true;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        mLockPreview = (ImageView) holder.findViewById(R.id.lock_wallpaper_preview);
        mHomePreview = (ImageView) holder.findViewById(R.id.home_wallpaper_preview);
        mApplyButton = (MaterialButton) holder.findViewById(R.id.apply_now_button);

        if (mApplyButton != null) {
            mApplyButton.setOnClickListener(v -> applyNewWallpaper());
        }

        loadWallpaperPreviews();
    }

    private void loadWallpaperPreviews() {
        if (!mAttached) return;
        if (mExecutor == null || mExecutor.isShutdown()) {
            mExecutor = Executors.newSingleThreadExecutor();
        }

        final int generation = ++mPreviewGeneration;
        mExecutor.execute(() -> {
            Bitmap lockWallpaper = null;
            Bitmap homeWallpaper = null;

            try {
                Drawable lockDrawable = mWallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK);
                if (lockDrawable instanceof BitmapDrawable) {
                    lockWallpaper = ((BitmapDrawable) lockDrawable).getBitmap();
                } else {
                    Drawable systemDrawable = mWallpaperManager.getDrawable();
                    if (systemDrawable instanceof BitmapDrawable) {
                        lockWallpaper = ((BitmapDrawable) systemDrawable).getBitmap();
                    }
                }

                Drawable homeDrawable = mWallpaperManager.getDrawable();
                if (homeDrawable instanceof BitmapDrawable) {
                    homeWallpaper = ((BitmapDrawable) homeDrawable).getBitmap();
                }
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Unable to allocate wallpaper previews", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to read wallpaper previews", e);
            }

            final Bitmap lockResult = lockWallpaper;
            final Bitmap homeResult = homeWallpaper;
            mHandler.post(() -> {
                if (!mAttached || generation != mPreviewGeneration) {
                    return;
                }

                mLockWallpaper = lockResult;
                mHomeWallpaper = homeResult;
                updatePreviewImages();
            });
        });
    }

    private void updatePreviewImages() {
        if (mLockPreview != null && mLockWallpaper != null) {
            mLockPreview.setImageBitmap(mLockWallpaper);
            mLockPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }

        if (mHomePreview != null && mHomeWallpaper != null) {
            mHomePreview.setImageBitmap(mHomeWallpaper);
            mHomePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
    }

    private void applyNewWallpaper() {
        Context context = getContext();
        if (context == null) return;

        if (mApplyButton != null) {
            mApplyButton.setEnabled(false);
            mApplyButton.setText(R.string.lock_glymps_applying);
        }

        ComponentName component = new ComponentName(
                GLYMPS_CONTROL_PACKAGE,
                GLYMPS_CONTROL_RECEIVER);
        try {
            context.getPackageManager().getReceiverInfo(component, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Wallpaper Glymps control receiver is unavailable", e);
            Toast.makeText(
                    context,
                    R.string.lock_glymps_service_error,
                    Toast.LENGTH_SHORT).show();
            restoreApplyButton();
            return;
        }

        Intent intent = new Intent(ACTION_APPLY_NOW);
        intent.setComponent(component);

        try {
            context.sendBroadcast(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to request a new Wallpaper Glymps image", e);
            Toast.makeText(
                    context,
                    R.string.lock_glymps_service_error,
                    Toast.LENGTH_SHORT).show();
            restoreApplyButton();
            return;
        }

        mHandler.postDelayed(() -> {
            if (!mAttached) return;

            restoreApplyButton();
            mHandler.postDelayed(this::loadWallpaperPreviews, 1000);
        }, 2000);
    }

    private void restoreApplyButton() {
        if (mApplyButton != null) {
            mApplyButton.setEnabled(true);
            mApplyButton.setText(R.string.lock_glymps_apply_now);
        }
    }

    public void refreshPreviews() {
        loadWallpaperPreviews();
    }

    @Override
    public void onDetached() {
        mAttached = false;
        mPreviewGeneration++;
        mHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdownNow();
        }
        mExecutor = null;

        mLockPreview = null;
        mHomePreview = null;
        mApplyButton = null;
        mLockWallpaper = null;
        mHomeWallpaper = null;

        super.onDetached();
    }
}
