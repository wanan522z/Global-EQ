package com.example.globalpeq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.View;

final class EqCurveView extends View {
    private static final int MIN_HZ = 20;
    private static final int MAX_HZ = 20000;
    private static final float CURVE_SAMPLE_STEP_PX = 0.24f;
    private static final float REF_SAMPLE_STEP_PX = 1.0f;
    private static final long ANIMATION_FRAME_DELAY_MS = 33L;
    private static final long VISUAL_FADE_DURATION_MS = 280L;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frequencyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Reusable path, shader and effect structures to avoid garbage collection overhead
    private final Path curvePath = new Path();
    private final Path refCurvePath = new Path();
    private DashPathEffect dashPathEffect;
    private LinearGradient sweepGradient;

    // Dynamic sweeping light and advanced halo paints
    private final Paint glowPaint0 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint0b = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint1b = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint2b = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint3 = new Paint(Paint.ANTI_ALIAS_FLAG); // Inner solid halo core
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private float sweepPhase = 0.0f;
    private long lastTime = 0;
    private long lastInvalidateAt = 0L;

    // Performance cache indicators
    private boolean pathDirty = true;
    private boolean glowCacheDirty = true;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private Bitmap glowCacheBitmap;
    private Canvas glowCacheCanvas;
    private boolean animationSuppressed;
    private boolean visualStateInitialized;
    private float visualLevel = 0f;
    private float visualFromLevel = 0f;
    private float targetVisualLevel = 0f;
    private long visualTransitionStartAt = 0L;
    private final Paint glowBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private Preset preset = Preset.flat(false);
    private FrequencyCurve deviceCurve = FrequencyCurve.DEFAULT;
    private FrequencyCurve targetCurve = FrequencyCurve.DEFAULT;
    private int maxDb = 18;

    EqCurveView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        gridPaint.setColor(Color.argb(20, 255, 255, 255));
        gridPaint.setStrokeWidth(1.5f);
        minorGridPaint.setColor(Color.argb(11, 255, 255, 255));
        minorGridPaint.setStrokeWidth(1f);
        zeroPaint.setColor(Color.argb(55, 255, 255, 255));
        zeroPaint.setStrokeWidth(2f);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        curvePaint.setAntiAlias(true);
        curvePaint.setFilterBitmap(true);
        curvePaint.setDither(true);
        referencePaint.setStyle(Paint.Style.STROKE);
        referencePaint.setStrokeWidth(3f);
        referencePaint.setAntiAlias(true);
        referencePaint.setFilterBitmap(true);
        referencePaint.setDither(true);
        dashPathEffect = new DashPathEffect(new float[]{12f, 10f}, 0f);
        textPaint.setColor(Color.argb(160, 255, 255, 255));
        textPaint.setTextSize(22f);
        frequencyTextPaint.setColor(Color.argb(105, 220, 230, 245));
        frequencyTextPaint.setTextSize(17f);
        frequencyTextPaint.setTextAlign(Paint.Align.CENTER);

        // Configure advanced glow layers as a wide, soft bloom:
        // low brightness, large spread, and a gradual fade instead of neon edges.
        glowPaint0.setStyle(Paint.Style.STROKE);
        glowPaint0.setStrokeCap(Paint.Cap.ROUND);
        glowPaint0.setStrokeJoin(Paint.Join.ROUND);
        glowPaint0.setStrokeWidth(28f);
        glowPaint0.setColor(Color.argb(9, 170, 210, 255));
        glowPaint0.setAntiAlias(true);
        glowPaint0.setFilterBitmap(true);
        glowPaint0.setDither(true);

        glowPaint0b.setStyle(Paint.Style.STROKE);
        glowPaint0b.setStrokeCap(Paint.Cap.ROUND);
        glowPaint0b.setStrokeJoin(Paint.Join.ROUND);
        glowPaint0b.setStrokeWidth(24f);
        glowPaint0b.setColor(Color.argb(11, 150, 212, 255));
        glowPaint0b.setAntiAlias(true);
        glowPaint0b.setFilterBitmap(true);
        glowPaint0b.setDither(true);

