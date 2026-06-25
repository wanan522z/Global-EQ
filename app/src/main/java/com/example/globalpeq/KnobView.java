package com.example.globalpeq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

final class KnobView extends View {
    interface Listener {
        void onValueChanged(int value);
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int min;
    private int max;
    private int value;
    private String suffix = "";
    private float downY;
    private int downValue;
    private Listener listener;

    KnobView(Context context) {
        super(context);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(10f);
        basePaint.setColor(Color.argb(35, 255, 255, 255));
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(10f);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(Color.rgb(0, 255, 255));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(32f);
    }

    void configure(int min, int max, int value, String suffix, Listener listener) {
        this.min = min;
        this.max = max;
        this.suffix = suffix == null ? "" : suffix;
        this.listener = listener;
        setValue(value, false);
    }

    void setValue(int nextValue, boolean notify) {
        int clamped = Math.max(min, Math.min(max, nextValue));
        if (clamped == value) {
            invalidate();
            return;
        }
        value = clamped;
        invalidate();
        if (notify && listener != null) {
            listener.onValueChanged(value);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) - 24f;
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        
        basePaint.setStrokeWidth(10f);
        canvas.drawArc(left, top, left + size, top + size, 135, 270, false, basePaint);
        
        float sweep = 270f * (value - min) / Math.max(1f, max - min);
        
        // Draw neon glow under-arc
        fillPaint.setStrokeWidth(18f);
        fillPaint.setColor(Color.argb(60, 0, 255, 255));
        canvas.drawArc(left, top, left + size, top + size, 135, sweep, false, fillPaint);
        
        // Draw active arc core
        fillPaint.setStrokeWidth(10f);
        fillPaint.setColor(Color.rgb(0, 255, 255));
        canvas.drawArc(left, top, left + size, top + size, 135, sweep, false, fillPaint);
        
        textPaint.setColor(Color.WHITE);
        canvas.drawText(value + suffix, getWidth() / 2f, getHeight() / 2f + 11f, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downValue = value;
                return true;
            case MotionEvent.ACTION_MOVE:
                float delta = downY - event.getY();
                int range = max - min;
                int next = downValue + Math.round(delta * range / Math.max(80f, getHeight()));
                setValue(next, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
