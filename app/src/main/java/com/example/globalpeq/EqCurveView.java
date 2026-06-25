package com.example.globalpeq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BlurMaskFilter;
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
    private static final float CURVE_SAMPLE_STEP_PX = 0.5f;
    private static final float REF_SAMPLE_STEP_PX = 1.5f;
    private static final int CURVE_SUPERSAMPLE_SCALE = 2;

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
    private final Paint glowPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint3 = new Paint(Paint.ANTI_ALIAS_FLAG); // Inner solid halo core
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curveLayerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private float sweepPhase = 0.0f;
    private long lastTime = 0;

    // Performance cache indicators
    private boolean pathDirty = true;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private Bitmap curveLayerBitmap;
    private Canvas curveLayerCanvas;
    private int curveLayerWidth = 0;
    private int curveLayerHeight = 0;

    private Preset preset = Preset.flat(false);
    private FrequencyCurve deviceCurve = FrequencyCurve.DEFAULT;
    private FrequencyCurve targetCurve = FrequencyCurve.DEFAULT;
    private int maxDb = 18;

    EqCurveView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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

        // Configure advanced glow layers (optimizing halo and fixing anti-aliasing)
        // 1. Far outer ring: larger spread, but with very soft alpha gradient transition
        glowPaint1.setStyle(Paint.Style.STROKE);
        glowPaint1.setStrokeCap(Paint.Cap.ROUND);
        glowPaint1.setStrokeJoin(Paint.Join.ROUND);
        glowPaint1.setStrokeWidth(36f); // Increased from 24f to expand the outer edge
        glowPaint1.setColor(Color.argb(12, 0, 220, 255)); // Reduced alpha from 40 to 12 for soft gradient fade
        glowPaint1.setAntiAlias(true);
        glowPaint1.setFilterBitmap(true);
        glowPaint1.setDither(true);
        glowPaint1.setMaskFilter(new BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL));

        // 2. Middle ring: intermediate spread, smooth alpha
        glowPaint2.setStyle(Paint.Style.STROKE);
        glowPaint2.setStrokeCap(Paint.Cap.ROUND);
        glowPaint2.setStrokeJoin(Paint.Join.ROUND);
        glowPaint2.setStrokeWidth(16f); // Increased from 12f for broader middle band
        glowPaint2.setColor(Color.argb(22, 130, 65, 255)); // Reduced from 70 to 22 for smoother fade
        glowPaint2.setAntiAlias(true);
        glowPaint2.setFilterBitmap(true);
        glowPaint2.setDither(true);
        glowPaint2.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));

        // 3. Inner solid halo core: small glow close to the actual curve to enhance overall look
        glowPaint3.setStyle(Paint.Style.STROKE);
        glowPaint3.setStrokeCap(Paint.Cap.ROUND);
        glowPaint3.setStrokeJoin(Paint.Join.ROUND);
        glowPaint3.setStrokeWidth(6f); // Tight, small overall glow width
        glowPaint3.setColor(Color.argb(45, 0, 255, 255)); // Higher alpha but very thin, preventing pixelation/aliasing
        glowPaint3.setAntiAlias(true);
        glowPaint3.setFilterBitmap(true);
        glowPaint3.setDither(true);
        glowPaint3.setMaskFilter(new BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL));

        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeCap(Paint.Cap.ROUND);
        sweepPaint.setStrokeJoin(Paint.Join.ROUND);
        sweepPaint.setStrokeWidth(5.0f); // Sleeker sweep line to reduce aliasing and make it look sharper
        sweepPaint.setAntiAlias(true);
        sweepPaint.setFilterBitmap(true);
        sweepPaint.setDither(true);

        curveLayerPaint.setDither(true);
        curveLayerPaint.setFilterBitmap(true);
    }

    void setPreset(Preset preset) {
        this.preset = preset == null ? Preset.flat(false) : preset;
        this.pathDirty = true;
        postInvalidateOnAnimation();
    }

    void setReferenceCurves(FrequencyCurve deviceCurve, FrequencyCurve targetCurve) {
        this.deviceCurve = deviceCurve == null ? FrequencyCurve.DEFAULT : deviceCurve;
        this.targetCurve = targetCurve == null ? FrequencyCurve.DEFAULT : targetCurve;
        this.pathDirty = true;
        postInvalidateOnAnimation();
    }

    void setMaxDb(int maxDb) {
        int next = maxDb == 6 || maxDb == 12 ? maxDb : 18;
        if (this.maxDb == next) {
            return;
        }
        this.maxDb = next;
        this.pathDirty = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
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
            lastWidth = width;
            lastHeight = height;
        }

        drawCurveLayer(canvas, width, height, left, right);

        canvas.drawText("+" + maxDb, left, top + 24f, textPaint);
        canvas.drawText("0 dB", left, mid - 6f, textPaint);
        canvas.drawText("-" + maxDb, left, bottom - 6f, textPaint);

        // Keep running the animation loop smoothly if enabled
        if (preset.enabled) {
            postInvalidateOnAnimation();
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
        double min = Math.log(MIN_HZ);
        double max = Math.log(MAX_HZ);
        double ratio = Math.max(0, Math.min(1, (x - left) / (right - left)));
        return (int) Math.round(Math.exp(min + (max - min) * ratio));
    }

    private float gainToY(float db, float top, float bottom) {
        float clamped = Math.max(-maxDb, Math.min(maxDb, db));
        return top + (maxDb - clamped) * (bottom - top) / (maxDb * 2f);
    }

    private Path referencePath(FrequencyCurve curve, float left, float right, float top, float bottom) {
        refCurvePath.reset();
        appendSmoothedPath(refCurvePath, left, right, REF_SAMPLE_STEP_PX,
                x -> gainToY(curve.gainAtHz(xToFreq(x, left, right)), top, bottom));
        return refCurvePath;
    }

    private void buildCurvePath(Path path, float left, float right, float top, float bottom) {
        path.reset();
        appendSmoothedPath(path, left, right, CURVE_SAMPLE_STEP_PX, x -> {
            int hz = xToFreq(x, left, right);
            float db = deviceCurve.gainAtHz(hz) + PeqMath.visualGainAtHzMb(hz, preset) / 100f;
            return gainToY(db, top, bottom);
        });
    }

    private void drawCurveLayer(Canvas canvas, int width, int height, float left, float right) {
        ensureCurveLayer(width, height);
        if (curveLayerCanvas == null || curveLayerBitmap == null) {
            return;
        }

        curveLayerBitmap.eraseColor(Color.TRANSPARENT);
        curveLayerCanvas.save();
        curveLayerCanvas.scale(CURVE_SUPERSAMPLE_SCALE, CURVE_SUPERSAMPLE_SCALE);

        if (!targetCurve.isDefault()) {
            referencePaint.setColor(Color.argb(180, 190, 128, 255));
            referencePaint.setPathEffect(dashPathEffect);
            curveLayerCanvas.drawPath(refCurvePath, referencePaint);
            referencePaint.setPathEffect(null);
        }

        curvePaint.setStrokeWidth(5f);
        curvePaint.setDither(true);
        if (preset.enabled) {
            curvePaint.setColor(Color.argb(35, 0, 255, 255));
        } else {
            curvePaint.setColor(Color.argb(18, 130, 140, 150));
        }
        curveLayerCanvas.drawPath(curvePath, curvePaint);

        if (preset.enabled) {
            long now = System.currentTimeMillis();
            if (lastTime > 0) {
                float elapsed = (now - lastTime) / 1000f;
                sweepPhase += elapsed * 0.25f;
                if (sweepPhase > 1.0f) {
                    sweepPhase -= 1.0f;
                }
            }
            lastTime = now;

            curveLayerCanvas.drawPath(curvePath, glowPaint1);
            curveLayerCanvas.drawPath(curvePath, glowPaint2);
            curveLayerCanvas.drawPath(curvePath, glowPaint3);

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

            sweepMatrix.reset();
            float totalWidth = right - left;
            sweepMatrix.postTranslate(sweepPhase * totalWidth, 0);
            sweepGradient.setLocalMatrix(sweepMatrix);
            curveLayerCanvas.drawPath(curvePath, sweepPaint);
        } else {
            lastTime = 0;
        }

        curvePaint.setStrokeWidth(3.2f);
        if (preset.enabled) {
            curvePaint.setColor(Color.rgb(0, 255, 255));
        } else {
            curvePaint.setColor(Color.rgb(130, 140, 150));
        }
        curveLayerCanvas.drawPath(curvePath, curvePaint);

        curveLayerCanvas.restore();
        canvas.drawBitmap(curveLayerBitmap, 0f, 0f, curveLayerPaint);
    }

    private void ensureCurveLayer(int width, int height) {
        int targetWidth = Math.max(1, width * CURVE_SUPERSAMPLE_SCALE);
        int targetHeight = Math.max(1, height * CURVE_SUPERSAMPLE_SCALE);
        if (curveLayerBitmap != null && curveLayerWidth == targetWidth && curveLayerHeight == targetHeight) {
            return;
        }
        if (curveLayerBitmap != null) {
            curveLayerBitmap.recycle();
        }
        curveLayerBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        curveLayerCanvas = new Canvas(curveLayerBitmap);
        curveLayerWidth = targetWidth;
        curveLayerHeight = targetHeight;
    }

    private void appendSmoothedPath(Path path, float left, float right, float stepPx, CurveSampler sampler) {
        float clampedStep = Math.max(0.25f, stepPx);
        float x = left;
        float y = sampler.sample(x);
        path.moveTo(x, y);

        float prevX = x;
        float prevY = y;
        boolean hasSegment = false;
        for (x = left + clampedStep; x < right; x += clampedStep) {
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
        }
        path.quadTo(prevX, prevY, right, sampler.sample(right));
    }

    private interface CurveSampler {
        float sample(float x);
    }

}
