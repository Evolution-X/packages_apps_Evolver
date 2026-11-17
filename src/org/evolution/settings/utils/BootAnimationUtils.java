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
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Pattern;

public class BootAnimationUtils {

    private static final String TAG = "BootAnimationUtils";
    private static final int DEFAULT_FRAME_DURATION = 1000 / 30;
    // Small in-card preview
    public static final int PREVIEW_MAX_WIDTH = 240;
    // A preview keeps every decoded frame in memory. Frames are only skipped when the
    // decoded animation exceeds this budget.
    private static final long PREVIEW_MAX_BYTES = 64L * 1024 * 1024;
    // Fullscreen preview: larger frames, proportionally larger budget
    private static final int FULLSCREEN_PREVIEW_MAX_WIDTH = 720;
    private static final long FULLSCREEN_PREVIEW_MAX_BYTES = 192L * 1024 * 1024;
    // Used only when desc.txt has no usable size, so the frame size is unknown.
    private static final int PREVIEW_FALLBACK_MAX_FRAMES = 40;
    // Looped parts are unrolled into the preview, so a part that loops often is cut short.
    private static final int PREVIEW_MAX_LOOPS = 5;

    public static final String PROP_NAME = "persist.sys.bootanim.name";
    public static final String PROP_LEGACY_STYLE = "persist.sys.bootanimation_style";
    public static final String NAME_DEFAULT = "default";
    public static final String NAME_CUSTOM = "custom";
    public static final String NAME_RANDOM = "random";
    public static final String CUSTOM_PATH = "/data/misc/bootanim/bootanimation.zip";
    // Shutdown animation selection, resolved by BootAnimation.cpp the same way
    public static final String SHUTDOWN_PROP = "persist.sys.bootanim.shutdown";
    public static final String SHUTDOWN_CUSTOM_PATH = "/data/misc/bootanim/shutdownanimation.zip";

