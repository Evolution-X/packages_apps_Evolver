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
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.android.settings.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WallpaperPreviewPreference extends Preference {
    
    private ImageView mLockPreview;
    private ImageView mHomePreview;
    private TextView mLockLabel;
    private TextView mHomeLabel;
    private MaterialButton mApplyButton;
    private MaterialCardView mLockCard;
    private MaterialCardView mHomeCard;
    
    private ExecutorService mExecutor;
    private Handler mHandler;
    private WallpaperManager mWallpaperManager;
    
    private Bitmap mLockWallpaper;
    private Bitmap mHomeWallpaper;
    
    public WallpaperPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_wallpaper_preview);
        mExecutor = Executors.newSingleThreadExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mWallpaperManager = WallpaperManager.getInstance(context);
    }
    
    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        
        mLockCard = (MaterialCardView) holder.findViewById(R.id.lock_wallpaper_card);
        mHomeCard = (MaterialCardView) holder.findViewById(R.id.home_wallpaper_card);
        mLockPreview = (ImageView) holder.findViewById(R.id.lock_wallpaper_preview);
        mHomePreview = (ImageView) holder.findViewById(R.id.home_wallpaper_preview);
        mLockLabel = (TextView) holder.findViewById(R.id.lock_wallpaper_label);
        mHomeLabel = (TextView) holder.findViewById(R.id.home_wallpaper_label);
        mApplyButton = (MaterialButton) holder.findViewById(R.id.apply_now_button);
        
        if (mApplyButton != null) {
            mApplyButton.setOnClickListener(v -> applyNewWallpaper());
        }
        
        loadWallpaperPreviews();
    }
    
    private void loadWallpaperPreviews() {
        if (mExecutor == null || mExecutor.isShutdown()) {
            mExecutor = Executors.newSingleThreadExecutor();
        }
        
        mExecutor.execute(() -> {
            try {
                Drawable lockDrawable = mWallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK);
                if (lockDrawable instanceof BitmapDrawable) {
                    mLockWallpaper = ((BitmapDrawable) lockDrawable).getBitmap();
                } else {
                    Drawable systemDrawable = mWallpaperManager.getDrawable();
                    if (systemDrawable instanceof BitmapDrawable) {
                        mLockWallpaper = ((BitmapDrawable) systemDrawable).getBitmap();
                    }
                }
                
                Drawable homeDrawable = mWallpaperManager.getDrawable();
                if (homeDrawable instanceof BitmapDrawable) {
                    mHomeWallpaper = ((BitmapDrawable) homeDrawable).getBitmap();
                }
                
                mHandler.post(() -> updatePreviewImages());
                
            } catch (Exception e) {
                e.printStackTrace();
            }
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
        
        Intent intent = new Intent();
        intent.setClassName("com.android.systemui",
            "com.android.systemui.lockglymps.LockGlympsService");
        intent.setAction("APPLY_NOW");
        context.startService(intent);
        
        mHandler.postDelayed(() -> {
            if (mApplyButton != null) {
                mApplyButton.setEnabled(true);
                mApplyButton.setText(R.string.lock_glymps_apply_now);
            }
            mHandler.postDelayed(this::loadWallpaperPreviews, 1000);
        }, 2000);
    }
    
    public void refreshPreviews() {
        loadWallpaperPreviews();
    }
    
    @Override
    public void onDetached() {
        super.onDetached();
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdown();
            mExecutor = null;
        }
        if (mLockWallpaper != null && !mLockWallpaper.isRecycled()) {
            mLockWallpaper = null;
        }
        if (mHomeWallpaper != null && !mHomeWallpaper.isRecycled()) {
            mHomeWallpaper = null;
        }
    }
}
