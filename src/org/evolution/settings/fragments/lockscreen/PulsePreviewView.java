package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Animated stand-in for SystemUI's pulse renderers, fed by a fake beat. It follows the pulse
 * settings live and mimics every renderer of pulse_renderer, see {@link #supports(String)}. Keep
 * the drawing in step with the *StyleRenderer classes in SystemUI.
 */
public class PulsePreviewView extends View {
    private static final String RENDERER_SOLID = "solid";
    private static final String RENDERER_FADING = "fading";
    private static final String RENDERER_MINIMAL = "minimal";
    private static final String RENDERER_NEON = "neon";
    private static final String RENDERER_RETRO = "retro";
    private static final String RENDERER_SPARKLE = "sparkle";
    private static final String RENDERER_MATRIX = "matrix";
    private static final String RENDERER_PARTICLE = "particle";
    private static final String RENDERER_WAVEFORM = "waveform";
    private static final String RENDERER_DOTWAVE = "dotwave";
    private static final String COLOR_LAVALAMP = "lavalamp";
    private static final String COLOR_CUSTOM = "custom";

    private static final int FRAME_MS = 25;
    private static final int BEAT_MS = 480;
    private static final int PULSE_ALPHA = (int) (0.88f * 255);
    private static final float SOLID_SMOOTHING = 0.2f;
    private static final float MINIMAL_SMOOTHING = 0.3f;
    private static final float ROUNDED_RADIUS = 32f;
    private static final float NEON_SMOOTHING = 0.25f;
    private static final float RETRO_SMOOTHING = 0.15f;
    private static final float DOTWAVE_SMOOTHING = 0.2f;
    private static final float WAVEFORM_SMOOTHING = 0.25f;
    private static final float SPARKLE_SMOOTHING = 0.25f;
    private static final float MATRIX_SMOOTHING = 0.22f;
    private static final int RETRO_SEGMENTS = 12;
    private static final int SPARKLES_PER_BAR = 10;
    private static final int INITIAL_PARTICLES = 150;
    private static final int MAX_PARTICLES = 300;
    private static final float PARTICLE_LIFE_DECAY = 0.012f;
    private static final float PARTICLE_AUDIO_GATE = 0.08f;
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFade = new Paint();
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMatrixGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMatrixBright = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMatrixMedium = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMatrixDark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMatrixTextGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mRetroColors = new int[RETRO_SEGMENTS];
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();
    private final float[] mRadii = {ROUNDED_RADIUS, ROUNDED_RADIUS, ROUNDED_RADIUS,
            ROUNDED_RADIUS, 0f, 0f, 0f, 0f};
    private final float[] mHsv = {0f, 1f, 1f};
    private final Random mRandom = new Random(7);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            readSettings();
        }
    };
    private final float mDensity;
    private final int mAccent;
    private float[] mTargets = new float[0];
    private float[] mLevels = new float[0];
    private float[] mPhases = new float[0];
    private float[] mFadePoints = new float[0];
    private Bitmap mBitmap;
    private Canvas mBitmapCanvas;
    private final ArrayList<Particle> mParticles = new ArrayList<>();
    private int mParticleW;
    private int mParticleH;
    private float mBass;
    private float mMid;
    private float mTreble;
    private float mAudio;
    private float[] mSparkleX = new float[0];
    private float[] mSparkleY = new float[0];
    private float[] mSparkleVx = new float[0];
    private float[] mSparkleVy = new float[0];
    private float[] mSparkleSize = new float[0];
    private float[] mSparkleLife = new float[0];
    private boolean[] mSparkleAlive = new boolean[0];
    private int mSparkleCursor;
    private long mSparkleLast;
    private String[][] mMatrixChars = new String[0][];
    private int mMatrixTick;

    private String mRenderer = RENDERER_SOLID;
    private String mColorMode = COLOR_LAVALAMP;
    private int mCustomColor = Color.WHITE;
    private boolean mRounded;
    private float mHeightScale = 1f;

    public PulsePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mAccent = context.getColor(android.R.color.system_accent1_100);
        mFade.setColor(Color.argb(200, 255, 255, 255));
        mFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeCap(Paint.Cap.BUTT);
        mPaint.setStyle(Paint.Style.FILL);
        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlowPaint.setMaskFilter(new BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL));
        mCorePaint.setStyle(Paint.Style.STROKE);
        mCorePaint.setStrokeCap(Paint.Cap.ROUND);
        mCorePaint.setColor(Color.WHITE);
        mFillPaint.setStyle(Paint.Style.FILL);
        mSparklePaint.setStyle(Paint.Style.FILL);
        mSparklePaint.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL));
        mTrailPaint.setStyle(Paint.Style.FILL);
        mParticlePaint.setStyle(Paint.Style.FILL);
        mMatrixGlow.setStyle(Paint.Style.FILL);
        mMatrixGlow.setMaskFilter(new BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL));
        for (Paint text : new Paint[] {mMatrixBright, mMatrixMedium, mMatrixDark,
                mMatrixTextGlow}) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.MONOSPACE);
            text.setStyle(Paint.Style.FILL);
        }
        mMatrixTextGlow.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));
        // The meter keeps its own colors: the first 60% green, the next 25% yellow, the rest red.
        final int green = (int) (RETRO_SEGMENTS * 0.6f);
        final int yellow = (int) (RETRO_SEGMENTS * 0.25f);
        for (int i = 0; i < RETRO_SEGMENTS; i++) {
            mRetroColors[i] = i < green ? Color.argb(255, 50, 220, 80)
                    : i < green + yellow ? Color.argb(255, 255, 200, 50)
                    : Color.argb(255, 255, 50, 50);
        }
        setBars(32);
        readSettings();
    }

    /** Whether the preview can draw the given renderer value of pulse_renderer. */
    public static boolean supports(String renderer) {
        if (renderer == null) {
            return false;
        }
        switch (renderer) {
            case RENDERER_SOLID, RENDERER_FADING, RENDERER_MINIMAL, RENDERER_NEON,
                    RENDERER_RETRO, RENDERER_SPARKLE, RENDERER_MATRIX, RENDERER_PARTICLE,
                    RENDERER_WAVEFORM, RENDERER_DOTWAVE -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (String key : new String[] {
                Settings.Secure.PULSE_RENDERER,
                Settings.Secure.PULSE_COLOR,
                Settings.Secure.PULSE_CUSTOM_COLOR,
                Settings.Secure.PULSE_BAR_COUNT,
                Settings.Secure.PULSE_HEIGHT_MULTIPLIER,
                Settings.Secure.PULSE_ROUNDED_BARS}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        readSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().getContentResolver().unregisterContentObserver(mObserver);
        recycleBitmap();
    }

    private void readSettings() {
        final Context context = getContext();
        String renderer = Settings.Secure.getStringForUser(context.getContentResolver(),
                Settings.Secure.PULSE_RENDERER, UserHandle.USER_CURRENT);
        String newRenderer = supports(renderer) ? renderer : RENDERER_SOLID;
        if (!newRenderer.equals(mRenderer)) {
            mRenderer = newRenderer;
            recycleBitmap();
            mParticles.clear();
            mParticleW = 0;
            mSparkleLast = 0;
        }
        String color = Settings.Secure.getStringForUser(context.getContentResolver(),
                Settings.Secure.PULSE_COLOR, UserHandle.USER_CURRENT);
        mColorMode = color != null ? color : COLOR_LAVALAMP;
        mCustomColor = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.PULSE_CUSTOM_COLOR, Color.WHITE, UserHandle.USER_CURRENT);
        mRounded = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.PULSE_ROUNDED_BARS, 0, UserHandle.USER_CURRENT) == 1;
        mHeightScale = Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.PULSE_HEIGHT_MULTIPLIER, 100, UserHandle.USER_CURRENT) / 100f;
        setBars(Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.PULSE_BAR_COUNT, 32, UserHandle.USER_CURRENT));
        invalidate();
    }

    private void setBars(int bars) {
        if (bars == mLevels.length) {
            return;
        }
        mTargets = new float[bars];
        mLevels = new float[bars];
        mPhases = new float[bars];
        mFadePoints = new float[bars * 4];
        for (int i = 0; i < bars; i++) {
            mPhases[i] = mRandom.nextFloat() * 6.283f;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        step(height);
        int color = pulseColor();
        switch (mRenderer) {
            case RENDERER_FADING -> drawFading(canvas, width, height, color);
            case RENDERER_MINIMAL -> drawMinimal(canvas, width, height, color);
            case RENDERER_NEON -> drawNeon(canvas, width, height, color);
            case RENDERER_RETRO -> drawRetro(canvas, width, height);
            case RENDERER_SPARKLE -> drawSparkle(canvas, width, height, color);
            case RENDERER_MATRIX -> drawMatrix(canvas, width, height, color);
            case RENDERER_PARTICLE -> drawParticles(canvas, width, height, color);
            case RENDERER_WAVEFORM -> drawWaveform(canvas, width, height, color);
            case RENDERER_DOTWAVE -> drawDotWave(canvas, width, height, color);
            default -> drawSolid(canvas, width, height, color);
        }
        if (isShown()) {
            postInvalidateDelayed(FRAME_MS);
        }
    }

    /** Bass-heavy bar heights, in pixels, that kick on the beat and shimmer in between. */
    private void step(int height) {
        long now = SystemClock.uptimeMillis();
        float beat = 1f - (now % BEAT_MS) / (float) BEAT_MS;
        beat = beat * beat;
        float t = now / 1000f;
        int n = mTargets.length;
        for (int i = 0; i < n; i++) {
            float x = i / (float) Math.max(1, n - 1);
            float body = 0.85f - 0.45f * x;
            float shimmer = 0.5f + 0.5f * (float) Math.sin(t * (3f + 9f * x) + mPhases[i]);
            float kick = beat * (1f - x) * 0.55f;
            float level = Math.min(1f, body * (0.25f + 0.55f * shimmer) + kick
                    + mRandom.nextFloat() * 0.08f);
            // The particles do not follow the height setting, as in SystemUI.
            final float scale = RENDERER_PARTICLE.equals(mRenderer) ? 1f : mHeightScale;
            mTargets[i] = Math.min(height, level * scale * height);
        }
    }

    /** Same colors as PulseRenderer, the album color has no art here and uses the accent. */
    private int pulseColor() {
        int color;
        if (COLOR_LAVALAMP.equals(mColorMode)) {
            mHsv[0] = (System.currentTimeMillis() / 50) % 360;
            return Color.HSVToColor(PULSE_ALPHA, mHsv);
        } else if (COLOR_CUSTOM.equals(mColorMode)) {
            color = mCustomColor;
        } else {
            color = mAccent;
        }
        return (PULSE_ALPHA << 24) | (color & 0x00FFFFFF);
    }

    private float smooth(int i, float smoothing, float max) {
        float h = mLevels[i] + smoothing * (mTargets[i] - mLevels[i]);
        h = Math.max(2f, Math.min(max, h));
        mLevels[i] = h;
        return h;
    }

    private void drawSolid(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        float gap = 2f * mDensity;
        float barWidth = Math.max(0f, width - (n - 1) * gap) / n;
        mPaint.setColor(color);
        for (int i = 0; i < n; i++) {
            float left = i * (barWidth + gap);
            mRect.set(left, height - smooth(i, SOLID_SMOOTHING, height), left + barWidth, height);
            if (mRounded) {
                mPath.reset();
                mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
                canvas.drawPath(mPath, mPaint);
            } else {
                canvas.drawRect(mRect, mPaint);
            }
        }
    }

    private void drawFading(Canvas canvas, int width, int height, int color) {
        if (mBitmap == null || mBitmap.getWidth() != width || mBitmap.getHeight() != height) {
            recycleBitmap();
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mBitmapCanvas = new Canvas(mBitmap);
        }
        int n = mTargets.length;
        float gap = 2f * mDensity;
        float barWidth = Math.max(1f, (width - (n - 1) * gap) / n);
        mLinePaint.setColor(color);
        mLinePaint.setStrokeWidth(barWidth);
        mLinePaint.setPathEffect(new DashPathEffect(new float[] {4f * mDensity, mDensity}, 0f));
        float x = barWidth * 0.5f;
        int pi = 0;
        for (int i = 0; i < n; i++) {
            mFadePoints[pi++] = x;
            mFadePoints[pi++] = height;
            mFadePoints[pi++] = x;
            mFadePoints[pi++] = height - mTargets[i];
            x += barWidth + gap;
        }
        mBitmapCanvas.drawLines(mFadePoints, 0, n * 4, mLinePaint);
        mBitmapCanvas.drawPaint(mFade);
        canvas.drawBitmap(mBitmap, 0f, 0f, null);
    }

    private void drawMinimal(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        if (n < 2) {
            return;
        }
        mLinePaint.setPathEffect(null);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setStrokeWidth(2.5f);
        mLinePaint.setColor(Color.argb(180, Color.red(color), Color.green(color),
                Color.blue(color)));
        float spacing = width / (float) (n - 1);
        mPath.reset();
        float prevX = 0f;
        float prevY = height - smooth(0, MINIMAL_SMOOTHING, height);
        mPath.moveTo(prevX, prevY);
        for (int i = 1; i < n; i++) {
            float x = i * spacing;
            float y = height - smooth(i, MINIMAL_SMOOTHING, height);
            mPath.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f);
            prevX = x;
            prevY = y;
        }
        mPath.lineTo(prevX, prevY);
        canvas.drawPath(mPath, mLinePaint);
        mLinePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawNeon(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        float gap = 2f * mDensity;
        float barWidth = Math.max(1f, (width - (n - 1) * gap) / n);
        mCorePaint.setStrokeWidth(Math.max(barWidth * 0.3f, 2f));
        mGlowPaint.setStrokeWidth(Math.max(barWidth * 0.8f, 4f));
        mGlowPaint.setColor(Color.argb(180, Color.red(color), Color.green(color),
                Color.blue(color)));
        for (int i = 0; i < n; i++) {
            float x = i * (barWidth + gap) + barWidth * 0.5f;
            float top = height - smooth(i, NEON_SMOOTHING, height);
            canvas.drawLine(x, height, x, top, mGlowPaint);
            canvas.drawLine(x, height, x, top, mCorePaint);
        }
    }

    private void drawRetro(Canvas canvas, int width, int height) {
        int n = mLevels.length;
        float gap = 2f * mDensity;
        float barWidth = Math.max(1f, (width - (n - 1) * gap) / n);
        float segmentGap = 2f;
        float segmentHeight = Math.max(3f,
                (height - (RETRO_SEGMENTS - 1) * segmentGap) / RETRO_SEGMENTS);
        int unlit = (int) (0.5f * RETRO_SEGMENTS);
        for (int i = 0; i < n; i++) {
            float left = i * (barWidth + gap);
            float percent = smooth(i, RETRO_SMOOTHING, height) / height;
            int lit = (int) (percent * RETRO_SEGMENTS);
            for (int seg = 0; seg < RETRO_SEGMENTS; seg++) {
                float top = height - (seg + 1) * (segmentHeight + segmentGap) + segmentGap;
                mRect.set(left, top, left + barWidth, top + segmentHeight);
                if (seg <= lit) {
                    mPaint.setColor(mRetroColors[seg]);
                    canvas.drawRect(mRect, mPaint);
                } else if (seg < unlit) {
                    mPaint.setColor(Color.argb(40, 100, 100, 100));
                    canvas.drawRect(mRect, mPaint);
                }
            }
        }
    }

    private void drawDotWave(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        float gap = 2f * mDensity;
        float barWidth = Math.max(0f, width - (n - 1) * gap) / n;
        float radius = Math.max(1f, Math.min(barWidth / 2f, height / 2f));
        mPaint.setColor(color);
        for (int i = 0; i < n; i++) {
            float centerX = i * (barWidth + gap) + barWidth / 2f;
            float top = height - smooth(i, DOTWAVE_SMOOTHING, height);
            canvas.drawCircle(centerX, Math.max(radius, Math.min(height, top)), radius, mPaint);
        }
    }

    private void drawWaveform(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        if (n < 2) {
            return;
        }
        mLinePaint.setPathEffect(null);
        mLinePaint.setStrokeCap(mRounded ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mLinePaint.setStrokeWidth(Math.max(mDensity * 3f, height * 0.003f));
        mLinePaint.setColor(color);
        int alpha = Math.max(0x24, Math.min(0x6E, (int) (Color.alpha(color) / 2.5f)));
        mFillPaint.setColor((alpha << 24) | (color & 0x00FFFFFF));
        float spacing = width / (float) (n - 1);
        float prevX = 0f;
        float prevY = height - smooth(0, WAVEFORM_SMOOTHING, height);
        mPath.reset();
        mPath.moveTo(prevX, prevY);
        Path fill = new Path();
        fill.moveTo(prevX, height);
        fill.lineTo(prevX, prevY);
        for (int i = 1; i < n; i++) {
            float x = i * spacing;
            float y = height - smooth(i, WAVEFORM_SMOOTHING, height);
            mPath.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f);
            fill.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f);
            prevX = x;
            prevY = y;
        }
        mPath.lineTo(prevX, prevY);
        fill.lineTo(prevX, prevY);
        fill.lineTo((n - 1) * spacing, height);
        fill.close();
        canvas.drawPath(fill, mFillPaint);
        canvas.drawPath(mPath, mLinePaint);
        mLinePaint.setStrokeCap(Paint.Cap.BUTT);
        mLinePaint.setStrokeJoin(Paint.Join.MITER);
    }

    private void drawSparkle(Canvas canvas, int width, int height, int color) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (mSparkleLast != 0 ? now - mSparkleLast : 16) / 1000f);
        mSparkleLast = now;
        int n = mLevels.length;
        int pool = Math.max(128, n * SPARKLES_PER_BAR * 3);
        if (mSparkleAlive.length != pool) {
            mSparkleX = new float[pool];
            mSparkleY = new float[pool];
            mSparkleVx = new float[pool];
            mSparkleVy = new float[pool];
            mSparkleSize = new float[pool];
            mSparkleLife = new float[pool];
            mSparkleAlive = new boolean[pool];
            mSparkleCursor = 0;
        }
        for (int i = 0; i < n; i++) {
            smooth(i, SPARKLE_SMOOTHING, height);
        }
        float spacing = width / (float) n;
        for (int i = 0; i < n; i++) {
            float energy = mLevels[i] / Math.max(2f, height);
            float emit = energy * SPARKLES_PER_BAR;
            int spawn = (int) emit;
            if (mRandom.nextFloat() < emit - spawn) {
                spawn++;
            }
            for (int k = 0; k < spawn; k++) {
                int slot = obtainSparkle();
                if (slot < 0) {
                    break;
                }
                float speed = 60f + 220f * energy;
                float sizeBase = 2.5f + 3.5f * energy;
                mSparkleAlive[slot] = true;
                mSparkleSize[slot] = sizeBase * (0.7f + 0.6f * mRandom.nextFloat());
                mSparkleX[slot] = (i + 0.5f) * spacing
                        + (mRandom.nextFloat() - 0.5f) * spacing * 0.45f;
                mSparkleY[slot] = height - mLevels[i];
                mSparkleVx[slot] = (mRandom.nextFloat() - 0.5f) * (40f + 50f * energy);
                mSparkleVy[slot] = -(speed * (0.6f + 0.8f * mRandom.nextFloat()));
                mSparkleLife[slot] = 0.65f + 0.7f * energy;
            }
        }
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int baseAlpha = Color.alpha(color);
        for (int idx = 0; idx < pool; idx++) {
            if (!mSparkleAlive[idx]) {
                continue;
            }
            mSparkleVy[idx] += -20f * dt;
            mSparkleX[idx] += mSparkleVx[idx] * dt;
            mSparkleY[idx] += mSparkleVy[idx] * dt;
            mSparkleLife[idx] -= 1.4f * dt;
            float x = mSparkleX[idx];
            float y = mSparkleY[idx];
            float size = mSparkleSize[idx];
            if (mSparkleLife[idx] <= 0f || y + size < 0f || x < -8f || x > width + 8f) {
                mSparkleAlive[idx] = false;
                continue;
            }
            int alpha = Math.max(0, Math.min(255, (int) (baseAlpha * mSparkleLife[idx])));
            mSparklePaint.setColor(Color.argb(alpha, r, g, b));
            mTrailPaint.setColor(Color.argb(
                    Math.max(0, Math.min(255, (int) (alpha * 0.6f))), r, g, b));
            float tail = Math.max(0f, mSparkleVy[idx] * -0.03f);
            if (tail > 0f) {
                canvas.drawRect(x - size * 0.35f, y - tail, x + size * 0.35f, y, mTrailPaint);
            }
            canvas.drawCircle(x, y, size, mSparklePaint);
        }
    }

    private int obtainSparkle() {
        int pool = mSparkleAlive.length;
        for (int k = 0; k < pool; k++) {
            int i = (mSparkleCursor + k) % pool;
            if (!mSparkleAlive[i]) {
                mSparkleCursor = i + 1;
                return i;
            }
        }
        return -1;
    }

    private void drawMatrix(Canvas canvas, int width, int height, int color) {
        int n = mLevels.length;
        float gap = 2f * mDensity;
        float columnWidth = Math.max(1f, (width - (n - 1) * gap) / n);
        float charSize = Math.max(16f, Math.min(36f, columnWidth * 0.7f));
        int maxChars = Math.max(1, (int) (height / (charSize * 1.1f)));
        if (mMatrixChars.length != n || (n > 0 && mMatrixChars[0].length != maxChars)) {
            mMatrixChars = new String[n][maxChars];
            for (String[] column : mMatrixChars) {
                for (int j = 0; j < maxChars; j++) {
                    column[j] = DIGITS[mRandom.nextInt(10)];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            smooth(i, MATRIX_SMOOTHING, height);
        }
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        mMatrixBright.setTextSize(charSize);
        mMatrixMedium.setTextSize(charSize);
        mMatrixDark.setTextSize(charSize);
        mMatrixTextGlow.setTextSize(charSize);
        mMatrixBright.setColor(Color.argb(255, r, g, b));
        mMatrixMedium.setColor(Color.argb(200, (int) (r * 0.86f), (int) (g * 0.86f),
                (int) (b * 0.86f)));
        mMatrixDark.setColor(Color.argb(120, (int) (r * 0.63f), (int) (g * 0.63f),
                (int) (b * 0.63f)));
        boolean change = ++mMatrixTick >= 3;
        if (change) {
            mMatrixTick = 0;
        }
        float spacing = charSize * 1.15f;
        for (int i = 0; i < n; i++) {
            float h = mLevels[i];
            if (h < 10f) {
                continue;
            }
            String[] column = mMatrixChars[i];
            float x = i * (columnWidth + gap) + columnWidth * 0.5f;
            if (change && mRandom.nextFloat() < 0.4f) {
                int count = Math.min(column.length, 1 + mRandom.nextInt(3));
                for (int k = 0; k < count; k++) {
                    column[mRandom.nextInt(column.length)] = DIGITS[mRandom.nextInt(10)];
                }
            }
            int numChars = Math.max(1, Math.min(maxChars, (int) (h / spacing)));
            float glowWidth = columnWidth * 0.85f;
            int glowAlpha = Math.max(0, Math.min(160, (int) (160 * h / height)));
            mMatrixGlow.setColor(Color.argb(glowAlpha, (int) (r * 0.78f), (int) (g * 0.78f),
                    (int) (b * 0.78f)));
            canvas.drawRect(x - glowWidth / 2f, height - h, x + glowWidth / 2f, height,
                    mMatrixGlow);
            for (int j = 0; j < numChars; j++) {
                float y = height - j * spacing - charSize * 0.25f;
                String digit = column[j % column.length];
                float fade = j / (float) numChars;
                Paint text = fade < 0.25f ? mMatrixBright
                        : fade < 0.6f ? mMatrixMedium : mMatrixDark;
                if (fade < 0.4f) {
                    int strength = Math.max(0, Math.min(200, (int) ((1f - fade * 2.5f) * 200)));
                    mMatrixTextGlow.setColor(Color.argb(strength, r, g, b));
                    canvas.drawText(digit, x, y, mMatrixTextGlow);
                }
                canvas.drawText(digit, x, y, text);
            }
        }
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        float life = 1f;
        int color = Color.WHITE;
        boolean bass;
        boolean mid;
        boolean treble;
        int alpha = 0xFF;
    }

    private void drawParticles(Canvas canvas, int width, int height, int color) {
        if (mParticleW != width || mParticleH != height) {
            mParticleW = width;
            mParticleH = height;
            mParticles.clear();
            for (int i = 0; i < INITIAL_PARTICLES; i++) {
                spawnParticle(color);
            }
        }
        // Band levels from the beat, as ParticleStyleRenderer reads them from the audio data.
        int size = mTargets.length;
        if (size >= 4) {
            int bassEnd = Math.max(1, Math.min(size / 4, size));
            int midEnd = Math.max(bassEnd + 1, Math.min((size * 3) / 4, size));
            float peak = 1f;
            for (float h : mTargets) {
                peak = Math.max(peak, h);
            }
            mBass = Math.min(1f, bandMean(peak, 0, bassEnd));
            mMid = Math.min(1f, bandMean(peak, bassEnd, midEnd));
            mTreble = Math.min(1f, bandMean(peak, midEnd, size));
            mAudio = Math.max(0f, Math.min(1f, (mBass + mMid + mTreble) / 3f));
        }
        if (mAudio > PARTICLE_AUDIO_GATE && mParticles.size() < MAX_PARTICLES) {
            if (mRandom.nextFloat() < 0.42f * mAudio) {
                spawnParticle(color);
            }
            int burst = (int) (mAudio * 5f);
            for (int s = 0; s < burst && mParticles.size() < MAX_PARTICLES; s++) {
                spawnParticle(color);
            }
        }
        for (Particle p : mParticles) {
            p.color = particleColor(p, color);
            p.vy += 0.04f;
            if (p.bass && mBass > PARTICLE_AUDIO_GATE) {
                p.vy -= mBass * 2f;
                p.vx += (mRandom.nextFloat() - 0.5f) * mBass;
            }
            if (p.mid && mMid > PARTICLE_AUDIO_GATE) {
                p.vx += (mRandom.nextFloat() - 0.5f) * mMid;
                p.vy += (mRandom.nextFloat() - 0.5f) * mMid;
            }
            if (p.treble && mTreble > PARTICLE_AUDIO_GATE) {
                float boost = 1f + 0.1f * mTreble;
                p.vx *= boost;
                p.vy *= boost;
            }
            p.vx *= 0.99f;
            p.vy *= 0.99f;
            p.x += p.vx;
            p.y += p.vy;
            p.life -= PARTICLE_LIFE_DECAY;
            p.alpha = Math.max(0, Math.min(255, (int) (p.life * 255f)));
            if (p.x < 0f || p.x > width) {
                p.vx *= -0.8f;
                p.x = Math.max(0f, Math.min(width, p.x));
            }
            if (p.y < 0f || p.y > height) {
                p.vy *= -0.8f;
                p.y = Math.max(0f, Math.min(height, p.y));
            }
        }
        mParticles.removeIf(p -> p.life <= 0f || p.alpha <= 0);
        mTrailPaint.setStyle(Paint.Style.STROKE);
        mTrailPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrailPaint.setStrokeWidth(mDensity * 1.5f);
        for (Particle p : mParticles) {
            float speedSq = p.vx * p.vx + p.vy * p.vy;
            if (speedSq < 0.04f) {
                continue;
            }
            float speed = (float) Math.sqrt(speedSq);
            float trail = Math.min(35f, speed * 12f);
            if (trail < 1f) {
                continue;
            }
            mTrailPaint.setColor(Color.argb(Math.max(0, Math.min(0xC0, p.alpha / 2)),
                    Color.red(p.color), Color.green(p.color), Color.blue(p.color)));
            canvas.drawLine(p.x, p.y, p.x - p.vx * trail / speed, p.y - p.vy * trail / speed,
                    mTrailPaint);
        }
        mTrailPaint.setStyle(Paint.Style.FILL);
        for (Particle p : mParticles) {
            mParticlePaint.setColor(p.color);
            mParticlePaint.setAlpha(Math.max(0, p.alpha / 5));
            canvas.drawCircle(p.x, p.y, p.size * 1.9f, mParticlePaint);
            mParticlePaint.setAlpha(Math.max(0, p.alpha / 2));
            canvas.drawCircle(p.x, p.y, p.size * 1.3f, mParticlePaint);
            mParticlePaint.setAlpha(p.alpha);
            canvas.drawCircle(p.x, p.y, p.size, mParticlePaint);
        }
    }

    private float bandMean(float peak, int from, int to) {
        int end = Math.min(to, mTargets.length);
        float sum = 0f;
        int count = 0;
        for (int i = from; i < end; i++) {
            sum += mTargets[i];
            count++;
        }
        return count <= 0 ? 0f : (sum / count) / peak;
    }

    private void spawnParticle(int color) {
        if (mParticleW <= 0 || mParticleH <= 0 || mParticles.size() >= MAX_PARTICLES) {
            return;
        }
        Particle p = new Particle();
        p.x = mRandom.nextFloat() * mParticleW;
        p.y = mRandom.nextFloat() * mParticleH;
        p.vx = (mRandom.nextFloat() - 0.5f) * 4f;
        p.vy = (mRandom.nextFloat() - 0.5f) * 4f;
        p.size = mRandom.nextFloat() * 2f + mDensity * 3f;
        p.bass = mRandom.nextFloat() < 0.3f;
        p.mid = mRandom.nextFloat() < 0.4f;
        p.treble = mRandom.nextFloat() < 0.3f;
        p.color = particleColor(p, color);
        mParticles.add(p);
    }

    /** Each particle leans warmer, greener or bluer than the base color by the band it follows. */
    private static int particleColor(Particle p, int base) {
        int r = Color.red(base);
        int g = Color.green(base);
        int b = Color.blue(base);
        if (p.bass) {
            return Color.argb(0xFF, Math.min(0xFF, r + 0x32), g, Math.max(0, b - 0x1E));
        } else if (p.mid) {
            return Color.argb(0xFF, r, Math.min(0xFF, g + 0x1E), b);
        } else if (p.treble) {
            return Color.argb(0xFF, Math.max(0, r - 0x1E), g, Math.min(0xFF, b + 0x32));
        }
        return base;
    }

    private void recycleBitmap() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
            mBitmapCanvas = null;
        }
    }
}