    private static final String MEDIA_DIR = "/product/media/";
    private static final String DEFAULT_PATH = MEDIA_DIR + "bootanimation.zip";
    private static final String NAMED_PREFIX = "bootanimation_";
    private static final String NAMED_SUFFIX = ".zip";
    private static final String SHUTDOWN_NAMED_PREFIX = "shutdownanimation_";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]+");
    private static final Pattern FRAME_ENTRY_PATTERN = Pattern.compile(
            "[^/]+/.+\\.(png|jpg)$", Pattern.CASE_INSENSITIVE);

    // Order of the old persist.sys.bootanimation_style index, only used to read an index that
    // SystemServer has not turned into a name yet. Matches LEGACY_BOOTANIM_NAMES in
    // SystemServer.java.
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
     * Style that will actually boot. "random" is always valid: BootAnimation.cpp picks an
     * installed zip on every boot. Otherwise BootAnimation.cpp falls back to the default
     * animation when the selected zip is missing, e.g. a style removed from the build or a
     * deleted custom zip.
     */
    public static String getEffectiveName() {
        String name = getCurrentName();
        if (NAME_RANDOM.equals(name)) return NAME_RANDOM;
        String path = getPathForName(name);
        return path != null && new File(path).isFile() ? name : NAME_DEFAULT;
    }

    /** Zip file for a style name, or null if the name has no file of its own. */
    public static String getPathForName(String name) {
        if (NAME_DEFAULT.equals(name)) return DEFAULT_PATH;
        if (NAME_CUSTOM.equals(name)) return CUSTOM_PATH;
        // "random" resolves to an installed zip on every boot; there is no file to preview.
        if (NAME_RANDOM.equals(name)) return null;
        if (!isValidName(name)) return null;
        return MEDIA_DIR + NAMED_PREFIX + name + NAMED_SUFFIX;
    }

    /**
     * Styles to offer: default, the known styles that are installed (in the order of
     * themes_boot_animation_values), any other bootanimation_[name].zip found in /product/media,
     * then random and custom last. A new style only needs its zip in the build.
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
                if (isValidName(name) && !NAME_CUSTOM.equals(name) && !NAME_RANDOM.equals(name)
                        && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        names.add(NAME_RANDOM);
        names.add(NAME_CUSTOM);
        return names;
    }

    /** A random installed style to preview while "random" is selected, engine semantics. */
    public static String pickRandomPreviewName(Context context) {
        List<String> candidates = new ArrayList<>();
        for (String name : getStyleNames(context)) {
            if (NAME_RANDOM.equals(name) || NAME_CUSTOM.equals(name)) continue;
            String path = getPathForName(name);
            if (path != null && new File(path).isFile()) candidates.add(name);
        }
        if (candidates.isEmpty()) return NAME_DEFAULT;
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    /** Display name of a style; styles without an entry get their file name tidied up. */
    public static String getLabel(Context context, String name) {
        if (NAME_RANDOM.equals(name)) {
            return context.getString(R.string.boot_animation_style_random);
        }
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
    // Shutdown animation helpers (BootAnimation.cpp reads the same properties)
    // -----------------------------------------------------------------------

    /** True when the build ships a stock shutdown animation. */
    public static boolean hasShutdownDefault() {
        return new File("/product/media/shutdownanimation.zip").isFile()
                || new File("/oem/media/shutdownanimation.zip").isFile()
                || new File("/system/media/shutdownanimation.zip").isFile();
    }

    /** Shutdown styles: the stock animation, any shutdownanimation_[name].zip, then custom. */
    public static List<String> getShutdownStyleNames() {
        List<String> names = new ArrayList<>();
        if (hasShutdownDefault()) names.add(NAME_DEFAULT);
        File[] files = new File(MEDIA_DIR).listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String fileName = file.getName();
                if (!fileName.startsWith(SHUTDOWN_NAMED_PREFIX) || !fileName.endsWith(NAMED_SUFFIX)) {
                    continue;
                }
                String name = fileName.substring(SHUTDOWN_NAMED_PREFIX.length(),
                        fileName.length() - NAMED_SUFFIX.length());
                if (isValidName(name) && !NAME_CUSTOM.equals(name) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        if (new File(SHUTDOWN_CUSTOM_PATH).isFile()) names.add(NAME_CUSTOM);
        return names;
    }

    /** Zip file of a shutdown style, or null when the style has no single file. */
    public static String getShutdownPathForName(String name) {
        if (NAME_DEFAULT.equals(name)) return null;
        if (NAME_CUSTOM.equals(name)) return SHUTDOWN_CUSTOM_PATH;
        if (!isValidName(name)) return null;
        return MEDIA_DIR + SHUTDOWN_NAMED_PREFIX + name + NAMED_SUFFIX;
    }

    /** Style that will actually play at shutdown; missing files fall back like the engine. */
    public static String getShutdownEffectiveName() {
        String name = SystemProperties.get(SHUTDOWN_PROP, "");
        if (NAME_CUSTOM.equals(name)) {
            return new File(SHUTDOWN_CUSTOM_PATH).isFile() ? NAME_CUSTOM : NAME_DEFAULT;
        }
        if (!name.isEmpty() && isValidName(name)) {
            String path = MEDIA_DIR + SHUTDOWN_NAMED_PREFIX + name + NAMED_SUFFIX;
            if (new File(path).isFile()) return name;
        }
        return NAME_DEFAULT;
    }

    /** Selects the shutdown animation; an empty name restores the stock one. */
    public static void applyShutdownStyle(String name) {
        SystemProperties.set(SHUTDOWN_PROP, name);
    }

    // -----------------------------------------------------------------------
    // Frame extraction (PNG/JPG based animations)
    // -----------------------------------------------------------------------

    public static AnimationDrawable getBootAnimationFramesFromPath(Context context, String path) {
        return getBootAnimationFramesFromPath(context, path, PREVIEW_MAX_WIDTH, PREVIEW_MAX_BYTES);
    }

    /** Fullscreen preview: same pipeline at a higher resolution and budget. */
    public static AnimationDrawable getFullscreenPreviewFrames(Context context, String path) {
        return getBootAnimationFramesFromPath(context, path,
                FULLSCREEN_PREVIEW_MAX_WIDTH, FULLSCREEN_PREVIEW_MAX_BYTES);
    }

    public static AnimationDrawable getBootAnimationFramesFromPath(Context context, String path,
            int previewMaxWidth, long previewMaxBytes) {
        AnimationDrawable animationDrawable = new AnimationDrawable();
        File file = new File(path);
        if (!file.exists()) return animationDrawable;
        try (ZipFile zipFile = new ZipFile(file)) {
            DynamicColors dynamicColors = getDynamicColors(zipFile);
            int frameDuration = getFrameDuration(zipFile);
            int[] animSize = getAnimationSize(zipFile);
            List<Part> parts = getParts(zipFile);
            if (parts.isEmpty()) {
                parts.add(new Part("part0"));
            }
            // Index of the part that interpolates the dynamic colors, if any
            int dynamicPartIndex = -1;
            if (dynamicColors != null) {
                for (int i = 0; i < parts.size(); i++) {
                    if (parts.get(i).name.equals(dynamicColors.partName)) {
                        dynamicPartIndex = i;
                        break;
                    }
                }
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
                float previewScale = Math.min(1f, previewMaxWidth / (float) animSize[0]);
                long frameBytes = 4L * Math.round(animSize[0] * previewScale)
                        * Math.round(animSize[1] * previewScale);
                long maxFrames = Math.max(1L, previewMaxBytes / frameBytes);
                stride = (int) Math.max(1L, (total + maxFrames - 1) / maxFrames);
            } else {
                stride = Math.max(1, (total + PREVIEW_FALLBACK_MAX_FRAMES - 1)
                        / PREVIEW_FALLBACK_MAX_FRAMES);
            }
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                List<String> frames = partFrames.get(i);
                boolean isDynamicPart = dynamicColors != null && i == dynamicPartIndex;
                boolean postDynamicColoring = dynamicColors != null
                        && dynamicPartIndex >= 0 && i > dynamicPartIndex;
                List<BitmapDrawable> decoded = new ArrayList<>();
                loadFramesFromPart(context, zipFile, decoded, frames, stride,
                        loadTrimData(zipFile, part.name), animSize, previewMaxWidth,
                        dynamicColors, isDynamicPart, postDynamicColoring);
                if (decoded.isEmpty()) continue;
                int background = part.background == null
                        ? PartFrame.NO_BACKGROUND : part.background;
                // A count of 0 loops until boot completes, which the preview plays once. The
                // pause is in frames of the real fps and follows every play of the part.
                int plays = part.count <= 0 ? 1 : Math.min(part.count, PREVIEW_MAX_LOOPS);
                for (int play = 0; play < plays; play++) {
                    for (int f = 0; f < decoded.size(); f++) {
                        animationDrawable.addFrame(
                                new PartFrame(decoded.get(f), background, i, play),
                                frameDuration * stride);
                    }
                    if (part.pause > 0) {
                        animationDrawable.addFrame(
                                new PartFrame(decoded.get(decoded.size() - 1), background, i, play),
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
        Header header = getHeader(zipFile);
        if (header.fps > 0) return 1000 / header.fps;
        return DEFAULT_FRAME_DURATION;
    }

    /** Returns {width, height} from the first desc.txt line, or {0, 0} if unreadable. */
    public static int[] getAnimationSize(ZipFile zipFile) {
        Header header = getHeader(zipFile);
        return new int[] { header.width, header.height };
    }

    /** The desc.txt header: "W H FPS [PROGRESS]". */
    public static class Header {
        public int width;
        public int height;
        public int fps;
        public boolean progressEnabled;
    }

    public static Header getHeader(ZipFile zipFile) {
        Header header = new Header();
        try {
            ZipEntry descEntry = zipFile.getEntry("desc.txt");
            if (descEntry == null) return header;
            try (InputStream is = zipFile.getInputStream(descEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line = reader.readLine();
                if (line == null) return header;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length >= 3) {
                    header.width = parseIntSafe(tokens[0]);
                    header.height = parseIntSafe(tokens[1]);
                    header.fps = parseIntSafe(tokens[2]);
                }
                header.progressEnabled = tokens.length >= 4 && parseIntSafe(tokens[3]) != 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading desc.txt header", e);
        }
        return header;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The dynamic_colors line of desc.txt plus the engine's end colors, or null. */
    public static class DynamicColors {
        public String partName;
        public final int[] startColors = new int[4];
        public final int[] endColors = new int[4];
        public int transitionStart;
        public int transitionEnd;
    }

    /**
     * Parses the optional "dynamic_colors PATH #C1 #C2 #C3 #C4 [start end]" line of desc.txt
     * (see FORMAT.md). The start colors are #RRGGBB from desc.txt; the end colors come from
     * persist.bootanim.color1..4 as signed decimal int strings (see parseDecimalColor); a
     * missing color keeps its start color, which produces no color transition, exactly like
     * the engine.
     */
    public static DynamicColors getDynamicColors(ZipFile zipFile) {
        ZipEntry descEntry = zipFile.getEntry("desc.txt");
        if (descEntry == null) return null;
        try (InputStream is = zipFile.getInputStream(descEntry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 6 || !"dynamic_colors".equals(tokens[0])) continue;
                DynamicColors colors = new DynamicColors();
                colors.partName = tokens[1];
                for (int i = 0; i < 4; i++) {
                    try {
                        colors.startColors[i] = parseHexColor(tokens[2 + i]);
                    } catch (IllegalArgumentException e) {
                        colors.startColors[i] = 0xFF000000;
                    }
                    colors.endColors[i] = colors.startColors[i];
                }
                if (tokens.length >= 8) {
                    colors.transitionStart = parseIntSafe(tokens[6]);
                    colors.transitionEnd = parseIntSafe(tokens[7]);
                }
                for (int i = 0; i < 4; i++) {
                    String end = SystemProperties.get("persist.bootanim.color" + (i + 1), "");
                    if (!end.isEmpty()) {
                        colors.endColors[i] = parseDecimalColor(end);
                    }
                }
                return colors;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading dynamic colors from desc.txt", e);
        }
        return null;
    }

    /** Parses #RRGGBB into an opaque ARGB int, like the engine's parseColor. */
    private static int parseHexColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            throw new IllegalArgumentException("Bad color: " + hex);
        }
        return (int) (0xFF000000L | (Long.parseLong(hex.substring(1), 16) & 0xFFFFFFL));
    }

    /**
     * Parses a signed decimal int string the way the engine's parseColorDecimalString does
     * (BootAnimation.cpp:995-1009): e.g. "-2757722" is 0xFFD5EBA6, alpha is dropped, and a
     * non-numeric string parses to 0 (black) like atoi.
     */
    private static int parseDecimalColor(String value) {
        int colorInt;
        try {
            colorInt = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0; // atoi on a non-numeric string
        }
        return 0xFF000000 | (colorInt & 0x00FFFFFF);
    }

    /**
     * Recolors a preview frame the way the engine's dynamic coloring shader does
     * (BootAnimationShaders.h): the R/G/B/A channels of the texture are masks for the four
     * colors, pixels that use all four channels render as a shade of white, and the output
     * is fully opaque. [frameIndexInPart] is the real frame index, not the stride-skipped
     * display index.
     */
    public static void applyDynamicColors(Bitmap bitmap, DynamicColors colors,
            int frameIndexInPart, boolean isDynamicPart, boolean postDynamicColoring) {
        if (bitmap == null || colors == null) return;
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return;
        final float progress;
        if (isDynamicPart) {
            progress = Math.max(0f, Math.min(1f, (frameIndexInPart - colors.transitionStart)
                    / (float) Math.max(colors.transitionEnd - colors.transitionStart, 1)));
        } else {
            progress = postDynamicColoring ? 1f : 0f;
        }
        final float[] start = new float[12];
        final float[] end = new float[12];
        for (int c = 0; c < 4; c++) {
            start[c * 3] = Color.red(colors.startColors[c]) / 255f;
            start[c * 3 + 1] = Color.green(colors.startColors[c]) / 255f;
            start[c * 3 + 2] = Color.blue(colors.startColors[c]) / 255f;
            end[c * 3] = Color.red(colors.endColors[c]) / 255f;
            end[c * 3 + 1] = Color.green(colors.endColors[c]) / 255f;
            end[c * 3 + 2] = Color.blue(colors.endColors[c]) / 255f;
        }
        final int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            final int p = pixels[i];
            final float r = ((p >> 16) & 0xff) / 255f;
            final float g = ((p >> 8) & 0xff) / 255f;
            final float b = (p & 0xff) / 255f;
            final float a = ((p >> 24) & 0xff) / 255f;
            int outR;
            int outG;
            int outB;
            if (r > 0.05f && g > 0.05f && b > 0.05f && a > 0.05f) {
                // All four channels in use: a plain white shade, like the shader's white mask
                final float shade = (r + g + b + a) * 0.25f;
                outR = outG = outB = Math.round(shade * 255f);
            } else {
                outR = clamp255(r * lerp(start[0], end[0], progress)
                        + g * lerp(start[3], end[3], progress)
                        + b * lerp(start[6], end[6], progress)
                        + a * lerp(start[9], end[9], progress));
                outG = clamp255(r * lerp(start[1], end[1], progress)
                        + g * lerp(start[4], end[4], progress)
                        + b * lerp(start[7], end[7], progress)
                        + a * lerp(start[10], end[10], progress));
                outB = clamp255(r * lerp(start[2], end[2], progress)
                        + g * lerp(start[5], end[5], progress)
                        + b * lerp(start[8], end[8], progress)
                        + a * lerp(start[11], end[11], progress));
            }
            pixels[i] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int clamp255(float channel) {
        return Math.max(0, Math.min(255, Math.round(channel * 255f)));
    }

    /** A preview frame that knows the background colour of its part, which the preview shows. */
    public static class PartFrame extends LayerDrawable {
        /** The part declares no color; the engine then clears to black. */
        public static final int NO_BACKGROUND = -1;

        public final int background;
        public final int partIndex;
        public final int playIndex;

        PartFrame(Drawable frame, int background) {
            this(frame, background, -1, -1);
        }

        PartFrame(Drawable frame, int background, int partIndex, int playIndex) {
            super(new Drawable[] { frame });
            this.background = background;
            this.partIndex = partIndex;
            this.playIndex = playIndex;
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
    // Animation metadata
    // -----------------------------------------------------------------------

    /** Details of a boot animation zip, shown in the settings details card. */
    public static class AnimationInfo {
        public int width;
        public int height;
        public int fps;
        public int partCount;
        public int frameCount;
        public long zipBytes;
        public boolean hasSound;
        public boolean hasTrim;
        public boolean hasDynamicColors;
        public boolean progressEnabled;
        public boolean aspectMismatch;
    }

    /** Reads the details of [zipPath]; [screenW]/[screenH] feed the aspect check. */
    public static AnimationInfo getAnimationInfo(String zipPath, int screenW, int screenH) {
        AnimationInfo info = new AnimationInfo();
        File file = new File(zipPath);
        if (!file.isFile()) return info;
        info.zipBytes = file.length();
        try (ZipFile zipFile = new ZipFile(file)) {
            Header header = getHeader(zipFile);
            info.width = header.width;
            info.height = header.height;
            info.fps = header.fps;
            info.progressEnabled = header.progressEnabled;
            info.partCount = getParts(zipFile).size();
            boolean hasSound = false;
            boolean hasTrim = false;
            int frames = 0;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.indexOf('/') > 0 && name.endsWith("/audio.wav")) {
                    hasSound = true;
                } else if (name.indexOf('/') > 0 && name.endsWith("/trim.txt")) {
                    hasTrim = true;
                } else if (FRAME_ENTRY_PATTERN.matcher(name).matches()) {
                    frames++;
                }
            }
            info.hasSound = hasSound;
            info.hasTrim = hasTrim;
            info.frameCount = frames;
            info.hasDynamicColors = getDynamicColors(zipFile) != null;
            if (info.width > 0 && info.height > 0 && screenW > 0 && screenH > 0) {
                float zipRatio = info.width / (float) info.height;
                float screenRatio = screenW / (float) screenH;
                info.aspectMismatch = Math.abs(zipRatio - screenRatio) / screenRatio > 0.12f;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading boot animation info from " + zipPath, e);
        }
        return info;
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
            List<BitmapDrawable> out, List<String> frameNames,
            int stride, List<Rect> trimData, int[] animSize, int previewMaxWidth,
            DynamicColors dynamicColors, boolean isDynamicPart, boolean postDynamicColoring) {
        try {
            final boolean hasSize = animSize[0] > 0 && animSize[1] > 0;
            final float scale = hasSize
                    ? Math.min(1f, previewMaxWidth / (float) animSize[0]) : 1f;
            // Dynamic coloring reads the raw channels as masks, so decode non-premultiplied
            // exactly like the engine does.
            int sample = 1;
            while (hasSize && animSize[0] / (sample * 2) >= previewMaxWidth) sample *= 2;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPremultiplied = dynamicColors == null;
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
                    // Recolor before compositing: dynamic frames are fully opaque afterwards,
                    // so the trim composition keeps clean straight values.
                    if (dynamicColors != null) {
                        applyDynamicColors(bitmap, dynamicColors, frameIndex,
                                isDynamicPart, postDynamicColoring);
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
