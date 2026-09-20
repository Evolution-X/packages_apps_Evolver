/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.cutoutprogress.ring;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.PathParser;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a stable camera geometry in current logical-display coordinates.
 *
 * Source priority:
 *  1. SystemUI camera-protection path supplied by the device overlay.
 *  2. DisplayCutout path intersected with the most plausible cutout bound.
 *  3. DisplayCutout bounding rectangle normalized to a compact camera shape.
 *
 * Protection paths are authored in natural/physical display coordinates. They must be scaled by
 * physicalPixelDisplaySizeRatio and rotated into the current logical coordinate space. The logic
 * intentionally mirrors DisplayCutoutBaseView so rotation and resolution changes stay consistent
 * with SystemUI's own screen-decoration geometry.
 */
final class CameraCutoutGeometryResolver {

    static final int SOURCE_NONE = 0;
    static final int SOURCE_CAMERA_PROTECTION = 1;
    static final int SOURCE_DISPLAY_CUTOUT_PATH = 2;
    static final int SOURCE_DISPLAY_CUTOUT_BOUNDS = 3;

    private static final float SAFE_AREA_MIN_ASPECT = 1.15f;
    private static final float SAFE_AREA_MAX_ASPECT = 2.50f;
    private static final float PILL_ASPECT_THRESHOLD = 1.20f;
    private static final float EDGE_TOLERANCE_PX = 2f;
    private static final float SAFE_AREA_MIN_FILL_RATIO = 0.92f;
    private static final float SAFE_AREA_MAX_SCREEN_FRACTION = 0.15f;

    static final class ResolvedGeometry {
        final Path path;
        final RectF bounds;
        final int source;
        final int rotation;
        final boolean normalizedSafeArea;
        final boolean pillLike;

        ResolvedGeometry(
                Path path,
                RectF bounds,
                int source,
                int rotation,
                boolean normalizedSafeArea,
                boolean pillLike) {
            this.path = new Path(path);
            this.bounds = new RectF(bounds);
            this.source = source;
            this.rotation = rotation;
            this.normalizedSafeArea = normalizedSafeArea;
            this.pillLike = pillLike;
        }
    }

    private static final class Candidate {
        final Path path;
        final RectF bounds;
        final int source;

        Candidate(Path path, RectF bounds, int source) {
            this.path = path;
            this.bounds = bounds;
            this.source = source;
        }
    }

    private final Context mContext;

    CameraCutoutGeometryResolver(Context context) {
        mContext = context;
    }

    ResolvedGeometry resolve(DisplayCutout cutout) {
        final Display display = mContext.getDisplay();
        final DisplayInfo info = new DisplayInfo();
        if (display != null) {
            display.getDisplayInfo(info);
        }

        final int rotation = resolveRotation(cutout, info);
        final int logicalWidth = resolveLogicalWidth(info);
        final int logicalHeight = resolveLogicalHeight(info);
        final Candidate reference = cutout != null
                ? chooseDisplayCutoutCandidate(cutout, logicalWidth, logicalHeight)
                : null;

        Path protection = loadAndTransformProtectionPath(
                display, cutout, info, rotation, logicalWidth, logicalHeight,
                reference != null ? reference.bounds : null);
        if (isUsable(protection)) {
            RectF bounds = boundsOf(protection);
            boolean normalized = false;
            if (looksLikeEdgeSafeArea(protection, bounds, logicalWidth, logicalHeight)) {
                float diameter = Math.min(bounds.width(), bounds.height());
                if (diameter > 0f) {
                    RectF compact = new RectF(
                            bounds.centerX() - diameter / 2f,
                            bounds.centerY() - diameter / 2f,
                            bounds.centerX() + diameter / 2f,
                            bounds.centerY() + diameter / 2f);
                    Path normalizedPath = new Path();
                    normalizedPath.addOval(compact, Path.Direction.CW);
                    protection = normalizedPath;
                    bounds = compact;
                    normalized = true;
                }
            }
            return buildResolved(
                    protection,
                    bounds,
                    SOURCE_CAMERA_PROTECTION,
                    rotation,
                    normalized,
                    false,
                    logicalWidth,
                    logicalHeight);
        }

        if (cutout == null) return null;

        Candidate candidate = reference != null ? reference : chooseDisplayCutoutCandidate(
                cutout, logicalWidth, logicalHeight);
        if (candidate == null || candidate.bounds.isEmpty()) return null;

        RectF bounds = candidate.bounds;
        Path path = candidate.path;
        boolean normalized = false;

        if (looksLikeEdgeSafeArea(path, bounds, logicalWidth, logicalHeight)) {
            float diameter = Math.min(bounds.width(), bounds.height());
            if (diameter > 0f) {
                RectF compact = new RectF(
                        bounds.centerX() - diameter / 2f,
                        bounds.centerY() - diameter / 2f,
                        bounds.centerX() + diameter / 2f,
                        bounds.centerY() + diameter / 2f);
                Path normalizedPath = new Path();
                normalizedPath.addOval(compact, Path.Direction.CW);
                path = normalizedPath;
                bounds = compact;
                normalized = true;
            }
        }

        return buildResolved(
                path,
                bounds,
                candidate.source,
                rotation,
                normalized,
                false,
                logicalWidth,
                logicalHeight);
    }

