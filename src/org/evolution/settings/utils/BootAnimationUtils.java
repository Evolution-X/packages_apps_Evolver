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
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.util.evolution.PixelPropsUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

public class BootAnimationUtils {

    private static final String TAG = "BootAnimationUtils";
    private static final int DEFAULT_FRAME_DURATION = 1000 / 30;

    // Public style index constants — keep in sync with BOOT_ANIMATION_FILES order
    public static final int STYLE_DEFAULT            = 0;
    public static final int STYLE_GOOGLE             = 7;
    public static final int STYLE_GOOGLE_MONET       = 8;
    public static final int STYLE_CUSTOM             = 13;

    private static final String[] BOOT_ANIMATION_FILES = {
        "/product/media/bootanimation.zip",               // 0
        "/product/media/bootanimation_evo_reveal.zip",    // 1
        "/product/media/bootanimation_aokp.zip",          // 2
        "/product/media/bootanimation_cm.zip",            // 3
        "/product/media/bootanimation_ctos.zip",          // 4
        "/product/media/bootanimation_cyberpunk.zip",     // 5
        "/product/media/bootanimation_du.zip",            // 6
        "/product/media/bootanimation_google.zip",        // 7
        "/product/media/bootanimation_google_monet.zip",  // 8
        "/product/media/bootanimation_pac.zip",           // 9
        "/product/media/bootanimation_rr.zip",            // 10
        "/product/media/bootanimation_slim.zip",          // 11
        "/product/media/bootanimation_valorant.zip",      // 12
        "/data/misc/bootanim/bootanimation.zip",          // 13 (custom/user-picked)
    };

    /**
     * Returns true when this device is a genuine Pixel (Pixel 3+) and
     * boot animation overrides have no effect. Uses PixelPropsUtils which
     * checks ro.product.model and ro.soc.manufacturer.
     */
    public static boolean isPixelDevice() {
        if (PixelPropsUtils.isCustomForkBuild()) {
            return true;
        }

        return PixelPropsUtils.isSupportedPixelDevice();
    }

    public static int getBootAnimStyle() {
        return SystemProperties.getInt("persist.sys.bootanimation_style", 0);
    }

    public static String getSelectedBootAnimation() {
        final int style = getBootAnimStyle();
        if (style >= 0 && style < BOOT_ANIMATION_FILES.length) {
            return BOOT_ANIMATION_FILES[style];
        }
        return null;
    }

    /**
     * Returns true for styles that ship a single animated WebP/GIF at the
     * zip root rather than a folder of PNG frames.
     */
    public static boolean isAnimatedImageStyle(int style) {
        return style == STYLE_GOOGLE || style == STYLE_GOOGLE_MONET;
    }

    // -----------------------------------------------------------------------
    // Frame extraction (PNG/JPG based animations)
    // -----------------------------------------------------------------------

    public static AnimationDrawable getBootAnimationFrames(Context context) {
        AnimationDrawable animationDrawable = new AnimationDrawable();
        String path = getSelectedBootAnimation();
        if (path == null) return animationDrawable;

        File file = new File(path);
        if (!file.exists()) return animationDrawable;

        try (ZipFile zipFile = new ZipFile(file)) {
            int frameDuration = getFrameDuration(zipFile);
            List<String> partNames = getPartNames(zipFile);

            if (partNames.isEmpty()) {
                // Fallback: attempt to load "part0" directly
                loadFramesFromPart(context, zipFile, animationDrawable,
                        "part0", frameDuration, loadTrimData(zipFile, "part0"));
            } else {
                for (String partName : partNames) {
                    loadFramesFromPart(context, zipFile, animationDrawable,
                            partName, frameDuration, loadTrimData(zipFile, partName));
                }
            }
            animationDrawable.setOneShot(false);
        } catch (Exception e) {
            Log.e(TAG, "Error loading boot animation frames", e);
        }
        return animationDrawable;
    }

