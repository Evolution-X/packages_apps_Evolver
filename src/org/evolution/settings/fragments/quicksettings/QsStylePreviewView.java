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
import android.graphics.Path;
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
    public static final int REMIX = 4;

    // The Remix looks, the values of Settings.System.QS_REMIX_STYLE and QsStyle in SystemUI.
    // 7 belonged to RR's Catalyxx glass, which Liquid Glass replaces.
    public static final int LOOK_STOCK = 0;
    public static final int LOOK_REMIX = 1;
    public static final int LOOK_COMPACT = 2;
    public static final int LOOK_GLASS = 3;
    public static final int LOOK_CIRCLES = 4;
    public static final int LOOK_CLASSIC = 5;
    public static final int LOOK_CARDS = 6;
    public static final int LOOK_NEON = 8;
    public static final int LOOK_BENTO = 9;
    public static final int LOOK_SHAPES = 10;
    public static final int LOOK_BLUEPRINT = 11;
    public static final int LOOK_DUO = 12;
    public static final int LOOK_SOFT = 13;

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
    private static final int SOFT_SURFACE = 0xFF1C1716;
    // Lobes an on tile grows in Shapes, and how deep, as SystemUI picks them.
    private static final int[] LOBES = {8, 4, 6, 12, 5};
    private static final float[] DEPTHS = {.07f, .13f, .09f, .045f, .1f};

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
    private final Path mPath = new Path();
    private final Drawable[] mIcons = new Drawable[ICONS.length];
    private final String[] mLabels;
    private final String mOn;
    private final String mOff;
    private final int mPrimary;
    private final int mOnPrimary;
    private final int mAccent500;
    private final int mAccent600;
    private final int mAccent700;
    private final int mAccent900;
    // The bright accent the newer Remix looks fill with, and the ink on it.
    private final int mVivid;
    private final int mVividInk;
    private final float mDensity;
    private int mStyle = CARD;
    private int mNothingColors = NOTHING_COLORS_FLAT;
    private int mRemixLook = LOOK_STOCK;
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
        mAccent600 = context.getColor(android.R.color.system_accent1_600);
        mAccent700 = context.getColor(android.R.color.system_accent1_700);
        mAccent900 = context.getColor(android.R.color.system_accent1_900);
        mVivid = context.getColor(android.R.color.system_primary_dark);
        mVividInk = context.getColor(android.R.color.system_on_primary_dark);
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

    /** The Remix look, as the values of Settings.System.QS_REMIX_STYLE. */
    public void setRemixLook(int look) {
        if (mRemixLook != look) {
            mRemixLook = look;
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
        } else if (mStyle == REMIX && mRemixLook == LOOK_SOFT) {
            // Soft stays flat; its tiles need the panel's own color around them.
            mPaint.setColor(SOFT_SURFACE);
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
            case REMIX:
                return drawRemixSlider(canvas);
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
            case REMIX:
                drawRemixTiles(canvas, top, bottom);
                break;
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

    // Remix: the looks of the ResurrectedRemixOS tiles, as RemixTile in SystemUI draws them.
    private float drawRemixSlider(Canvas canvas) {
        switch (mRemixLook) {
            case LOOK_REMIX: {
                mRect.set(0, 0, d(WIDTH), d(72));
                mPaint.setShader(new LinearGradient(0, 0, mRect.right, mRect.bottom,
                        mAccent600, mAccent900, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(mRect, d(26), d(26), mPaint);
                mPaint.setShader(null);
                pill(canvas, 14, 14, WIDTH - 28, 44, 22, 0x29FFFFFF, Color.WHITE);
                return 72;
            }
            case LOOK_COMPACT:
                pill(canvas, 0, 0, WIDTH, 30, 15, SURFACE, mPrimary);
                return 30;
            case LOOK_GLASS:
                pill(canvas, 0, 0, WIDTH, 40, 20, 0x0DFFFFFF, (mPrimary & 0x00FFFFFF) | 0x8C000000);
                outline(canvas, 0, 0, WIDTH, 40, 20, 0x29FFFFFF);
                return 40;
            case LOOK_CLASSIC: {
                mPaint.setColor(mPrimary);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(d(2.2f));
                canvas.drawCircle(d(12), d(12), d(5), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                pill(canvas, 34, 10, WIDTH - 34, 4, 2, 0x2EFFFFFF, mPrimary);
                canvas.drawCircle(d(34 + (WIDTH - 34) * .62f), d(12), d(10), mPaint);
                return 24;
            }
            case LOOK_NEON: {
                final float knob = 34 + (WIDTH - 34) * .62f;
                sun(canvas, 12, 12, mVivid);
                pill(canvas, 34, 9, WIDTH - 34, 6, 3, 0x1AFFFFFF, mVivid);
                mGlow.setColor(alpha(mVivid, .6f));
                mGlow.setMaskFilter(new BlurMaskFilter(d(5), BlurMaskFilter.Blur.NORMAL));
                mRect.set(d(34), d(9), d(knob), d(15));
                canvas.drawRoundRect(mRect, d(3), d(3), mGlow);
                mPaint.setColor(0xFF050307);
                canvas.drawCircle(d(knob), d(12), d(10), mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(d(2));
                mPaint.setColor(mVivid);
                canvas.drawCircle(d(knob), d(12), d(10), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                return 24;
            }
            case LOOK_BENTO:
                pill(canvas, 0, 0, WIDTH, 52, 22, 0x1AFFFFFF, mVivid);
                return 52;
            case LOOK_SHAPES: {
                final float split = WIDTH * .62f;
                mPaint.setColor(mVivid);
                round(canvas, 0, 2, split - 6, 52, 18);
                round(canvas, split - 2, 0, split + 2, 54, 2);
                mPaint.setColor(0x2EFFFFFF);
                round(canvas, split + 6, 2, WIDTH, 52, 18);
                return 54;
            }
            case LOOK_BLUEPRINT: {
                mPaint.setColor(0x40FFFFFF);
                for (int i = 0; i <= 10; i++) {
                    final float x = WIDTH * i / 10f;
                    round(canvas, x - .5f, i % 5 == 0 ? 10 : 16, x + .5f, 24, 0);
                }
                round(canvas, 0, 23.5f, WIDTH, 24.5f, 0);
                mPaint.setColor(mVivid);
                round(canvas, 0, 23, WIDTH * .62f, 25, 0);
                round(canvas, WIDTH * .62f - 1, 0, WIDTH * .62f + 1, 24, 0);
                return 26;
            }
            case LOOK_DUO: {
                mPaint.setColor(mVivid);
                segment(canvas, 0, 0, 44, 44, 14, 5);
                sun(canvas, 22, 22, mVividInk);
                final float split = 47 + (WIDTH - 47) * .62f;
                mPaint.setColor(0x12FFFFFF);
                segment(canvas, 47, 0, WIDTH, 44, 5, 14);
                mPaint.setColor(alpha(mVivid, .3f));
                segment(canvas, 47, 0, split, 44, 5, 0);
                mPaint.setColor(mVivid);
                round(canvas, split - 2, 10, split + 2, 34, 2);
                return 44;
            }
            case LOOK_SOFT: {
                final float knob = 34 + (WIDTH - 34) * .62f;
                sun(canvas, 12, 15, 0xB3EDEEF0);
                mPaint.setColor(0xFF110D0C);
                round(canvas, 34, 6, WIDTH, 24, 9);
                mPaint.setColor(mVivid);
                round(canvas, 36, 8, knob, 22, 7);
                mGlow.setColor(0xE60B0908);
                mGlow.setMaskFilter(new BlurMaskFilter(d(5), BlurMaskFilter.Blur.NORMAL));
                canvas.drawCircle(d(knob + 3), d(18), d(15), mGlow);
                mPaint.setColor(0xFF2A2321);
                canvas.drawCircle(d(knob), d(15), d(15), mPaint);
                mPaint.setColor(mVivid);
                canvas.drawCircle(d(knob), d(15), d(4), mPaint);
                return 30;
            }
            default:
                pill(canvas, 0, 0, WIDTH, 44, 22, SURFACE, mPrimary);
                return 44;
        }
    }

    private void drawRemixTiles(Canvas canvas, float top, float bottom) {
        final boolean uniform = mRemixLook == LOOK_CLASSIC || mRemixLook == LOOK_CARDS
                || mRemixLook == LOOK_SHAPES;
        final int cols = (mRemixLook == LOOK_CLASSIC || mRemixLook == LOOK_SHAPES) ? 5
                : mRemixLook == LOOK_CARDS ? 3 : 4;
        final float gap;
        switch (mRemixLook) {
            case LOOK_CLASSIC: gap = 4; break;
            case LOOK_SHAPES: gap = 6; break;
            case LOOK_SOFT: gap = 14; break;
            case LOOK_CARDS:
            case LOOK_GLASS:
            case LOOK_NEON:
            case LOOK_BENTO: gap = 10; break;
            default: gap = 8; break;
        }
        final float height = remixTileHeight();
        final float colWidth = (WIDTH - gap * (cols - 1)) / cols;
        float y = top;
        int col = 0;
        for (int i = 0; i < ICONS.length; i++) {
            final int span = !uniform && i < WIDE ? 2 : 1;
            if (col + span > cols) {
                col = 0;
                y += height + gap;
            }
            if (y >= bottom) {
                return;
            }
            final float x = col * (colWidth + gap);
            drawRemixTile(canvas, i, x, y, span * colWidth + (span - 1) * gap, height, span > 1);
            col += span;
        }
    }

    private float remixTileHeight() {
        switch (mRemixLook) {
            case LOOK_REMIX: return 60;
            case LOOK_COMPACT: return 48;
            case LOOK_GLASS: return 64;
            case LOOK_CIRCLES: return 80;
            case LOOK_CLASSIC: return 78;
            case LOOK_CARDS: return 104;
            case LOOK_NEON:
            case LOOK_SOFT: return 64;
            case LOOK_BENTO: return 80;
            case LOOK_SHAPES: return 88;
            case LOOK_BLUEPRINT: return 56;
            case LOOK_DUO: return 60;
            default: return 72;
        }
    }

    private void drawRemixTile(Canvas canvas, int i, float x, float y, float w, float h,
            boolean wide) {
        final boolean on = ON[i];
        mRect.set(d(x), d(y), d(x + w), d(y + h));
        switch (mRemixLook) {
            case LOOK_CLASSIC: {
                icon(canvas, i, x + w / 2, y + h / 2 - 10, 26,
                        on ? mPrimary : 0x8CEDEEF0);
                label(canvas, mLabels[i], x + w / 2, y + h / 2 + 22, w - 4, 11.5f, false,
                        on ? ON_SURFACE : MUTED, true);
                return;
            }
            case LOOK_CIRCLES:
                if (!wide) {
                    mPaint.setColor(on ? mPrimary : SURFACE);
                    canvas.drawCircle(d(x + w / 2), d(y + 30), d(28), mPaint);
                    icon(canvas, i, x + w / 2, y + 30, 24, on ? mOnPrimary : ON_SURFACE);
                    label(canvas, mLabels[i], x + w / 2, y + 74, w, 11.5f, true, ON_SURFACE,
                            true);
                    return;
                }
                break;
            case LOOK_CARDS: {
                fill(canvas, on ? mPrimary : SURFACE, 26);
                mPaint.setColor(on ? alpha(mOnPrimary, .14f) : SURFACE);
                canvas.drawCircle(d(x + 14 + 19), d(y + 14 + 19), d(19), mPaint);
                icon(canvas, i, x + 33, y + 33, 20, on ? mOnPrimary : ON_SURFACE);
                label(canvas, mLabels[i], x + 14, y + h - 34, w - 28, 14, true,
                        on ? mOnPrimary : ON_SURFACE, false);
                label(canvas, on ? mOn : mOff, x + 14, y + h - 16, w - 28, 12, false,
                        on ? alpha(mOnPrimary, .8f) : MUTED, false);
                return;
            }
            case LOOK_SHAPES: {
                final float cx = x + w / 2;
                final float cy = y + 31;
                blob(cx, cy, 29, LOBES[i % LOBES.length], on ? DEPTHS[i % DEPTHS.length] : 0);
                mPaint.setColor(on ? mVivid : SURFACE);
                canvas.drawPath(mPath, mPaint);
                icon(canvas, i, cx, cy, 24, on ? mVividInk : ON_SURFACE);
                label(canvas, mLabels[i], cx, y + 80, w, 11.5f, true, ON_SURFACE, true);
                return;
            }
            case LOOK_BENTO:
                if (!wide) {
                    final int ink = on ? mVividInk : ON_SURFACE;
                    fill(canvas, on ? mVivid : SURFACE, 22);
                    icon(canvas, i, x + w / 2, y + h / 2 - 9, 22, ink);
                    label(canvas, mLabels[i], x + w / 2, y + h / 2 + 20, w - 6, 11, true, ink,
                            true);
                    return;
                }
                fill(canvas, on ? mVivid : SURFACE, 26);
                remixWideLabels(canvas, i, x, y, w, h, 8, 46,
                        on ? alpha(mVividInk, .14f) : 0x14FFFFFF, on ? mVividInk : ON_SURFACE,
                        on ? alpha(mVividInk, .8f) : MUTED, false);
                return;
            case LOOK_NEON:
                if (on) {
                    mGlow.setColor(alpha(mVivid, .55f));
                    mGlow.setMaskFilter(new BlurMaskFilter(d(10), BlurMaskFilter.Blur.NORMAL));
                    canvas.drawRoundRect(mRect, d(20), d(20), mGlow);
                }
                fill(canvas, on ? alpha(mVivid, .1f) : 0x08FFFFFF, 20);
                outline(canvas, x, y, w, h, 20, on ? mVivid : 0x1FFFFFFF);
                if (!wide) {
                    icon(canvas, i, x + w / 2, y + h / 2, 22, on ? mVivid : 0xB3EDEEF0);
                    return;
                }
                remixWideLabels(canvas, i, x, y, w, h, 2, 40, Color.TRANSPARENT,
                        on ? mVivid : 0xB3EDEEF0, on ? mVivid : MUTED, false);
                return;
            case LOOK_BLUEPRINT: {
                if (on) {
                    fill(canvas, alpha(mVivid, .08f), 10);
                }
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(d(1));
                mPaint.setColor(on ? mVivid : 0x2EFFFFFF);
                mRect.inset(d(.5f), d(.5f));
                canvas.drawRoundRect(mRect, d(10), d(10), mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                final int ink = on ? mVivid : 0xB3EDEEF0;
                if (!wide) {
                    icon(canvas, i, x + w / 2, y + h / 2, 20, ink);
                    return;
                }
                remixWideLabels(canvas, i, x, y, w, h, 2, 36, Color.TRANSPARENT, ink,
                        on ? mVivid : MUTED, true);
                return;
            }
            case LOOK_DUO: {
                final int segment = on ? mVivid : 0x1AFFFFFF;
                final int ink = on ? mVividInk : ON_SURFACE;
                mPaint.setColor(segment);
                if (!wide) {
                    round(canvas, x, y, x + w, y + h, 20);
                    icon(canvas, i, x + w / 2, y + h / 2, 22, ink);
                    return;
                }
                segment(canvas, x, y, x + h, y + h, 20, 6);
                icon(canvas, i, x + h / 2, y + h / 2, 22, ink);
                mPaint.setColor(0x12FFFFFF);
                segment(canvas, x + h + 3, y, x + w, y + h, 6, 20);
                final float textLeft = x + h + 15;
                final float textWidth = x + w - textLeft - 10;
                label(canvas, mLabels[i], textLeft, y + h / 2 - 2, textWidth, 14, true,
                        ON_SURFACE, false);
                label(canvas, on ? mOn : mOff, textLeft, y + h / 2 + 14, textWidth, 12, false,
                        on ? mVivid : MUTED, false);
                return;
            }
            case LOOK_SOFT: {
                if (on) {
                    // Pressed in: darker, shaded at the top left edge.
                    fill(canvas, 0xFF161211, 22);
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(d(3));
                    mPaint.setShader(new LinearGradient(mRect.left, mRect.top, mRect.right,
                            mRect.bottom, 0xCC0B0908, 0x402E2725, Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(mRect, d(22), d(22), mPaint);
                    mPaint.setShader(null);
                    mPaint.setStyle(Paint.Style.FILL);
                } else {
                    mGlow.setMaskFilter(new BlurMaskFilter(d(7), BlurMaskFilter.Blur.NORMAL));
                    mGlow.setColor(0xE60B0908);
                    canvas.save();
                    canvas.translate(d(4), d(4));
                    canvas.drawRoundRect(mRect, d(22), d(22), mGlow);
                    canvas.restore();
                    mGlow.setColor(0xFF2E2725);
                    canvas.save();
                    canvas.translate(-d(3), -d(3));
                    canvas.drawRoundRect(mRect, d(22), d(22), mGlow);
                    canvas.restore();
                    fill(canvas, SOFT_SURFACE, 22);
                }
                final int ink = on ? mVivid : 0xBFEDEEF0;
                if (!wide) {
                    icon(canvas, i, x + w / 2, y + h / 2, 22, ink);
                    return;
                }
                remixWideLabels(canvas, i, x, y, w, h, 4, 40, Color.TRANSPARENT, ink,
                        on ? mVivid : MUTED, false);
                return;
            }
            default:
                break;
        }

        int bg;
        int chip = Color.TRANSPARENT;
        int ink;
        int sub;
        float r;
        float chipSize;
        float chipRadius;
        float labelSize = 14;
        float subSize = 12;
        switch (mRemixLook) {
            case LOOK_REMIX:
                r = 20;
                bg = on ? Color.TRANSPARENT : 0xFF1E2024;
                if (on) {
                    mPaint.setShader(new LinearGradient(mRect.left, mRect.top, mRect.right,
                            mRect.bottom, mAccent500, mAccent700, Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
                    mPaint.setShader(null);
                }
                chip = on ? 0x38FFFFFF : 0xFF2B2E33;
                ink = on ? Color.WHITE : 0xFFE9EAEC;
                sub = on ? 0xCCFFFFFF : 0xFF9DA1A7;
                chipSize = 40;
                chipRadius = 20;
                break;
            case LOOK_COMPACT:
                r = 24;
                bg = on ? mPrimary : 0x17FFFFFF;
                chip = on ? alpha(mOnPrimary, .14f) : SURFACE;
                ink = on ? mOnPrimary : ON_SURFACE;
                sub = on ? alpha(mOnPrimary, .8f) : MUTED;
                chipSize = 36;
                chipRadius = 18;
                labelSize = 13;
                subSize = 11;
                break;
            case LOOK_GLASS:
                r = 22;
                if (on) {
                    mGlow.setColor(alpha(mAccent500, .45f));
                    mGlow.setMaskFilter(new BlurMaskFilter(d(14), BlurMaskFilter.Blur.NORMAL));
                    canvas.drawRoundRect(mRect, d(r), d(r), mGlow);
                }
                bg = on ? alpha(mPrimary, .2f) : 0x09FFFFFF;
                ink = on ? mPrimary : ON_SURFACE;
                sub = on ? mPrimary : MUTED;
                chipSize = 44;
                chipRadius = 22;
                break;
            case LOOK_CIRCLES:
                r = 26;
                bg = on ? mPrimary : SURFACE;
                chip = on ? alpha(mOnPrimary, .14f) : SURFACE;
                ink = on ? mOnPrimary : ON_SURFACE;
                sub = on ? alpha(mOnPrimary, .8f) : MUTED;
                chipSize = 48;
                chipRadius = 24;
                break;
            default:
                r = on ? 24 : h / 2;
                bg = wide ? SURFACE : (on ? mPrimary : SURFACE);
                chip = on ? mPrimary : 0x1FFFFFFF;
                ink = on ? mOnPrimary : ON_SURFACE;
                sub = MUTED;
                chipSize = 56;
                chipRadius = on ? 16 : 28;
                break;
        }
        fill(canvas, bg, r);
        if (mRemixLook == LOOK_GLASS) {
            outline(canvas, x, y, w, h, r, on ? alpha(mPrimary, .95f) : 0x21FFFFFF);
        }
        if (!wide) {
            icon(canvas, i, x + w / 2, y + h / 2, mRemixLook == LOOK_COMPACT ? 20 : 24, ink);
            return;
        }
        final float chipLeft = x + 8;
        final float cy = y + h / 2;
        if (chip != Color.TRANSPARENT) {
            mPaint.setColor(chip);
            mRect.set(d(chipLeft), d(cy - chipSize / 2), d(chipLeft + chipSize),
                    d(cy + chipSize / 2));
            canvas.drawRoundRect(mRect, d(chipRadius), d(chipRadius), mPaint);
        }
        int chipInk = ink;
        if (mRemixLook == LOOK_STOCK) {
            chipInk = on ? mOnPrimary : ON_SURFACE;
        }
        icon(canvas, i, chipLeft + chipSize / 2, cy, mRemixLook == LOOK_COMPACT ? 18 : 22, chipInk);
        final int labelInk = mRemixLook == LOOK_STOCK ? ON_SURFACE : ink;
        final float textLeft = chipLeft + chipSize + 8;
        final float textWidth = x + w - textLeft - (mRemixLook == LOOK_CIRCLES ? 26 : 10);
        label(canvas, mLabels[i], textLeft, cy - 2, textWidth, labelSize, true, labelInk, false);
        label(canvas, on ? mOn : mOff, textLeft, cy + subSize + 2, textWidth, subSize, false,
                sub, false);
        if (mRemixLook == LOOK_CIRCLES && !mMini) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(d(2.2f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(alpha(ink, .6f));
            final float cx = x + w - 16;
            canvas.drawLine(d(cx - 3), d(cy - 6), d(cx + 3), d(cy), mPaint);
            canvas.drawLine(d(cx + 3), d(cy), d(cx - 3), d(cy + 6), mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    /** A wide tile's icon in an optional chip, then its name and state. */
    private void remixWideLabels(Canvas canvas, int i, float x, float y, float w, float h,
            float chipLeftPad, float chipSize, int chip, int ink, int sub, boolean caps) {
        final float chipLeft = x + chipLeftPad + 6;
        final float cy = y + h / 2;
        if (chip != Color.TRANSPARENT) {
            mPaint.setColor(chip);
            canvas.drawCircle(d(chipLeft + chipSize / 2), d(cy), d(chipSize / 2), mPaint);
        }
        icon(canvas, i, chipLeft + chipSize / 2, cy, 22, ink);
        final float textLeft = chipLeft + chipSize + 6;
        final float textWidth = x + w - textLeft - 10;
        final String name = caps ? mLabels[i].toUpperCase() : mLabels[i];
        final String state = ON[i] ? mOn : mOff;
        if (caps) {
            mono(canvas, name, textLeft, cy - 1, textWidth, 11, ON_SURFACE);
            mono(canvas, state.toUpperCase(), textLeft, cy + 13, textWidth, 9.5f, sub);
        } else {
            label(canvas, name, textLeft, cy - 2, textWidth, 14, true,
                    mRemixLook == LOOK_BENTO ? ink : ON_SURFACE, false);
            label(canvas, state, textLeft, cy + 14, textWidth, 12, false, sub, false);
        }
    }

    /** Blueprint's monospace caps. */
    private void mono(Canvas canvas, String text, float x, float baseline, float width,
            float size, int color) {
        if (mMini || text == null) {
            return;
        }
        mText.setTextSize(d(size));
        mText.setTypeface(Typeface.MONOSPACE);
        mText.setLetterSpacing(.06f);
        mText.setColor(color);
        final CharSequence fitted = TextUtils.ellipsize(text, mText, d(width),
                TextUtils.TruncateAt.END);
        canvas.drawText(fitted, 0, fitted.length(), d(x), d(baseline), mText);
        mText.setLetterSpacing(0);
    }

    /** Fills a rounded rect in the current paint color. */
    private void round(Canvas canvas, float left, float top, float right, float bottom, float r) {
        mRect.set(d(left), d(top), d(right), d(bottom));
        canvas.drawRoundRect(mRect, d(r), d(r), mPaint);
    }

    /** Fills a Duo segment: radius start on the left corners, end on the right ones. */
    private void segment(Canvas canvas, float left, float top, float right, float bottom,
            float start, float end) {
        mRect.set(d(left), d(top), d(right), d(bottom));
        mPath.reset();
        mPath.addRoundRect(mRect, new float[] {d(start), d(start), d(end), d(end), d(end),
                d(end), d(start), d(start)}, Path.Direction.CW);
        canvas.drawPath(mPath, mPaint);
    }

    /** Sets mPath to a circle growing lobes, like a Shapes tile. */
    private void blob(float cx, float cy, float radius, int lobes, float depth) {
        mPath.reset();
        final float base = radius / (1 + depth);
        for (int i = 0; i < 96; i++) {
            final double angle = i * 2 * Math.PI / 96;
            final float r = (float) (base * (1 + depth * Math.cos(lobes * angle)));
            final float x = d(cx + r * (float) Math.sin(angle));
            final float y = d(cy - r * (float) Math.cos(angle));
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        mPath.close();
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