    private ResolvedGeometry buildResolved(
            Path path,
            RectF bounds,
            int source,
            int rotation,
            boolean normalized,
            boolean forcePill,
            int logicalWidth,
            int logicalHeight) {
        if (path == null || bounds == null || bounds.isEmpty()) return null;
        float min = Math.min(bounds.width(), bounds.height());
        float max = Math.max(bounds.width(), bounds.height());
        float aspect = min > 0f ? max / min : 1f;
        boolean pill = forcePill || (!normalized && aspect >= PILL_ASPECT_THRESHOLD);

        // Reject obviously invalid geometry that cannot belong to the current logical display.
        if (logicalWidth > 0 && logicalHeight > 0) {
            RectF display = new RectF(0f, 0f, logicalWidth, logicalHeight);
            RectF intersection = new RectF(bounds);
            if (!intersection.intersect(display) || intersection.isEmpty()) {
                return null;
            }
        }

        return new ResolvedGeometry(path, bounds, source, rotation, normalized, pill);
    }

    private Candidate chooseDisplayCutoutCandidate(
            DisplayCutout cutout, int logicalWidth, int logicalHeight) {
        Path allPath = null;
        try {
            allPath = cutout.getCutoutPath();
        } catch (NoSuchMethodError ignored) {
        }

        List<Rect> rects = new ArrayList<>(4);
        addNonEmpty(rects, cutout.getBoundingRectTop());
        addNonEmpty(rects, cutout.getBoundingRectLeft());
        addNonEmpty(rects, cutout.getBoundingRectRight());
        addNonEmpty(rects, cutout.getBoundingRectBottom());

        Candidate best = null;
        double bestScore = Double.MAX_VALUE;

        for (Rect rect : rects) {
            RectF rectF = new RectF(rect);
            Path candidatePath = null;
            RectF candidateBounds = null;
            int source = SOURCE_DISPLAY_CUTOUT_BOUNDS;

            if (isUsable(allPath)) {
                Path clipped = new Path(allPath);
                Path clip = new Path();
                clip.addRect(rectF, Path.Direction.CW);
                if (clipped.op(clip, Path.Op.INTERSECT) && isUsable(clipped)) {
                    Candidate contour = chooseBestContour(
                            clipped, logicalWidth, logicalHeight, SOURCE_DISPLAY_CUTOUT_PATH);
                    if (contour != null) {
                        candidatePath = contour.path;
                        candidateBounds = contour.bounds;
                        source = contour.source;
                    }
                }
            }

            if (candidatePath == null) {
                candidatePath = new Path();
                candidatePath.addRect(rectF, Path.Direction.CW);
                candidateBounds = rectF;
            }

            double score = candidateScore(candidateBounds, logicalWidth, logicalHeight);
            if (score < bestScore) {
                bestScore = score;
                best = new Candidate(candidatePath, candidateBounds, source);
            }
        }

        // Some implementations may expose a path while bounding rects are absent.
        if (best == null && isUsable(allPath)) {
            best = chooseBestContour(
                    allPath, logicalWidth, logicalHeight, SOURCE_DISPLAY_CUTOUT_PATH);
        }
        return best;
    }

    private static Candidate chooseBestContour(
            Path path, int logicalWidth, int logicalHeight, int source) {
        if (!isUsable(path)) return null;

        PathMeasure measure = new PathMeasure(path, true);
        Candidate best = null;
        double bestScore = Double.MAX_VALUE;
        do {
            float length = measure.getLength();
            if (length <= 0f) continue;

            Path contour = new Path();
            if (!measure.getSegment(0f, length, contour, true) || contour.isEmpty()) continue;
            contour.close();
            RectF bounds = boundsOf(contour);
            if (bounds.isEmpty()) continue;

            double score = candidateScore(bounds, logicalWidth, logicalHeight);
            if (score < bestScore) {
                bestScore = score;
                best = new Candidate(contour, bounds, source);
            }
        } while (measure.nextContour());

        return best;
    }

