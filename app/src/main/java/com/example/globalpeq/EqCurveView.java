package com.example.globalpeq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.DashPathEffect;
import android.view.View;

final class EqCurveView extends View {
    private static final int MIN_HZ = 20;
    private static final int MAX_HZ = 20000;
    private static final int MAX_DB = 18;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frequencyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Preset preset = Preset.flat(false);
    private FrequencyCurve deviceCurve = FrequencyCurve.DEFAULT;
    private FrequencyCurve targetCurve = FrequencyCurve.DEFAULT;

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
        referencePaint.setStyle(Paint.Style.STROKE);
        referencePaint.setStrokeWidth(3f);
        textPaint.setColor(Color.argb(160, 255, 255, 255));
        textPaint.setTextSize(22f);
        frequencyTextPaint.setColor(Color.argb(105, 220, 230, 245));
        frequencyTextPaint.setTextSize(17f);
        frequencyTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setPreset(Preset preset) {
        this.preset = preset == null ? Preset.flat(false) : preset;
        postInvalidateOnAnimation();
    }

    void setReferenceCurves(FrequencyCurve deviceCurve, FrequencyCurve targetCurve) {
        this.deviceCurve = deviceCurve == null ? FrequencyCurve.DEFAULT : deviceCurve;
        this.targetCurve = targetCurve == null ? FrequencyCurve.DEFAULT : targetCurve;
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

        for (int i = -2; i <= 2; i++) {
            float y = gainToY(i * 6, top, bottom);
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

        if (!targetCurve.isDefault()) {
            referencePaint.setColor(Color.argb(180, 190, 128, 255));
            referencePaint.setPathEffect(new DashPathEffect(new float[]{12f, 10f}, 0f));
            canvas.drawPath(referencePath(targetCurve, left, right, top, bottom), referencePaint);
            referencePaint.setPathEffect(null);
        }

        Path path = new Path();
        for (int x = (int) left; x <= (int) right; x++) {
            int hz = xToFreq(x, left, right);
            float db = deviceCurve.gainAtHz(hz) + PeqMath.visualGainAtHzMb(hz, preset) / 100f;
            float y = gainToY(db, top, bottom);
            if (x == (int) left) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        // Draw subtle glow under the active curve.
        curvePaint.setStrokeWidth(7f);
        if (preset.enabled) {
            curvePaint.setColor(Color.argb(35, 0, 255, 255));
        } else {
            curvePaint.setColor(Color.argb(18, 130, 140, 150));
        }
        canvas.drawPath(path, curvePaint);

        // Draw sharp active curve core
        curvePaint.setStrokeWidth(3.2f);
        if (preset.enabled) {
            curvePaint.setColor(Color.rgb(0, 255, 255));
        } else {
            curvePaint.setColor(Color.rgb(130, 140, 150));
        }
        canvas.drawPath(path, curvePaint);

        canvas.drawText("+18", left, top + 24f, textPaint);
        canvas.drawText("0 dB", left, mid - 6f, textPaint);
        canvas.drawText("-18", left, bottom - 6f, textPaint);
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
        float clamped = Math.max(-MAX_DB, Math.min(MAX_DB, db));
        return top + (MAX_DB - clamped) * (bottom - top) / (MAX_DB * 2f);
    }

    private Path referencePath(FrequencyCurve curve, float left, float right, float top, float bottom) {
        Path path = new Path();
        for (int x = (int) left; x <= (int) right; x++) {
            int hz = xToFreq(x, left, right);
            float y = gainToY(curve.gainAtHz(hz), top, bottom);
            if (x == (int) left) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path;
    }

}
