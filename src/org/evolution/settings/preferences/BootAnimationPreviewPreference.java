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
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

import org.evolution.settings.utils.BootAnimationUtils;

public class BootAnimationPreviewPreference extends Preference {

    private ImageView mImageView;
    private ProgressBar mLoadingSpinner;
    private LoadPreviewTask mCurrentTask;

    public BootAnimationPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_bootanimation_preview);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mImageView = (ImageView) holder.findViewById(R.id.bootanimation_preview_image);
        mLoadingSpinner = (ProgressBar) holder.findViewById(R.id.bootanimation_loading_spinner);
        loadBootAnimationPreview();
    }

    public void loadBootAnimationPreview() {
        if (mCurrentTask != null && mCurrentTask.getStatus() != AsyncTask.Status.FINISHED) {
            mCurrentTask.cancel(true);
        }
        int bootAnimStyle = BootAnimationUtils.getBootAnimStyle();
        if (bootAnimStyle == 5 || bootAnimStyle == 6) {
            if (mImageView != null) {
                Drawable drawable = getContext().getDrawable(
                        bootAnimStyle == 5 ? R.drawable.google_gemini : R.drawable.google_monet);
                mImageView.setImageDrawable(drawable);
            }
        } else {
            mCurrentTask = new LoadPreviewTask();
            mCurrentTask.execute();
        }
    }

    private class LoadPreviewTask extends AsyncTask<Void, Void, AnimationDrawable> {
        @Override
        protected AnimationDrawable doInBackground(Void... voids) {
            if (isCancelled()) return null;
            AnimationDrawable originalDrawable = BootAnimationUtils.getBootAnimationFrames(getContext());
            if (originalDrawable == null) {
                return null;
            }
            AnimationDrawable fixedDrawable = new AnimationDrawable();
            for (int i = 0; i < originalDrawable.getNumberOfFrames(); i++) {
                if (isCancelled()) return null;
                Drawable frame = originalDrawable.getFrame(i);
                int duration = originalDrawable.getDuration(i);
                if (duration < 16) { // 16 ms is around 60fps
                    duration = 1000 / 60; // Set to 60fps as a fallback
                }
                fixedDrawable.addFrame(frame, duration);
            }
            fixedDrawable.setOneShot(false); // Ensure the animation loops
            return fixedDrawable;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (mImageView != null) {
                mImageView.setVisibility(View.GONE);
            }
            if (mLoadingSpinner != null) {
                mLoadingSpinner.setVisibility(View.VISIBLE);
            }
        }

        @Override
        protected void onPostExecute(AnimationDrawable animationDrawable) {
            if (isCancelled()) return;
            if (mLoadingSpinner != null) {
                mLoadingSpinner.setVisibility(View.GONE);
            }
            if (mImageView != null) {
                mImageView.setVisibility(View.VISIBLE);
            }
            if (animationDrawable != null) {
                mImageView.setImageDrawable(animationDrawable);
                animationDrawable.start();
            }
        }
    }
}
