/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.cutoutprogress.ring;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

public final class CapsuleRingRenderer implements RingViewRenderer {

    private final Path mOutline = new Path();
    private final PathMeasure mMeasure = new PathMeasure();
    private final Path mWorkPath = new Path();
    private final float[] mTangent = new float[2];
    private float mTotalLength = 0f;

    @Override
    public void updateBounds(RectF bounds) {
        mOutline.reset();
        mWorkPath.reset();
        mTotalLength = 0f;
        if (bounds == null || bounds.isEmpty()) {
            mMeasure.setPath(null, false);
            return;
        }
        buildCapsule(bounds);
        mMeasure.setPath(mOutline, false);
        mTotalLength = mMeasure.getLength();
    }

    private void buildCapsule(RectF b) {
        float r = Math.min(b.width(), b.height()) / 2f;
        float cx = b.centerX();

        mOutline.moveTo(cx, b.top);

        if (b.width() >= b.height()) {
            mOutline.lineTo(b.right - r, b.top);
            mOutline.arcTo(b.right - 2*r, b.top, b.right, b.bottom, -90f, 180f, false);
            mOutline.lineTo(b.left + r, b.bottom);
            mOutline.arcTo(b.left, b.top, b.left + 2*r, b.bottom, 90f, 180f, false);
        } else {
            mOutline.arcTo(b.left, b.top, b.right, b.top + 2*r, -90f, 90f, false);
            mOutline.lineTo(b.right, b.bottom - r);
            mOutline.arcTo(b.left, b.bottom - 2*r, b.right, b.bottom, 0f, 180f, false);
            mOutline.lineTo(b.left, b.top + r);
            mOutline.arcTo(b.left, b.top, b.right, b.top + 2*r, 180f,  90f, false);
        }
        mOutline.close();
    }

    @Override
    public void drawFullRing(Canvas canvas, Paint paint) {
        if (mTotalLength == 0f) return;
        canvas.drawPath(mOutline, paint);
    }

    @Override
    public void drawProgress(Canvas canvas, float sweepFraction,
                             boolean clockwise, Paint paint) {
        if (mTotalLength == 0f) return;
        if (sweepFraction >= 1f) { drawFullRing(canvas, paint); return; }
        float len = Math.max(0f, Math.min(1f, sweepFraction)) * mTotalLength;
        mWorkPath.reset();
        if (clockwise) {
            mMeasure.getSegment(0f, len, mWorkPath, true);
        } else {
            mMeasure.getSegment(mTotalLength - len, mTotalLength, mWorkPath, true);
        }
        canvas.drawPath(mWorkPath, paint);
    }

    @Override
    public void drawSymmetricProgress(Canvas canvas, float sweepFraction, Paint paint) {
        if (mTotalLength <= 0f) return;
        float fraction = Math.max(0f, Math.min(1f, sweepFraction));
        if (fraction <= 0f) return;
        if (fraction >= 1f) {
            drawFullRing(canvas, paint);
            return;
        }
        float length = mTotalLength * fraction;
        float start = mTotalLength * 0.5f - length * 0.5f;
        mWorkPath.reset();
        appendWrappedSegment(start, length, mWorkPath);
        canvas.drawPath(mWorkPath, paint);
    }

    private void appendWrappedSegment(float start, float length, Path out) {
        if (mTotalLength <= 0f || length <= 0f) return;
        if (length >= mTotalLength) {
            out.addPath(mOutline);
            return;
        }
        float s = start % mTotalLength;
        if (s < 0f) s += mTotalLength;
        float e = s + length;
        if (e <= mTotalLength) {
            mMeasure.getSegment(s, e, out, true);
        } else {
            mMeasure.getSegment(s, mTotalLength, out, true);
            mMeasure.getSegment(0f, e - mTotalLength, out, true);
        }
    }

    @Override
    public boolean getPointAndOutwardNormal(float fraction, float[] position, float[] normal) {
        if (position == null || position.length < 2 || normal == null || normal.length < 2
                || mTotalLength <= 0f) return false;
        float f = fraction - (float) Math.floor(fraction);
        if (!mMeasure.getPosTan(mTotalLength * f, position, mTangent)) return false;
        float len = (float) Math.hypot(mTangent[0], mTangent[1]);
        if (len <= 0f) return false;
        // CapsuleRingRenderer builds its contour visually clockwise from the top.
        float tx = mTangent[0] / len;
        float ty = mTangent[1] / len;
        normal[0] = ty;
        normal[1] = -tx;
        return true;
    }

    @Override
    public void drawSegmented(Canvas canvas,
                              int segments, float gapDeg, float arcDeg,
                              int highlight,
                              Paint basePaint, Paint shinePaint, float alpha) {
        if (mTotalLength <= 0f || segments <= 0) return;
        float safeArcDeg = Math.max(0f, arcDeg);
        float safeGapDeg = Math.max(0f, gapDeg);
        float totalDeg = segments * (safeArcDeg + safeGapDeg);
        if (totalDeg <= 0f) return;
        float segLen = mTotalLength * (safeArcDeg / totalDeg);
        float gapLen = mTotalLength * (safeGapDeg / totalDeg);

        for (int i = 0; i < segments; i++) {
            float start = i * (segLen + gapLen);
            mWorkPath.reset();
            mMeasure.getSegment(start, start + segLen, mWorkPath, true);

            if (i == highlight || i == highlight - 1) {
                Paint tmp = new Paint(shinePaint);
                tmp.setAlpha((int)(255 * Math.max(0f, Math.min(1f, alpha))));
                canvas.drawPath(mWorkPath, tmp);
            } else {
                canvas.drawPath(mWorkPath, basePaint);
            }
        }
    }
}
