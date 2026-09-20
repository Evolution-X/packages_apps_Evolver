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
package org.evolution.settings.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

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
import java.util.Arrays;

public class MediaUtils {
    private static final String TAG = "MediaUtils";
    private static final int BUFFER_SIZE = 8192;
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;
    private static final String PREFS_NAME = "video_wallpaper_prefs";
    private static final String KEY_WALLPAPER_PATH = "current_wallpaper_path";
    private static final String STORAGE_ROOT = "/sdcard/Evolution-X";
    private static final String LEGACY_STORAGE_ROOT = "/sdcard/Lunaris-OS";

    private static final List<String> SUPPORTED_VIDEO_FORMATS = Arrays.asList("mp4");
    private static final List<String> SUPPORTED_IMAGE_FORMATS = Arrays.asList("gif", "webp");

    public static String saveMediaToWallpaperStorage(Context context, Uri mediaUri) {
        return saveMediaToExternalStorage(context, mediaUri, "Wallpapers", "wallpaper");
    }

    public static String saveMediaToExternalStorage(
            Context context, Uri mediaUri, String featurePath, String filePrefix) {
        if (context == null || mediaUri == null || featurePath == null || filePrefix == null) {
            Log.e(TAG, "Invalid media import arguments");
            return null;
        }

        File outputFile = null;
        try {
            String extension = getFileExtension(context, mediaUri);
            if (!isValidWallpaperFormat(extension)) {
                Log.e(TAG, "Unsupported file format: " + extension);
                return null;
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            String fileName = filePrefix + "_" + timeStamp + extension;
            File directory = new File(STORAGE_ROOT, featurePath);
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
                return null;
            }

            outputFile = new File(directory, fileName);
            long bytesCopied;
            try (InputStream inputStream = getInputStreamFromUri(context, mediaUri)) {
                if (inputStream == null) {
                    Log.e(TAG, "Failed to get input stream from URI");
                    return null;
                }
                try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    bytesCopied = copyStreamWithLimit(
                            inputStream, outputStream, MAX_FILE_SIZE);
                }
            }

            if (bytesCopied < 0L) {
                Log.e(TAG, "File size exceeds maximum allowed size");
                outputFile.delete();
                return null;
            }

            deleteOldFiles(directory, filePrefix, outputFile);
            String absolutePath = outputFile.getAbsolutePath();
            saveWallpaperPath(context, absolutePath);

            Log.d(TAG, "Media saved successfully: " + absolutePath
                    + " (" + bytesCopied + " bytes)");
            return absolutePath;

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "IO error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
        }

