/*
 * Copyright (C) 2024 risingOS
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

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

import org.evolution.settings.utils.BootAnimationUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BootAnimationPreviewPreference extends Preference {

    private static final String TAG = "BootAnimPreviewPref";

    private ImageView mImageView;
    private ProgressBar mLoadingSpinner;
    private LoadPreviewTask mCurrentTask;

    public BootAnimationPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.boot_animation_preview);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mImageView = (ImageView) holder.findViewById(R.id.boot_animation_preview_image);
        mLoadingSpinner = (ProgressBar) holder.findViewById(R.id.boot_animation_loading_spinner);
        loadBootAnimationPreview();
    }

    public void loadBootAnimationPreview() {
        if (mCurrentTask != null
                && mCurrentTask.getStatus() != AsyncTask.Status.FINISHED) {
            mCurrentTask.cancel(true);
        }
        mCurrentTask = new LoadPreviewTask();
        mCurrentTask.execute();
    }

    // -----------------------------------------------------------------------
    // AsyncTask
    // -----------------------------------------------------------------------

    private class LoadPreviewTask extends AsyncTask<Void, Void, Drawable> {

        @Override
        protected void onPreExecute() {
            if (mImageView != null)      mImageView.setVisibility(View.GONE);
            if (mLoadingSpinner != null)  mLoadingSpinner.setVisibility(View.VISIBLE);
        }

        @Override
        protected Drawable doInBackground(Void... voids) {
            if (isCancelled()) return null;

            int style = BootAnimationUtils.getBootAnimStyle();

            // Google / Google Monet: zip ships a single animated WebP at its root.
            // Try to extract and decode it with ImageDecoder (API 28+).
            if (BootAnimationUtils.isAnimatedImageStyle(style)) {
                Drawable animated = loadAnimatedImageFromZip();
                if (animated != null) return animated;
                // If the WebP wasn't found (shouldn't happen), fall through to frames
            }

            // Standard PNG/JPG frame-by-frame animation
            AnimationDrawable original =
                    BootAnimationUtils.getBootAnimationFrames(getContext());
            if (original == null || original.getNumberOfFrames() == 0) return null;

            AnimationDrawable fixed = new AnimationDrawable();
            for (int i = 0; i < original.getNumberOfFrames(); i++) {
                if (isCancelled()) return null;
                int duration = original.getDuration(i);
                if (duration < 16) duration = 1000 / 60; // clamp to max 60 fps
                fixed.addFrame(original.getFrame(i), duration);
            }
            fixed.setOneShot(false);
            return fixed;
        }

        @Override
        protected void onPostExecute(Drawable drawable) {
            if (isCancelled()) return;
            if (mLoadingSpinner != null) mLoadingSpinner.setVisibility(View.GONE);
            if (mImageView != null) {
                mImageView.setVisibility(View.VISIBLE);
                if (drawable != null) {
                    mImageView.setImageDrawable(drawable);
                    // Start the animation — type depends on what was decoded
                    if (drawable instanceof AnimatedImageDrawable) {
                        ((AnimatedImageDrawable) drawable).start();
                    } else if (drawable instanceof AnimationDrawable) {
                        ((AnimationDrawable) drawable).start();
                    }
                }
            }
        }

        /**
         * Google boot animations ship a single animated WebP (or GIF) at the
         * root of the zip (not inside a part folder). Find it, read it into a
         * byte array, and decode with ImageDecoder so we get an
         * AnimatedImageDrawable that loops correctly.
         */
        private Drawable loadAnimatedImageFromZip() {
            String zipPath = BootAnimationUtils.getSelectedBootAnimation();
            if (zipPath == null) return null;
            File zipFile = new File(zipPath);
            if (!zipFile.exists()) return null;

            try (ZipFile zf = new ZipFile(zipFile)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();
                    // Top-level animated image only (no "/" means not in a sub-folder)
                    if (!name.contains("/")
                            && (name.endsWith(".webp") || name.endsWith(".gif"))) {
                        try (InputStream is = zf.getInputStream(entry)) {
                            byte[] bytes = is.readAllBytes();
                            ImageDecoder.Source src =
                                    ImageDecoder.createSource(ByteBuffer.wrap(bytes));
                            // Use onHeaderDecoded to set repeating if needed
                            return ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                                decoder.setPostProcessor(null); // keep full color
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load animated image from zip", e);
            }
            return null;
        }
    }
}