    private static double candidateScore(RectF b, int logicalWidth, int logicalHeight) {
        if (b == null || b.isEmpty()) return Double.MAX_VALUE;

        double area = Math.max(1.0, (double) b.width() * b.height());
        double displayArea = Math.max(
                1.0, (double) Math.max(1, logicalWidth) * Math.max(1, logicalHeight));
        double areaTerm = area / displayArea;

        double min = Math.max(1.0, Math.min(b.width(), b.height()));
        double max = Math.max(b.width(), b.height());
        double aspect = max / min;
        double aspectPenalty = Math.max(0.0, aspect - 1.0);

        // Prefer compact camera-like geometry while still strongly preferring the smaller physical
        // non-functional region when a display reports more than one cutout.
        return areaTerm * 1000.0 + aspectPenalty * 0.08;
    }

    private Path loadAndTransformProtectionPath(
            Display display,
            DisplayCutout cutout,
            DisplayInfo info,
            int rotation,
            int logicalWidth,
            int logicalHeight,
            RectF referenceBounds) {
        String displayUniqueId = display != null ? display.getUniqueId() : null;

        ProtectionSpec inner = new ProtectionSpec(
                safeString(R.string.config_innerBuiltInDisplayCutoutProtection),
                safeString(R.string.config_protectedInnerScreenUniqueId), false);
        ProtectionSpec outer = new ProtectionSpec(
                safeString(R.string.config_frontBuiltInDisplayCutoutProtection),
                safeString(R.string.config_protectedScreenUniqueId), true);

        // An explicit display-id match is authoritative. This is the normal path for foldables.
        if (displayUniqueId != null && !displayUniqueId.isEmpty()) {
            if (inner.explicitlyMatches(displayUniqueId)) {
                return transformProtectionSpec(inner, display, cutout, info, rotation,
                        logicalWidth, logicalHeight);
            }
            if (outer.explicitlyMatches(displayUniqueId)) {
                return transformProtectionSpec(outer, display, cutout, info, rotation,
                        logicalWidth, logicalHeight);
            }
        }

        List<ProtectionSpec> generic = new ArrayList<>(2);
        if (outer.isGeneric()) generic.add(outer);
        if (inner.isGeneric()) generic.add(inner);
        if (generic.isEmpty()) return null;
        if (generic.size() == 1) {
            return transformProtectionSpec(generic.get(0), display, cutout, info, rotation,
                    logicalWidth, logicalHeight);
        }

        // Older overlays sometimes omit displayUniqueId for both inner and outer paths. Instead
        // of blindly choosing the outer path, transform both and pick the one that best matches
        // the cutout visible on the current logical display.
        Path best = null;
        double bestScore = Double.MAX_VALUE;
        for (ProtectionSpec spec : generic) {
            Path transformed = transformProtectionSpec(spec, display, cutout, info, rotation,
                    logicalWidth, logicalHeight);
            if (!isUsable(transformed)) continue;
            RectF bounds = boundsOf(transformed);
            double score = protectionMatchScore(bounds, referenceBounds,
                    logicalWidth, logicalHeight, spec.isOuter);
            if (score < bestScore) {
                bestScore = score;
                best = transformed;
            }
        }
        return best;
    }

    private Path transformProtectionSpec(
            ProtectionSpec spec,
            Display display,
            DisplayCutout cutout,
            DisplayInfo info,
            int rotation,
            int logicalWidth,
            int logicalHeight) {
        if (spec == null || !spec.hasPath()) return null;
        final Path path;
        try {
            path = PathParser.createPathFromPathData(spec.pathData);
        } catch (Throwable ignored) {
            return null;
        }
        if (!isUsable(path)) return null;

        float ratio = resolvePhysicalPixelRatio(
                cutout, display, logicalWidth, logicalHeight, rotation);
        Matrix matrix = new Matrix();
        matrix.postScale(ratio, ratio);

        int lw = logicalWidth > 0 ? logicalWidth : resolveLogicalWidth(info);
        int lh = logicalHeight > 0 ? logicalHeight : resolveLogicalHeight(info);
        boolean flipped = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int naturalWidth = flipped ? lh : lw;
        int naturalHeight = flipped ? lw : lh;
        transformPhysicalToLogicalCoordinates(rotation, naturalWidth, naturalHeight, matrix);
        path.transform(matrix);
        return path;
    }

