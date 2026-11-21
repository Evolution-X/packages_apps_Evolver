/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.quicksettings;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.settings.R;

/**
 * A Quick settings mock in one of the SystemUI QS panel styles, the values of
 * Settings.System.QS_PANEL_STYLE. Sizes are SystemUI dp on a 340 wide panel, scaled to the
 * view. Mini mode drops the text for thumbnails. The drawing follows the tile code in
 * SystemUI's Tile.kt and the Nothing palette in NothingQsColors.kt, keep them in step.
 *
 * Based on Remixer's QsStylePreviewView.
 */
public class QsStylePreviewView extends View {
    public static final int CARD = 0;
    public static final int CLASSIC = 1;
    public static final int COLOROS = 2;
    public static final int NOTHING = 3;

    // Values of Settings.System.QS_NOTHING_COLOR_STYLE.
    public static final int NOTHING_COLORS_CLASSIC = 0;
    public static final int NOTHING_COLORS_FLAT = 1;
    public static final int NOTHING_COLORS_TRANSLUCENT = 2;

    private static final float WIDTH = 340f;
    // The tiles fade out over this much dp at the bottom instead of being cut off.
    private static final float FADE = 36f;
    private static final int[] ICONS = {
        R.drawable.qs_style_preview_wifi, R.drawable.qs_style_preview_bluetooth,
        R.drawable.qs_style_preview_flashlight, R.drawable.qs_style_preview_dnd,
        R.drawable.qs_style_preview_rotate, R.drawable.qs_style_preview_dark,
        R.drawable.qs_style_preview_hotspot, R.drawable.qs_style_preview_airplane,
        R.drawable.qs_style_preview_data_saver, R.drawable.qs_style_preview_nfc,
        R.drawable.qs_style_preview_sync, R.drawable.qs_style_preview_qr,
    };
    private static final boolean[] ON = {
        true, true, false, false, true, true, false, false, false, true, true, false,
    };
    // Internet and Bluetooth are the wide tiles of the Card and ColorOS styles.
    private static final int WIDE = 2;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final int SURFACE = 0x1AFFFFFF;
    private static final int ON_SURFACE = 0xFFEDEEF0;
    private static final int MUTED = 0xFFB4B8BE;

    // Nothing OS 5.0 dark palette, nothing_os_* in SystemUI, and the classic dark grey one,
    // nothing_qs_*.
    private static final int NOTHING_PANEL = 0xFF0D0D0D;
    private static final int NOTHING_OS_SURFACE = 0xFF1B1B1B;
    private static final int NOTHING_OS_ACTIVE = 0xFFE1E1E1;
    private static final int NOTHING_OS_CIRCLE = 0xFF303030;
    private static final int NOTHING_OS_SLIDER_FILL = 0xFFE2E2E2;
    private static final int NOTHING_OS_SLIDER_INK = 0xFF1A1A1A;
    private static final int NOTHING_QS_SURFACE = 0xFF262626;
    private static final int NOTHING_QS_ACTIVE = 0xFFFFFFFF;
    private static final int NOTHING_QS_CIRCLE = 0xFF383838;
    private static final int NOTHING_QS_SLIDER_INK = 0xFF1E1E1E;
    private static final int NOTHING_DIVIDER_ON_ACTIVE = 0x33000000;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFade = new Paint();
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Drawable[] mIcons = new Drawable[ICONS.length];
    private final String[] mLabels;
    private final String mOn;
    private final String mOff;
    private final int mPrimary;
    private final int mOnPrimary;
    private final int mAccent500;
    private final float mDensity;
    private int mStyle = CARD;
    private int mNothingColors = NOTHING_COLORS_FLAT;
    private boolean mMini;
    private float mScale;

    public QsStylePreviewView(Context context) {
        this(context, null);
    }

