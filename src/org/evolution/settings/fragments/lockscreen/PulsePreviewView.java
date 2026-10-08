package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Animated stand-in for SystemUI's pulse renderers, fed by a fake beat. It follows the pulse
 * settings live and mimics SolidLineStyleRenderer, FadingBlockStyleRenderer and
 * MinimalStyleRenderer, see {@link #supports(String)}. Keep the drawing in step with them.
 */
public class PulsePreviewView extends View {
    private static final String RENDERER_SOLID = "solid";
    private static final String RENDERER_FADING = "fading";
    private static final String RENDERER_MINIMAL = "minimal";
    private static final String COLOR_LAVALAMP = "lavalamp";
    private static final String COLOR_CUSTOM = "custom";

    private static final int FRAME_MS = 25;
    private static final int BEAT_MS = 480;
    private static final int PULSE_ALPHA = (int) (0.88f * 255);
    private static final float SOLID_SMOOTHING = 0.2f;
    private static final float MINIMAL_SMOOTHING = 0.3f;
    private static final float ROUNDED_RADIUS = 32f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFade = new Paint();
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
        setBars(32);
        readSettings();
    }

    /** Whether the preview can draw the given renderer value of pulse_renderer. */
    public static boolean supports(String renderer) {
        return RENDERER_SOLID.equals(renderer) || RENDERER_FADING.equals(renderer)
                || RENDERER_MINIMAL.equals(renderer);
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
            mTargets[i] = Math.min(height, level * mHeightScale * height);
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

    private void recycleBitmap() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
            mBitmapCanvas = null;
        }
    }
}
