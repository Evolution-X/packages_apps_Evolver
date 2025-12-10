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
package org.evolution.settings.fragments.themes.fonts;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.settings.R;

import org.evolution.settings.utils.ExternalFontInstaller;

public class FontPreviewDialog extends Dialog {

    public interface OnFontInstallListener {
        void onInstall(Uri fontUri);
        void onCancel();
    }

    private Uri mFontUri;
    private OnFontInstallListener mListener;
    private Context mContext;
    private ExternalFontInstaller mFontInstaller;

    private TextView mPreviewTitle;
    private TextView mPreviewSubtitle;
    private TextView mPreviewBody;
    private TextView mPreviewNumbers;
    private TextView mPreviewSpecial;
    private Button mInstallButton;
    private Button mCancelButton;

    public FontPreviewDialog(@NonNull Context context, Uri fontUri) {
        super(context);
        mContext = context;
        mFontUri = fontUri;
        mFontInstaller = new ExternalFontInstaller(context);
    }

    public void setOnFontInstallListener(OnFontInstallListener listener) {
        mListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_font_preview);

        mPreviewTitle = findViewById(R.id.preview_title);
        mPreviewSubtitle = findViewById(R.id.preview_subtitle);
        mPreviewBody = findViewById(R.id.preview_body);
        mPreviewNumbers = findViewById(R.id.preview_numbers);
        mPreviewSpecial = findViewById(R.id.preview_special);
        mInstallButton = findViewById(R.id.install_button);
        mCancelButton = findViewById(R.id.cancel_button);

        loadFontPreview();

        mInstallButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onInstall(mFontUri);
            }
            dismiss();
        });

        mCancelButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onCancel();
            }
            dismiss();
        });
    }

    private void loadFontPreview() {
        try {
            // Use ExternalFontInstaller to load typeface for preview
            Typeface typeface = mFontInstaller.loadTypefaceFromUri(mContext, mFontUri);

            if (typeface != null) {
                mPreviewTitle.setTypeface(typeface);
                mPreviewSubtitle.setTypeface(typeface);
                mPreviewBody.setTypeface(typeface);
                mPreviewNumbers.setTypeface(typeface);
                mPreviewSpecial.setTypeface(typeface);
            } else {
                Toast.makeText(mContext, R.string.font_preview_error, Toast.LENGTH_SHORT).show();
                dismiss();
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(mContext, R.string.font_preview_error, Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }
}
