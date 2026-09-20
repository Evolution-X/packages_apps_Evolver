/*
 * Copyright (C) 2023-2024 the risingOS Android Project
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

public class ImageUtils {
    private static final String TAG = "ImageUtils";
    private static final int BUFFER_SIZE = 8192;
    private static final long MAX_ANIMATED_IMAGE_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_STATIC_DIMENSION = 4096;

    public static String saveImageToInternalStorage(
            Context context, Uri imgUri, String featurePath, String filePrefix) {
        if (context == null || imgUri == null || featurePath == null || filePrefix == null) {
            return null;
        }

        File outputFile = null;
        try {
            String sourceExtension = getFileExtension(context, imgUri);
            boolean isGif = ".gif".equalsIgnoreCase(sourceExtension);
            boolean isWebp = ".webp".equalsIgnoreCase(sourceExtension);
            String outputExtension = (isGif || isWebp) ? sourceExtension : ".png";

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            String imageFileName = filePrefix + "_" + timeStamp + outputExtension;

            File directory = new File("/sdcard/Evolution-X/" + featurePath);
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
                return null;
            }

            outputFile = new File(directory, imageFileName);
            if (isGif || isWebp) {
                try (InputStream input = getInputStreamFromUri(context, imgUri);
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    if (input == null) {
                        Log.e(TAG, "Failed to get input stream from URI");
                        outputFile.delete();
                        return null;
                    }
                    long copied = copyStreamWithLimit(
                            input, output, MAX_ANIMATED_IMAGE_BYTES);
                    if (copied < 0L) {
                        Log.e(TAG, "Animated image exceeds size limit");
                        outputFile.delete();
                        return null;
                    }
                }
            } else {
                Bitmap bitmap = decodeSampledBitmap(
                        context, imgUri, MAX_STATIC_DIMENSION, MAX_STATIC_DIMENSION);
                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from stream");
                    return null;
                }
                try {
                    try (FileOutputStream output = new FileOutputStream(outputFile)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            Log.e(TAG, "Failed to compress bitmap");
                            outputFile.delete();
                            return null;
                        }
                        output.flush();
                    }
                } finally {
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            deleteOldFiles(directory, filePrefix, outputFile);
            Log.d(TAG, "Image saved successfully: " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "IO error: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
        }

        if (outputFile != null && outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "Failed to delete incomplete image: " + outputFile.getAbsolutePath());
        }
        return null;
    }

    private static Bitmap decodeSampledBitmap(
            Context context, Uri uri, int reqWidth, int reqHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getInputStreamFromUri(context, uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > reqWidth
                || bounds.outHeight / sampleSize > reqHeight) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sampleSize;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getInputStreamFromUri(context, uri)) {
            if (input == null) return null;
            return BitmapFactory.decodeStream(input, null, decode);
        }
    }

    private static InputStream getInputStreamFromUri(Context context, Uri imgUri) throws IOException {
        if (imgUri.toString().startsWith("content://com.google.android.apps.photos.contentprovider")) {
            List<String> segments = imgUri.getPathSegments();
            if (segments.size() > 2) {
                String mediaUriString = URLDecoder.decode(segments.get(2), StandardCharsets.UTF_8.name());
                Uri mediaUri = Uri.parse(mediaUriString);
                return context.getContentResolver().openInputStream(mediaUri);
            } else {
                throw new FileNotFoundException("Failed to parse Google Photos content URI");
            }
        } else {
            return context.getContentResolver().openInputStream(imgUri);
        }
    }

    private static long copyStreamWithLimit(
            InputStream input, FileOutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long total = 0L;
        while ((bytesRead = input.read(buffer)) != -1) {
            total += bytesRead;
            if (total > maxBytes) {
                return -1L;
            }
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
        return total;
    }

    private static void deleteOldFiles(File directory, String filePrefix, File keep) {
        try {
            File[] files = directory.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return name.startsWith(filePrefix) &&
                        (lower.endsWith(".png") || lower.endsWith(".gif") ||
                         lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                         lower.endsWith(".webp"));
            });

            if (files != null) {
                for (File file : files) {
                    if (!file.equals(keep) && !file.delete()) {
                        Log.w(TAG, "Failed to delete old file: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting old files: " + e.getMessage());
        }
    }

    private static String getFileExtension(Context context, Uri uri) {
        String extension = ".png";

        if ("content".equals(uri.getScheme())) {
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null) {
                extension = getExtensionFromMimeType(mimeType);
            }
        }

        if (".png".equals(extension) && uri.getPath() != null) {
            extension = getExtensionFromPath(uri.getPath());
        }

        return extension;
    }

    private static String getExtensionFromMimeType(String mimeType) {
        mimeType = mimeType.toLowerCase(Locale.ROOT);
        if (mimeType.contains("gif")) {
            return ".gif";
        } else if (mimeType.contains("webp")) {
            return ".webp";
        } else if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
            return ".jpg";
        } else if (mimeType.contains("png")) {
            return ".png";
        }
        return ".png";
    }

    private static String getExtensionFromPath(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".gif")) {
            return ".gif";
        } else if (lowerPath.endsWith(".webp")) {
            return ".webp";
        } else if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            return ".jpg";
        } else if (lowerPath.endsWith(".png")) {
            return ".png";
        }
        return ".png";
    }

}