    public QsStylePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mPrimary = context.getColor(android.R.color.system_accent1_200);
        mOnPrimary = context.getColor(android.R.color.system_accent1_800);
        mAccent500 = context.getColor(android.R.color.system_accent1_500);
        mLabels = getResources().getStringArray(R.array.qs_style_preview_labels);
        mOn = getResources().getString(R.string.qs_style_preview_on);
        mOff = getResources().getString(R.string.qs_style_preview_off);
        for (int i = 0; i < ICONS.length; i++) {
            final Drawable icon = context.getDrawable(ICONS[i]);
            mIcons[i] = icon != null ? icon.mutate() : null;
        }
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        (mMini ? 14 : 28) * mDensity);
            }
        });
        setClipToOutline(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setStyle(int style) {
        if (mStyle != style) {
            mStyle = style;
            invalidate();
        }
    }

    /** The Nothing color style, as the values of Settings.System.QS_NOTHING_COLOR_STYLE. */
    public void setNothingColorStyle(int colors) {
        if (mNothingColors != colors) {
            mNothingColors = colors;
            invalidate();
        }
    }

    public void setMini(boolean mini) {
        mMini = mini;
        invalidateOutline();
        invalidate();
    }

    private float d(float value) {
        return value * mScale;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        drawBackdrop(canvas, w, h);

        final float pad = (mMini ? 6 : 14) * mDensity;
        mScale = (w - pad * 2) / WIDTH;
        if (!mMini) {
            canvas.saveLayer(0, 0, w, h, null);
        }
        canvas.save();
        canvas.translate(pad, pad);
        final float top = drawSlider(canvas) + 12;
        drawTiles(canvas, top, (h - pad) / mScale);
        canvas.restore();
        if (!mMini) {
            // Tiles fade out at the bottom instead of being cut off.
            final float fade = h - FADE * mDensity;
            mFade.setShader(new LinearGradient(0, fade, 0, h, Color.BLACK,
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, fade, w, h, mFade);
            canvas.restore();
        }
        if (isSelected()) {
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2.5f * mDensity);
            mPaint.setColor(mPrimary);
            mRect.set(0, 0, w, h);
            mRect.inset(1.25f * mDensity, 1.25f * mDensity);
            canvas.drawRoundRect(mRect, 13 * mDensity, 13 * mDensity, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    /** What is behind the panel: a wallpaper under the glass and the translucent panels. */
    private void drawBackdrop(Canvas canvas, int w, int h) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(null);
        final boolean wallpaper = mStyle == COLOROS
                || (mStyle == NOTHING && mNothingColors == NOTHING_COLORS_TRANSLUCENT);
        if (wallpaper) {
            mPaint.setShader(new LinearGradient(0, 0, w * .4f, h, 0xFF3B2440, 0xFF101527,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(new RadialGradient(w * .25f, h * .15f, Math.max(w, h) * .8f,
                    alpha(mAccent500, .75f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(new RadialGradient(w * .85f, h * .9f, Math.max(w, h) * .7f,
                    0xCC2E5BD8, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
        }
        if (mStyle == NOTHING) {
            mPaint.setColor(mNothingColors == NOTHING_COLORS_TRANSLUCENT
                    ? alpha(NOTHING_PANEL, .7f) : NOTHING_PANEL);
            canvas.drawRect(0, 0, w, h, mPaint);
        } else if (mStyle == COLOROS) {
            mPaint.setColor(0x33000000);
            canvas.drawRect(0, 0, w, h, mPaint);
        } else {
            mPaint.setColor(0xFF0A0B0D);
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(new RadialGradient(w * .2f, h * .08f, Math.max(w, h) * .7f,
                    alpha(mAccent500, .4f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
        }
    }

    /** Draws the brightness slider and returns its height in dp. */
    private float drawSlider(Canvas canvas) {
        switch (mStyle) {
            case COLOROS:
                pill(canvas, 0, 0, WIDTH, 40, 20, 0x26FFFFFF, 0x66FFFFFF);
                outline(canvas, 0, 0, WIDTH, 40, 20, 0x40FFFFFF);
                sun(canvas, 22, 20, Color.WHITE);
                return 40;
            case NOTHING:
                pill(canvas, 0, 0, WIDTH, 52, 26, nothingSliderTrack(), nothingSliderFill());
                sun(canvas, 26, 26, nothingSliderInk());
                return 52;
            default:
                pill(canvas, 0, 0, WIDTH, 44, 22, SURFACE, mPrimary);
                sun(canvas, 22, 22, mOnPrimary);
                return 44;
        }
    }

    private void drawTiles(Canvas canvas, float top, float bottom) {
        switch (mStyle) {
            case CLASSIC:
                drawClassic(canvas, top, bottom);
                break;
            case COLOROS:
                drawColorOS(canvas, top, bottom);
                break;
            case NOTHING:
                drawNothing(canvas, top, bottom);
                break;
            default:
                drawCard(canvas, top, bottom);
                break;
        }
    }

    // Card: the wide Internet and Bluetooth tiles, then icon tiles that are rounded squares
    // when on and pills when off.
    private void drawCard(Canvas canvas, float top, float bottom) {
        final float gap = 8;
        final float height = 64;
        final float colWidth = (WIDTH - gap * 3) / 4;
        float y = top;
        int col = 0;
        for (int i = 0; i < ICONS.length; i++) {
            final int span = i < WIDE ? 2 : 1;
            if (col + span > 4) {
                col = 0;
                y += height + gap;
            }
            if (y >= bottom) {
                return;
            }
            final float x = col * (colWidth + gap);
            final float w = span * colWidth + (span - 1) * gap;
            final boolean on = ON[i];
            mRect.set(d(x), d(y), d(x + w), d(y + height));
            fill(canvas, on ? mPrimary : SURFACE, on ? 24 : height / 2);
            final int ink = on ? mOnPrimary : ON_SURFACE;
            if (span == 1) {
                icon(canvas, i, x + w / 2, y + height / 2, 24, ink);
            } else {
                wide(canvas, i, x, y, w, height, 52, on ? alpha(mOnPrimary, .14f) : 0x1FFFFFFF,
                        ink, on ? alpha(mOnPrimary, .8f) : MUTED);
            }
            col += span;
        }
    }

    // Classic: every tile is a round icon with its label below.
    private void drawClassic(Canvas canvas, float top, float bottom) {
        final float gap = 8;
        final float height = 88;
        final float colWidth = (WIDTH - gap * 3) / 4;
        float y = top;
        int col = 0;
        for (int i = 0; i < ICONS.length; i++) {
            if (col == 4) {
                col = 0;
                y += height + gap;
            }
            if (y >= bottom) {
                return;
            }
            final float cx = col * (colWidth + gap) + colWidth / 2;
            final boolean on = ON[i];
            mPaint.setColor(on ? mPrimary : SURFACE);
            canvas.drawCircle(d(cx), d(y + 30), d(30), mPaint);
            icon(canvas, i, cx, y + 30, 24, on ? mOnPrimary : ON_SURFACE);
            label(canvas, mLabels[i], cx, y + 78, colWidth + gap, 11.5f, false, ON_SURFACE,
                    true);
            col++;
        }
    }

    // ColorOS: the wide tiles are glass pills, the icon tiles glass circles that light up with
    // the accent, labels below.
    private void drawColorOS(Canvas canvas, float top, float bottom) {
        final float gap = 17;
        final float colWidth = (WIDTH - gap * 3) / 4;
        float y = top;
        for (int i = 0; i < WIDE; i++) {
            final float w = 2 * colWidth + gap;
            final float x = i * (w + gap);
            final boolean on = ON[i];
            mRect.set(d(x), d(y), d(x + w), d(y + 66));
            fill(canvas, on ? alpha(mPrimary, .4f) : 0x26FFFFFF, 33);
            outline(canvas, x, y, w, 66, 33, on ? alpha(mPrimary, .9f) : 0x55FFFFFF);
            wide(canvas, i, x, y, w, 66, 46, on ? mPrimary : 0x26FFFFFF,
                    on ? mOnPrimary : Color.WHITE, on ? Color.WHITE : MUTED);
        }
        y += 66 + 14;
        int col = 0;
        for (int i = WIDE; i < ICONS.length; i++) {
            if (col == 4) {
                col = 0;
                y += 96;
            }
            if (y >= bottom) {
                return;
            }
            final float cx = col * (colWidth + gap) + colWidth / 2;
            final boolean on = ON[i];
            if (on) {
                mGlow.setColor(alpha(mPrimary, .45f));
                mGlow.setMaskFilter(new BlurMaskFilter(d(12), BlurMaskFilter.Blur.NORMAL));
                canvas.drawCircle(d(cx), d(y + 30), d(30), mGlow);
            }
            mPaint.setColor(on ? alpha(mPrimary, .95f) : 0x26FFFFFF);
            canvas.drawCircle(d(cx), d(y + 30), d(30), mPaint);
            if (!on) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(d(1.2f));
                mPaint.setColor(0x55FFFFFF);
                canvas.drawCircle(d(cx), d(y + 30), d(29.4f), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }
            icon(canvas, i, cx, y + 30, 24, on ? mOnPrimary : Color.WHITE);
            label(canvas, mLabels[i], cx, y + 78, colWidth + gap, 11.5f, false, Color.WHITE,
                    true);
            col++;
        }
    }

    // Nothing: square cells on a 4 column grid, holding circles (1x1), pills (2x1) and quads
    // (2x2).
    private void drawNothing(Canvas canvas, float top, float bottom) {
        final float gap = 16;
        final float cell = (WIDTH - gap * 3) / 4;
        final float pillWidth = 2 * cell + gap;
        float y = top;
        for (int k = 0; k < 2; k++) {
            nothingPill(canvas, k, k * (pillWidth + gap), y, pillWidth, cell);
        }
        y += cell + gap;
        if (y >= bottom) {
            return;
        }
        for (int k = 0; k < 4; k++) {
            nothingCircle(canvas, 2 + k, k * (cell + gap), y, cell);
        }
        y += cell + gap;
        if (y >= bottom) {
            return;
        }
        nothingQuad(canvas, 6, 0, y, pillWidth);
        for (int k = 0; k < 4; k++) {
            nothingCircle(canvas, 7 + k, (2 + k % 2) * (cell + gap),
                    y + (k / 2) * (cell + gap), cell);
        }
    }

    private void nothingPill(Canvas canvas, int i, float x, float y, float w, float h) {
        final boolean on = ON[i];
        mRect.set(d(x), d(y), d(x + w), d(y + h));
        fill(canvas, on ? nothingActive() : nothingSurface(), h / 2);
        final int ink = on ? nothingInkOnActive() : Color.WHITE;
        final float chip = h - 20;
        mPaint.setColor(on ? NOTHING_DIVIDER_ON_ACTIVE : nothingIconCircle());
        canvas.drawCircle(d(x + 10 + chip / 2), d(y + h / 2), d(chip / 2), mPaint);
        icon(canvas, i, x + 10 + chip / 2, y + h / 2, 26, ink);
        final float textLeft = x + 10 + chip + 8;
        label(canvas, mLabels[i], textLeft, y + h / 2 - 2, x + w - textLeft - 8, 14, true, ink,
                false);
        label(canvas, on ? mOn : mOff, textLeft, y + h / 2 + 14, x + w - textLeft - 8, 12,
                false, alpha(ink, .7f), false);
    }

    private void nothingCircle(Canvas canvas, int i, float x, float y, float size) {
        final boolean on = ON[i];
        mPaint.setColor(on ? nothingActive() : nothingSurface());
        canvas.drawCircle(d(x + size / 2), d(y + size / 2), d(size / 2), mPaint);
        icon(canvas, i, x + size / 2, y + size / 2, 28, on ? nothingInkOnActive() : Color.WHITE);
    }

    private void nothingQuad(Canvas canvas, int i, float x, float y, float size) {
        final boolean on = ON[i];
        mRect.set(d(x), d(y), d(x + size), d(y + size));
        fill(canvas, on ? nothingActive() : nothingSurface(), 28);
        final int ink = on ? nothingInkOnActive() : Color.WHITE;
        mPaint.setColor(on ? NOTHING_DIVIDER_ON_ACTIVE : nothingIconCircle());
        canvas.drawCircle(d(x + 14 + 26), d(y + 14 + 26), d(26), mPaint);
        icon(canvas, i, x + 40, y + 40, 28, ink);
        label(canvas, mLabels[i], x + 16, y + size - 38, size - 32, 15, true, ink, false);
        label(canvas, on ? mOn : mOff, x + 16, y + size - 18, size - 32, 12, false,
                alpha(ink, .7f), false);
    }

    private int nothingSurface() {
        return mNothingColors == NOTHING_COLORS_CLASSIC ? NOTHING_QS_SURFACE : NOTHING_OS_SURFACE;
    }

    private int nothingActive() {
        return mNothingColors == NOTHING_COLORS_CLASSIC ? NOTHING_QS_ACTIVE : NOTHING_OS_ACTIVE;
    }

    private int nothingIconCircle() {
        return mNothingColors == NOTHING_COLORS_CLASSIC ? NOTHING_QS_CIRCLE : NOTHING_OS_CIRCLE;
    }

    private int nothingInkOnActive() {
        return Color.BLACK;
    }

    private int nothingSliderTrack() {
        return nothingSurface();
    }

    private int nothingSliderFill() {
        return NOTHING_OS_SLIDER_FILL;
    }

    private int nothingSliderInk() {
        return mNothingColors == NOTHING_COLORS_CLASSIC
                ? NOTHING_QS_SLIDER_INK : NOTHING_OS_SLIDER_INK;
    }

    /** A wide tile's icon in a chip, then its name and state. */
    private void wide(Canvas canvas, int i, float x, float y, float w, float h, float chipSize,
            int chip, int ink, int sub) {
        final float chipLeft = x + 8;
        final float cy = y + h / 2;
        mPaint.setColor(chip);
        canvas.drawCircle(d(chipLeft + chipSize / 2), d(cy), d(chipSize / 2), mPaint);
        icon(canvas, i, chipLeft + chipSize / 2, cy, 22, ink);
        final float textLeft = chipLeft + chipSize + 8;
        final float textWidth = x + w - textLeft - 10;
        label(canvas, mLabels[i], textLeft, cy - 2, textWidth, 14, true, ink, false);
        label(canvas, ON[i] ? mOn : mOff, textLeft, cy + 14, textWidth, 12, false, sub, false);
    }

    /** A sun: a ring. */
    private void sun(Canvas canvas, float cx, float cy, int color) {
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(d(2.2f));
        canvas.drawCircle(d(cx), d(cy), d(5), mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** A slider track at 62%. */
    private void pill(Canvas canvas, float x, float y, float width, float height, float r,
            int track, int fill) {
        mPaint.setColor(track);
        mRect.set(d(x), d(y), d(x + width), d(y + height));
        canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
        mPaint.setColor(fill);
        mRect.set(d(x), d(y), d(x + width * .62f), d(y + height));
        canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
    }

    private void outline(Canvas canvas, float x, float y, float width, float height, float r,
            int color) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(d(1.5f));
        mPaint.setColor(color);
        mRect.set(d(x), d(y), d(x + width), d(y + height));
        mRect.inset(d(.75f), d(.75f));
        canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** Fills the tile rect in mRect. */
    private void fill(Canvas canvas, int color, float r) {
        if (color == Color.TRANSPARENT) {
            return;
        }
        mPaint.setColor(color);
        canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
    }

    private void icon(Canvas canvas, int i, float cx, float cy, float size, int color) {
        final Drawable icon = mIcons[i];
        if (icon == null) {
            return;
        }
        final float half = d(size) / 2;
        icon.setBounds(Math.round(d(cx) - half), Math.round(d(cy) - half),
                Math.round(d(cx) + half), Math.round(d(cy) + half));
        icon.setTint(color);
        icon.draw(canvas);
    }

    /** One line of text starting at x, or centered on it. */
    private void label(Canvas canvas, String text, float x, float baseline, float width,
            float size, boolean bold, int color, boolean centered) {
        if (mMini || text == null) {
            return;
        }
        mText.setTextSize(d(size));
        mText.setTypeface(bold ? MEDIUM : Typeface.DEFAULT);
        mText.setColor(color);
        final CharSequence fitted = TextUtils.ellipsize(text, mText, d(width),
                TextUtils.TruncateAt.END);
        final float textWidth = mText.measureText(fitted, 0, fitted.length());
        final float start = centered ? d(x) - textWidth / 2 : d(x);
        canvas.drawText(fitted, 0, fitted.length(), start, d(baseline), mText);
    }

    private static int alpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }
}
