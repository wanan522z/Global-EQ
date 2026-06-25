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
    private int neutralValue;
    private String suffix = "";
    private float downY;
    private int downValue;
    private Listener listener;

    KnobView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(7f);
        basePaint.setColor(Color.argb(35, 255, 255, 255));
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(7f);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(Color.rgb(0, 245, 212));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(30f);
    }

    void configure(int min, int max, int value, String suffix, Listener listener) {
        this.min = min;
        this.max = max;
        this.neutralValue = min;
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
        boolean enabled = isEnabled();
        boolean active = enabled && value != neutralValue;
        
        // Draw neon glow under-arc
        if (active) {
            fillPaint.setStrokeWidth(18f);
            fillPaint.setColor(Color.argb(78, 0, 245, 212));
            fillPaint.setShadowLayer(12f, 0, 0, Color.argb(170, 0, 245, 212));
            canvas.drawArc(left, top, left + size, top + size, 135, sweep, false, fillPaint);
            fillPaint.clearShadowLayer();
        }
        
        // Draw active arc core
        fillPaint.setStrokeWidth(10f);
        fillPaint.setColor(enabled ? Color.rgb(0, 245, 212) : Color.argb(95, 170, 178, 190));
        if (active) {
            fillPaint.setShadowLayer(5f, 0, 0, Color.argb(200, 0, 245, 212));
        }
        canvas.drawArc(left, top, left + size, top + size, 135, sweep, false, fillPaint);
        fillPaint.clearShadowLayer();
        
        textPaint.setColor(active ? Color.rgb(0, 245, 212) : enabled ? Color.argb(185, 255, 255, 255) : Color.argb(120, 190, 198, 210));
        if (active) {
            textPaint.setShadowLayer(5f, 0, 0, Color.argb(160, 0, 245, 212));
        } else {
            textPaint.clearShadowLayer();
        }
        canvas.drawText(value + suffix, getWidth() / 2f, getHeight() / 2f + 11f, textPaint);
        textPaint.clearShadowLayer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
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