        if (outputFile != null && outputFile.exists() && !outputFile.delete()) {
            Log.w(TAG, "Failed to delete incomplete media: " + outputFile.getAbsolutePath());
        }
        return null;
    }

    private static void saveWallpaperPath(Context context, String path) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_WALLPAPER_PATH, path).apply();
            Log.d(TAG, "Saved wallpaper path to SharedPreferences: " + path);
        } catch (Exception e) {
            Log.e(TAG, "Error saving wallpaper path: " + e.getMessage());
        }
    }

    public static String getWallpaperPath(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String path = prefs.getString(KEY_WALLPAPER_PATH, null);
            Log.d(TAG, "Retrieved wallpaper path: " + path);
            return path;
        } catch (Exception e) {
            Log.e(TAG, "Error getting wallpaper path: " + e.getMessage());
            return null;
        }
    }

    public static File getCurrentWallpaperFile(Context context) {
        String savedPath = getWallpaperPath(context);
        if (savedPath != null) {
            File file = new File(savedPath);
            if (file.exists()) {
                Log.d(TAG, "Found wallpaper from SharedPreferences: " + savedPath);
                return file;
            }
        }

        File wallpaper = findNewestWallpaper(new File(STORAGE_ROOT, "Wallpapers"));
        if (wallpaper == null) {
            // Keep compatibility with files saved by older builds that still
            // used the upstream Lunaris directory name.
            wallpaper = findNewestWallpaper(new File(LEGACY_STORAGE_ROOT, "Wallpapers"));
        }

        if (wallpaper != null) {
            Log.d(TAG, "Found wallpaper from directory scan: " + wallpaper.getAbsolutePath());
            saveWallpaperPath(context, wallpaper.getAbsolutePath());
            return wallpaper;
        }

        Log.d(TAG, "No wallpaper file found");
        return null;
    }

    private static File findNewestWallpaper(File directory) {
        if (!directory.exists()) return null;

        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return name.startsWith("wallpaper") &&
                    (lower.endsWith(".mp4") || lower.endsWith(".gif") || lower.endsWith(".webp"));
        });
        if (files == null || files.length == 0) return null;

        Arrays.sort(files, (first, second) ->
                Long.compare(second.lastModified(), first.lastModified()));
        return files[0];
    }

    public static boolean deleteCurrentWallpaper(Context context) {
        File wallpaperFile = getCurrentWallpaperFile(context);
        if (wallpaperFile != null && wallpaperFile.exists()) {
            boolean deleted = wallpaperFile.delete();
            if (deleted) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().remove(KEY_WALLPAPER_PATH).apply();
                Log.d(TAG, "Deleted wallpaper: " + wallpaperFile.getAbsolutePath());
            }
            return deleted;
        }
        return false;
    }

    public static boolean isVideoFormat(String filePath) {
        if (filePath == null) return false;
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_VIDEO_FORMATS.contains(extension);
    }

    public static boolean isAnimatedImageFormat(String filePath) {
        if (filePath == null) return false;
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_IMAGE_FORMATS.contains(extension);
    }

    private static boolean isValidWallpaperFormat(String extension) {
        if (extension == null || extension.isEmpty()) return false;
        String ext = extension.toLowerCase(Locale.ROOT).replace(".", "");
        return SUPPORTED_VIDEO_FORMATS.contains(ext) || SUPPORTED_IMAGE_FORMATS.contains(ext);
    }

    private static InputStream getInputStreamFromUri(Context context, Uri uri) throws IOException {
        if (uri.toString().startsWith("content://com.google.android.apps.photos.contentprovider")) {
            List<String> segments = uri.getPathSegments();
            if (segments.size() > 2) {
                String mediaUriString = URLDecoder.decode(segments.get(2), StandardCharsets.UTF_8.name());
                Uri mediaUri = Uri.parse(mediaUriString);
                return context.getContentResolver().openInputStream(mediaUri);
            } else {
                throw new FileNotFoundException("Failed to parse Google Photos content URI");
            }
        } else {
            return context.getContentResolver().openInputStream(uri);
        }
    }

    private static long copyStreamWithLimit(InputStream input, FileOutputStream output,
                                           long maxSize) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalBytes = 0;

        while ((bytesRead = input.read(buffer)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > maxSize) {
                return -1;
            }
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
        return totalBytes;
    }

    private static void deleteOldFiles(File directory, String filePrefix, File keep) {
        try {
            File[] files = directory.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return name.startsWith(filePrefix) &&
                       (lower.endsWith(".mp4") || lower.endsWith(".gif") ||
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
        String extension = null;

        if ("content".equals(uri.getScheme())) {
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null) {
                extension = getExtensionFromMimeType(mimeType);
            }
        }

        if (extension == null && uri.getPath() != null) {
            extension = getExtensionFromPath(uri.getPath());
        }

        return extension;
    }

    private static String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return null;

        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("mp4") || lower.equals("video/mp4")) {
            return ".mp4";
        } else if (lower.contains("gif") || lower.equals("image/gif")) {
            return ".gif";
        } else if (lower.contains("webp") || lower.equals("image/webp")) {
            return ".webp";
        }

        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension != null) {
            return "." + extension;
        }

        return null;
    }

    private static String getExtensionFromPath(String path) {
        if (path == null) return null;

        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".mp4")) {
            return ".mp4";
        } else if (lowerPath.endsWith(".gif")) {
            return ".gif";
        } else if (lowerPath.endsWith(".webp")) {
            return ".webp";
        }

        return null;
    }

}
