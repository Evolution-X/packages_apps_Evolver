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

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

/**
 * Traces an arbitrary single-contour camera/cutout path while preserving the standard ring
 * semantics: progress begins at the visual top, clockwise/counter-clockwise direction is stable,
 * and independent lanes scale the same physical path outwards through updateBounds().
 */
public final class PathRingRenderer implements RingViewRenderer {

    private static final int START_SAMPLES = 128;
    private static final int ORIENTATION_SAMPLES = 64;
    private static final float EPSILON = 0.01f;

    private final Path mBasePath = new Path();
    private final Path mPath = new Path();
    private final Path mWorkPath = new Path();
    private final PathMeasure mMeasure = new PathMeasure();
    private final RectF mBaseBounds = new RectF();
    private final RectF mLastBounds = new RectF();
    private final Matrix mMatrix = new Matrix();
    private final float[] mTangent = new float[2];

    private boolean mHasBasePath;
    private boolean mHasLastBounds;
    private int mGeneration;
    private int mAppliedGeneration = -1;
    private float mTotalLength;
    private float mStartDistance;
    private float mOppositeDistance;
    private boolean mPathOrderClockwise = true;
    private boolean mMetricsResolved;

    public static boolean canTracePath(Path path) {
        if (path == null || path.isEmpty()) return false;
        PathMeasure measure = new PathMeasure(path, true);
        if (measure.getLength() <= EPSILON) return false;
        return !measure.nextContour();
    }

    public void setBasePath(Path path) {
        mBasePath.reset();
        mBaseBounds.setEmpty();
        mHasBasePath = path != null && !path.isEmpty() && canTracePath(path);
        if (mHasBasePath) {
            mBasePath.set(path);
            mBasePath.computeBounds(mBaseBounds, true);
            if (mBaseBounds.isEmpty()) mHasBasePath = false;
        }
        mGeneration++;
        mHasLastBounds = false;
        mAppliedGeneration = -1;
        mTotalLength = 0f;
        mMetricsResolved = false;
    }

    @Override
    public void updateBounds(RectF bounds) {
        if (!mHasBasePath || bounds == null || bounds.isEmpty()) {
            clearResolvedPath();
            return;
        }

        if (mAppliedGeneration == mGeneration && mHasLastBounds && sameBounds(mLastBounds, bounds)) {
            return;
        }

        mPath.reset();
        mMatrix.reset();
        if (!mMatrix.setRectToRect(mBaseBounds, bounds, Matrix.ScaleToFit.FILL)) {
            clearResolvedPath();
            return;
        }
        mBasePath.transform(mMatrix, mPath);
        mMeasure.setPath(mPath, true);
        mTotalLength = mMeasure.getLength();
        if (mTotalLength <= EPSILON) {
            clearResolvedPath();
            return;
        }

        // Top/bottom/orientation sampling is intentionally lazy. Aurora/background layers only
        // need drawFullRing(), so eagerly sampling the contour here would repeat expensive work
        // for every glow lane on every frame.
        mMetricsResolved = false;

        mLastBounds.set(bounds);
        mHasLastBounds = true;
        mAppliedGeneration = mGeneration;
    }

    @Override
    public void drawFullRing(Canvas canvas, Paint paint) {
        if (mTotalLength <= EPSILON) return;
        canvas.drawPath(mPath, paint);
    }

    @Override
    public void drawProgress(Canvas canvas, float sweepFraction,
                             boolean clockwise, Paint paint) {
        if (mTotalLength <= EPSILON) return;
        ensureMetrics();
        float fraction = clamp01(sweepFraction);
        if (fraction <= 0f) return;
        if (fraction >= 1f) {
            drawFullRing(canvas, paint);
            return;
        }

        float length = mTotalLength * fraction;
        mWorkPath.reset();
        appendVisualRange(0f, length, clockwise, mWorkPath);
        canvas.drawPath(mWorkPath, paint);
    }

    @Override
    public void drawSymmetricProgress(Canvas canvas, float sweepFraction, Paint paint) {
        if (mTotalLength <= EPSILON) return;
        ensureMetrics();
        float fraction = clamp01(sweepFraction);
        if (fraction <= 0f) return;
        if (fraction >= 1f) {
            drawFullRing(canvas, paint);
            return;
        }

        // Expand around the actual visual bottom. Half the contour length is not necessarily the
        // geometric opposite of the top on asymmetric camera/protection paths.
        float length = mTotalLength * fraction;
        mWorkPath.reset();
        appendPhysicalRange(mOppositeDistance - length * 0.5f, length, mWorkPath);
        canvas.drawPath(mWorkPath, paint);
    }

    @Override
    public boolean getPointAndOutwardNormal(float fraction, float[] position, float[] normal) {
        if (position == null || position.length < 2 || normal == null || normal.length < 2
                || mTotalLength <= EPSILON) return false;
        ensureMetrics();

        float f = fraction - (float) Math.floor(fraction);
        float sign = mPathOrderClockwise ? 1f : -1f;
        float distance = normalizeDistance(mStartDistance + sign * mTotalLength * f);
        if (!mMeasure.getPosTan(distance, position, mTangent)) return false;

        float tx = mTangent[0] * sign;
        float ty = mTangent[1] * sign;
        float len = (float) Math.hypot(tx, ty);
        if (len <= EPSILON) return false;
        tx /= len;
        ty /= len;

        // Visual-clockwise contour in Android's +Y-down coordinate space: rotating the tangent
        // by -90 degrees produces the outward normal.
        normal[0] = ty;
        normal[1] = -tx;
        return true;
    }

