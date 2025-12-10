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
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.evolution.settings.utils.ExternalFontInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

public class GithubFontPickerDialog extends Dialog {

    private static final String TAG = "GithubFontPickerDialog";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/Evolution-X/vendor_extras/contents/prebuilt/product/fonts?ref=bq1";
    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/Evolution-X/vendor_extras/bq1/prebuilt/product/fonts/";

    public interface OnFontSelectedListener {
        void onFontInstalled(String fontName);
    }

    private RecyclerView mRecyclerView;
    private ProgressBar mProgressBar;
    private TextView mEmptyView;
    private FontAdapter mAdapter;
    private List<FontItem> mFontList = new ArrayList<>();
    private ExternalFontInstaller mFontInstaller;
    private File mFontsDirectory;
    private OnFontSelectedListener mListener;

    public GithubFontPickerDialog(@NonNull Context context) {
        super(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        mFontInstaller = new ExternalFontInstaller(context);

        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            mFontsDirectory = new File(externalFilesDir, "Fonts");
        } else {
            mFontsDirectory = new File(context.getFilesDir(), "Fonts");
        }

        if (!mFontsDirectory.exists()) {
            mFontsDirectory.mkdirs();
        }
    }

    public void setOnFontSelectedListener(OnFontSelectedListener listener) {
        mListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_github_font_picker);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                           ViewGroup.LayoutParams.MATCH_PARENT);
        }

        mRecyclerView = findViewById(R.id.font_recycler_view);
        mProgressBar = findViewById(R.id.progress_bar);
        mEmptyView = findViewById(R.id.empty_view);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new FontAdapter(mFontList);
        mRecyclerView.setAdapter(mAdapter);

        fetchFontsFromGithub();
    }

    private void fetchFontsFromGithub() {
        mProgressBar.setVisibility(View.VISIBLE);
        mEmptyView.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setRequestProperty("User-Agent", "Lunaris-Font-Installer");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("GitHub API returned HTTP " + responseCode);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONArray jsonArray = new JSONArray(response.toString());
                List<FontItem> fonts = new ArrayList<>();

                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject fontObject = jsonArray.getJSONObject(i);
                    String name = fontObject.getString("name");

                    if (name.endsWith(".ttf") || name.endsWith(".otf")) {
                        String downloadUrl = GITHUB_RAW_URL + name;
                        String displayName = name.substring(0, name.lastIndexOf('.'));
                        fonts.add(new FontItem(displayName, downloadUrl, name));
                    }
                }

                mRecyclerView.post(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    if (fonts.isEmpty()) {
                        mEmptyView.setVisibility(View.VISIBLE);
                    } else {
                        mFontList.clear();
                        mFontList.addAll(fonts);
                        mAdapter.notifyDataSetChanged();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch fonts", e);
                mRecyclerView.post(() -> {
                    mProgressBar.setVisibility(View.GONE);
                    mEmptyView.setVisibility(View.VISIBLE);
                    Toast.makeText(getContext(), "Failed to fetch fonts: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private class FontAdapter extends RecyclerView.Adapter<FontAdapter.FontViewHolder> {

        private List<FontItem> mItems;

        public FontAdapter(List<FontItem> items) {
            mItems = items;
        }

        @NonNull
        @Override
        public FontViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_github_font, parent, false);
            return new FontViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FontViewHolder holder, int position) {
            FontItem item = mItems.get(position);
            holder.bind(item);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        class FontViewHolder extends RecyclerView.ViewHolder {
            private TextView mFontName;
            private Button mPreviewButton;
            private Button mInstallButton;
            private ProgressBar mDownloadProgress;
            private ImageView mDownloadedIcon;

            public FontViewHolder(@NonNull View itemView) {
                super(itemView);
                mFontName = itemView.findViewById(R.id.font_name);
                mPreviewButton = itemView.findViewById(R.id.preview_button);
                mInstallButton = itemView.findViewById(R.id.install_button);
                mDownloadProgress = itemView.findViewById(R.id.download_progress);
                mDownloadedIcon = itemView.findViewById(R.id.downloaded_icon);
            }

            public void bind(FontItem item) {
                mFontName.setText(item.displayName);

                File fontFile = new File(mFontsDirectory, item.fileName);
                boolean isDownloaded = fontFile.exists();

                updateDownloadStatus(isDownloaded);

                mPreviewButton.setOnClickListener(v -> {
                    if (isDownloaded) {
                        openPreview(fontFile, item.displayName);
                    } else {
                        downloadAndPreview(item, fontFile);
                    }
                });

                mInstallButton.setOnClickListener(v -> {
                    if (isDownloaded) {
                        installFont(fontFile, item.displayName);
                    } else {
                        Toast.makeText(getContext(),
                                R.string.download_first_message, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            private void updateDownloadStatus(boolean isDownloaded) {
                if (isDownloaded) {
                    mDownloadedIcon.setVisibility(View.VISIBLE);
                    mInstallButton.setEnabled(true);
                    mInstallButton.setAlpha(1.0f);
                } else {
                    mDownloadedIcon.setVisibility(View.GONE);
                    mInstallButton.setEnabled(false);
                    mInstallButton.setAlpha(0.5f);
                }
            }

            private void downloadAndPreview(FontItem item, File targetFile) {
                mPreviewButton.setVisibility(View.GONE);
                mInstallButton.setVisibility(View.GONE);
                mDownloadProgress.setVisibility(View.VISIBLE);

                new Thread(() -> {
                    try {
                        downloadFont(item.downloadUrl, targetFile);

                        mPreviewButton.post(() -> {
                            mDownloadProgress.setVisibility(View.GONE);
                            mPreviewButton.setVisibility(View.VISIBLE);
                            mInstallButton.setVisibility(View.VISIBLE);
                            updateDownloadStatus(true);
                            openPreview(targetFile, item.displayName);
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Download failed", e);
                        mPreviewButton.post(() -> {
                            mDownloadProgress.setVisibility(View.GONE);
                            mPreviewButton.setVisibility(View.VISIBLE);
                            mInstallButton.setVisibility(View.VISIBLE);
                            Toast.makeText(getContext(),
                                    "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                }).start();
            }

            private void openPreview(File fontFile, String fontName) {
                Uri fontUri = Uri.fromFile(fontFile);
                GithubFontPreviewDialog previewDialog = new GithubFontPreviewDialog(getContext(), fontUri, fontName);
                previewDialog.setOnFontInstallListener(new GithubFontPreviewDialog.OnFontInstallListener() {
                    @Override
                    public void onInstall(Uri uri, String name) {
                        installFont(fontFile, name);
                    }

                    @Override
                    public void onCancel() {
                        // Do nothing
                    }
                });
                previewDialog.show();
            }

            private void installFont(File fontFile, String displayName) {
                new Thread(() -> {
                    String postScriptName = mFontInstaller.installFontFromFile(getContext(), fontFile);

                    if (postScriptName != null) {
                        Settings.Secure.putStringForUser(
                                getContext().getContentResolver(),
                                "custom_font_name",
                                postScriptName,
                                UserHandle.USER_CURRENT
                        );
                        mPreviewButton.post(() -> {
                            Toast.makeText(getContext(),
                                    R.string.custom_font_installed_success, Toast.LENGTH_SHORT).show();
                            if (mListener != null) {
                                mListener.onFontInstalled(displayName);
                            }
                            dismiss();
                        });
                    } else {
                        mPreviewButton.post(() -> {
                            Toast.makeText(getContext(),
                                    R.string.custom_font_install_failed, Toast.LENGTH_SHORT).show();
                        });
                    }
                }).start();
            }
        }
    }

    private void downloadFont(String downloadUrl, File targetFile) throws Exception {
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        URL url = new URL(downloadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Lunaris-Font-Installer");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("Server returned HTTP " + responseCode);
        }

        try (InputStream inputStream = connection.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(targetFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            if (targetFile.exists()) {
                targetFile.delete();
            }
            throw e;
        }
    }

    private static class FontItem {
        String displayName;
        String downloadUrl;
        String fileName;

        FontItem(String displayName, String downloadUrl, String fileName) {
            this.displayName = displayName;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
        }
    }
}