    private static double protectionMatchScore(
            RectF candidate, RectF reference, int logicalWidth, int logicalHeight,
            boolean outerPreferred) {
        if (candidate == null || candidate.isEmpty()) return Double.MAX_VALUE;
        if (reference == null || reference.isEmpty()) {
            // Single-display/UDC fallback: prefer the outer/front resource, then the smaller
            // compact region to avoid accidentally selecting an inner-panel mask.
            double area = Math.max(1.0, (double) candidate.width() * candidate.height());
            return (outerPreferred ? 0.0 : 1.0) + area * 1e-9;
        }

        double dw = Math.max(1.0, logicalWidth);
        double dh = Math.max(1.0, logicalHeight);
        double dx = (candidate.centerX() - reference.centerX()) / dw;
        double dy = (candidate.centerY() - reference.centerY()) / dh;
        double centerDistance = dx * dx + dy * dy;

        double a = Math.max(1.0, (double) candidate.width() * candidate.height());
        double b = Math.max(1.0, (double) reference.width() * reference.height());
        double sizePenalty = Math.abs(Math.log(a / b));

        RectF overlap = new RectF(candidate);
        boolean intersects = overlap.intersect(reference) && !overlap.isEmpty();
        double overlapPenalty = intersects ? 0.0 : 1.0;
        return centerDistance * 10.0 + sizePenalty * 0.15 + overlapPenalty;
    }

    private static float resolvePhysicalPixelRatio(
            DisplayCutout cutout, Display display, int logicalWidth, int logicalHeight,
            int rotation) {
        if (cutout != null) {
            try {
                float parsed = cutout.getCutoutPathParserInfo().getPhysicalPixelDisplaySizeRatio();
                if (Float.isFinite(parsed) && parsed > 0f) return parsed;
            } catch (Throwable ignored) {
            }
        }

        if (display != null) {
            Display.Mode mode = display.getMode();
            if (mode != null) {
                boolean flipped = rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270;
                int naturalLogicalWidth = flipped ? logicalHeight : logicalWidth;
                int naturalLogicalHeight = flipped ? logicalWidth : logicalHeight;
                int physicalWidth = mode.getPhysicalWidth();
                int physicalHeight = mode.getPhysicalHeight();
                if (naturalLogicalWidth > 0 && naturalLogicalHeight > 0
                        && physicalWidth > 0 && physicalHeight > 0) {
                    float sx = naturalLogicalWidth / (float) physicalWidth;
                    float sy = naturalLogicalHeight / (float) physicalHeight;
                    if (Float.isFinite(sx) && Float.isFinite(sy) && sx > 0f && sy > 0f) {
                        // Resolution overrides should be uniform. Use the smaller factor if an
                        // OEM reports slightly asymmetric logical dimensions.
                        return Math.min(sx, sy);
                    }
                }
            }
        }
        return 1f;
    }

    private static final class ProtectionSpec {
        final String pathData;
        final String displayUniqueId;
        final boolean isOuter;

        ProtectionSpec(String pathData, String displayUniqueId, boolean isOuter) {
            this.pathData = pathData == null ? "" : pathData.trim();
            this.displayUniqueId = displayUniqueId == null ? "" : displayUniqueId.trim();
            this.isOuter = isOuter;
        }

        boolean hasPath() { return !pathData.isEmpty(); }
        boolean explicitlyMatches(String currentDisplayUniqueId) {
            return hasPath() && !displayUniqueId.isEmpty()
                    && displayUniqueId.equals(currentDisplayUniqueId);
        }
        boolean isGeneric() { return hasPath() && displayUniqueId.isEmpty(); }
    }

    private String safeString(int resId) {
        try {
            return mContext.getResources().getString(resId);
        } catch (Resources.NotFoundException ignored) {
            return "";
        }
    }

    private int resolveRotation(DisplayCutout cutout, DisplayInfo info) {
        if (info != null
                && info.rotation >= Surface.ROTATION_0
                && info.rotation <= Surface.ROTATION_270) {
            return info.rotation;
        }
        if (cutout != null) {
            try {
                return cutout.getCutoutPathParserInfo().getRotation();
            } catch (Throwable ignored) {
            }
        }
        Display display = mContext.getDisplay();
        return display != null ? display.getRotation() : Surface.ROTATION_0;
    }