        // 1. Outer haze: still broad, but slightly more visible.
        glowPaint1.setStyle(Paint.Style.STROKE);
        glowPaint1.setStrokeCap(Paint.Cap.ROUND);
        glowPaint1.setStrokeJoin(Paint.Join.ROUND);
        glowPaint1.setStrokeWidth(20f);
        glowPaint1.setColor(Color.argb(13, 130, 215, 255));
        glowPaint1.setAntiAlias(true);
        glowPaint1.setFilterBitmap(true);
        glowPaint1.setDither(true);

        glowPaint1b.setStyle(Paint.Style.STROKE);
        glowPaint1b.setStrokeCap(Paint.Cap.ROUND);
        glowPaint1b.setStrokeJoin(Paint.Join.ROUND);
        glowPaint1b.setStrokeWidth(16.5f);
        glowPaint1b.setColor(Color.argb(15, 142, 218, 255));
        glowPaint1b.setAntiAlias(true);
        glowPaint1b.setFilterBitmap(true);
        glowPaint1b.setDither(true);

        // 2. Mid haze: connects the bloom to the curve body without becoming a hard neon edge.
        glowPaint2.setStyle(Paint.Style.STROKE);
        glowPaint2.setStrokeCap(Paint.Cap.ROUND);
        glowPaint2.setStrokeJoin(Paint.Join.ROUND);
        glowPaint2.setStrokeWidth(13f);
        glowPaint2.setColor(Color.argb(17, 150, 220, 255));
        glowPaint2.setAntiAlias(true);
        glowPaint2.setFilterBitmap(true);
        glowPaint2.setDither(true);

        glowPaint2b.setStyle(Paint.Style.STROKE);
        glowPaint2b.setStrokeCap(Paint.Cap.ROUND);
        glowPaint2b.setStrokeJoin(Paint.Join.ROUND);
        glowPaint2b.setStrokeWidth(10.5f);
        glowPaint2b.setColor(Color.argb(18, 168, 224, 255));
        glowPaint2b.setAntiAlias(true);
        glowPaint2b.setFilterBitmap(true);
        glowPaint2b.setDither(true);

