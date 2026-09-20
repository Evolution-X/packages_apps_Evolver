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
        Bitmap source = null;
        Bitmap scaled = null;

        try {
            source = decodeSampledBitmap(context, imgUri, TARGET_SIZE, TARGET_SIZE);
            if (source == null) {
                Log.e(TAG, "BitmapFactory failed to decode stream");
                return null;
            }

            final float scale = Math.min(
                    TARGET_SIZE / (float) source.getWidth(),
                    TARGET_SIZE / (float) source.getHeight());
            final int width = Math.max(1, Math.round(source.getWidth() * scale));
            final int height = Math.max(1, Math.round(source.getHeight() * scale));
            scaled = (width == source.getWidth() && height == source.getHeight())
                    ? source
                    : Bitmap.createScaledBitmap(source, width, height, true);

            File directory = new File("/sdcard/Evolution-X/" + FEATURE_PATH);
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            File outFile = new File(directory, FILE_PREFIX + "_" + timestamp + ".png");

            boolean compressed;
            try (FileOutputStream outputStream = new FileOutputStream(outFile)) {
                compressed = scaled.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                outputStream.flush();
            }

            if (!compressed) {
                Log.e(TAG, "Failed to compress bitmap to PNG");
                if (!outFile.delete()) {
                    Log.w(TAG, "Could not delete incomplete logo file: " + outFile.getName());
                }
                return null;
            }

            deleteOldFiles(directory, outFile);
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
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (source != null && !source.isRecycled()) {
                source.recycle();
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Bitmap decodeSampledBitmap(
            Context context, Uri uri, int reqWidth, int reqHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = openInputStream(context, uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= reqWidth
                && bounds.outHeight / (sampleSize * 2) >= reqHeight) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sampleSize;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = openInputStream(context, uri)) {
            if (input == null) return null;
            return BitmapFactory.decodeStream(input, null, decode);
        }
    }

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

    /** Deletes previously saved logo PNG files while preserving the new one. */
    private static void deleteOldFiles(File directory, File keep) {
        try {
            File[] files = directory.listFiles(
                    (dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(".png"));
            if (files != null) {
                for (File file : files) {
                    if (!file.equals(keep) && !file.delete()) {
                        Log.w(TAG, "Could not delete: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up old logo files: " + e.getMessage());
        }
    }
}
