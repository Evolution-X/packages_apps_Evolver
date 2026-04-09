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
    
    public static String saveImageToInternalStorage(Context context, Uri imgUri, String featurePath, String filePrefix) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        
        try {
            inputStream = getInputStreamFromUri(context, imgUri);
            if (inputStream == null) {
                Log.e(TAG, "Failed to get input stream from URI");
                return null;
            }

            String extension = getFileExtension(context, imgUri);
            boolean isGif = extension.equalsIgnoreCase(".gif");
            boolean isWebp = extension.equalsIgnoreCase(".webp");

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String imageFileName = filePrefix + "_" + timeStamp + extension;

            File directory = new File("/sdcard/Evolution-X/" + featurePath);
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
                return null;
            }

            deleteOldFiles(directory, filePrefix);

            File outputFile = new File(directory, imageFileName);

            if (isGif || isWebp) {
                outputStream = new FileOutputStream(outputFile);
                copyStream(inputStream, outputStream);
            } else {
                Bitmap bitmap = null;
                try {
                    bitmap = BitmapFactory.decodeStream(inputStream);
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to decode bitmap from stream");
                        return null;
                    }
                    
                    outputStream = new FileOutputStream(outputFile);
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)) {
                        Log.e(TAG, "Failed to compress bitmap");
                        return null;
                    }
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            Log.d(TAG, "Image saved successfully: " + outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
            
        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found: " + e.getMessage());
            return null;
        } catch (IOException e) {
            Log.e(TAG, "IO error: " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
            return null;
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
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

    private static void copyStream(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
    }

    private static void deleteOldFiles(File directory, String filePrefix) {
        try {
            File[] files = directory.listFiles((dir, name) -> 
                    name.startsWith(filePrefix) && 
                    (name.endsWith(".png") || name.endsWith(".gif") || 
                     name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                     name.endsWith(".webp")));
            
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) {
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

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }
}
