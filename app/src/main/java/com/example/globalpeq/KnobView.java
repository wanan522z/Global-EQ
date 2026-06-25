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
    /** 数字点击回调：由宿主弹出数值输入对话框，回调写入新值 */
    interface TapListener {
        void onDigitTapped(KnobView knob);
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
    private float downX;
    private boolean maybeTap;
    private Listener listener;
    private TapListener tapListener;

    KnobView(Context context) {
        super(context);
        // 不使用 LAYER_TYPE_SOFTWARE：硬件加速下 shadowLayer 抗锯齿更好，
        // 避免软件层在高清屏产生 bitmap 缩放锯齿伪影
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeCap(Paint.Cap.ROUND);
        basePaint.setColor(Color.argb(35, 255, 255, 255));
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    void configure(int min, int max, int value, String suffix, Listener listener) {
        this.min = min;
        this.max = max;
        this.neutralValue = min;
        this.suffix = suffix == null ? "" : suffix;
        this.listener = listener;
        setValue(value, false);
    }

    void setTapListener(TapListener l) {
        this.tapListener = l;
    }

    int getMin() { return min; }
    int getMax() { return max; }
    int getValue() { return value; }
    String getSuffix() { return suffix; }

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
        // 弧形直径留白 dp(16)，让旋钮在有限空间内更大，与数字比例协调
        float size = Math.min(getWidth(), getHeight()) - dp(16);
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;

        // 材质对齐 HorizontalBassSlider：
        // 1) 底层轨道（暗胶囊感）：细线低透明
        basePaint.setStrokeWidth(dp(2));
        canvas.drawArc(left, top, left + size, top + size, 135, 270, false, basePaint);

        float sweep = 270f * (value - min) / Math.max(1f, max - min);
        boolean enabled = isEnabled();
        boolean active = enabled && value != neutralValue;

        // 2) 活动段发光（STROKE + shadowLayer，模仿滑杆活动段）
        if (active) {
            fillPaint.setStrokeWidth(dp(4));
            fillPaint.setColor(Color.rgb(0, 245, 212));
            fillPaint.setShadowLayer(dp(6), 0, 0, Color.argb(180, 0, 245, 212));
            canvas.drawArc(left, top, left + size, top + size, 135, sweep, false, fillPaint);
            fillPaint.clearShadowLayer();
        }

        // 3) 数字 LED（模仿滑杆 thumb 中心 LED 指示）
        // 字号与旋钮标题(13sp)协调，bold 增强可读性
        textPaint.setTextSize(sp(12));
        textPaint.setFakeBoldText(true);
        if (active) {
            textPaint.setColor(Color.rgb(0, 245, 212));
            textPaint.setShadowLayer(dp(4), 0, 0, Color.argb(150, 0, 245, 212));
        } else if (enabled) {
            textPaint.setColor(Color.argb(185, 255, 255, 255));
            textPaint.clearShadowLayer();
        } else {
            textPaint.setColor(Color.argb(120, 190, 198, 210));
            textPaint.clearShadowLayer();
        }
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(value + suffix, getWidth() / 2f, textY, textPaint);
        textPaint.clearShadowLayer();
        textPaint.setFakeBoldText(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downX = event.getX();
                downValue = value;
                maybeTap = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = downY - event.getY();
                float dx = Math.abs(event.getX() - downX);
                // 水平位移为主或微小垂直位移视为点击，不进入滑动调值
                if (dx > dp(10) && dx > Math.abs(dy)) {
                    maybeTap = false;
                }
                if (Math.abs(dy) > dp(8)) {
                    maybeTap = false;
                    int range = max - min;
                    int next = downValue + Math.round(dy * range / Math.max(80f, getHeight()));
                    setValue(next, true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (maybeTap && tapListener != null) {
                    tapListener.onDigitTapped(this);
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                maybeTap = false;
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
