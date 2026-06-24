/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Specialised image utility for the Status Bar custom logo feature.
 *
 * Unlike the generic {@link ImageUtils}, this class:
 *  - Downscales the selected image to {@link #TARGET_SIZE}×{@link #TARGET_SIZE} px before
 *    saving, keeping the file tiny and consistent with how SystemUI renders the logo
 *    (it is shown at ~status_bar_system_icons_height, typically 16–24 dp).
 *  - Saves into a dedicated sub-folder so it never conflicts with the AOD image.
 *  - Always outputs a lossless PNG (no GIF / WebP pass-through needed at this scale).
 */
public class StatusBarLogoImageUtils {

    private static final String TAG = "StatusBarLogoImageUtils";

    /** Target dimension in pixels for the saved logo bitmap. */
    private static final int TARGET_SIZE = 64;  // 64 px is crisp enough at any density

    /** Folder inside /sdcard/Evolution-X/ where the file is stored. */
    private static final String FEATURE_PATH = "statusbar_logo";

    /** Prefix used for the saved file and for cleaning up old copies. */
    private static final String FILE_PREFIX = "STATUSBAR_LOGO_IMAGE";

    /**
     * Opens {@code imgUri}, downscales the bitmap to {@link #TARGET_SIZE}×{@link #TARGET_SIZE},
     * saves it as a PNG and returns the absolute path, or {@code null} on failure.
     */
    public static String saveLogoImage(Context context, Uri imgUri) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        try {
            inputStream = openInputStream(context, imgUri);
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for " + imgUri);
                return null;
            }

            // Decode full bitmap (logo images are typically small picks from gallery)
            Bitmap source = BitmapFactory.decodeStream(inputStream);
            if (source == null) {
                Log.e(TAG, "BitmapFactory failed to decode stream");
                return null;
            }

            // Scale down to TARGET_SIZE × TARGET_SIZE
            Bitmap scaled = Bitmap.createScaledBitmap(source, TARGET_SIZE, TARGET_SIZE, true);
            source.recycle();

            // Prepare output directory
            File directory = new File("/sdcard/Evolution-X/" + FEATURE_PATH);
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
                scaled.recycle();
                return null;
            }

            // Remove stale copies first
            deleteOldFiles(directory);

            // Build unique filename
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File outFile = new File(directory, FILE_PREFIX + "_" + timestamp + ".png");

            // Write PNG
            outputStream = new FileOutputStream(outFile);
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                Log.e(TAG, "Failed to compress bitmap to PNG");
                scaled.recycle();
                return null;
            }
            scaled.recycle();

            Log.d(TAG, "Status bar logo saved: " + outFile.getAbsolutePath());
            return outFile.getAbsolutePath();

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "IO error: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory while processing logo image");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Handles the Google Photos content URI quirk, same pattern as ImageUtils. */
    private static InputStream openInputStream(Context context, Uri uri) throws IOException {
        String uriStr = uri.toString();
        if (uriStr.startsWith("content://com.google.android.apps.photos.contentprovider")) {
            List<String> segments = uri.getPathSegments();
            if (segments.size() > 2) {
                String mediaUriString = URLDecoder.decode(segments.get(2), StandardCharsets.UTF_8.name());
                return context.getContentResolver().openInputStream(Uri.parse(mediaUriString));
            }
            throw new FileNotFoundException("Cannot parse Google Photos URI: " + uriStr);
        }
        return context.getContentResolver().openInputStream(uri);
    }

    /** Deletes any previously saved logo PNG files from the folder. */
    private static void deleteOldFiles(File directory) {
        try {
            File[] files = directory.listFiles(
                    (dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(".png"));
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        Log.w(TAG, "Could not delete: " + f.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up old logo files: " + e.getMessage());
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }
}