    @Override
    public void drawSegmented(Canvas canvas,
                              int segments, float gapDeg, float arcDeg,
                              int highlight,
                              Paint basePaint, Paint shinePaint, float alpha) {
        if (mTotalLength <= EPSILON || segments <= 0) return;
        ensureMetrics();

        float totalDeg = segments * (Math.max(0f, arcDeg) + Math.max(0f, gapDeg));
        if (totalDeg <= 0f) return;

        float segmentLength = mTotalLength * Math.max(0f, arcDeg) / totalDeg;
        float gapLength = mTotalLength * Math.max(0f, gapDeg) / totalDeg;
        for (int i = 0; i < segments; i++) {
            mWorkPath.reset();
            appendVisualRange(i * (segmentLength + gapLength),
                    segmentLength, true, mWorkPath);
            Paint drawPaint = basePaint;
            if (i == highlight || i == highlight - 1) {
                Paint tmp = new Paint(shinePaint);
                tmp.setAlpha((int) (255f * clamp01(alpha)));
                drawPaint = tmp;
            }
            canvas.drawPath(mWorkPath, drawPaint);
        }
    }

    private void appendVisualRange(float visualOffset, float length,
                                   boolean clockwise, Path out) {
        boolean forward = clockwise == mPathOrderClockwise;
        if (forward) {
            appendPhysicalRange(mStartDistance + visualOffset, length, out);
        } else {
            appendPhysicalRange(mStartDistance - visualOffset - length, length, out);
        }
    }

    private void appendPhysicalRange(float start, float length, Path out) {
        if (mTotalLength <= EPSILON || length <= 0f) return;
        if (length >= mTotalLength - EPSILON) {
            out.addPath(mPath);
            return;
        }

        float s = normalizeDistance(start);
        float e = s + length;
        if (e <= mTotalLength) {
            mMeasure.getSegment(s, e, out, true);
        } else {
            mMeasure.getSegment(s, mTotalLength, out, true);
            mMeasure.getSegment(0f, e - mTotalLength, out, true);
        }
    }

    private void ensureMetrics() {
        if (mMetricsResolved || mTotalLength <= EPSILON || !mHasLastBounds) return;
        mStartDistance = findVisualTopDistance(mLastBounds);
        mOppositeDistance = findVisualBottomDistance(mLastBounds);
        mPathOrderClockwise = determinePathOrderClockwise();
        mMetricsResolved = true;
    }

    private float findVisualTopDistance(RectF bounds) {
        float bestDistance = 0f;
        double bestScore = Double.MAX_VALUE;
        float[] pos = new float[2];
        float targetX = bounds.centerX();
        float targetY = bounds.top;

        for (int i = 0; i < START_SAMPLES; i++) {
            float d = mTotalLength * i / START_SAMPLES;
            if (!mMeasure.getPosTan(d, pos, null)) continue;
            double dx = pos[0] - targetX;
            double dy = pos[1] - targetY;
            // Bias strongly toward the top edge, then toward horizontal center.
            double score = dy * dy * 4.0 + dx * dx;
            if (score < bestScore) {
                bestScore = score;
                bestDistance = d;
            }
        }
        return bestDistance;
    }

    private float findVisualBottomDistance(RectF bounds) {
        float bestDistance = 0f;
        double bestScore = Double.MAX_VALUE;
        float[] pos = new float[2];
        float targetX = bounds.centerX();
        float targetY = bounds.bottom;

        for (int i = 0; i < START_SAMPLES; i++) {
            float d = mTotalLength * i / START_SAMPLES;
            if (!mMeasure.getPosTan(d, pos, null)) continue;
            double dx = pos[0] - targetX;
            double dy = pos[1] - targetY;
            double score = dy * dy * 4.0 + dx * dx;
            if (score < bestScore) {
                bestScore = score;
                bestDistance = d;
            }
        }
        return bestDistance;
    }

    private boolean determinePathOrderClockwise() {
        float[] first = new float[2];
        float[] prev = new float[2];
        float[] cur = new float[2];
        if (!mMeasure.getPosTan(0f, first, null)) return true;
        prev[0] = first[0];
        prev[1] = first[1];
        double twiceArea = 0.0;

        for (int i = 1; i < ORIENTATION_SAMPLES; i++) {
            float d = mTotalLength * i / ORIENTATION_SAMPLES;
            if (!mMeasure.getPosTan(d, cur, null)) continue;
            twiceArea += (double) prev[0] * cur[1] - (double) cur[0] * prev[1];
            prev[0] = cur[0];
            prev[1] = cur[1];
        }
        twiceArea += (double) prev[0] * first[1] - (double) first[0] * prev[1];

        // Android canvas coordinates have +Y downward, therefore a positive shoelace area
        // corresponds to visually clockwise traversal.
        return twiceArea >= 0.0;
    }

    private float normalizeDistance(float distance) {
        float d = distance % mTotalLength;
        if (d < 0f) d += mTotalLength;
        return d;
    }

    private void clearResolvedPath() {
        mPath.reset();
        mWorkPath.reset();
        mMeasure.setPath(null, false);
        mTotalLength = 0f;
        mMetricsResolved = false;
        mHasLastBounds = false;
        mAppliedGeneration = -1;
    }

    private static boolean sameBounds(RectF a, RectF b) {
        return Math.abs(a.left - b.left) < EPSILON
                && Math.abs(a.top - b.top) < EPSILON
                && Math.abs(a.right - b.right) < EPSILON
                && Math.abs(a.bottom - b.bottom) < EPSILON;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