        // 3. Near haze: a restrained inner bloom, intentionally not too bright.
        glowPaint3.setStyle(Paint.Style.STROKE);
        glowPaint3.setStrokeCap(Paint.Cap.ROUND);
        glowPaint3.setStrokeJoin(Paint.Join.ROUND);
        glowPaint3.setStrokeWidth(8.5f);
        glowPaint3.setColor(Color.argb(20, 190, 230, 255));
        glowPaint3.setAntiAlias(true);
        glowPaint3.setFilterBitmap(true);
        glowPaint3.setDither(true);

        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeCap(Paint.Cap.ROUND);
        sweepPaint.setStrokeJoin(Paint.Join.ROUND);
        sweepPaint.setStrokeWidth(5.0f); // Sleeker sweep line to reduce aliasing and make it look sharper
        sweepPaint.setAntiAlias(true);
        sweepPaint.setFilterBitmap(true);
        sweepPaint.setDither(true);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setStrokeWidth(4.2f);
        edgePaint.setAntiAlias(true);
        edgePaint.setDither(true);
    }

    void setPreset(Preset preset) {
        this.preset = preset == null ? Preset.flat(false) : preset;
        if (!visualStateInitialized) {
            float initialLevel = this.preset.enabled ? 1f : 0f;
            visualLevel = initialLevel;
            visualFromLevel = initialLevel;
            targetVisualLevel = initialLevel;
            visualStateInitialized = true;
        }
        this.pathDirty = true;
        this.glowCacheDirty = true;
        postInvalidateOnAnimation();
    }

    void setReferenceCurves(FrequencyCurve deviceCurve, FrequencyCurve targetCurve) {
        this.deviceCurve = deviceCurve == null ? FrequencyCurve.DEFAULT : deviceCurve;
        this.targetCurve = targetCurve == null ? FrequencyCurve.DEFAULT : targetCurve;
        this.pathDirty = true;
        this.glowCacheDirty = true;
        postInvalidateOnAnimation();
    }

    void setMaxDb(int maxDb) {
        int next = maxDb == 6 || maxDb == 12 ? maxDb : 18;
        if (this.maxDb == next) {
            return;
        }
        this.maxDb = next;
        this.pathDirty = true;
        this.glowCacheDirty = true;
        postInvalidateOnAnimation();
    }

    void setAnimationSuppressed(boolean suppressed) {
        if (animationSuppressed == suppressed) {
            return;
        }
        animationSuppressed = suppressed;
        if (suppressed) {
            lastTime = 0L;
            lastInvalidateAt = 0L;
        }
        postInvalidateOnAnimation();
    }

    void setVisualEnabled(boolean enabled, boolean animate) {
        float nextLevel = enabled ? 1f : 0f;
        if (!visualStateInitialized) {
            visualLevel = nextLevel;
            visualFromLevel = nextLevel;
            targetVisualLevel = nextLevel;
            visualStateInitialized = true;
            invalidate();
            return;
        }
        syncVisualLevel(System.currentTimeMillis());
        if (!animate) {
            visualLevel = nextLevel;
            visualFromLevel = nextLevel;
            targetVisualLevel = nextLevel;
            visualTransitionStartAt = 0L;
            invalidate();
            return;
        }
        if (Math.abs(targetVisualLevel - nextLevel) < 0.001f && Math.abs(visualLevel - nextLevel) < 0.001f) {
            return;
        }
        visualFromLevel = visualLevel;
        targetVisualLevel = nextLevel;
        visualTransitionStartAt = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        syncVisualLevel(now);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float left = 12f;
        float right = width - 12f;
        float top = 12f;
        float bottom = height - 34f;
        float mid = (top + bottom) / 2f;

        float gridStep = maxDb / 3f;
        for (int i = -2; i <= 2; i++) {
            float y = gainToY(i * gridStep, top, bottom);
            canvas.drawLine(left, y, right, y, i == 0 ? zeroPaint : gridPaint);
        }
        int[] majorMarks = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        int[] minorMarks = {30, 40, 60, 70, 80, 90, 150, 300, 400, 600, 700, 800, 900,
                1500, 3000, 4000, 6000, 7000, 8000, 9000, 15000};
        for (int mark : minorMarks) {
            float x = freqToX(mark, left, right);
            canvas.drawLine(x, top, x, bottom, minorGridPaint);
        }
        for (int mark : majorMarks) {
            float x = freqToX(mark, left, right);
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }
        int[] labels = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        for (int label : labels) {
            float x = Math.max(left + 10f, Math.min(right - 10f, freqToX(label, left, right)));
            canvas.drawText(frequencyLabel(label), x, height - 9f, frequencyTextPaint);
        }

        // Cache-based Path reconstruction to prevent log/exp math and path allocation overhead inside onDraw
        if (pathDirty || width != lastWidth || height != lastHeight) {
            buildCurvePath(curvePath, left, right, top, bottom);

            if (!targetCurve.isDefault()) {
                referencePath(targetCurve, left, right, top, bottom);
            }

            pathDirty = false;
            glowCacheDirty = true;
            lastWidth = width;
            lastHeight = height;
        }

        drawCurveLayer(canvas, left, right);

        canvas.drawText("+" + maxDb, left, top + 24f, textPaint);
        canvas.drawText("0 dB", left, mid - 6f, textPaint);
        canvas.drawText("-" + maxDb, left, bottom - 6f, textPaint);

        // Keep running the animation loop smoothly if enabled
        if ((visualLevel > 0.001f || isVisualTransitionRunning()) && !animationSuppressed) {
            if (now - lastInvalidateAt >= ANIMATION_FRAME_DELAY_MS) {
                lastInvalidateAt = now;
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(ANIMATION_FRAME_DELAY_MS - (now - lastInvalidateAt));
            }
        }
    }

    private float freqToX(int hz, float left, float right) {
        double min = Math.log(MIN_HZ);
        double max = Math.log(MAX_HZ);
        double value = Math.log(Math.max(MIN_HZ, Math.min(MAX_HZ, hz)));
        return (float) (left + (value - min) * (right - left) / (max - min));
    }

    private String frequencyLabel(int hz) {
        return hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
    }

    private int xToFreq(float x, float left, float right) {
        return (int) Math.round(xToFrequency(x, left, right));
    }

    private double xToFrequency(float x, float left, float right) {
        double min = Math.log(MIN_HZ);
        double max = Math.log(MAX_HZ);
        double ratio = Math.max(0, Math.min(1, (x - left) / (right - left)));
        return Math.exp(min + (max - min) * ratio);
    }

    private float gainToY(float db, float top, float bottom) {
        float clamped = Math.max(-maxDb, Math.min(maxDb, db));
        return top + (maxDb - clamped) * (bottom - top) / (maxDb * 2f);
    }

    private Path referencePath(FrequencyCurve curve, float left, float right, float top, float bottom) {
        refCurvePath.reset();
        appendSmoothedPath(refCurvePath, left, right, REF_SAMPLE_STEP_PX,
                x -> gainToY(curve.gainAtFrequency(xToFrequency(x, left, right)), top, bottom));
        return refCurvePath;
    }

    private void buildCurvePath(Path path, float left, float right, float top, float bottom) {
        path.reset();
        appendSmoothedPath(path, left, right, CURVE_SAMPLE_STEP_PX, x -> {
            double hz = xToFrequency(x, left, right);
            float db = deviceCurve.gainAtFrequency(hz) + PeqMath.visualGainAtFrequencyMb(hz, preset) / 100f;
            return gainToY(db, top, bottom);
        });
    }

    private void drawCurveLayer(Canvas canvas, float left, float right) {
        float enabledAmount = clamp01(visualLevel);
        if (!targetCurve.isDefault()) {
            referencePaint.setColor(Color.argb(180, 190, 128, 255));
            referencePaint.setPathEffect(dashPathEffect);
            canvas.drawPath(refCurvePath, referencePaint);
            referencePaint.setPathEffect(null);
        }

        if (enabledAmount > 0.001f) {
            ensureGlowCache(getWidth(), getHeight(), left, right);
            if (glowCacheBitmap != null && !glowCacheBitmap.isRecycled()) {
                glowBitmapPaint.setAlpha(Math.round(255f * enabledAmount));
                canvas.drawBitmap(glowCacheBitmap, 0f, 0f, glowBitmapPaint);
            }
            long now = System.currentTimeMillis();
            if (!animationSuppressed && lastTime > 0) {
                float elapsed = (now - lastTime) / 1000f;
                sweepPhase += elapsed * 0.25f;
                if (sweepPhase > 1.0f) {
                    sweepPhase -= 1.0f;
                }
            }
            lastTime = (animationSuppressed || enabledAmount <= 0.001f) ? 0L : now;

            if (sweepGradient == null) {
                sweepGradient = new LinearGradient(
                        0, 0, right - left, 0,
                        new int[]{
                                Color.argb(0, 0, 255, 255),
                                Color.argb(120, 0, 255, 255),
                                Color.argb(255, 255, 255, 255),
                                Color.argb(140, 255, 60, 180),
                                Color.argb(120, 130, 65, 255),
                                Color.argb(90, 255, 180, 0),
                                Color.argb(0, 0, 255, 255)
                        },
                        new float[]{0.0f, 0.2f, 0.5f, 0.65f, 0.8f, 0.9f, 1.0f},
                        Shader.TileMode.REPEAT
                );
                sweepPaint.setShader(sweepGradient);
            }

            if (!animationSuppressed) {
                sweepMatrix.reset();
                float totalWidth = right - left;
                sweepMatrix.postTranslate(sweepPhase * totalWidth, 0);
                sweepGradient.setLocalMatrix(sweepMatrix);
                sweepPaint.setAlpha(Math.round(255f * enabledAmount));
                canvas.drawPath(curvePath, sweepPaint);
            }
        } else {
            lastTime = 0;
            lastInvalidateAt = 0L;
        }

        curvePaint.setStrokeWidth(5f);
        curvePaint.setDither(true);
        curvePaint.setColor(lerpColor(
                Color.argb(18, 130, 140, 150),
                Color.argb(35, 0, 255, 255),
                enabledAmount));
        canvas.drawPath(curvePath, curvePaint);

        edgePaint.setColor(lerpColor(
                Color.argb(58, 150, 160, 172),
                Color.argb(105, 130, 245, 255),
                enabledAmount));
        canvas.drawPath(curvePath, edgePaint);

        curvePaint.setStrokeWidth(3.2f);
        curvePaint.setColor(lerpColor(
                Color.rgb(130, 140, 150),
                Color.rgb(0, 255, 255),
                enabledAmount));
        canvas.drawPath(curvePath, curvePaint);
    }

    private void syncVisualLevel(long now) {
        if (!visualStateInitialized) {
            return;
        }
        if (visualTransitionStartAt <= 0L) {
            visualLevel = targetVisualLevel;
            return;
        }
        float progress = Math.min(1f, Math.max(0f, (now - visualTransitionStartAt) / (float) VISUAL_FADE_DURATION_MS));
        visualLevel = visualFromLevel + (targetVisualLevel - visualFromLevel) * progress;
        if (progress >= 1f) {
            visualLevel = targetVisualLevel;
            visualTransitionStartAt = 0L;
            visualFromLevel = targetVisualLevel;
        }
    }

    private boolean isVisualTransitionRunning() {
        return visualTransitionStartAt > 0L && Math.abs(visualLevel - targetVisualLevel) > 0.001f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int lerpColor(int from, int to, float amount) {
        float t = clamp01(amount);
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    private void ensureGlowCache(int width, int height, float left, float right) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (glowCacheBitmap == null
                || glowCacheBitmap.getWidth() != width
                || glowCacheBitmap.getHeight() != height) {
            if (glowCacheBitmap != null) {
                glowCacheBitmap.recycle();
            }
            glowCacheBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            glowCacheCanvas = new Canvas(glowCacheBitmap);
            glowCacheDirty = true;
        }
        if (!glowCacheDirty || glowCacheCanvas == null) {
            return;
        }
        glowCacheBitmap.eraseColor(Color.TRANSPARENT);

        curvePaint.setStrokeWidth(5f);
        curvePaint.setDither(true);
        curvePaint.setColor(Color.argb(35, 0, 255, 255));
        glowCacheCanvas.drawPath(curvePath, curvePaint);

        edgePaint.setColor(Color.argb(105, 130, 245, 255));
        glowCacheCanvas.drawPath(curvePath, edgePaint);

        glowCacheCanvas.drawPath(curvePath, glowPaint0);
        glowCacheCanvas.drawPath(curvePath, glowPaint0b);
        glowCacheCanvas.drawPath(curvePath, glowPaint1);
        glowCacheCanvas.drawPath(curvePath, glowPaint1b);
        glowCacheCanvas.drawPath(curvePath, glowPaint2);
        glowCacheCanvas.drawPath(curvePath, glowPaint2b);
        glowCacheCanvas.drawPath(curvePath, glowPaint3);

        glowCacheDirty = false;
    }

    private void appendSmoothedPath(Path path, float left, float right, float stepPx, CurveSampler sampler) {
        float clampedStep = Math.max(0.25f, stepPx);
        float x = left;
        float y = sampler.sample(x);
        path.moveTo(x, y);

        float prevX = x;
        float prevY = y;
        boolean hasSegment = false;
        for (x = left + adaptiveStepPx(left, right, left, clampedStep); x < right; ) {
            float nextY = sampler.sample(x);
            float midX = (prevX + x) * 0.5f;
            float midY = (prevY + nextY) * 0.5f;
            if (!hasSegment) {
                path.lineTo(midX, midY);
                hasSegment = true;
            } else {
                path.quadTo(prevX, prevY, midX, midY);
            }
            prevX = x;
            prevY = nextY;
            x += adaptiveStepPx(left, right, x, clampedStep);
        }
        path.quadTo(prevX, prevY, right, sampler.sample(right));
    }

    private float adaptiveStepPx(float left, float right, float x, float baseStepPx) {
        double hz = xToFrequency(x, left, right);
        if (hz < 120d) {
            return Math.max(0.08f, baseStepPx * 0.28f);
        }
        if (hz < 200d) {
            return Math.max(0.11f, baseStepPx * 0.38f);
        }
        if (hz < 420d) {
            return Math.max(0.16f, baseStepPx * 0.54f);
        }
        return baseStepPx;
    }

    private interface CurveSampler {
        float sample(float x);
    }

}