    // -----------------------------------------------------------------------
    // desc.txt parsing
    // -----------------------------------------------------------------------

    /** Returns the per-frame display duration in milliseconds derived from fps. */
    private static int getFrameDuration(ZipFile zipFile) {
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry != null) {
                try (InputStream is = zipFile.getInputStream(descEntry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line = reader.readLine(); // first line: "W H FPS"
                    if (line != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            int fps = Integer.parseInt(parts[2]);
                            if (fps > 0) return 1000 / fps;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading fps from desc.txt", e);
        }
        return DEFAULT_FRAME_DURATION;
    }

    /**
     * Parses desc.txt and returns ordered part folder names.
     * Part lines start with 'p' or 'c': "p <count> <pause> <folder>"
     */
    private static List<String> getPartNames(ZipFile zipFile) {
        List<String> parts = new ArrayList<>();
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry == null) return parts;
            try (InputStream is = zipFile.getInputStream(descEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    if (firstLine) { firstLine = false; continue; } // skip "W H FPS" header
                    line = line.trim();
                    if (line.startsWith("p ") || line.startsWith("c ")) {
                        String[] tokens = line.split("\\s+");
                        if (tokens.length >= 4) {
                            parts.add(tokens[3]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing part names from desc.txt", e);
        }
        return parts;
    }

    // -----------------------------------------------------------------------
    // Trim data
    // -----------------------------------------------------------------------

    private static List<Rect> loadTrimData(ZipFile zipFile, String partName) {
        List<Rect> trimRects = new ArrayList<>();
        try {
            ZipEntry trimEntry = zipFile.getEntry(partName + "/trim.txt");
            if (trimEntry == null) return trimRects;
            try (InputStream is = zipFile.getInputStream(trimEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Format: WxH+X+Y
                    String[] p = line.trim().split("[x+]");
                    if (p.length == 4) {
                        int w = Integer.parseInt(p[0].trim());
                        int h = Integer.parseInt(p[1].trim());
                        int x = Integer.parseInt(p[2].trim());
                        int y = Integer.parseInt(p[3].trim());
                        trimRects.add(new Rect(x, y, x + w, y + h));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading trim data for " + partName, e);
        }
        return trimRects;
    }

    // -----------------------------------------------------------------------
    // Frame loading
    // -----------------------------------------------------------------------

    private static void loadFramesFromPart(Context context, ZipFile zipFile,
            AnimationDrawable animationDrawable, String partName,
            int frameDuration, List<Rect> trimData) {
        try {
            Pattern framePattern = Pattern.compile(
                    Pattern.quote(partName) + "/.*\\.(png|jpg)$",
                    Pattern.CASE_INSENSITIVE);

            // Collect names first so we can sort them (ZipFile.entries() order is undefined)
            List<String> frameNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (framePattern.matcher(name).matches()) {
                    frameNames.add(name);
                }
            }
            Collections.sort(frameNames);

            int frameIndex = 0;
            for (String entryName : frameNames) {
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) continue;
                try (InputStream is = zipFile.getInputStream(entry)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap == null) {
                        Log.w(TAG, "Failed to decode frame: " + entryName);
                        frameIndex++;
                        continue;
                    }
                    if (frameIndex < trimData.size()) {
                        Rect r = trimData.get(frameIndex);
                        int left   = Math.max(0, r.left);
                        int top    = Math.max(0, r.top);
                        int width  = Math.min(r.width(),  bitmap.getWidth()  - left);
                        int height = Math.min(r.height(), bitmap.getHeight() - top);
                        if (width > 0 && height > 0) {
                            bitmap = Bitmap.createBitmap(bitmap, left, top, width, height);
                        }
                    }
                    animationDrawable.addFrame(
                            new BitmapDrawable(context.getResources(), bitmap),
                            frameDuration);
                    frameIndex++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading frames from " + partName, e);
        }
    }
}