    private int resolveLogicalWidth(DisplayInfo info) {
        if (info != null && info.logicalWidth > 0) return info.logicalWidth;
        return mContext.getResources().getDisplayMetrics().widthPixels;
    }

    private int resolveLogicalHeight(DisplayInfo info) {
        if (info != null && info.logicalHeight > 0) return info.logicalHeight;
        return mContext.getResources().getDisplayMetrics().heightPixels;
    }

    private static boolean looksLikeEdgeSafeArea(
            Path path, RectF b, int width, int height) {
        if (path == null || b == null || b.isEmpty()) return false;
        float min = Math.min(b.width(), b.height());
        float max = Math.max(b.width(), b.height());
        if (min <= 0f) return false;
        float aspect = max / min;
        // Moderately elongated masks are common around punch-hole cameras. Extremely wide
        // regions are real notches/cutouts and must not be collapsed into a fake circle.
        if (aspect < SAFE_AREA_MIN_ASPECT || aspect > SAFE_AREA_MAX_ASPECT) return false;

        int shortDisplay = Math.min(Math.max(0, width), Math.max(0, height));
        if (shortDisplay > 0 && max > shortDisplay * SAFE_AREA_MAX_SCREEN_FRACTION) return false;

        boolean touchesLeft = b.left <= EDGE_TOLERANCE_PX;
        boolean touchesTop = b.top <= EDGE_TOLERANCE_PX;
        boolean touchesRight = width > 0 && b.right >= width - EDGE_TOLERANCE_PX;
        boolean touchesBottom = height > 0 && b.bottom >= height - EDGE_TOLERANCE_PX;
        if (!(touchesLeft || touchesTop || touchesRight || touchesBottom)) return false;

        // A safe-area mask is normally almost the full bounding rectangle. A real circle/ellipse
        // fills ~78.5% of its bounds and a pill also leaves curved-corner area unused.
        float fillRatio = approximatePathFillRatio(path, b);
        return fillRatio >= SAFE_AREA_MIN_FILL_RATIO;
    }

    private static float approximatePathFillRatio(Path path, RectF bounds) {
        float boxArea = bounds.width() * bounds.height();
        if (boxArea <= 0f) return 0f;

        PathMeasure measure = new PathMeasure(path, true);
        float length = measure.getLength();
        if (length <= 0f) return 0f;
        // Multiple contours are ambiguous (for example dual holes). Do not normalize them into a
        // single synthetic camera circle.
        if (measure.nextContour()) return 0f;
        measure.setPath(path, true);

        final int samples = 64;
        float[] first = new float[2];
        float[] prev = new float[2];
        float[] cur = new float[2];
        if (!measure.getPosTan(0f, first, null)) return 0f;
        prev[0] = first[0];
        prev[1] = first[1];
        double twiceArea = 0.0;
        for (int i = 1; i < samples; i++) {
            float d = length * i / samples;
            if (!measure.getPosTan(d, cur, null)) continue;
            twiceArea += (double) prev[0] * cur[1] - (double) cur[0] * prev[1];
            prev[0] = cur[0];
            prev[1] = cur[1];
        }
        twiceArea += (double) prev[0] * first[1] - (double) first[0] * prev[1];
        float area = (float) (Math.abs(twiceArea) * 0.5);
        return Math.max(0f, Math.min(1f, area / boxArea));
    }

    private static void addNonEmpty(List<Rect> out, Rect rect) {
        if (rect != null && !rect.isEmpty()) {
            out.add(new Rect(rect));
        }
    }

    private static RectF boundsOf(Path path) {
        RectF bounds = new RectF();
        if (path != null && !path.isEmpty()) {
            path.computeBounds(bounds, true);
        }
        return bounds;
    }

    private static boolean isUsable(Path path) {
        return path != null && !path.isEmpty();
    }

    private static void transformPhysicalToLogicalCoordinates(
            int rotation, int physicalWidth, int physicalHeight, Matrix out) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return;
            case Surface.ROTATION_90:
                out.postRotate(270f);
                out.postTranslate(0f, physicalWidth);
                return;
            case Surface.ROTATION_180:
                out.postRotate(180f);
                out.postTranslate(physicalWidth, physicalHeight);
                return;
            case Surface.ROTATION_270:
                out.postRotate(90f);
                out.postTranslate(physicalHeight, 0f);
                return;
            default:
                return;
        }
    }
}
