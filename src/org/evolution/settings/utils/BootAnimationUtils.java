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
package org.evolution.settings.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;
import android.graphics.BitmapFactory;

public class BootAnimationUtils {

    private static final String TAG = "BootAnimationUtils";
    private static final int DEFAULT_FRAME_DURATION = 1000 / 30;

    // List of bootanimation files corresponding to each style
    private static final String[] BOOT_ANIMATION_FILES = {
        "/product/media/bootanimation.zip",
        "/product/media/bootanimation_cyberpunk.zip",
        "/product/media/bootanimation_google.zip",
        "/product/media/bootanimation_google_monet.zip",
        "/product/media/bootanimation_valorant.zip",
        "/data/misc/bootanim/bootanimation.zip",
    };

    public static AnimationDrawable getBootAnimationFrames(Context context) {
        AnimationDrawable animationDrawable = new AnimationDrawable();
        String selectedBootAnimation = getSelectedBootAnimation();
        if (selectedBootAnimation != null) {
            File bootAnimationFile = new File(selectedBootAnimation);
            if (bootAnimationFile.exists()) {
                try (ZipFile zipFile = new ZipFile(bootAnimationFile)) {
                    int frameDuration = getFrameDuration(zipFile);
                    // Load frames from all available parts
                    for (int i = 0; i <= 4; i++) {
                        String partName = "part" + i;
                        if (hasPart(zipFile, partName)) {
                            boolean isPType = isPTypePart(zipFile, partName);
                            List<Rect> trimData = loadTrimData(zipFile, partName);
                            loadFramesFromPart(context, zipFile, animationDrawable, partName, frameDuration, isPType, trimData);
                        }
                    }
                    animationDrawable.setOneShot(false); // Loop the animation
                } catch (Exception e) {
                    Log.e(TAG, "Error loading boot animation frames", e);
                }
            }
        }
        return animationDrawable;
    }

    private static String getSelectedBootAnimation() {
        int style = SystemProperties.getInt("persist.sys.bootanimation_style", 0);
        if (style >= 0 && style < BOOT_ANIMATION_FILES.length) {
            return BOOT_ANIMATION_FILES[style];
        }
        return null;
    }

    private static int getFrameDuration(ZipFile zipFile) {
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry != null) {
                InputStream is = zipFile.getInputStream(descEntry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line = reader.readLine();
                if (line != null) {
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        int fps = Integer.parseInt(parts[2]);
                        return 1000 / fps;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading desc.txt", e);
        }
        return DEFAULT_FRAME_DURATION;
    }

    private static boolean hasPart(ZipFile zipFile, String partName) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith(partName + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPTypePart(ZipFile zipFile, String partName) {
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry != null) {
                InputStream is = zipFile.getInputStream(descEntry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("p ") && line.contains(partName)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error determining part type", e);
        }
        return false;
    }

    private static List<Rect> loadTrimData(ZipFile zipFile, String partName) {
        List<Rect> trimRects = new ArrayList<>();
        try {
            ZipEntry trimEntry = zipFile.getEntry(partName + "/trim.txt");
            if (trimEntry != null) {
                InputStream is = zipFile.getInputStream(trimEntry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("[x+]");
                    if (parts.length == 4) {
                        int width = Integer.parseInt(parts[0]);
                        int height = Integer.parseInt(parts[1]);
                        int x = Integer.parseInt(parts[2]);
                        int y = Integer.parseInt(parts[3]);
                        trimRects.add(new Rect(x, y, x + width, y + height));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading trim data", e);
        }
        return trimRects;
    }

    private static void loadFramesFromPart(Context context, ZipFile zipFile, AnimationDrawable animationDrawable, String partName, int frameDuration, boolean isPType, List<Rect> trimData) {
        try {
            Pattern pngPattern = Pattern.compile(partName + "/.*\\.png$");
            Pattern jpgPattern = Pattern.compile(partName + "/.*\\.jpg$");
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int frameIndex = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (pngPattern.matcher(entryName).matches() || jpgPattern.matcher(entryName).matches()) {
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Bitmap bitmap = BitmapFactory.decodeStream(is);

                        // Apply trim if available and within bounds
                        if (frameIndex < trimData.size()) {
                            Rect trimRect = trimData.get(frameIndex);

                            // Ensure trimRect is within the bounds of the bitmap
                            int adjustedWidth = Math.min(trimRect.width(), bitmap.getWidth() - trimRect.left);
                            int adjustedHeight = Math.min(trimRect.height(), bitmap.getHeight() - trimRect.top);

                            if (adjustedWidth > 0 && adjustedHeight > 0) {
                                bitmap = Bitmap.createBitmap(bitmap, trimRect.left, trimRect.top, adjustedWidth, adjustedHeight);
                            } else {
                                Log.w(TAG, "Trim rectangle exceeds bitmap dimensions, skipping trim for frame " + frameIndex);
                            }
                        }

                        Drawable frame = new BitmapDrawable(context.getResources(), bitmap);
                        int adjustedFrameDuration = isPType ? frameDuration * 3 : frameDuration;
                        animationDrawable.addFrame(frame, adjustedFrameDuration);
                        frameIndex++;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading frames from " + partName, e);
        }
    }
}
