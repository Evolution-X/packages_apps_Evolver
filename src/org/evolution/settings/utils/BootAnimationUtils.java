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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.util.evolution.PixelPropsUtils;
import com.android.settings.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

public class BootAnimationUtils {

    private static final String TAG = "BootAnimationUtils";
    private static final int DEFAULT_FRAME_DURATION = 1000 / 30;
    private static final int PREVIEW_MAX_WIDTH = 240;
    // A preview keeps every decoded frame in memory. Frames are only skipped when the
    // decoded animation exceeds this budget.
    private static final long PREVIEW_MAX_BYTES = 64L * 1024 * 1024;
    // Used only when desc.txt has no usable size, so the frame size is unknown.
    private static final int PREVIEW_FALLBACK_MAX_FRAMES = 40;
    // Looped parts are unrolled into the preview, so a part that loops often is cut short.
    private static final int PREVIEW_MAX_LOOPS = 5;

    public static final String PROP_NAME = "persist.sys.bootanim.name";
    public static final String PROP_LEGACY_STYLE = "persist.sys.bootanimation_style";
    public static final String NAME_DEFAULT = "default";
    public static final String NAME_CUSTOM = "custom";
    public static final String CUSTOM_PATH = "/data/misc/bootanim/bootanimation.zip";

    private static final String MEDIA_DIR = "/product/media/";
    private static final String DEFAULT_PATH = MEDIA_DIR + "bootanimation.zip";
    private static final String NAMED_PREFIX = "bootanimation_";
    private static final String NAMED_SUFFIX = ".zip";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]+");

    // Order of the old persist.sys.bootanimation_style index, only used to read an index that
    // SystemServer has not turned into a name yet. Matches BOOT_ANIMATION_FILES in
    // BootAnimation.cpp.
    private static final String[] LEGACY_STYLE_NAMES = {
        NAME_DEFAULT, "evo_reveal", "aokp", "cm", "ctos", "cyberpunk", "du", "google", "pac",
        "rr", "slim", "valorant", NAME_CUSTOM,
    };

    /**
     * Returns true when this device is a genuine Pixel (Pixel 3+) and
     * boot animation overrides have no effect. Uses PixelPropsUtils which
     * checks ro.product.model and ro.soc.manufacturer.
     */
    public static boolean isPixelDevice() {
        return PixelPropsUtils.isSupportedPixelDevice();
    }

    /**
     * Returns true when the ROM was built without custom boot animations,
     * i.e. TARGET_INCLUDE_BOOT_ANIMATIONS := false in the device tree.
     */
    public static boolean isBootAnimationSelectorDisabled() {
        return !SystemProperties.getBoolean("ro.boot.custom_bootanim", true);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Name of the selected style. Falls back to the legacy index while no name has been set,
     * the same way BootAnimation.cpp resolves it.
     */
    public static String getCurrentName() {
        String name = SystemProperties.get(PROP_NAME, "");
        if (!name.isEmpty()) return name;
        int index = SystemProperties.getInt(PROP_LEGACY_STYLE, 0);
        return index >= 0 && index < LEGACY_STYLE_NAMES.length
                ? LEGACY_STYLE_NAMES[index] : NAME_DEFAULT;
    }

    /**
     * Style that will actually boot. BootAnimation.cpp falls back to the default animation when
     * the selected zip is missing, e.g. a style removed from the build or a deleted custom zip.
     */
    public static String getEffectiveName() {
        String name = getCurrentName();
        String path = getPathForName(name);
        return path != null && new File(path).isFile() ? name : NAME_DEFAULT;
    }

    /** Zip file for a style name, or null if the name is not valid. */
    public static String getPathForName(String name) {
        if (NAME_DEFAULT.equals(name)) return DEFAULT_PATH;
        if (NAME_CUSTOM.equals(name)) return CUSTOM_PATH;
        if (!isValidName(name)) return null;
        return MEDIA_DIR + NAMED_PREFIX + name + NAMED_SUFFIX;
    }

    /**
     * Styles to offer: default, the known styles that are installed (in the order of
     * themes_boot_animation_values), any other bootanimation_[name].zip found in /product/media,
     * and custom last. A new style only needs its zip in the build.
     */
    public static List<String> getStyleNames(Context context) {
        String[] known = context.getResources()
                .getStringArray(R.array.themes_boot_animation_values);
        List<String> names = new ArrayList<>();
        names.add(NAME_DEFAULT);
        for (String name : known) {
            if (NAME_DEFAULT.equals(name) || NAME_CUSTOM.equals(name)) continue;
            String path = getPathForName(name);
            if (path != null && new File(path).isFile()) names.add(name);
        }
        File[] files = new File(MEDIA_DIR).listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String fileName = file.getName();
                if (!fileName.startsWith(NAMED_PREFIX) || !fileName.endsWith(NAMED_SUFFIX)) {
                    continue;
                }
                String name = fileName.substring(NAMED_PREFIX.length(),
                        fileName.length() - NAMED_SUFFIX.length());
                if (isValidName(name) && !NAME_CUSTOM.equals(name) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        names.add(NAME_CUSTOM);
        return names;
    }

    /** Display name of a style; styles without an entry get their file name tidied up. */
    public static String getLabel(Context context, String name) {
        String[] values = context.getResources()
                .getStringArray(R.array.themes_boot_animation_values);
        String[] entries = context.getResources()
                .getStringArray(R.array.themes_boot_animation_entries);
        for (int i = 0; i < values.length && i < entries.length; i++) {
            if (values[i].equals(name)) return entries[i];
        }
        String text = name.replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // -----------------------------------------------------------------------
    // Frame extraction (PNG/JPG based animations)
    // -----------------------------------------------------------------------

    public static AnimationDrawable getBootAnimationFramesFromPath(Context context, String path) {
        AnimationDrawable animationDrawable = new AnimationDrawable();
        File file = new File(path);
        if (!file.exists()) return animationDrawable;
        try (ZipFile zipFile = new ZipFile(file)) {
            int frameDuration = getFrameDuration(zipFile);
            int[] animSize = getAnimationSize(zipFile);
            List<Part> parts = getParts(zipFile);
            if (parts.isEmpty()) {
                parts.add(new Part("part0"));
            }
            List<List<String>> partFrames = new ArrayList<>();
            int total = 0;
            for (Part part : parts) {
                List<String> frames = getFrameNames(zipFile, part.name);
                partFrames.add(frames);
                total += frames.size();
            }
            // Keep every stride-th frame only when the decoded frames exceed the byte budget,
            // and stretch its duration to keep the speed
            int stride;
            if (animSize[0] > 0 && animSize[1] > 0) {
                float previewScale = Math.min(1f, PREVIEW_MAX_WIDTH / (float) animSize[0]);
                long frameBytes = 4L * Math.round(animSize[0] * previewScale)
                        * Math.round(animSize[1] * previewScale);
                long maxFrames = Math.max(1L, PREVIEW_MAX_BYTES / frameBytes);
                stride = (int) Math.max(1L, (total + maxFrames - 1) / maxFrames);
            } else {
                stride = Math.max(1, (total + PREVIEW_FALLBACK_MAX_FRAMES - 1)
                        / PREVIEW_FALLBACK_MAX_FRAMES);
            }
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                List<Drawable> frames = new ArrayList<>();
                loadFramesFromPart(context, zipFile, frames, partFrames.get(i), stride,
                        loadTrimData(zipFile, part.name), animSize);
                if (frames.isEmpty()) continue;
                if (part.background != null) {
                    for (int f = 0; f < frames.size(); f++) {
                        frames.set(f, new PartFrame(frames.get(f), part.background));
                    }
                }
                // A count of 0 loops until boot completes, which the preview plays once. The
                // pause is in frames of the real fps and follows every play of the part.
                int plays = part.count <= 0 ? 1 : Math.min(part.count, PREVIEW_MAX_LOOPS);
                for (int play = 0; play < plays; play++) {
                    for (Drawable frame : frames) {
                        animationDrawable.addFrame(frame, frameDuration * stride);
                    }
                    if (part.pause > 0) {
                        animationDrawable.addFrame(frames.get(frames.size() - 1),
                                part.pause * frameDuration);
                    }
                }
            }
            animationDrawable.setOneShot(false);
        } catch (Exception e) {
            Log.e(TAG, "Error loading boot animation frames from " + path, e);
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

    /** Returns {width, height} from the first desc.txt line, or {0, 0} if unreadable. */
    public static int[] getAnimationSize(ZipFile zipFile) {
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry != null) {
                try (InputStream is = zipFile.getInputStream(descEntry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line = reader.readLine(); // first line: "W H FPS"
                    if (line != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            return new int[] {
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1])
                            };
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading size from desc.txt", e);
        }
        return new int[] {0, 0};
    }

    /** A preview frame that knows the background colour of its part, which the preview shows. */
    public static class PartFrame extends LayerDrawable {
        public final int background;

        PartFrame(Drawable frame, int background) {
            super(new Drawable[] { frame });
            this.background = background;
        }
    }

    /** A part line of desc.txt: "p|c|f <count> <pause> <folder> [fade] [#RRGGBB]". */
    private static class Part {
        final String name;
        int count = 1;
        int pause = 0;
        Integer background;

        Part(String name) {
            this.name = name;
        }
    }

    /** Parses desc.txt and returns the parts in play order. */
    private static List<Part> getParts(ZipFile zipFile) {
        List<Part> parts = new ArrayList<>();
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry == null) return parts;
            try (InputStream is = zipFile.getInputStream(descEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                boolean firstLine = true;
                while ((line = reader.readLine()) != null) {
                    if (firstLine) { firstLine = false; continue; } // skip "W H FPS" header
                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length < 4 || !tokens[0].matches("[pcf]")) continue;
                    try {
                        Part part = new Part(tokens[3]);
                        part.count = Integer.parseInt(tokens[1]);
                        part.pause = Integer.parseInt(tokens[2]);
                        // 'f' parts carry a fade frame count before the colour
                        int colorIndex = "f".equals(tokens[0]) ? 5 : 4;
                        if (tokens.length > colorIndex
                                && tokens[colorIndex].matches("#[0-9a-fA-F]{6}")) {
                            part.background = Color.parseColor(tokens[colorIndex]);
                        }
                        parts.add(part);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Skipping desc.txt line: " + line);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing parts from desc.txt", e);
        }
        return parts;
    }

    // -----------------------------------------------------------------------
    // Trim data
    // -----------------------------------------------------------------------

    public static List<Rect> loadTrimData(ZipFile zipFile, String partName) {
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

    /** Frame entries of a part, sorted by name (ZipFile.entries() order is undefined). */
    private static List<String> getFrameNames(ZipFile zipFile, String partName) {
        Pattern framePattern = Pattern.compile(
                Pattern.quote(partName) + "/.*\\.(png|jpg)$",
                Pattern.CASE_INSENSITIVE);
        List<String> frameNames = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (framePattern.matcher(name).matches()) {
                frameNames.add(name);
            }
        }
        Collections.sort(frameNames);
        return frameNames;
    }

    private static void loadFramesFromPart(Context context, ZipFile zipFile,
            List<Drawable> out, List<String> frameNames,
            int stride, List<Rect> trimData, int[] animSize) {
        try {
            final boolean hasSize = animSize[0] > 0 && animSize[1] > 0;
            final float scale = hasSize
                    ? Math.min(1f, PREVIEW_MAX_WIDTH / (float) animSize[0]) : 1f;
            // Decode at a fraction of the size instead of full frames; scale covers the rest.
            int sample = 1;
            while (hasSize && animSize[0] / (sample * 2) >= PREVIEW_MAX_WIDTH) sample *= 2;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final float remaining = Math.min(1f, scale * sample);

            // trim.txt has one line per frame of the part, so skipped frames still count
            for (int frameIndex = 0; frameIndex < frameNames.size(); frameIndex += stride) {
                String entryName = frameNames.get(frameIndex);
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) continue;
                try (InputStream is = zipFile.getInputStream(entry)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
                    if (bitmap == null) {
                        Log.w(TAG, "Failed to decode frame: " + entryName);
                        continue;
                    }
                    if (hasSize && frameIndex < trimData.size()) {
                        // trim.txt is a placement offset for an already-trimmed PNG
                        // (see BootAnimation.cpp), not a crop rect: composite it.
                        Rect r = trimData.get(frameIndex);
                        Bitmap composed = Bitmap.createBitmap(
                                Math.round(animSize[0] * scale),
                                Math.round(animSize[1] * scale),
                                Bitmap.Config.ARGB_8888);
                        new Canvas(composed).drawBitmap(bitmap, null,
                                new RectF(r.left * scale, r.top * scale,
                                        r.right * scale, r.bottom * scale), null);
                        bitmap.recycle();
                        bitmap = composed;
                    } else if (remaining < 1f) {
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                                Math.max(1, Math.round(bitmap.getWidth() * remaining)),
                                Math.max(1, Math.round(bitmap.getHeight() * remaining)), true);
                        if (scaled != bitmap) bitmap.recycle();
                        bitmap = scaled;
                    }
                    out.add(new BitmapDrawable(context.getResources(), bitmap));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading boot animation frames", e);
        }
    }
}
