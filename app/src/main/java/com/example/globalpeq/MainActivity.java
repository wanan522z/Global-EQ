package com.example.globalpeq;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.BlurMaskFilter;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final int HISTORY_LIMIT = 30;
    private static final int REQUEST_IMPORT_DEVICE_CURVE = 4101;
    private static final int REQUEST_IMPORT_TARGET_CURVE = 4102;
    private static final int REQUEST_MONITOR_CAPTURE = 4103;
    private static final int REQUEST_MONITOR_AUDIO_PERMISSION = 4104;
    private static final int REQUEST_SHIZUKU_PERMISSION = 4105;
    private static final int REQUEST_IMPORT_PRESET_JSON = 4106;
    private static final int REQUEST_IMPORT_DEVICE_CONFIG_JSON = 4107;
    private static final int REQUEST_EXPORT_PRESET_JSON = 4108;
    private static final int REQUEST_EXPORT_DEVICE_CONFIG_JSON = 4109;
    private static final int EQ_EDIT_FIELD_FREQ = 0;
    private static final int EQ_EDIT_FIELD_GAIN = 1;
    private static final int EQ_EDIT_FIELD_Q = 2;
    private static final int GEQ_COMMIT_DELAY_MS = 280;
    private static final int PEQ_BAND_COMMIT_DELAY_MS = 280;
    private static final int PEQ_TOGGLE_COMMIT_DELAY_MS = 90;
    private static final long LIVE_EQ_PREVIEW_DELAY_MS = 48L;
    private static final long EQ_TEXT_INPUT_APPLY_DELAY_MS = 260L;
    private static final long ENABLE_TOGGLE_COMMIT_DELAY_MS = 110L;
    private static final long ENABLE_TOGGLE_SHIZUKU_COMMIT_DELAY_MS = 280L;
    private static final long ENABLE_TOGGLE_INTERACTION_LOCK_MS = 260L;
    private static final long ENABLE_TOGGLE_SHIZUKU_INTERACTION_LOCK_MS = 720L;
    private static final long EXTRA_BASS_TOGGLE_COMMIT_DELAY_MS = 320L;
    private static final long ENABLE_TOGGLE_UI_DELAY_MS = 48L;
    private static final long ENABLE_NEON_HEADER_DELAY_MS = 90L;
    private static final long ENABLE_NEON_CURVE_DELAY_MS = 460L;
    private static final long DISABLE_NEON_CURVE_DELAY_MS = 280L;
    private static final long ENABLE_NEON_PEQ_START_DELAY_MS = 660L;
    private static final long ENABLE_NEON_PEQ_STEP_DELAY_MS = 60L;
    private static final long ENABLE_CAPTURE_REQUEST_EXTRA_DELAY_MS = 140L;
    private static final long DEFERRED_INTEGER_INPUT_COMMIT_DELAY_MS = 150L;
    private static final long PRESET_PERSIST_DELAY_MS = 420L;
    private static final long EQ_EDIT_FADE_IN_MS = 180L;
    private static final long EQ_EDIT_FADE_OUT_MS = 160L;
    private static final long ACTIVE_APP_REFRESH_INTERVAL_MS = 5000L;
    private static final String[] CURVE_RANGE_LABELS = {"±6", "±12", "±18"};
    private static final String[] CURVE_SMOOTHING_LABELS = {"Default", "1/3", "1/6", "1/12", "1/24"};
    private static final String[] REVERB_TYPE_LABELS = {"Default", "Hall", "Plate", "Chamber", "Room", "Studio"};
    private static final String[] VIRTUAL_BASS_MODE_LABELS = {"Default", "System", "DSP"};
    private static final String UI_LANGUAGE_EN = "en";
    private static final String UI_LANGUAGE_ZH = "zh";
    private static final SliderValueMapper LINEAR_SLIDER_MAPPER = new SliderValueMapper() {
        @Override
        public float valueToFraction(int value, int min, int max) {
            return (value - min) / Math.max(1f, max - min);
        }

        @Override
        public int fractionToValue(float fraction, int min, int max) {
            return Math.round(min + (max - min) * fraction);
        }
    };
    private static final SliderValueMapper REVERB_MAIN_SLIDER_MAPPER = new SliderValueMapper() {
        @Override
        public float valueToFraction(int value, int min, int max) {
            if (value <= -300) {
                float tailRange = Math.max(1f, -300f - min);
                return Math.max(0f, Math.min(tailRange, value - min)) / tailRange * 0.2f;
            }
            return 0.2f + Math.max(0f, Math.min(600f, value + 300f)) / 600f * 0.8f;
        }

        @Override
        public int fractionToValue(float fraction, int min, int max) {
            float t = Math.max(0f, Math.min(1f, fraction));
            if (t <= 0.2f) {
                return clamp(Math.round(min + (t / 0.2f) * (-300f - min)), min, max);
            }
            return clamp(Math.round(-300f + ((t - 0.2f) / 0.8f) * 600f), min, max);
        }
    };
    private static final SliderValueMapper REVERB_DECAY_SLIDER_MAPPER = new SliderValueMapper() {
        @Override
        public float valueToFraction(int value, int min, int max) {
            if (value <= 400) {
                return Math.max(0f, Math.min(400f, value)) / 400f * 0.6f;
            }
            return 0.6f + Math.max(0f, Math.min(max - 400f, value - 400f)) / Math.max(1f, max - 400f) * 0.4f;
        }

        @Override
        public int fractionToValue(float fraction, int min, int max) {
            float t = Math.max(0f, Math.min(1f, fraction));
            if (t <= 0.6f) {
                return clamp(Math.round((t / 0.6f) * 400f), min, max);
            }
            return clamp(Math.round(400f + ((t - 0.6f) / 0.4f) * (max - 400f)), min, max);
        }
    };
    private static final SliderValueMapper REVERB_MIX_SLIDER_MAPPER = new SliderValueMapper() {
        @Override
        public float valueToFraction(int value, int min, int max) {
            if (value <= 50) {
                return Math.max(0f, Math.min(50f, value)) / 50f * 0.3f;
            }
            return 0.3f + Math.max(0f, Math.min(max - 50f, value - 50f)) / Math.max(1f, max - 50f) * 0.7f;
        }

        @Override
        public int fractionToValue(float fraction, int min, int max) {
            float t = Math.max(0f, Math.min(1f, fraction));
            if (t <= 0.3f) {
                return clamp(Math.round((t / 0.3f) * 50f), min, max);
            }
            return clamp(Math.round(50f + ((t - 0.3f) / 0.7f) * (max - 50f)), min, max);
        }
    };
    private static final SliderValueMapper REVERB_PREDELAY_SLIDER_MAPPER = new SliderValueMapper() {
        @Override
        public float valueToFraction(int value, int min, int max) {
            if (value <= 50) {
                return Math.max(0f, Math.min(50f, value)) / 50f * 0.5f;
            }
            return 0.5f + Math.max(0f, Math.min(max - 50f, value - 50f)) / Math.max(1f, max - 50f) * 0.5f;
        }

        @Override
        public int fractionToValue(float fraction, int min, int max) {
            float t = Math.max(0f, Math.min(1f, fraction));
            if (t <= 0.5f) {
                return clamp(Math.round((t / 0.5f) * 50f), min, max);
            }
            return clamp(Math.round(50f + ((t - 0.5f) / 0.5f) * (max - 50f)), min, max);
        }
    };

    private PresetRepository repository;
    private GlobalEqualizerEngine engine;
    private AudioOutputDeviceMonitor deviceMonitor;
    private AudioOutputDevice currentDevice;
    private EqCurveView curveView;
    private View curveFrameView;
    private EditText pregainInput;
    private Button deviceCurveButton;
    private Button targetCurveButton;
    private Spinner curveRangeSpinner;
    private LinearLayout eqPage;
    private LinearLayout extraPage;
    private LinearLayout settingsPage;
    private LinearLayout monitorSettingsPage;
    private MainPageHost mainPageHost;
    private LinearLayout rows;
    private KnobView cutoffKnob;
    private KnobView amountKnob;
    private HorizontalBassSlider virtualBassSlider;
    private VerticalReverbSlider reverbMainSlider;
    private VerticalReverbSlider reverbDecaySlider;
    private VerticalReverbSlider reverbPredelaySlider;
    private VerticalReverbSlider reverbSizeSlider;
    private VerticalReverbSlider reverbMixSlider;
    private TextView reverbTypeButton;
    private TextView bassModeButton;
    private EditText virtualBassCutoffInput;
    private TextView reverbTitleView;
    private TextView virtualBassTitleView;
    private TextView extraBassTitleView;
    private FrameLayout topControlOverlay;
    private TextView statusText;
    private TextView engineStatusValueView;
    private TextView engineStatusTitleView;
    private ImageView monitoredAppIconView;
    private View bottomNavView;
    private Spinner deviceSpinner;
    private Spinner savedPresetSpinner;
    private TextView modeSpinner;
    private TextView processingModeButton;
    private TextView settingsPanelDetailView;
    private TextView settingsStatusLabelView;
    private TextView advancedModeDetailButton;
    private TextView advancedMonitorAppButton;
    private TextView monitorCaptureStatusView;
    private TextView shizukuRuntimeTitleView;
    private TextView shizukuRuntimeDetailView;
    private TextView shizukuRuntimeModeView;
    private TextView shizukuRuntimePlaybackView;
    private TextView shizukuRuntimeMuteView;
    private TextView shizukuRuntimeReplayView;
    private TextView shizukuAccessButton;
    private TextView shizukuAccessStatusView;
    private TextView advancedModeSummaryView;
    private TextView languageLabelView;
    private TextView languageButton;
    private TextView aboutTitleView;
    private TextView aboutTextView;
    private TextView footerTextView;
    private TextView monitorSettingsTitleView;
    private TextView monitorSettingsDetailView;
    private TextView shizukuAccessLabelView;
    private TextView monitoredAppLabelView;
    private TextView latencyLabelView;
    private TextView bufferLabelView;
    private TextView pollIntervalLabelView;
    private TextView lookaheadLabelView;
    private TextView limiterCeilingLabelView;
    private TextView limiterReleaseLabelView;
    private LinearLayout settingsRootContent;
    private Button presetSelectButton;
    private Button undoButton;
    private Button redoButton;
    private Button savePresetButton;
    private TextView eqTabButton;
    private TextView extraTabButton;
    private TextView settingsTabButton;
    private View bottomTabIndicator;
    private LinearLayout bottomTabStrip;
    private Switch enabledSwitch;
    private Switch autoSwitchOutputSwitch;
    private Switch extraBassSwitch;
    private LinearLayout header;
    private Preset runningPreset;
    private Preset editingPreset;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // Slow-moving shimmer does not need 60fps; throttling it cuts a large amount of
    // software text redraw cost while keeping the motion visually smooth.
    private static final int SHIMMER_FPS_DELAY = 66;
    private long lastShimmerTime = 0L;
    // 记录每个 view 上次构建 shader 时所用的宽度；仅在尺寸变化时重建，避免每帧 GC 与重分配
    private final java.util.Map<TextView, Integer> textStyleVersion = new java.util.HashMap<>();
    private final java.util.Map<TextView, Float> shimmerViewPhases = new java.util.HashMap<>();
    private final java.util.Map<TextView, Boolean> titleVisualStates = new java.util.HashMap<>();
    private final List<TextView> shimmerTargetViews = new ArrayList<>();
    // 流光速度：每秒平移 0.05 个视图宽度（约 20 秒一个周期）。
    // 极致缓慢滚动，营造静谧高雅的流光氛围。
    private static final float SHIMMER_FLOW_RATE = 0.05f;
    private static final float TAB_SHIMMER_SPEED_MULTIPLIER = 4.2f;
    private final Runnable shimmerAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (lastShimmerTime > 0) {
                float elapsed = (now - lastShimmerTime) / 1000f;
                advanceShimmerPhases(elapsed);
            }
            lastShimmerTime = now;

            for (int i = shimmerTargetViews.size() - 1; i >= 0; i--) {
                TextView view = shimmerTargetViews.get(i);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !view.isAttachedToWindow()) {
                    continue;
                }
                int width = settingsTitleGradientWidth(view);
                if (width <= 0) {
                    width = Math.max(view.getMeasuredWidth(),
                            view.getLayoutParams() != null ? view.getLayoutParams().width : 0);
                    if (width <= 0) {
                        width = dp(120);
                    }
                }

                // 关键修复：每帧把 phase 偏移直接 baked 进 LinearGradient 的坐标参数，
                // 而非用 setLocalMatrix。硬件加速下 TextView 文字走 glyph atlas 渲染，
                // shader.setLocalMatrix() 的变化不被文字渲染管线识别为 paint 变化，
                // 导致 matrix 更新了但 glyph 不重绘（视觉不动）。
                // 每帧新建 shader（坐标含偏移）强制硬件层刷新，invalidate 触发重绘。
                applyShimmerFrame(view, width, currentShimmerPhaseForView(view));
            }
            if (!shimmerTargetViews.isEmpty()) {
                uiHandler.postDelayed(this, SHIMMER_FPS_DELAY);
            } else {
                lastShimmerTime = 0L;
            }
        }
    };

    // 每帧调用：根据 view 类型 + 状态选色阶，把 phase 偏移 baked 进渐变坐标。
    // 恢复原始状态判定（isEditingPresetActive / runningPreset.enabled），
    // 不同状态用不同色阶，但都用 baked offset 方式（不用 setLocalMatrix）。
    private void applyShimmerFrame(TextView view, int width, float phase) {
        if (view == null || width <= 0) {
        }

        boolean usesCustomGlow = view instanceof GlowTitleTextView;
        int[] colors;
        if (view == statusText) {
            boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
            if (!supported || hasClip) {
                return;
            }
            colors = isEditingPresetActive() ? SHIMMER_LIVE_COLORS : SHIMMER_EDIT_COLORS;
        } else if (view == modeSpinner) {
            if (!isModeVisualEnabled()) {
                return;
            }
            colors = SHIMMER_MODE_ON_COLORS;
        } else {
            colors = SHIMMER_BRIGHT_COLORS;
        }

        float offset = phase * width;
        view.getPaint().setShader(new LinearGradient(
                offset, 0, width + offset, 0,
                colors,
                SHIMMER_POSITIONS,
                Shader.TileMode.REPEAT));
        view.setTextColor(Color.WHITE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                && view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        if (view == statusText) {
            view.getPaint().setShadowLayer(dpf(8f), 0, 0, Color.argb(195, 0, 245, 212));
            if (usesCustomGlow) {
                applyGlowToTextView(view, Color.argb(188, 0, 245, 212), 5.25f);
            }
        } else if (usesCustomGlow) {
            applyGlowToTextView(view, Color.argb(210, 120, 220, 255), 7.4f);
        } else {
            view.getPaint().setShadowLayer(dpf(5.5f), 0, 0, Color.argb(138, 120, 220, 255));
        }
        view.invalidate();
    }

    private float shimmerSpeedMultiplierForView(TextView view) {
        if (view == eqTabButton || view == extraTabButton || view == settingsTabButton) {
            return TAB_SHIMMER_SPEED_MULTIPLIER;
        }
        return 1f;
    }

    private float currentShimmerPhaseForView(TextView view) {
        if (view == null) {
            return 0f;
        }
        Float phase = shimmerViewPhases.get(view);
        if (phase != null) {
            return phase;
        }
        float seeded = seedShimmerPhaseForView(view);
        shimmerViewPhases.put(view, seeded);
        return seeded;
    }

    private void advanceShimmerPhases(float elapsedSeconds) {
        if (elapsedSeconds <= 0f) {
            return;
        }
        for (int i = 0; i < shimmerTargetViews.size(); i++) {
            TextView view = shimmerTargetViews.get(i);
            float phase = currentShimmerPhaseForView(view);
            phase += elapsedSeconds * SHIMMER_FLOW_RATE * shimmerSpeedMultiplierForView(view);
            shimmerViewPhases.put(view, phase - (float) Math.floor(phase));
        }
    }

    private float seedShimmerPhaseForView(TextView view) {
        int hash = System.identityHashCode(view);
        float phase = (hash * 0.6180339f) % 1f;
        if (phase < 0f) {
            phase += 1f;
        }
        return phase;
    }

    // 璀璨亮色蓝绿流光色阶：极大精简渐变色标（由9个缩减为5个），使单色宽度更宽、过渡更丝滑，大幅节约每一帧的渐变插值计算开销！
    private static final float[] SHIMMER_POSITIONS = {0.0f, 0.28f, 0.5f, 0.72f, 1.0f};

    // 亮色阶：超高亮炽白冰蓝流光——压掉绿色、加大浅亮蓝与超白核心占比，整体发光亮度直接拉满！
    private static final int[] SHIMMER_BRIGHT_COLORS = {
            Color.rgb(150, 235, 255),  // 浅亮蓝白 (B>G，偏蓝的发光白)
            Color.rgb(50, 210, 255),   // 极亮电光冰蓝 (B>>G，纯净冰蓝，零绿色)
            Color.rgb(255, 255, 255),  // 纯白超炽亮核心 (Super HotCore White)
            Color.rgb(50, 210, 255),   // 极亮电光冰蓝
            Color.rgb(150, 235, 255)   // 浅亮蓝白
    };
    // Live 模式 statusText：同亮色阶（极亮冰蓝与炽白光晕，动感璀璨）
    private static final int[] SHIMMER_LIVE_COLORS = SHIMMER_BRIGHT_COLORS;
    // Edit 模式 statusText：高雅通透、极其明亮的浅冰蓝白（去绿，偏蓝与白）
    private static final int[] SHIMMER_EDIT_COLORS = {
            Color.rgb(180, 230, 255),  // 极亮淡蓝白
            Color.rgb(110, 210, 255),  // 极高亮冰蓝
            Color.rgb(255, 255, 255),  // 纯白核心
            Color.rgb(110, 210, 255),  // 极高亮冰蓝
            Color.rgb(180, 230, 255)   // 极亮淡蓝白
    };
    // modeSpinner enabled：亮色阶（与 Live 同）
    private static final int[] SHIMMER_MODE_ON_COLORS = SHIMMER_BRIGHT_COLORS;

    private final List<Preset> undoStack = new ArrayList<>();
    private final List<Preset> redoStack = new ArrayList<>();
    private Preset pendingGeqHistorySnapshot;
    private Preset pendingPeqBandHistorySnapshot;
    private Preset pendingPeqToggleHistorySnapshot;
    private int pendingGeqPreviewIndex = -1;
    private int pendingGeqPreviewGainMb;
    private int pendingPeqPreviewIndex = -1;
    private ParametricBand pendingPeqPreviewBand;
    private final Runnable commitGeqUpdateRunnable = this::commitPendingGeqUpdate;
    private final Runnable previewGeqUpdateRunnable = this::flushPendingGeqPreview;
    private final Runnable commitPeqBandUpdateRunnable = this::commitPendingPeqBandUpdate;
    private final Runnable previewPeqBandUpdateRunnable = this::flushPendingPeqBandPreview;
    private final Runnable commitPeqToggleRunnable = this::commitPendingPeqToggle;
    private final Runnable commitEnabledToggleRunnable = this::commitPendingEnabledToggle;
    private final Runnable commitExtraBassToggleRunnable = this::commitPendingExtraBassToggle;
    private final Runnable refreshEnabledToggleUiRunnable = this::refreshPendingEnabledToggleUi;
    private final Runnable unlockEnabledToggleInteractionRunnable = this::unlockEnabledToggleInteraction;
    private final Runnable enableNeonHeaderRunnable = this::activateEnabledNeonHeader;
    private final Runnable enableNeonCurveRunnable = this::activateEnabledNeonCurve;
    private final Runnable disableNeonCurveRunnable = this::activateDisabledNeonCurve;
    private final Runnable enablePeqBandStepRunnable = this::activateNextPeqBandVisual;
    private final Runnable delayedShizukuReadyRunnable = () -> ensureShizukuModeReady(true);
    private final Runnable persistPresetStateRunnable = this::flushPendingPresetPersistence;
    private boolean supported;
    private boolean updatingUi;
    private boolean autoSwitchOutput;
    private int curveGraphMaxDb = 18;
    private String selectedDeviceCurveName = "Default";
    private String selectedTargetCurveName = "Default";
    private FrequencyCurve selectedDeviceCurveSource = FrequencyCurve.DEFAULT;
    private FrequencyCurve selectedTargetCurveSource = FrequencyCurve.DEFAULT;
    private FrequencyCurve selectedDeviceCurveBase = FrequencyCurve.DEFAULT;
    private FrequencyCurve selectedTargetCurveBase = FrequencyCurve.DEFAULT;
    private FrequencyCurve selectedDeviceCurve = FrequencyCurve.DEFAULT;
    private FrequencyCurve selectedTargetCurve = FrequencyCurve.DEFAULT;
    private float deviceCurveGainOffsetDb = 0f;
    private float targetCurveGainOffsetDb = 0f;
    private String deviceCurveSmoothing = "Default";
    private String targetCurveSmoothing = "Default";
    private List<AudioOutputDevice> deviceChoices = new ArrayList<>();
    private final List<View> curveGainDimViews = new ArrayList<>();
    private final List<View> eqEditDimViews = new ArrayList<>();
    private View contentRootView;
    private View activeEqEditOverlay;
    private int activeEqEditBandIndex = -1;
    private int activeEqEditField = EQ_EDIT_FIELD_FREQ;
    private boolean keyboardVisible;
    private boolean suppressEqOverlayHideOnKeyboardDismiss;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private boolean extraBassEnabledState;
    private int activeMainPageIndex;
    private int selectedBassModeIndex;
    private ProcessingMode processingMode = ProcessingMode.SYSTEM_EQ;
    private String uiLanguage = UI_LANGUAGE_EN;
    private AdvancedModeConfig advancedModeConfig = AdvancedModeConfig.DEFAULT;
    private Preset pendingEnabledApplyPreset;
    private Preset pendingEnabledPersistPreset;
    private Boolean pendingExtraBassEnabledState;
    private boolean pendingEnabledUiRefresh;
    private boolean enabledToggleInteractionLocked;
    private boolean modeVisualEnabled = true;
    private boolean curveVisualEnabled = true;
    private boolean[] peqBandVisualEnabled = new boolean[0];
    private boolean peqVisualSequenceRunning;
    private int pendingPeqVisualIndex;
    private boolean monitorSettingsOpen;
    private boolean pendingMonitorCaptureAuthorization;
    private String activePlaybackPackageName = "";
    private String lastDeviceSpinnerSignature = "";
    private String lastDeviceSpinnerSelectedKey = "";
    private String lastSavedPresetSpinnerSignature = "";
    private String lastSavedPresetSpinnerSelectedName = "";
    private EqMode lastRenderedHeaderMode;
    private EqMode lastRenderedRowsMode;
    private int lastRenderedRowsBandCount = -1;
    private boolean pendingEditingPresetPersistence;
    private boolean pendingRunningPresetPersistence;
    private String pendingExportJson;
    private String pendingExportSuccessMessage;
    private final ShizukuCompat.StateListener shizukuStateListener = this::handleShizukuStateChanged;
    private final ShizukuCompat.PermissionResultListener shizukuPermissionResultListener = this::handleShizukuPermissionResult;
    private final Runnable activePlaybackPackageRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshActivePlaybackPackageFromRepository();
            if (!isFinishing() && !isDestroyedCompat()) {
                uiHandler.postDelayed(this, ACTIVE_APP_REFRESH_INTERVAL_MS);
            }
        }
    };
    private final Runnable monitorStatusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitorSettingsOpen) {
                return;
            }
            refreshMonitorStatusViews();
            uiHandler.postDelayed(this, ACTIVE_APP_REFRESH_INTERVAL_MS);
        }
    };
    private boolean awaitingInitialDeviceMonitorEvent;
    private boolean suppressInitialDeviceReapply;
    private boolean hasStartedDeviceMonitorOnce;
    private boolean startupProcessingRecoveryPending = true;
    private OnBackInvokedCallback systemBackCallback;
    private boolean systemBackCallbackRegistered;

    private static final class PeqBandRowHolder {
        View enable;
        TextView type;
        EditText frequency;
        EditText gain;
        EditText q;
        View delete;
    }

    private static final class InstalledAppEntry {
        final ApplicationInfo appInfo;
        final String label;
        final String packageName;
        final String normalizedLabel;
        final String normalizedPackage;

        InstalledAppEntry(ApplicationInfo appInfo, String label, String packageName) {
            this.appInfo = appInfo;
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.normalizedLabel = this.label.toLowerCase(Locale.US);
            this.normalizedPackage = this.packageName.toLowerCase(Locale.US);
        }
    }

    private static final class MonitoredAppListEntry {
        final String label;
        final String packageName;
        final String normalizedLabel;
        final String normalizedPackage;

        MonitoredAppListEntry(String label, String packageName) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.normalizedLabel = this.label.toLowerCase(Locale.US);
            this.normalizedPackage = this.packageName.toLowerCase(Locale.US);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerSystemBackCallback();
        repository = new PresetRepository(this);
        engine = GlobalEqRuntime.engine();
        supported = true;
        autoSwitchOutput = repository.loadAutoSwitchOutput();
        processingMode = repository.loadProcessingMode();
        uiLanguage = repository.loadUiLanguage();
        advancedModeConfig = repository.loadAdvancedModeConfig();
        selectedDeviceCurveName = repository.loadSelectedDeviceCurveName();
        selectedTargetCurveName = repository.loadSelectedTargetCurveName();
        deviceCurveGainOffsetDb = repository.loadDeviceCurveGainOffsetDb();
        targetCurveGainOffsetDb = repository.loadTargetCurveGainOffsetDb();
        deviceCurveSmoothing = repository.loadDeviceCurveSmoothing();
        targetCurveSmoothing = repository.loadTargetCurveSmoothing();
        refreshDeviceCurveCache();
        refreshTargetCurveCache();
        deviceMonitor = new AudioOutputDeviceMonitor(this);
        currentDevice = repository.loadSelectedDevice();
        if (currentDevice == null) {
            currentDevice = deviceMonitor.currentOutputDevice();
            repository.saveSelectedDevice(currentDevice);
        }
        boolean masterEnabled = repository.loadMasterEnabled();
        Preset loadedPreset = repository.loadPreset(currentDevice, processingMode);
        Preset limitedPreset = limitPresetForHeadroom(loadedPreset);
        boolean loadedWasLimited = !limitedPreset.toJson().equals(loadedPreset.toJson());
        loadedPreset = limitedPreset;
        runningPreset = loadedPreset.withEnabled(masterEnabled && supported);
        modeVisualEnabled = runningPreset.enabled;
        curveVisualEnabled = runningPreset.enabled;
        editingPreset = runningPreset;
        syncEditingStateFromPreset();

        requestRuntimePermissions();
        setContentView(buildContent());
        installKeyboardVisibilityListener();
        renderAll();
        if (loadedWasLimited && runningPreset.enabled) {
            if (processingMode == ProcessingMode.SHIZUKU_MUTE) {
                applyRunningPreset(false, false);
            } else {
                applyRunningPreset();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShizukuCompat.addStateListener(shizukuStateListener);
        ShizukuCompat.addPermissionResultListener(shizukuPermissionResultListener);
        refreshActivePlaybackPackageFromRepository();
        uiHandler.removeCallbacks(activePlaybackPackageRefreshRunnable);
        uiHandler.post(activePlaybackPackageRefreshRunnable);
        boolean serviceActive = repository != null && syncRuntimeStateWithServiceProcess();
        suppressInitialDeviceReapply = hasStartedDeviceMonitorOnce && serviceActive;
        hasStartedDeviceMonitorOnce = true;
        awaitingInitialDeviceMonitorEvent = true;
        deviceMonitor.start(this::handleDetectedOutputDevice);
        uiHandler.post(this::maybeEnsureProcessingActive);
    }

    @Override
    protected void onStop() {
        commitPendingGeqUpdate();
        commitPendingPeqToggle();
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.removeCallbacks(refreshEnabledToggleUiRunnable);
        uiHandler.removeCallbacks(unlockEnabledToggleInteractionRunnable);
        uiHandler.removeCallbacks(delayedShizukuReadyRunnable);
        uiHandler.removeCallbacks(activePlaybackPackageRefreshRunnable);
        uiHandler.removeCallbacks(monitorStatusRefreshRunnable);
        cancelEnabledNeonSequence();
        refreshPendingEnabledToggleUi();
        commitPendingEnabledToggle();
        flushPendingPresetPersistence();
        activePlaybackPackageName = "";
        deviceMonitor.stop();
        ShizukuCompat.removePermissionResultListener(shizukuPermissionResultListener);
        ShizukuCompat.removeStateListener(shizukuStateListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null && processingMode == ProcessingMode.SHIZUKU_MUTE) {
            repository.saveShizukuMuteStatus(
                    ShizukuCompat.describeState(this),
                    ShizukuCompat.hasPermission());
        }
        refreshActivePlaybackPackageFromRepository();
        refreshRuntimeStatusUi();
        refreshDeviceSelectionUi();
        updateEditStateLabels();
        maybeEnsureProcessingActive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        // 窗口获得焦点时所有 view 已完成布局，getWidth() 返回真实值。
        // 此时强制重建 modeSpinner 和 active tab 的 shader，确保流光用正确宽度运行。
        // 解决 onCreate 中注册时 getWidth()==0 导致 shader width=1 的问题。
        if (modeSpinner != null && modeSpinner.getWidth() > 0) {
            styleSettingsTitleText(modeSpinner);
            registerShimmerView(modeSpinner);
        }
        updateBottomNavSelection(activeMainPageIndex);
        
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (shouldDismissKeyboardOnTouch(focused, event)) {
                closeKeyboard(focused);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        unregisterSystemBackCallback();
        shimmerTargetViews.clear();
        uiHandler.removeCallbacks(shimmerAnimationRunnable);
        uiHandler.removeCallbacks(commitPeqToggleRunnable);
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.removeCallbacks(refreshEnabledToggleUiRunnable);
        uiHandler.removeCallbacks(unlockEnabledToggleInteractionRunnable);
        uiHandler.removeCallbacks(delayedShizukuReadyRunnable);
        uiHandler.removeCallbacks(activePlaybackPackageRefreshRunnable);
        uiHandler.removeCallbacks(persistPresetStateRunnable);
        cancelEnabledNeonSequence();
        removeKeyboardVisibilityListener();
        super.onDestroy();
    }

    private void installKeyboardVisibilityListener() {
        contentRootView = findViewById(android.R.id.content);
        if (contentRootView == null) {
            return;
        }
        keyboardLayoutListener = () -> {
            if (contentRootView == null) {
                return;
            }
            Rect visibleFrame = new Rect();
            contentRootView.getWindowVisibleDisplayFrame(visibleFrame);
            int rootHeight = contentRootView.getRootView().getHeight();
            if (rootHeight <= 0) {
                return;
            }
            int hiddenHeight = rootHeight - visibleFrame.bottom;
            boolean visibleNow = hiddenHeight > rootHeight * 0.15f;
            boolean suppressedOverlayDismiss = suppressEqOverlayHideOnKeyboardDismiss;
            if (keyboardVisible && !visibleNow && activeEqEditOverlay != null && !suppressedOverlayDismiss) {
                hideEqEditOverlay();
            }
            if (keyboardVisible && !visibleNow) {
                suppressEqOverlayHideOnKeyboardDismiss = false;
                if (!suppressedOverlayDismiss) {
                    clearEditingFocusAfterKeyboardDismiss();
                }
            }
            keyboardVisible = visibleNow;
        };
        contentRootView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
    }

    private void removeKeyboardVisibilityListener() {
        if (contentRootView == null || keyboardLayoutListener == null) {
            return;
        }
        contentRootView.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        keyboardLayoutListener = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MONITOR_CAPTURE) {
            pendingMonitorCaptureAuthorization = false;
            if (resultCode != RESULT_OK || data == null) {
                repository.saveMonitorCaptureAuthorized(false);
                repository.saveMonitorCaptureStatus("Capture authorization was cancelled.", false);
                refreshRuntimeStatusUi();
                return;
            }
            repository.saveMonitorCaptureStatus("Starting native capture...", false);
            refreshRuntimeStatusUi();
            Intent service = buildRunningPresetServiceIntent(GlobalEqForegroundService.ACTION_BOOTSTRAP_CAPTURE);
            service.putExtra(GlobalEqForegroundService.EXTRA_CAPTURE_RESULT_CODE, resultCode);
            service.putExtra(GlobalEqForegroundService.EXTRA_CAPTURE_DATA, new Intent(data));
            startCompatibleForegroundService(service);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_EXPORT_PRESET_JSON || requestCode == REQUEST_EXPORT_DEVICE_CONFIG_JSON) {
            if (pendingExportJson == null || pendingExportJson.trim().isEmpty()) {
                Toast.makeText(this, tr("Nothing to export", "没有可导出的内容"), Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                writeTextToUri(data.getData(), pendingExportJson);
                Toast.makeText(
                        this,
                        pendingExportSuccessMessage == null ? tr("Export complete", "导出完成") : pendingExportSuccessMessage,
                        Toast.LENGTH_SHORT
                ).show();
            } catch (IOException ex) {
                Toast.makeText(this, tr("Export failed", "导出失败"), Toast.LENGTH_SHORT).show();
            } finally {
                pendingExportJson = null;
                pendingExportSuccessMessage = null;
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_PRESET_JSON) {
            try {
                importPresetJsonFromUri(data.getData());
            } catch (IOException ex) {
                Toast.makeText(this, tr("Preset import failed", "预设导入失败"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_DEVICE_CONFIG_JSON) {
            try {
                importDeviceConfigJsonFromUri(data.getData());
            } catch (IOException ex) {
                Toast.makeText(this, tr("Global config import failed", "全局配置导入失败"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_IMPORT_DEVICE_CURVE && requestCode != REQUEST_IMPORT_TARGET_CURVE) {
            return;
        }

        try {
            FrequencyCurve curve = readCurveFromUri(data.getData());
            if (curve.isDefault()) {
                Toast.makeText(this, "No valid curve points found", Toast.LENGTH_SHORT).show();
                return;
            }
            if (requestCode == REQUEST_IMPORT_DEVICE_CURVE) {
                repository.saveDeviceCurve(curve);
                selectedDeviceCurveName = curve.name;
                selectedDeviceCurveSource = curve;
                deviceCurveGainOffsetDb = 0f;
                deviceCurveSmoothing = "Default";
                refreshDeviceCurveCache();
            } else {
                repository.saveTargetCurve(curve);
                selectedTargetCurveName = curve.name;
                selectedTargetCurveSource = curve;
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                refreshTargetCurveCache();
            }
            syncCurrentCurveSettingsToEditingPreset(true);
            refreshCurveSettingsUi();
        } catch (IOException ex) {
            Toast.makeText(this, "Curve import failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDetectedOutputDevice(AudioOutputDevice device) {
        repository.saveKnownDevice(device);
        boolean sameDevice = currentDevice != null && currentDevice.key.equals(device.key);
        if (awaitingInitialDeviceMonitorEvent) {
            awaitingInitialDeviceMonitorEvent = false;
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            if (suppressInitialDeviceReapply) {
                suppressInitialDeviceReapply = false;
                adoptDevicePresetForCurrentMode(currentDevice, true);
                renderAll();
                return;
            }
            if (!autoSwitchOutput) {
                refreshDeviceSelectionUi();
                return;
            }
            if (sameDevice) {
                refreshDeviceSelectionUi();
                return;
            }
            adoptDevicePresetForCurrentMode(currentDevice, true);
            renderAll();
            return;
        }
        if (!autoSwitchOutput) {
            refreshDeviceSelectionUi();
            return;
        }

        if (sameDevice) {
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            refreshDeviceSelectionUi();
            return;
        }

        flushPendingPresetPersistence();
        currentDevice = device;
        repository.saveSelectedDevice(currentDevice);
        adoptDevicePresetForCurrentMode(currentDevice, true);
        renderAll();
        applyRunningPreset(shouldForceFullResetForCurrentMode());
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this) {
            private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w > 0 && h > 0) {
                    canvas.drawColor(Color.rgb(18, 18, 25));

                    RadialGradient cyanGlow = new RadialGradient(
                            w * 0.8f, h * 0.15f, Math.min(w, h) * 0.75f,
                            Color.argb(85, 0, 255, 255), Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                    );
                    blobPaint.setShader(cyanGlow);
                    canvas.drawCircle(w * 0.8f, h * 0.15f, Math.min(w, h) * 0.75f, blobPaint);

                    RadialGradient purpleGlow = new RadialGradient(
                            w * 0.15f, h * 0.75f, Math.min(w, h) * 0.85f,
                            Color.argb(70, 160, 100, 255), Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                    );
                    blobPaint.setShader(purpleGlow);
                    canvas.drawCircle(w * 0.15f, h * 0.75f, Math.min(w, h) * 0.85f, blobPaint);

                    blobPaint.setShader(null);

                    blobPaint.setColor(Color.argb(70, 18, 18, 25));
                    canvas.drawRect(0, 0, w, h, blobPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(6));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                view.setPadding(dp(12), top + dp(10), dp(12), dp(6));
                return insets;
            });
            root.post(root::requestApplyInsets);
        }

        mainPageHost = new MainPageHost(this);
        root.addView(mainPageHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        eqPage = new LinearLayout(this);
        eqPage.setOrientation(LinearLayout.VERTICAL);
        eqPage.setClipChildren(false);
        eqPage.setClipToPadding(false);
        mainPageHost.addView(eqPage, pageHostParams());

        extraPage = new LinearLayout(this);
        extraPage.setOrientation(LinearLayout.VERTICAL);
        extraPage.setVisibility(View.GONE);
        extraPage.setPadding(0, dp(16), 0, 0);
        extraPage.setClipChildren(false);
        extraPage.setClipToPadding(false);
        mainPageHost.addView(extraPage, pageHostParams());
        buildExtraPage(extraPage);

        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        settingsPage.setVisibility(View.GONE);
        settingsPage.setClipChildren(false);
        settingsPage.setClipToPadding(false);
        mainPageHost.addView(settingsPage, pageHostParams());
        buildSettingsPage(settingsPage);

        monitorSettingsPage = new LinearLayout(this);
        monitorSettingsPage.setOrientation(LinearLayout.VERTICAL);
        monitorSettingsPage.setVisibility(View.GONE);
        monitorSettingsPage.setPadding(0, dp(16), 0, 0);
        monitorSettingsPage.setClipChildren(false);
        monitorSettingsPage.setClipToPadding(false);
        monitorSettingsPage.setBackgroundColor(Color.TRANSPARENT);
        monitorSettingsPage.setClickable(true);
        mainPageHost.addView(monitorSettingsPage, pageHostParams());
        buildMonitorSettingsPage(monitorSettingsPage);

        LinearLayout controlCard = new LinearLayout(this);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        controlCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        controlCard.setBackground(createGlassCard(35));
        eqPage.addView(controlCard, blockParams(0));

        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        controlCard.setClipChildren(false);
        controlCard.setClipToPadding(false);
        controlCard.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        topControlOverlay = top;

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        topRow.setClipChildren(false);
        topRow.setClipToPadding(false);
        top.addView(topRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        modeSpinner = new GlowTitleTextView(this);
        modeSpinner.setTextSize(16);
        modeSpinner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        modeSpinner.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        modeSpinner.setSingleLine(true);
        modeSpinner.setIncludeFontPadding(false);
        modeSpinner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        modeSpinner.setPadding(0, 0, dp(6), 0);
        reserveStartGlowWithoutMoving(modeSpinner, 16);
        modeSpinner.setMinWidth(0);
        modeSpinner.setMinimumWidth(0);
        styleSettingsTitleText(modeSpinner);
        modeSpinner.setOnClickListener(v -> {
            if (editingPreset == null) {
                return;
            }
            showLimitedChoiceMenu(modeSpinner, EqMode.labels(), editingPreset.mode.ordinal(), position -> {
                EqMode nextMode = EqMode.values()[Math.max(0, Math.min(EqMode.values().length - 1, position))];
                if (editingPreset.mode != nextMode) {
                    setEditingPreset(editingPreset.withMode(nextMode), true);
                    renderAll();
                }
            });
        });
        LinearLayout leftCluster = new LinearLayout(this);
        leftCluster.setOrientation(LinearLayout.HORIZONTAL);
        leftCluster.setGravity(android.view.Gravity.CENTER_VERTICAL);
        topRow.addView(leftCluster, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
        );
        modeParams.leftMargin = dp(6);
        modeParams.rightMargin = dp(4);
        leftCluster.addView(modeSpinner, modeParams);

        monitoredAppIconView = new ImageView(this);
        monitoredAppIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconPad = monitoredAppIconGlowInsetPx();
        monitoredAppIconView.setPadding(iconPad, iconPad, iconPad, iconPad);
        monitoredAppIconView.setBackground(iconGlowDrawable(Color.argb(178, 120, 220, 255)));
        monitoredAppIconView.setClipToOutline(false);
        // 注意：不能再设 LAYER_TYPE_SOFTWARE——软件图层会把绘制裁剪到 View 尺寸，
        // 导致超出 36dp View 边界的光晕被切掉。新光晕用预模糊位图，无需软件渲染。
        monitoredAppIconView.setVisibility(View.GONE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                monitoredAppIconHostSizePx(),
                monitoredAppIconHostSizePx());
        iconParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("");
        enabledSwitch.setShowText(false);
        enabledSwitch.setEnabled(supported);
        enabledSwitch.setChecked(runningPreset != null && runningPreset.enabled);
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        styleTopSwitch(enabledSwitch, false);
        enabledSwitch.setTranslationY(dp(1));
        autoSwitchOutputSwitch = new Switch(this);
        autoSwitchOutputSwitch.setText("");
        autoSwitchOutputSwitch.setShowText(false);
        autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        autoSwitchOutputSwitch.setOnCheckedChangeListener(this::onAutoSwitchOutputChanged);
        styleTopSwitch(autoSwitchOutputSwitch, true);
        autoSwitchOutputSwitch.setTranslationY(dp(1));
        statusText = gradientTitleView("");
        statusText.setTextSize(12);
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(dp(10), dp(4), dp(10), dp(4));
        statusText.setTranslationY(dp(1));
        styleStatusText(false);
        int controlGap = 12;
        LinearLayout.LayoutParams autoSwitchParams = new LinearLayout.LayoutParams(
                dp(60),
                dp(30)
        );
        autoSwitchParams.rightMargin = dp(2);
        topRow.addView(autoSwitchOutputSwitch, autoSwitchParams);

        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.rightMargin = dp(2);
        topRow.addView(statusText, statusParams);

        topRow.addView(enabledSwitch, new LinearLayout.LayoutParams(dp(60), dp(30)));

        LinearLayout deviceRow = new LinearLayout(this);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        deviceRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controlCard.addView(deviceRow, blockParams(12));

        deviceSpinner = new Spinner(this);
        normalizeSpinnerSurface(deviceSpinner);
        deviceSpinner.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showDeviceChoiceMenu();
            }
            return true;
        });
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingUi || position < 0 || position >= deviceChoices.size()) {
                    return;
                }
                AudioOutputDevice selected = deviceChoices.get(position);
                if (!selected.key.equals(currentDevice.key)) {
                    selectOutputDevice(selected);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        deviceSpinner.setBackground(createEqControlBackground());
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(0, dp(34), 1.6f);
        deviceParams.leftMargin = 0;
        deviceParams.rightMargin = 0;
        deviceRow.addView(deviceSpinner, deviceParams);

        savedPresetSpinner = new Spinner(this);
        normalizeSpinnerSurface(savedPresetSpinner);
        savedPresetSpinner.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showSavedPresetChoiceMenu();
            }
            return true;
        });
        savedPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingUi) {
                    return;
                }
                String name = String.valueOf(parent.getItemAtPosition(position));
                if (runningPreset != null && !samePresetSelection(name, presetName(runningPreset))) {
                    loadMatchedPreset(name);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        savedPresetSpinner.setBackground(createEqControlBackground());
        LinearLayout.LayoutParams savedPresetParams = new LinearLayout.LayoutParams(0, dp(34), 1.4f);
        savedPresetParams.leftMargin = dp(12);
        savedPresetParams.rightMargin = 0;
        deviceRow.addView(savedPresetSpinner, savedPresetParams);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controlCard.addView(presetRow, blockParams(12));

        presetSelectButton = new MarqueeButton(this);
        presetSelectButton.setTextSize(13);
        presetSelectButton.setAllCaps(false);
        presetSelectButton.setGravity(android.view.Gravity.CENTER);
        configureCenteredMarquee(presetSelectButton);
        
        presetSelectButton.setOnClickListener(v -> showPresetMenu());
        presetRow.addView(presetSelectButton, presetButtonParams(0, 1.4f, 0, 12));

        undoButton = new Button(this);
        undoButton.setForeground(makeCurvedArrowDrawable(false));
        undoButton.setForegroundGravity(android.view.Gravity.CENTER);
        undoButton.setIncludeFontPadding(false);
        undoButton.setOnClickListener(v -> undoEdit());
        presetRow.addView(undoButton, presetButtonParams(dp(48), 0f, 0, 8));

        redoButton = new Button(this);
        redoButton.setForeground(makeCurvedArrowDrawable(true));
        redoButton.setForegroundGravity(android.view.Gravity.CENTER);
        redoButton.setIncludeFontPadding(false);
        redoButton.setOnClickListener(v -> redoEdit());
        presetRow.addView(redoButton, presetButtonParams(dp(48), 0f, 0, 8));

        savePresetButton = new Button(this);
        savePresetButton.setText("Save");
        savePresetButton.setTextSize(12);
        savePresetButton.setOnClickListener(v -> showSavePresetDialog());
        presetRow.addView(savePresetButton, presetButtonParams(dp(48), 0f, 0, 0));

        FrameLayout curveFrame = new FrameLayout(this);
        curveFrameView = curveFrame;
        curveFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        curveFrame.setBackground(strokeGlowRoundRectDrawable(
                Color.argb((int)(20 * 2.55f), 18, 22, 34),
                Color.argb(120, 0, 245, 212),
                dp(14),
                dp(3),
                Color.argb(80, 0, 245, 212)
        ));
        
        curveView = new EqCurveView(this);
        curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
        curveView.setMaxDb(curveGraphMaxDb);
        curveView.setOnClickListener(v -> {
            if (activeEqEditOverlay != null) {
                closeKeyboard(v);
            }
        });
        curveFrame.addView(curveView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        curveRangeSpinner = new Spinner(this);
        normalizeSpinnerSurface(curveRangeSpinner);
        CompactSpinnerAdapter rangeAdapter = new CompactSpinnerAdapter(CURVE_RANGE_LABELS, true);
        rangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        curveRangeSpinner.setAdapter(rangeAdapter);
        int rangeIndex = curveRangeIndex(curveGraphMaxDb);
        rangeAdapter.setSelectedPosition(rangeIndex);
        curveRangeSpinner.setSelection(rangeIndex);
        curveRangeSpinner.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        curveRangeSpinner.setBackground(curveControlBackground());
        curveRangeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                rangeAdapter.setSelectedPosition(position);
                setCurveGraphRange(position == 0 ? 6 : position == 1 ? 12 : 18);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        FrameLayout.LayoutParams rangeParams = new FrameLayout.LayoutParams(dp(42), dp(22));
        rangeParams.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
        rangeParams.topMargin = dp(7);
        rangeParams.rightMargin = dp(7);
        curveFrame.addView(curveRangeSpinner, rangeParams);

        deviceCurveButton = createCurveButton("Device");
        deviceCurveButton.setOnClickListener(v -> showDeviceCurveMenu());
        deviceCurveButton.setOnLongClickListener(v -> {
            showCurveGainDialog(false);
            return true;
        });
        FrameLayout.LayoutParams deviceCurveParams = new FrameLayout.LayoutParams(dp(82), dp(28));
        deviceCurveParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        deviceCurveParams.rightMargin = dp(156);
        deviceCurveParams.bottomMargin = dp(15);
        curveFrame.addView(deviceCurveButton, deviceCurveParams);

        targetCurveButton = createCurveButton("Target");
        targetCurveButton.setOnClickListener(v -> showTargetCurveMenu());
        targetCurveButton.setOnLongClickListener(v -> {
            showCurveGainDialog(true);
            return true;
        });
        FrameLayout.LayoutParams targetCurveParams = new FrameLayout.LayoutParams(dp(82), dp(28));
        targetCurveParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        targetCurveParams.rightMargin = dp(68);
        targetCurveParams.bottomMargin = dp(15);
        curveFrame.addView(targetCurveButton, targetCurveParams);

        pregainInput = createNumberInput("0.00", "Pre", value -> {
            int pregainMb = Math.round(value * 100f);
            setEditingPreset(editingPreset.withPregainMb(clamp(pregainMb, -2400, 1200)), true);
        });
        pregainInput.setTextSize(11);
        pregainInput.setGravity(android.view.Gravity.CENTER);
        pregainInput.setPadding(0, 0, 0, 0);
        styleCurveButton(pregainInput);
        FrameLayout.LayoutParams pregainParams = new FrameLayout.LayoutParams(dp(54), dp(28));
        pregainParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
        pregainParams.rightMargin = dp(8);
        pregainParams.bottomMargin = dp(15);
        curveFrame.addView(pregainInput, pregainParams);

        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.1f
        );
        curveParams.topMargin = dp(12);
        eqPage.addView(curveFrame, curveParams);

        FrameLayout listCardHolder = new FrameLayout(this);
        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCardHolder.setClipChildren(true);
        listCardHolder.setClipToPadding(true);
        listCard.setClipChildren(true);
        listCard.setClipToPadding(true);
        listCard.setPadding(dp(10), dp(9), dp(10), dp(10));
        listCard.setBackground(createGlassCard(30));

        LinearLayout.LayoutParams listCardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                2f
        );
        listCardParams.topMargin = dp(10);
        eqPage.addView(listCardHolder, listCardParams);

        FrameLayout.LayoutParams listCardInnerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        listCardInnerParams.bottomMargin = dp(8);
        listCardHolder.addView(listCard, listCardInnerParams);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(1), 0, dp(2));
        listCard.addView(header);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipChildren(true);
        scrollView.setClipToPadding(true);
        scrollView.setPadding(0, 0, 0, 0);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        scrollView.addView(rows);
        listCard.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout.LayoutParams bottomNavParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        bottomNavParams.topMargin = dp(4);
        bottomNavParams.bottomMargin = dp(16);
        bottomNavView = buildBottomNav();
        root.addView(bottomNavView, bottomNavParams);

        return root;
    }

    private FrameLayout.LayoutParams pageHostParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private void buildSettingsPage(LinearLayout page) {
        page.setPadding(0, dp(16), 0, 0);

        settingsRootContent = new LinearLayout(this);
        settingsRootContent.setOrientation(LinearLayout.VERTICAL);
        page.addView(settingsRootContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));
        settingsRootContent.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = gradientTitleView(processingModeTitleText());
        title.setText(processingModeTitleText());
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(title);
        LinearLayout.LayoutParams engineTitleParams = blockParams(0);
        // 抵消 gradientTitleView 的左 padding(22dp)，让标题文字左缘对齐下方 detail 正文（都从 panel 内容区左边开始）。
        // title view 左移进入 panel padding 区的 22dp 正好是空白 leftPadding，shadow 半径 5.5dp 仍落在 panel 16dp padding 内，不裁剪。
        engineTitleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(title, 12);
        panel.addView(title, engineTitleParams);
        engineStatusTitleView = title;

        settingsPanelDetailView = new TextView(this);
        settingsPanelDetailView.setText(settingsModeDetailText());
        settingsPanelDetailView.setTextSize(12);
        settingsPanelDetailView.setTextColor(Color.rgb(160, 170, 190));
        panel.addView(settingsPanelDetailView, blockParams(2));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel.addView(statusRow, blockParams(12));

        settingsStatusLabelView = new TextView(this);
        settingsStatusLabelView.setText(settingsStatusLabelText());
        settingsStatusLabelView.setTextSize(14);
        settingsStatusLabelView.setTextColor(Color.rgb(200, 210, 230));
        statusRow.addView(settingsStatusLabelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        engineStatusValueView = new GlowTitleTextView(this);
        engineStatusValueView.setText(engineStatusText());
        engineStatusValueView.setTextSize(14);
        engineStatusValueView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        engineStatusValueView.setPadding(dp(10), dp(4), dp(10), dp(4));
        engineStatusValueView.setOnClickListener(this::showProcessingModeChoiceMenu);
        styleGradientTitle(engineStatusValueView);
        statusRow.addView(engineStatusValueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        processingModeButton = engineStatusValueView;

        advancedModeDetailButton = createExtraChoiceButton();
        advancedModeDetailButton.setText(monitorSettingsTitleText());
        styleMonitorActionButton(advancedModeDetailButton, 0);
        advancedModeDetailButton.setOnClickListener(v -> showAdvancedSettingsSubpage());
        panel.addView(advancedModeDetailButton, blockParams(12));

        advancedModeSummaryView = new TextView(this);
        advancedModeSummaryView.setText(advancedModeSummaryText());
        advancedModeSummaryView.setTextSize(12);
        advancedModeSummaryView.setTextColor(Color.rgb(180, 190, 210));
        panel.addView(advancedModeSummaryView, blockParams(4));

        LinearLayout shizukuRuntimePanel = new LinearLayout(this);
        shizukuRuntimePanel.setOrientation(LinearLayout.VERTICAL);
        shizukuRuntimePanel.setClipChildren(false);
        shizukuRuntimePanel.setClipToPadding(false);
        shizukuRuntimePanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        shizukuRuntimePanel.setBackground(createGlassCard(30));
        LinearLayout.LayoutParams shizukuRuntimeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        shizukuRuntimeParams.topMargin = dp(16);
        panel.addView(shizukuRuntimePanel, shizukuRuntimeParams);

        shizukuRuntimeTitleView = gradientTitleView(shizukuRuntimeTitleText());
        shizukuRuntimeTitleView.setText(shizukuRuntimeTitleText());
        shizukuRuntimeTitleView.setTextSize(17);
        shizukuRuntimeTitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(shizukuRuntimeTitleView);
        LinearLayout.LayoutParams runtimeTitleParams = blockParams(0);
        runtimeTitleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(shizukuRuntimeTitleView, 12);
        shizukuRuntimePanel.addView(shizukuRuntimeTitleView, runtimeTitleParams);

        shizukuRuntimeDetailView = new TextView(this);
        shizukuRuntimeDetailView.setText(shizukuRuntimeDetailText());
        shizukuRuntimeDetailView.setTextSize(12);
        shizukuRuntimeDetailView.setTextColor(Color.rgb(160, 170, 190));
        shizukuRuntimePanel.addView(shizukuRuntimeDetailView, blockParams(4));

        shizukuRuntimeModeView = new TextView(this);
        shizukuRuntimeModeView.setText(shizukuRuntimeModeText());
        shizukuRuntimeModeView.setTextSize(13);
        shizukuRuntimeModeView.setTextColor(Color.rgb(225, 235, 255));
        shizukuRuntimePanel.addView(shizukuRuntimeModeView, blockParams(4));

        shizukuRuntimePlaybackView = new TextView(this);
        shizukuRuntimePlaybackView.setText(shizukuRuntimePlaybackText());
        shizukuRuntimePlaybackView.setTextSize(12);
        shizukuRuntimePlaybackView.setTextColor(Color.rgb(190, 205, 230));
        shizukuRuntimePanel.addView(shizukuRuntimePlaybackView, blockParams(2));

        shizukuRuntimeMuteView = new TextView(this);
        shizukuRuntimeMuteView.setText(shizukuRuntimeMuteText());
        shizukuRuntimeMuteView.setTextSize(12);
        shizukuRuntimeMuteView.setTextColor(Color.rgb(190, 205, 230));
        shizukuRuntimePanel.addView(shizukuRuntimeMuteView, blockParams(2));

        shizukuRuntimeReplayView = new TextView(this);
        shizukuRuntimeReplayView.setText(shizukuRuntimeReplayText());
        shizukuRuntimeReplayView.setTextSize(12);
        shizukuRuntimeReplayView.setTextColor(Color.rgb(190, 205, 230));
        shizukuRuntimePanel.addView(shizukuRuntimeReplayView, blockParams(2));

        languageButton = createExtraChoiceButton();
        languageButton.setText(languageButtonText());
        styleMonitorActionButton(languageButton, 132);
        languageButton.setOnClickListener(this::showLanguageChoiceMenu);
        panel.addView(labeledSettingsRow(settingsLanguageLabelText(), languageButton), blockParams(12));

        TextView importPresetButton = createExtraChoiceButton();
        importPresetButton.setText(tr("Import", "导入"));
        styleMonitorActionButton(importPresetButton, 132);
        importPresetButton.setOnClickListener(v -> openJsonImport(REQUEST_IMPORT_PRESET_JSON));
        panel.addView(labeledSettingsRow(tr("Preset JSON", "预设 JSON"), importPresetButton), blockParams(12));

        TextView exportPresetButton = createExtraChoiceButton();
        exportPresetButton.setText(tr("Export", "导出"));
        styleMonitorActionButton(exportPresetButton, 132);
        exportPresetButton.setOnClickListener(v -> showExportPresetChoiceDialog());
        panel.addView(labeledSettingsRow(tr("Preset JSON export", "预设 JSON 导出"), exportPresetButton), blockParams(8));

        TextView importDeviceConfigButton = createExtraChoiceButton();
        importDeviceConfigButton.setText(tr("Import", "导入"));
        styleMonitorActionButton(importDeviceConfigButton, 132);
        importDeviceConfigButton.setOnClickListener(v -> openJsonImport(REQUEST_IMPORT_DEVICE_CONFIG_JSON));
        panel.addView(labeledSettingsRow(tr("Global config JSON", "全局配置 JSON"), importDeviceConfigButton), blockParams(12));

        TextView exportDeviceConfigButton = createExtraChoiceButton();
        exportDeviceConfigButton.setText(tr("Export", "导出"));
        styleMonitorActionButton(exportDeviceConfigButton, 132);
        exportDeviceConfigButton.setOnClickListener(v -> exportCurrentDeviceConfigJson());
        panel.addView(labeledSettingsRow(tr("Global config export", "全局配置 JSON 导出"), exportDeviceConfigButton), blockParams(8));

        LinearLayout aboutPanel = new LinearLayout(this);
        aboutPanel.setOrientation(LinearLayout.VERTICAL);
        aboutPanel.setClipChildren(false);
        aboutPanel.setClipToPadding(false);
        aboutPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        aboutPanel.setBackground(createGlassCard(30));
        
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        aboutParams.topMargin = dp(16);
        settingsRootContent.addView(aboutPanel, aboutParams);

        aboutTitleView = gradientTitleView(aboutTitleText());
        aboutTitleView.setText(aboutTitleText());
        aboutTitleView.setTextSize(18);
        aboutTitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(aboutTitleView);
        LinearLayout.LayoutParams aboutTitleParams = blockParams(0);
        // 抵消 gradientTitleView 的左 padding(22dp)，让标题文字左缘对齐下方 aboutText 正文（都从 panel 内容区左边开始）。
        aboutTitleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(aboutTitleView, 12);
        aboutPanel.addView(aboutTitleView, aboutTitleParams);

        aboutTextView = new TextView(this);
        aboutTextView.setText(aboutBodyText());
        aboutTextView.setTextSize(13);
        aboutTextView.setTextColor(Color.rgb(180, 190, 210));
        aboutPanel.addView(aboutTextView, blockParams(8));

        footerTextView = new TextView(this);
        footerTextView.setText(footerText());
        footerTextView.setTextSize(11);
        footerTextView.setTextColor(Color.rgb(100, 110, 130));
        footerTextView.setGravity(android.view.Gravity.CENTER);
        
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerParams.topMargin = dp(32);
        settingsRootContent.addView(footerTextView, footerParams);

    }

    private void buildMonitorSettingsPage(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, dp(18));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(2), 0, dp(2), dp(20));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));
        body.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        monitorSettingsTitleView = gradientTitleView(monitorSettingsTitleText());
        monitorSettingsTitleView.setText(monitorSettingsTitleText());
        monitorSettingsTitleView.setTextSize(18);
        monitorSettingsTitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(monitorSettingsTitleView);
        LinearLayout.LayoutParams titleParams = blockParams(0);
        titleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(monitorSettingsTitleView, 12);
        panel.addView(monitorSettingsTitleView, titleParams);

        monitorSettingsDetailView = new TextView(this);
        monitorSettingsDetailView.setText(monitorSettingsDetailText());
        monitorSettingsDetailView.setTextSize(12);
        monitorSettingsDetailView.setTextColor(Color.rgb(160, 170, 190));
        panel.addView(monitorSettingsDetailView, blockParams(2));

        monitorCaptureStatusView = new TextView(this);
        monitorCaptureStatusView.setText(monitorCaptureStatusText());
        monitorCaptureStatusView.setTextSize(12);
        monitorCaptureStatusView.setTextColor(Color.rgb(180, 190, 210));
        panel.addView(monitorCaptureStatusView, blockParams(4));

        shizukuAccessButton = createExtraChoiceButton();
        shizukuAccessButton.setText(shizukuAccessButtonText());
        styleMonitorActionButton(shizukuAccessButton, 168);
        shizukuAccessButton.setOnClickListener(v -> handleShizukuAccessAction());
        panel.addView(labeledSettingsRow(shizukuAccessLabelText(), shizukuAccessButton), blockParams(12));

        shizukuAccessStatusView = new TextView(this);
        shizukuAccessStatusView.setText(shizukuAccessStatusText());
        shizukuAccessStatusView.setTextSize(12);
        shizukuAccessStatusView.setTextColor(Color.rgb(180, 190, 210));
        panel.addView(shizukuAccessStatusView, blockParams(4));

        advancedMonitorAppButton = createExtraChoiceButton();
        advancedMonitorAppButton.setText(advancedModeConfig.monitoredAppLabel.isEmpty()
                ? chooseAppText()
                : advancedModeConfig.monitoredAppLabel);
        styleMonitorActionButton(advancedMonitorAppButton, 188);
        advancedMonitorAppButton.setOnClickListener(v -> showMonitoredAppChoiceDialog());
        panel.addView(labeledSettingsRow(monitoredAppLabelText(), advancedMonitorAppButton), blockParams(12));

        panel.addView(createAdvancedNumberRow(latencyLabelText(), String.valueOf(advancedModeConfig.latencyMs), "20-400", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLatencyMs(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow(bufferLabelText(), String.valueOf(advancedModeConfig.bufferSizeFrames), "128-16384", value ->
                updateAdvancedModeConfig(advancedModeConfig.withBufferSizeFrames(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow(lookaheadLabelText(), String.valueOf(advancedModeConfig.lookaheadMs), "0-120", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLookaheadMs(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow(limiterCeilingLabelText(), String.valueOf(advancedModeConfig.limiterCeilingPermille), "930-999", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLimiterCeilingPermille(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow(limiterReleaseLabelText(), String.valueOf(advancedModeConfig.limiterReleaseMs), "20-400", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLimiterReleaseMs(value))), blockParams(6));
    }

    private View labeledSettingsRow(String labelText, View trailingView) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(14);
        label.setTextColor(Color.rgb(200, 210, 230));
        if (languageLabelView == null && labelText.equals(settingsLanguageLabelText())) {
            languageLabelView = label;
        } else if (shizukuAccessLabelView == null && labelText.equals(shizukuAccessLabelText())) {
            shizukuAccessLabelView = label;
        } else if (monitoredAppLabelView == null && labelText.equals(monitoredAppLabelText())) {
            monitoredAppLabelView = label;
        }
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(trailingView);
        return row;
    }

    private View createAdvancedNumberRow(String labelText, String valueText, String hint, IntChanged listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(14);
        label.setTextColor(Color.rgb(200, 210, 230));
        if (latencyLabelView == null && labelText.equals(latencyLabelText())) {
            latencyLabelView = label;
        } else if (bufferLabelView == null && labelText.equals(bufferLabelText())) {
            bufferLabelView = label;
        } else if (pollIntervalLabelView == null && labelText.equals(pollIntervalLabelText())) {
            pollIntervalLabelView = label;
        } else if (lookaheadLabelView == null && labelText.equals(lookaheadLabelText())) {
            lookaheadLabelView = label;
        } else if (limiterCeilingLabelView == null && labelText.equals(limiterCeilingLabelText())) {
            limiterCeilingLabelView = label;
        } else if (limiterReleaseLabelView == null && labelText.equals(limiterReleaseLabelText())) {
            limiterReleaseLabelView = label;
        }
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(valueText);
        input.setHint(hint);
        input.setTextSize(13);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setGravity(android.view.Gravity.CENTER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackground(createFieldBackground(12, 35, 8));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(100, 255, 255, 255));
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterUp = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_DONE || enterUp) {
                applyAdvancedNumberInput(input, listener);
                closeKeyboard(view);
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                applyAdvancedNumberInput(input, listener);
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(dp(132), dp(36)));
        return row;
    }

    private boolean isChineseUi() {
        return UI_LANGUAGE_ZH.equals(uiLanguage);
    }

    private String tr(String english, String chinese) {
        return isChineseUi() ? chinese : english;
    }

    private String chooseAppText() {
        return tr("Choose app", "选择应用");
    }

    private String settingsModeDetailText() {
        return isChineseUi()
                ? "\u4f7f\u7528\u4e0b\u65b9\u201c\u7cfb\u7edf\u5904\u7406\u201d\u53f3\u4fa7\u63a7\u4ef6\u5207\u6362\u5f15\u64ce\u6a21\u5f0f\u3002"
                : "Use the System Processing control below to switch backend mode.";
        /*
        return tr(
                "Use the System Processing control below to switch backend mode.",
                "点击上方标题即可切换后端模式。");
        */
    }

    private String settingsStatusLabelText() {
        return tr("System Processing:", "系统处理：");
    }

    private String settingsLanguageLabelText() {
        return tr("Language", "语言");
    }

    private String languageButtonText() {
        return isChineseUi() ? "中文" : "English";
    }

    private String virtualBassModeDisplayLabel(String value) {
        if (isChineseUi() && "system".equalsIgnoreCase(value)) {
            return "系统方案";
        }
        return value;
    }

    private String[] virtualBassModeDisplayLabels() {
        String[] labels = new String[VIRTUAL_BASS_MODE_LABELS.length];
        for (int i = 0; i < VIRTUAL_BASS_MODE_LABELS.length; i++) {
            labels[i] = virtualBassModeDisplayLabel(VIRTUAL_BASS_MODE_LABELS[i]);
        }
        return labels;
    }

    private String aboutTitleText() {
        return tr("About Global PEQ", "关于 Global PEQ");
    }

    private String aboutBodyText() {
        return tr(
                "Global PEQ is a low-latency, audiophile-grade Parametric Equalizer running natively on Android's high-performance audio routing engine. Enjoy tailored, professional equalization for all your playback devices.",
                "Global PEQ 是一款原生运行在 Android 高性能音频路由引擎上的低延迟发烧级 Parametric Equalizer，可为你的所有播放设备提供更细致、更专业的均衡调音。");
    }

    private String footerText() {
        return tr("Version 1.2.1 - Powered by WanAn522z", "Version 1.2.1 - Powered by WanAn522z");
    }

    private String monitorSettingsTitleText() {
        return tr("Shizuku Mode Settings", "Shizuku Mode 设置");
    }

    private String monitorSettingsDetailText() {
        return tr(
                "Authorize Shizuku, then Android playback capture. Shizuku Mode captures system audio globally and tries to mute the original playback sessions automatically.",
                "选择目标应用，完成 Android 回放捕获授权，并为第二套后端调整偏向低延迟的参数。若捕获已在运行，请将源应用静音以避免声音叠加。");
    }

    private String monitoredAppLabelText() {
        return tr("Monitored app", "监听应用");
    }

    private String latencyLabelText() {
        return tr("Latency (ms)", "延迟 (ms)");
    }

    private String bufferLabelText() {
        return tr("Buffer (frames)", "缓冲区 (frames)");
    }

    private String pollIntervalLabelText() {
        return tr("Poll interval (ms)", "轮询间隔 (ms)");
    }

    private String lookaheadLabelText() {
        return tr("Lookahead (ms)", "Lookahead (ms)");
    }

    private String limiterCeilingLabelText() {
        return tr("Limiter ceiling (\u2030)", "Limiter ceiling (\u2030)");
    }

    private String limiterReleaseLabelText() {
        return tr("Limiter release (ms)", "Limiter release (ms)");
    }

    private String unusedAdvancedLabelText() {
        return tr("DSP wet mix (%)", "DSP 湿声混合 (%)");
    }

    private String processingModeDisplayLabel(ProcessingMode mode) {
        if (mode == ProcessingMode.SHIZUKU_MUTE) {
            return "Shizuku Mode";
        }
        if (mode == ProcessingMode.GLOBAL_DSP) {
            return "Global DSP";
        }
        return "Default";
    }

    private String engineStatusText() {
        if (!supported) {
            return isChineseUi() ? "不支持" : "UNSUPPORTED";
        }
        return processingModeDisplayLabel(processingMode);
    }

    private String processingModeTitleText() {
        return isChineseUi() ? "\u5f15\u64ce\u72b6\u6001" : "Engine Status";
        /*
        return tr("Engine Status · ", "引擎状态 · ") + processingModeDisplayLabel(processingMode);
        */
    }

    private String translateMonitorCaptureStatus(String status) {
        String safe = status == null || status.trim().isEmpty() ? "Native capture is idle." : status;
        if (!isChineseUi()) {
            return safe;
        }
        if ("Native capture is idle.".equals(safe)) return "原生捕获空闲中。";
        if ("Capture authorization was cancelled.".equals(safe)) return "捕获授权已取消。";
        if ("Starting native capture...".equals(safe)) return "正在启动原生捕获...";
        if ("Record-audio permission was denied.".equals(safe)) return "录音权限已被拒绝。";
        if ("Grant record-audio permission to continue.".equals(safe)) return "请先授予录音权限再继续。";
        if ("Waiting for capture authorization...".equals(safe)) return "正在等待捕获授权...";
        if ("Native capture requires Android 10 or later.".equals(safe)) return "原生捕获需要 Android 10 或更高版本。";
        if ("Capture authorization could not be initialized.".equals(safe)) return "无法初始化捕获授权。";
        if ("Capture permission ended. Authorize again to resume.".equals(safe)) return "捕获权限已失效，请重新授权后恢复。";
        if ("Default mode active. Native capture disabled.".equals(safe)) return "当前为 Default 模式，原生捕获已禁用。";
        if ("Choose an app to monitor.".equals(safe)) return "请选择要监听的应用。";
        if ("Native capture is not authorized.".equals(safe)) return "原生捕获尚未授权。";
        if ("Native capture stopped. Re-authorize if the session was interrupted.".equals(safe)) return "原生捕获已停止，如会话中断请重新授权。";
        if (safe.startsWith("Capture authorized. Choose an app to monitor.")) return "捕获已授权，请选择要监听的应用。";
        if (safe.startsWith("Capture authorized for ") && safe.endsWith(".")) {
            return "已为 " + safe.substring("Capture authorized for ".length(), safe.length() - 1) + " 完成捕获授权。";
        }
        if (safe.startsWith("Monitoring ") && safe.endsWith(" via native capture.")) {
            return "正在通过原生捕获监听 " + safe.substring("Monitoring ".length(), safe.length() - " via native capture.".length()) + "。";
        }
        if (safe.startsWith("Monitoring ") && safe.endsWith(" via native capture. Mute the source app.")) {
            return "正在通过原生捕获监听 " + safe.substring("Monitoring ".length(), safe.length() - " via native capture. Mute the source app.".length()) + "。请将源应用静音。";
        }
        if (safe.startsWith("Armed for ") && safe.endsWith(" - waiting for playback.")) {
            return "已为 " + safe.substring("Armed for ".length(), safe.length() - " - waiting for playback.".length()) + " 就绪，等待播放中。";
        }
        return safe;
    }

    private String monitorCaptureStatusText() {
        if (!AudioProcessingPolicy.advancedModeEnabled(processingMode)) {
            return tr(
                    "Native capture is only used by Global DSP and Shizuku Mode.",
                    "原生捕获仅在第二套后端模式下使用。");
        }
        return translateMonitorCaptureStatus(repository.loadMonitorCaptureStatus());
    }

    private ShizukuRuntimeState currentShizukuRuntimeState() {
        return repository == null ? ShizukuRuntimeState.DEFAULT : repository.loadShizukuRuntimeState();
    }

    private String shizukuRuntimeTitleText() {
        return tr("Shizuku live status", "Shizuku 实时状态");
    }

    private String shizukuRuntimeDetailText() {
        return tr(
                "Shows the detected playback source, the package currently being muted, and the package currently being replayed.",
                "这里会直接显示当前检测到的播放源、当前被静音的包名，以及当前被回放的包名。");
    }

    private String shizukuRuntimeModeText() {
        if (processingMode != ProcessingMode.SHIZUKU_MUTE) {
            return tr(
                    "Switch to Shizuku Mode to inspect live mute and replay state.",
                    "切换到 Shizuku Mode 后，这里会显示实时静音和回放状态。");
        }
        ShizukuRuntimeState state = currentShizukuRuntimeState();
        if ("Source app could not be muted. Processed replay is off.".equals(state.captureStatus)) {
            return tr(
                    "Live output: the source app could not be muted, so processed replay has been turned off.",
                    "当前输出：源应用无法静音，所以处理后的回放已经关闭。");
        }
        boolean hasReplay = !state.activeReplayPackage.isEmpty();
        boolean hasMute = !state.activeMutedPackage.isEmpty();
        if (hasReplay && hasMute) {
            return tr(
                    "Live output: processed replay is active and the source session is being muted.",
                    "当前输出：处理后的回放已启用，并且源 session 正在被静音。");
        }
        if (hasReplay) {
            return tr(
                    "Live output: processed replay is active, but the source app may still be audible.",
                    "当前输出：处理后的回放已启用，但源应用可能仍然在出原声。");
        }
        if (hasMute) {
            return tr(
                    "Live output: the source session is muted and capture is waiting for stable playback.",
                    "当前输出：源 session 已被静音，捕获链路正在等待稳定回放。");
        }
        return tr(
                "Live output: waiting for a stable playback source.",
                "当前输出：正在等待稳定的播放源。");
    }

    private String shizukuRuntimePlaybackText() {
        return runtimePackageLine(
                tr("Detected playback", "检测到的播放源"),
                currentShizukuRuntimeState().activePlaybackPackage,
                tr("No active package yet", "暂时还没有检测到活跃包名"));
    }

    private String shizukuRuntimeMuteText() {
        return runtimePackageLine(
                tr("Muted package", "当前静音包名"),
                currentShizukuRuntimeState().activeMutedPackage,
                tr("No package is muted right now", "当前还没有成功静音任何包"));
    }

    private String shizukuRuntimeReplayText() {
        return runtimePackageLine(
                tr("Replay package", "当前回放包名"),
                currentShizukuRuntimeState().activeReplayPackage,
                tr("Replay has not locked onto a package yet", "回放链路暂时还没有锁定到包名"));
    }

    private String runtimePackageLine(String label, String packageName, String emptyText) {
        String safeLabel = label == null ? "" : label;
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.isEmpty()) {
            return safeLabel + ": " + emptyText;
        }
        return safeLabel + ": " + describeRuntimePackage(normalized);
    }

    private String describeRuntimePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "";
        }
        String normalized = packageName.trim();
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(normalized, 0));
            String safeLabel = label == null ? "" : label.toString().trim();
            if (!safeLabel.isEmpty() && !safeLabel.equals(normalized)) {
                return safeLabel + " · " + normalized;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return normalized;
    }

    private String advancedModeSummaryText() {
        if (processingMode == ProcessingMode.SYSTEM_EQ) {
            return tr(
                    "Default mode keeps the existing system EQ, virtual bass, and extra bass paths unchanged.",
                    "Default 模式会保持现有的系统 EQ、virtual bass 和 extra bass 路径不变。");
        }
        String appLabel = advancedModeConfig.monitoredAppLabel.isEmpty()
                ? tr("No app selected", "未选择应用")
                : advancedModeConfig.monitoredAppLabel;
        return String.format(Locale.US,
                tr("App: %s  |  %d ms  |  %d frames  |  poll %d ms",
                        "应用：%s  |  %d ms  |  %d frames  |  轮询 %d ms"),
                appLabel,
                advancedModeConfig.latencyMs,
                advancedModeConfig.bufferSizeFrames,
                advancedModeConfig.monitorIntervalMs);
    }

    private void showLanguageChoiceMenu(View anchor) {
        showLimitedChoiceMenu(anchor, new String[]{"English", "中文"}, isChineseUi() ? 1 : 0, position ->
                setUiLanguage(position == 1 ? UI_LANGUAGE_ZH : UI_LANGUAGE_EN));
    }

    private void setUiLanguage(String nextLanguage) {
        String normalized = UI_LANGUAGE_ZH.equals(nextLanguage) ? UI_LANGUAGE_ZH : UI_LANGUAGE_EN;
        if (normalized.equals(uiLanguage)) {
            return;
        }
        uiLanguage = normalized;
        repository.saveUiLanguage(uiLanguage);
        refreshSettingsLanguageViews();
    }

    private void refreshSettingsLanguageViews() {
        if (settingsPanelDetailView != null) {
            settingsPanelDetailView.setText(settingsModeDetailText());
        }
        if (settingsStatusLabelView != null) {
            settingsStatusLabelView.setText(settingsStatusLabelText());
        }
        if (engineStatusTitleView != null) {
            engineStatusTitleView.setText(processingModeTitleText());
            styleGradientTitle(engineStatusTitleView);
        }
        if (processingModeButton != null) {
            processingModeButton.setText(engineStatusText());
            styleGradientTitle(processingModeButton);
        }
        if (engineStatusValueView != null) {
            engineStatusValueView.setText(engineStatusText());
            styleGradientTitle(engineStatusValueView);
        }
        if (advancedModeDetailButton != null) {
            advancedModeDetailButton.setText(monitorSettingsTitleText());
        }
        if (advancedModeSummaryView != null) {
            advancedModeSummaryView.setText(advancedModeSummaryText());
        }
        if (shizukuRuntimeTitleView != null) {
            shizukuRuntimeTitleView.setText(shizukuRuntimeTitleText());
            styleGradientTitle(shizukuRuntimeTitleView);
        }
        if (shizukuRuntimeDetailView != null) {
            shizukuRuntimeDetailView.setText(shizukuRuntimeDetailText());
        }
        if (shizukuRuntimeModeView != null) {
            shizukuRuntimeModeView.setText(shizukuRuntimeModeText());
        }
        if (shizukuRuntimePlaybackView != null) {
            shizukuRuntimePlaybackView.setText(shizukuRuntimePlaybackText());
        }
        if (shizukuRuntimeMuteView != null) {
            shizukuRuntimeMuteView.setText(shizukuRuntimeMuteText());
        }
        if (shizukuRuntimeReplayView != null) {
            shizukuRuntimeReplayView.setText(shizukuRuntimeReplayText());
        }
        if (languageLabelView != null) {
            languageLabelView.setText(settingsLanguageLabelText());
        }
        if (languageButton != null) {
            languageButton.setText(languageButtonText());
        }
        if (aboutTitleView != null) {
            aboutTitleView.setText(aboutTitleText());
            styleGradientTitle(aboutTitleView);
        }
        if (aboutTextView != null) {
            aboutTextView.setText(aboutBodyText());
        }
        if (footerTextView != null) {
            footerTextView.setText(footerText());
        }
        if (monitorSettingsTitleView != null) {
            monitorSettingsTitleView.setText(monitorSettingsTitleText());
            styleGradientTitle(monitorSettingsTitleView);
        }
        if (monitorSettingsDetailView != null) {
            monitorSettingsDetailView.setText(monitorSettingsDetailText());
        }
        if (monitorCaptureStatusView != null) {
            monitorCaptureStatusView.setText(monitorCaptureStatusText());
        }
        if (shizukuAccessLabelView != null) {
            shizukuAccessLabelView.setText(shizukuAccessLabelText());
        }
        if (shizukuAccessButton != null) {
            shizukuAccessButton.setText(shizukuAccessButtonText());
        }
        if (shizukuAccessStatusView != null) {
            shizukuAccessStatusView.setText(shizukuAccessStatusText());
        }
        if (monitoredAppLabelView != null) {
            monitoredAppLabelView.setText(monitoredAppLabelText());
        }
        if (advancedMonitorAppButton != null && (advancedModeConfig.monitoredAppLabel == null || advancedModeConfig.monitoredAppLabel.isEmpty())) {
            advancedMonitorAppButton.setText(chooseAppText());
        }
        if (latencyLabelView != null) {
            latencyLabelView.setText(latencyLabelText());
        }
        if (bufferLabelView != null) {
            bufferLabelView.setText(bufferLabelText());
        }
        if (pollIntervalLabelView != null) {
            pollIntervalLabelView.setText(pollIntervalLabelText());
        }
        if (lookaheadLabelView != null) {
            lookaheadLabelView.setText(lookaheadLabelText());
        }
        if (limiterCeilingLabelView != null) {
            limiterCeilingLabelView.setText(limiterCeilingLabelText());
        }
        if (limiterReleaseLabelView != null) {
            limiterReleaseLabelView.setText(limiterReleaseLabelText());
        }
    }

    private void setProcessingMode(ProcessingMode nextMode) {
        ProcessingMode previousMode = processingMode;
        processingMode = nextMode == null ? ProcessingMode.SYSTEM_EQ : nextMode;
        repository.saveProcessingMode(processingMode);
        flushPendingPresetPersistence();
        if (currentDevice == null) {
            currentDevice = deviceMonitor.currentOutputDevice();
            repository.saveSelectedDevice(currentDevice);
        }
        adoptDevicePresetForCurrentMode(currentDevice, true);
        if (processingMode == ProcessingMode.SYSTEM_EQ) {
            if (previousMode.usesNativeCapture()) {
                stopShizukuCaptureNow();
            }
            if (monitorSettingsOpen) {
                hideAdvancedSettingsSubpage();
            }
            applyRunningPreset(true);
        } else {
            applyRunningPreset(false, false);
            if (runningPreset != null && runningPreset.enabled) {
                ensureNativeCaptureModeReady(true);
            }
        }
        renderAll();
    }

    private void showAdvancedSettingsSubpage() {
        if (!AudioProcessingPolicy.advancedModeEnabled(processingMode)
                || settingsPage == null
                || monitorSettingsPage == null
                || bottomNavView == null) {
            return;
        }
        monitorSettingsOpen = true;
        settingsPage.setVisibility(View.GONE);
        monitorSettingsPage.setVisibility(View.VISIBLE);
        monitorSettingsPage.bringToFront();
        bottomNavView.setVisibility(View.GONE);
        uiHandler.removeCallbacks(monitorStatusRefreshRunnable);
        uiHandler.post(monitorStatusRefreshRunnable);
        renderAll();
    }

    private void hideAdvancedSettingsSubpage() {
        if (settingsPage == null || monitorSettingsPage == null || bottomNavView == null) {
            return;
        }
        monitorSettingsOpen = false;
        monitorSettingsPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.VISIBLE);
        bottomNavView.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(monitorStatusRefreshRunnable);
        updateBottomNavSelection(activeMainPageIndex);
    }

    private boolean handleBackNavigation() {
        if (monitorSettingsOpen) {
            hideAdvancedSettingsSubpage();
            return true;
        }
        if (activeMainPageIndex != 0) {
            showEqPage();
            return true;
        }
        moveTaskToBack(false);
        return true;
    }

    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || systemBackCallbackRegistered) {
            return;
        }
        if (systemBackCallback == null) {
            systemBackCallback = () -> {
                if (!handleBackNavigation()) {
                    MainActivity.super.onBackPressed();
                }
            };
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                systemBackCallback);
        systemBackCallbackRegistered = true;
    }

    private void unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !systemBackCallbackRegistered
                || systemBackCallback == null) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBackCallback);
        systemBackCallbackRegistered = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MONITOR_AUDIO_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            pendingMonitorCaptureAuthorization = false;
            repository.saveMonitorCaptureStatus("Record-audio permission was denied.", false);
            renderAll();
            Toast.makeText(this, tr("Record audio permission is required for native capture", "原生捕获需要录音权限"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingMonitorCaptureAuthorization) {
            launchMonitorCaptureAuthorization();
        }
    }

    @Override
    public void onBackPressed() {
        if (handleBackNavigation()) {
            return;
        }
        super.onBackPressed();
    }

    private void updateAdvancedModeConfig(AdvancedModeConfig nextConfig) {
        advancedModeConfig = nextConfig == null ? AdvancedModeConfig.DEFAULT : nextConfig;
        repository.saveAdvancedModeConfig(advancedModeConfig);
        applyRunningPreset();
        renderAll();
    }

    private void applyAdvancedNumberInput(EditText input, IntChanged listener) {
        if (input == null || listener == null) {
            return;
        }
        try {
            listener.onChanged(Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
        }
    }

    private void launchMonitorCaptureAuthorization() {
        pendingMonitorCaptureAuthorization = true;
        repository.saveMonitorCaptureStatus("Waiting for capture authorization...", false);
        renderAll();
        Toast.makeText(this, tr("Android will now ask for playback-capture authorization", "Android 现在会请求回放捕获授权"), Toast.LENGTH_SHORT).show();
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            pendingMonitorCaptureAuthorization = false;
            Toast.makeText(this, tr("MediaProjection service unavailable", "MediaProjection 服务不可用"), Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MONITOR_CAPTURE);
    }

    private void ensureShizukuModeReady(boolean autoLaunchCapture) {
        if (processingMode != ProcessingMode.SHIZUKU_MUTE) {
            return;
        }
        if (pendingMonitorCaptureAuthorization) {
            renderAll();
            return;
        }
        boolean granted = ShizukuCompat.requestPermissionOrOpenManager(this, REQUEST_SHIZUKU_PERMISSION);
        if (granted) {
            ShizukuCompat.grantPermissionsAndAppOps(this);
        }
        repository.saveShizukuMuteStatus(ShizukuCompat.describeState(this), granted);
        if (!granted) {
            refreshRuntimeStatusUi();
            return;
        }
        if (!autoLaunchCapture || !shouldLaunchCaptureAuthorization()) {
            applyRunningPreset();
            refreshRuntimeStatusUi();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Native capture requires Android 10 or later", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMonitorCaptureAuthorization = true;
            repository.saveMonitorCaptureStatus("Grant record-audio permission to continue.", false);
            refreshRuntimeStatusUi();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MONITOR_AUDIO_PERMISSION);
            return;
        }
        launchMonitorCaptureAuthorization();
    }

    private boolean shouldLaunchCaptureAuthorization() {
        if (repository == null) {
            return true;
        }
        return !repository.loadMonitorCaptureAuthorized();
    }

    private void showMonitoredAppChoiceDialog() {
            AlertDialog[] dialogHolder = new AlertDialog[1];
            final List<MonitoredAppListEntry>[] monitoredHolder = new List[]{null};
            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(16), dp(8), dp(16), dp(10));

            EditText searchInput = new EditText(this);
            searchInput.setSingleLine(true);
            searchInput.setHint(tr("Search added apps", "搜索已添加应用"));
            searchInput.setTextSize(13);
            searchInput.setTextColor(Color.WHITE);
            searchInput.setHintTextColor(Color.argb(110, 255, 255, 255));
            searchInput.setBackground(createFieldBackground(20, 40, 8));
            searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            shell.addView(searchInput, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44)
            ));

            FrameLayout content = new FrameLayout(this);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(dp(220), Math.min(dp(460), getResources().getDisplayMetrics().heightPixels - dp(220)))
            );
            contentParams.topMargin = dp(10);
            shell.addView(content, contentParams);

            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(12), dp(10), dp(12), dp(12));

            ScrollView scroll = new ScrollView(this);
            scroll.setClipToPadding(false);
            scroll.addView(list);
            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            );
            scrollParams.rightMargin = dp(30);
            content.addView(scroll, scrollParams);

            LinearLayout indexBar = new LinearLayout(this);
            indexBar.setOrientation(LinearLayout.VERTICAL);
            indexBar.setGravity(android.view.Gravity.CENTER);
            indexBar.setPadding(dp(1), dp(6), dp(1), dp(6));
            indexBar.setBackground(plainRoundRectDrawable(
                    Color.argb(20, 255, 255, 255),
                    Color.argb(35, 255, 255, 255),
                    dp(8)
            ));
            FrameLayout.LayoutParams indexParams = new FrameLayout.LayoutParams(
                    dp(16),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL
            );
            indexParams.rightMargin = dp(1);
            content.addView(indexBar, indexParams);
            content.post(() -> {
                ViewGroup.LayoutParams rawParams = indexBar.getLayoutParams();
                if (!(rawParams instanceof FrameLayout.LayoutParams)) {
                    return;
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawParams;
                int targetHeight = Math.max(dp(220), Math.round(content.getHeight() * 0.8f));
                if (lp.height != targetHeight) {
                    lp.height = targetHeight;
                    indexBar.setLayoutParams(lp);
                }
            });

            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (monitoredHolder[0] == null) {
                        return;
                    }
                    rebuildMonitoredAppChoiceList(
                            list,
                            indexBar,
                            scroll,
                            monitoredHolder[0],
                            s == null ? "" : s.toString(),
                            dialogHolder
                    );
                }
            });
            showIndexedLoadingState(list, indexBar, tr("Loading added apps...", "正在加载已添加应用..."));

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setCustomTitle(dialogTitleView(tr("Choose monitored app", "选择监听应用")))
                    .setView(shell)
                    .setNegativeButton(tr("Close", "关闭"), null)
                    .create();
            dialogHolder[0] = dialog;
            dialog.show();
            styleDialog(dialog);

            new Thread(() -> {
                List<MonitoredAppListEntry> monitoredApps = loadMonitoredAppChoiceEntries();
                uiHandler.post(() -> {
                    if (dialogHolder[0] == null || !dialogHolder[0].isShowing()) {
                        return;
                    }
                    monitoredHolder[0] = monitoredApps;
                    rebuildMonitoredAppChoiceList(
                            list,
                            indexBar,
                            scroll,
                            monitoredApps,
                            searchInput.getText().toString(),
                            dialogHolder
                    );
                });
            }, "global-peq-monitored-apps").start();
        /*
        showLinearLoadingState(list, tr("Loading monitored apps...", "正在加载监听应用..."));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Choose monitored app", "选择监听应用")))
                .setView(scroll)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        */
    }

    private List<ResolveInfo> loadSuggestedMonitoredApps() {
        List<ResolveInfo> launchable = loadLaunchableActivities();
        List<ResolveInfo> suggested = new ArrayList<>();
        for (ResolveInfo info : launchable) {
            if (isLikelyMediaApp(info)) {
                suggested.add(info);
            }
        }
        if (advancedModeConfig.monitoredAppPackage != null && !advancedModeConfig.monitoredAppPackage.isEmpty()) {
            boolean alreadyIncluded = false;
            for (ResolveInfo info : suggested) {
                if (info.activityInfo != null
                        && advancedModeConfig.monitoredAppPackage.equals(info.activityInfo.packageName)) {
                    alreadyIncluded = true;
                    break;
                }
            }
            if (!alreadyIncluded) {
                for (ResolveInfo info : launchable) {
                    if (info.activityInfo != null
                            && advancedModeConfig.monitoredAppPackage.equals(info.activityInfo.packageName)) {
                        suggested.add(0, info);
                        break;
                    }
                }
            }
        }
        return suggested;
    }

    private void rebuildSuggestedMonitoredAppList(LinearLayout list,
                                                  List<ResolveInfo> suggested,
                                                  AlertDialog[] dialogHolder) {
        list.removeAllViews();
        list.addView(createMonitoredAppAddButton(dialogHolder), curveMenuRowParams(0));
        boolean clearActive = advancedModeConfig.monitoredAppPackage == null
                || advancedModeConfig.monitoredAppPackage.isEmpty();
        list.addView(createMonitoredAppClearRow(clearActive, dialogHolder), curveMenuRowParams(6));
        for (int i = 0; i < suggested.size(); i++) {
            ResolveInfo info = suggested.get(i);
            boolean active = info.activityInfo.packageName.equals(advancedModeConfig.monitoredAppPackage);
            Drawable icon = info.loadIcon(getPackageManager());
            CharSequence label = info.loadLabel(getPackageManager());
            String packageName = info.activityInfo.packageName;
            String titleText = label == null ? packageName : label.toString();
            list.addView(createMonitoredAppMenuRow(
                    icon,
                    titleText,
                    packageName,
                    active,
                    dialogHolder,
                    () -> updateAdvancedModeConfig(advancedModeConfig.withMonitoredApp(packageName, titleText))
            ), curveMenuRowParams(6));
        }
        if (suggested.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(tr(
                    "No suggested media apps were detected. Use Add to pick any installed app.",
                    "没有检测到推荐的媒体应用。可以通过 Add 选择任意已安装应用。"));
            empty.setTextSize(12);
            empty.setTextColor(Color.rgb(170, 180, 198));
            empty.setPadding(dp(4), dp(8), dp(4), dp(4));
            list.addView(empty, curveMenuRowParams(6));
        }
    }

    private List<ResolveInfo> loadLaunchableActivities() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(launcherIntent, 0);
        List<ResolveInfo> unique = new ArrayList<>();
        java.util.HashSet<String> seenPackages = new java.util.HashSet<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            if (getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            if (!seenPackages.add(info.activityInfo.packageName)) {
                continue;
            }
            unique.add(info);
        }
        unique.sort((left, right) -> {
            CharSequence leftLabel = left.loadLabel(getPackageManager());
            CharSequence rightLabel = right.loadLabel(getPackageManager());
            String l = leftLabel == null ? "" : leftLabel.toString();
            String r = rightLabel == null ? "" : rightLabel.toString();
            return l.compareToIgnoreCase(r);
        });
        return unique;
    }

    private boolean isLikelyMediaApp(ResolveInfo info) {
        if (info == null || info.activityInfo == null) {
            return false;
        }
        String label = String.valueOf(info.loadLabel(getPackageManager())).toLowerCase(Locale.US);
        String pkg = info.activityInfo.packageName.toLowerCase(Locale.US);
        String combined = label + " " + pkg;
        String[] keywords = {
                "music", "audio", "video", "media", "player", "podcast", "radio",
                "spotify", "youtube", "netflix", "vlc", "tidal", "deezer", "soundcloud",
                "qqmusic", "cloudmusic", "bilibili", "bili", "douyin", "tiktok",
                "musicfx", "stream", "tv", "tune", "amp", "fm"
        };
        for (String keyword : keywords) {
            if (combined.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private View createMonitoredAppAddButton(AlertDialog[] dialogHolder) {
        Button add = new Button(this);
        add.setText(tr("+ Add", "+ 添加"));
        add.setTextSize(14);
        add.setAllCaps(false);
        styleAccentButton(add, true);
        add.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            showInstalledAppPickerDialog();
        });
        return add;
    }

    private void showInstalledAppPickerDialog() {
        showInstalledAppPickerDialogEnhanced();
    }

    private void showInstalledAppPickerDialogEnhanced() {
        AlertDialog[] dialogHolder = new AlertDialog[1];
        final List<InstalledAppEntry>[] installedHolder = new List[]{null};
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(8), dp(16), dp(10));

        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint(tr("Search app or package", "搜索应用或包名"));
        searchInput.setTextSize(13);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.argb(110, 255, 255, 255));
        searchInput.setBackground(createFieldBackground(20, 40, 8));
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        shell.addView(searchInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        ));

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(dp(220), Math.min(dp(460), getResources().getDisplayMetrics().heightPixels - dp(220)))
        );
        contentParams.topMargin = dp(10);
        shell.addView(content, contentParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        scrollParams.rightMargin = dp(30);
        content.addView(scroll, scrollParams);

        LinearLayout indexBar = new LinearLayout(this);
        indexBar.setOrientation(LinearLayout.VERTICAL);
        indexBar.setGravity(android.view.Gravity.CENTER);
        indexBar.setPadding(dp(1), dp(6), dp(1), dp(6));
        indexBar.setBackground(plainRoundRectDrawable(
                Color.argb(20, 255, 255, 255),
                Color.argb(35, 255, 255, 255),
                dp(8)
        ));
        FrameLayout.LayoutParams indexParams = new FrameLayout.LayoutParams(
                dp(16),
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL
        );
        indexParams.rightMargin = dp(1);
        content.addView(indexBar, indexParams);
        content.post(() -> {
            ViewGroup.LayoutParams rawParams = indexBar.getLayoutParams();
            if (!(rawParams instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawParams;
            int targetHeight = Math.max(dp(220), Math.round(content.getHeight() * 0.8f));
            if (lp.height != targetHeight) {
                lp.height = targetHeight;
                indexBar.setLayoutParams(lp);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (installedHolder[0] == null) {
                    return;
                }
                rebuildInstalledAppPickerList(
                        list,
                        indexBar,
                        scroll,
                        installedHolder[0],
                        s == null ? "" : s.toString(),
                        dialogHolder
                );
            }
        });
        showIndexedLoadingState(list, indexBar, tr("Loading installed apps...", "正在加载已安装应用..."));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Add installed app", "添加已安装应用")))
                .setView(shell)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
        styleDialog(dialog);

        new Thread(() -> {
            List<InstalledAppEntry> loaded = loadInstalledAppEntries();
            uiHandler.post(() -> {
                if (dialogHolder[0] == null || !dialogHolder[0].isShowing()) {
                    return;
                }
                if (loaded.isEmpty()) {
                    Toast.makeText(this, tr("No installed apps available", "没有可用的已安装应用"), Toast.LENGTH_SHORT).show();
                    dialogHolder[0].dismiss();
                    return;
                }
                installedHolder[0] = loaded;
                rebuildInstalledAppPickerList(
                        list,
                        indexBar,
                        scroll,
                        loaded,
                        searchInput.getText().toString(),
                        dialogHolder
                );
            });
        }, "global-peq-installed-apps").start();
    }

    private List<InstalledAppEntry> loadInstalledAppEntries() {
        List<InstalledAppEntry> installed = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Set<String> seenPackages = new HashSet<>();
        for (ApplicationInfo info : pm.getInstalledApplications(installedAppListFlags())) {
            if (info == null) {
                continue;
            }
            if (getPackageName().equals(info.packageName)) {
                continue;
            }
            if (!seenPackages.add(info.packageName)) {
                continue;
            }
            String label = normalizeInstalledAppLabel(String.valueOf(pm.getApplicationLabel(info)), info.packageName);
            installed.add(new InstalledAppEntry(info, label, info.packageName));
        }
        final Collator collator = appLabelCollator();
        installed.sort((left, right) -> {
            int sectionCompare = compareAlphabetSections(
                    alphabetKeyForApp(left.label, left.packageName),
                    alphabetKeyForApp(right.label, right.packageName)
            );
            if (sectionCompare != 0) {
                return sectionCompare;
            }
            int labelCompare = collator.compare(left.label, right.label);
            if (labelCompare != 0) {
                return labelCompare;
            }
            return collator.compare(left.packageName, right.packageName);
        });
        return installed;
    }

    private int installedAppListFlags() {
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE;
            flags |= PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        }
        return flags;
    }

    private Collator appLabelCollator() {
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }

    private String normalizeInstalledAppLabel(String label, String packageName) {
        String normalized = label == null ? "" : label.trim();
        if (normalized.isEmpty()) {
            return packageName == null ? "" : packageName;
        }
        return normalized;
    }

    private List<MonitoredAppListEntry> loadMonitoredAppChoiceEntries() {
        List<MonitoredAppListEntry> entries = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (MonitoredAppListEntry entry : loadPreferredMonitoredAppEntries()) {
            addMonitoredAppChoiceEntry(entries, seenPackages, entry.label, entry.packageName);
        }
        if (advancedModeConfig.monitoredApps != null) {
            for (AdvancedModeConfig.MonitoredAppItem item : advancedModeConfig.monitoredApps) {
                if (item == null || item.packageName == null || item.packageName.trim().isEmpty()) {
                    continue;
                }
                addMonitoredAppChoiceEntry(
                        entries,
                        seenPackages,
                        normalizeInstalledAppLabel(item.label, item.packageName),
                        item.packageName
                );
            }
        }
        final Collator collator = appLabelCollator();
        entries.sort((left, right) -> {
            int sectionCompare = compareAlphabetSections(
                    alphabetKeyForApp(left.label, left.packageName),
                    alphabetKeyForApp(right.label, right.packageName)
            );
            if (sectionCompare != 0) {
                return sectionCompare;
            }
            int labelCompare = collator.compare(left.label, right.label);
            if (labelCompare != 0) {
                return labelCompare;
            }
            return collator.compare(left.packageName, right.packageName);
        });
        return entries;
    }

    private void rebuildMonitoredAppChoiceList(LinearLayout list,
                                               LinearLayout indexBar,
                                               ScrollView scroll,
                                               List<MonitoredAppListEntry> monitoredApps,
                                               String query,
                                               AlertDialog[] dialogHolder) {
        list.removeAllViews();
        indexBar.removeAllViews();

        list.addView(createMonitoredAppAddButton(dialogHolder), curveMenuRowParams(0));
        boolean clearActive = advancedModeConfig.monitoredAppPackage == null
                || advancedModeConfig.monitoredAppPackage.isEmpty();
        list.addView(createMonitoredAppClearRow(clearActive, dialogHolder), curveMenuRowParams(6));

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.US);
        java.util.LinkedHashMap<String, View> sectionAnchors = new java.util.LinkedHashMap<>();
        int matchCount = 0;
        String lastSection = null;
        for (MonitoredAppListEntry entry : monitoredApps) {
            if (!matchesMonitoredAppQuery(entry, normalizedQuery)) {
                continue;
            }
            String section = alphabetKeyForApp(entry.label, entry.packageName);
            if (!section.equals(lastSection)) {
                View header = createInstalledAppSectionHeader(section);
                list.addView(header, curveMenuRowParams(matchCount == 0 ? 10 : 10));
                sectionAnchors.put(section, header);
                lastSection = section;
            }
            boolean active = entry.packageName.equals(advancedModeConfig.monitoredAppPackage);
            list.addView(createMonitoredAppMenuRow(
                    loadApplicationIconOrFallback(entry.packageName),
                    entry.label,
                    entry.packageName,
                    active,
                    dialogHolder,
                    () -> updateAdvancedModeConfig(advancedModeConfig.withMonitoredApp(entry.packageName, entry.label))
            ), curveMenuRowParams(4));
            matchCount++;
        }

        if (matchCount == 0) {
            TextView empty = new TextView(this);
            empty.setText(monitoredApps.isEmpty()
                    ? tr("No monitored apps yet. Use Add to build your own list.", "还没有监听应用，先用 Add 手动添加。")
                    : tr("No added apps match your search.", "没有匹配当前搜索的已添加应用。"));
            empty.setTextSize(12);
            empty.setTextColor(Color.rgb(170, 180, 198));
            empty.setPadding(dp(4), dp(8), dp(4), dp(4));
            list.addView(empty, curveMenuRowParams(10));
            indexBar.setVisibility(View.GONE);
            scroll.post(() -> scroll.scrollTo(0, 0));
            return;
        }

        indexBar.setVisibility(View.VISIBLE);
        populateInstalledAppIndexBar(indexBar, new ArrayList<>(sectionAnchors.keySet()), sectionAnchors, scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private boolean matchesMonitoredAppQuery(MonitoredAppListEntry entry, String normalizedQuery) {
        if (entry == null) {
            return false;
        }
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return true;
        }
        return entry.normalizedLabel.contains(normalizedQuery)
                || entry.normalizedPackage.contains(normalizedQuery);
    }

    private Drawable loadApplicationIconOrFallback(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Exception ignored) {
            return getResources().getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private void showLinearLoadingState(LinearLayout list, String message) {
        list.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText(message);
        loading.setTextSize(12);
        loading.setTextColor(Color.rgb(170, 180, 198));
        loading.setPadding(dp(4), dp(8), dp(4), dp(4));
        list.addView(loading, curveMenuRowParams(0));
    }

    private void showIndexedLoadingState(LinearLayout list, LinearLayout indexBar, String message) {
        list.removeAllViews();
        indexBar.removeAllViews();
        indexBar.setVisibility(View.GONE);

        TextView loading = new TextView(this);
        loading.setText(message);
        loading.setTextSize(12);
        loading.setTextColor(Color.rgb(170, 180, 198));
        loading.setPadding(dp(4), dp(8), dp(4), dp(4));
        list.addView(loading, curveMenuRowParams(0));
    }

    private void rebuildInstalledAppPickerList(LinearLayout list,
                                               LinearLayout indexBar,
                                               ScrollView scroll,
                                               List<InstalledAppEntry> installed,
                                               String query,
                                               AlertDialog[] dialogHolder) {
        list.removeAllViews();
        indexBar.removeAllViews();

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.US);
        java.util.LinkedHashMap<String, View> sectionAnchors = new java.util.LinkedHashMap<>();
        int matchCount = 0;
        String lastSection = null;
        for (InstalledAppEntry entry : installed) {
            if (!matchesInstalledAppQuery(entry, normalizedQuery)) {
                continue;
            }
            String section = alphabetKeyForApp(entry.label, entry.packageName);
            if (!section.equals(lastSection)) {
                View header = createInstalledAppSectionHeader(section);
                list.addView(header, curveMenuRowParams(matchCount == 0 ? 0 : 10));
                sectionAnchors.put(section, header);
                lastSection = section;
            }
            boolean active = entry.packageName.equals(advancedModeConfig.monitoredAppPackage);
            list.addView(createMonitoredAppMenuRow(
                    getPackageManager().getApplicationIcon(entry.appInfo),
                    entry.label,
                    entry.packageName,
                    active,
                    dialogHolder,
                    () -> updateAdvancedModeConfig(advancedModeConfig.withAddedMonitoredApp(entry.packageName, entry.label))
            ), curveMenuRowParams(4));
            matchCount++;
        }

        if (matchCount == 0) {
            TextView empty = new TextView(this);
            empty.setText(tr("No apps match your search.", "没有匹配搜索的应用"));
            empty.setTextSize(12);
            empty.setTextColor(Color.rgb(170, 180, 198));
            empty.setPadding(dp(4), dp(8), dp(4), dp(4));
            list.addView(empty, curveMenuRowParams(0));
            indexBar.setVisibility(View.GONE);
            scroll.post(() -> scroll.scrollTo(0, 0));
            return;
        }

        indexBar.setVisibility(View.VISIBLE);
        populateInstalledAppIndexBar(indexBar, new ArrayList<>(sectionAnchors.keySet()), sectionAnchors, scroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private boolean matchesInstalledAppQuery(InstalledAppEntry entry, String query) {
        if (entry == null) {
            return false;
        }
        if (query == null || query.isEmpty()) {
            return true;
        }
        return entry.normalizedLabel.contains(query) || entry.normalizedPackage.contains(query);
    }

    private List<MonitoredAppListEntry> loadPreferredMonitoredAppEntries() {
        List<MonitoredAppListEntry> entries = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo info : loadLaunchableActivities()) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName == null ? "" : info.activityInfo.packageName;
            String label = normalizeInstalledAppLabel(String.valueOf(info.loadLabel(getPackageManager())), packageName);
            if (!isPreferredMonitoredApp(label, packageName)) {
                continue;
            }
            addMonitoredAppChoiceEntry(entries, seenPackages, label, packageName);
        }
        return entries;
    }

    private void addMonitoredAppChoiceEntry(List<MonitoredAppListEntry> entries,
                                            Set<String> seenPackages,
                                            String label,
                                            String packageName) {
        if (packageName == null) {
            return;
        }
        String normalizedPackage = packageName.trim();
        if (normalizedPackage.isEmpty() || !seenPackages.add(normalizedPackage)) {
            return;
        }
        entries.add(new MonitoredAppListEntry(
                normalizeInstalledAppLabel(label, normalizedPackage),
                normalizedPackage
        ));
    }

    private boolean isPreferredMonitoredApp(String label, String packageName) {
        return preferredAppInitial(label, packageName) != null;
    }

    private String alphabetKeyForApp(String label, String packageName) {
        String preferred = preferredAppInitial(label, packageName);
        if (preferred != null) {
            return preferred;
        }
        String letter = firstLatinLetter(label);
        if (letter != null) {
            return letter;
        }
        letter = packageMeaningfulInitial(packageName);
        if (letter != null) {
            return letter;
        }
        return "#";
    }

    private String preferredAppInitial(String label, String packageName) {
        return preferredAppInitialSafe(label, packageName);
    }

    /*
        String normalizedLabel = label == null ? "" : label.trim().toLowerCase(Locale.US);
        String normalizedPackage = packageName == null ? "" : packageName.trim().toLowerCase(Locale.US);
        if (normalizedLabel.contains("网易云") || normalizedPackage.contains("cloudmusic")) {
            return "N";
        }
        if (normalizedLabel.contains("qq音乐") || normalizedPackage.contains("qqmusic")) {
            return "Q";
        }
        if (normalizedLabel.contains("汽水音乐")
                || normalizedPackage.contains("luna.music")
                || normalizedPackage.contains("qishui")) {
            return "Q";
        }
        if (normalizedLabel.contains("apple music") || normalizedPackage.contains("apple.android.music")) {
            return "A";
        }
        if (normalizedLabel.contains("spotify") || normalizedPackage.contains("spotify.music")) {
            return "S";
        }
        if (normalizedLabel.contains("soundcloud") || normalizedPackage.contains("soundcloud.android")) {
            return "S";
        }
        return null;
    }

    */

    private String preferredAppInitialSafe(String label, String packageName) {
        String normalizedLabel = label == null ? "" : label.trim().toLowerCase(Locale.US);
        String normalizedPackage = packageName == null ? "" : packageName.trim().toLowerCase(Locale.US);
        if (normalizedLabel.contains("\u7f51\u6613\u4e91") || normalizedPackage.contains("cloudmusic")) {
            return "N";
        }
        if (normalizedLabel.contains("qq\u97f3\u4e50") || normalizedPackage.contains("qqmusic")) {
            return "Q";
        }
        if (normalizedLabel.contains("\u6c7d\u6c34\u97f3\u4e50")
                || normalizedPackage.contains("luna.music")
                || normalizedPackage.contains("qishui")) {
            return "Q";
        }
        if (normalizedLabel.contains("apple music") || normalizedPackage.contains("apple.android.music")) {
            return "A";
        }
        if (normalizedLabel.contains("spotify") || normalizedPackage.contains("spotify.music")) {
            return "S";
        }
        if (normalizedLabel.contains("soundcloud") || normalizedPackage.contains("soundcloud.android")) {
            return "S";
        }
        return null;
    }

    private String firstLatinLetter(String text) {
        if (text == null) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toUpperCase(text.charAt(i));
            if (ch >= 'A' && ch <= 'Z') {
                return String.valueOf(ch);
            }
        }
        return null;
    }

    private String packageMeaningfulInitial(String packageName) {
        if (packageName == null) {
            return null;
        }
        String[] parts = packageName.trim().split("\\.");
        for (String rawPart : parts) {
            if (rawPart == null) {
                continue;
            }
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.US);
            if ("com".equals(lower)
                    || "cn".equals(lower)
                    || "net".equals(lower)
                    || "org".equals(lower)
                    || "android".equals(lower)
                    || "app".equals(lower)) {
                continue;
            }
            String letter = firstLatinLetter(part);
            if (letter != null) {
                return letter;
            }
        }
        return null;
    }

    private int compareAlphabetSections(String left, String right) {
        String safeLeft = left == null || left.isEmpty() ? "#" : left;
        String safeRight = right == null || right.isEmpty() ? "#" : right;
        if (safeLeft.equals(safeRight)) {
            return 0;
        }
        if ("#".equals(safeLeft)) {
            return 1;
        }
        if ("#".equals(safeRight)) {
            return -1;
        }
        return safeLeft.compareTo(safeRight);
    }

    private TextView createInstalledAppSectionHeader(String section) {
        TextView header = new TextView(this);
        header.setText(section);
        header.setTextSize(12);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleCyanGlowText(header);
        header.setPadding(dp(4), dp(2), dp(4), dp(2));
        return header;
    }

    private void populateInstalledAppIndexBar(LinearLayout indexBar,
                                              List<String> sections,
                                              java.util.LinkedHashMap<String, View> sectionAnchors,
                                              ScrollView scroll) {
        if (sections == null || sections.isEmpty()) {
            indexBar.setVisibility(View.GONE);
            return;
        }
        for (String section : sections) {
            TextView letter = new TextView(this);
            letter.setText(section);
            letter.setTextSize(9.5f);
            letter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            letter.setGravity(android.view.Gravity.CENTER);
            letter.setIncludeFontPadding(false);
            letter.setMaxLines(1);
            styleDimPlainText(letter);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
            );
            indexBar.addView(letter, params);
        }
        indexBar.setOnTouchListener((view, event) -> {
            int count = sections.size();
            if (count == 0) {
                return false;
            }
            float clampedY = Math.max(0f, Math.min(event.getY(), Math.max(1, view.getHeight()) - 1f));
            int index = clamp((int) (clampedY / Math.max(1f, view.getHeight()) * count), 0, count - 1);
            highlightInstalledAppIndex(indexBar, index);
            String key = sections.get(index);
            View target = sectionAnchors.get(key);
            if (target != null) {
                scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - dp(6))));
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.postDelayed(() -> highlightInstalledAppIndex(indexBar, -1), 120L);
            }
            return true;
        });
    }

    private void highlightInstalledAppIndex(LinearLayout indexBar, int activeIndex) {
        for (int i = 0; i < indexBar.getChildCount(); i++) {
            View child = indexBar.getChildAt(i);
            if (!(child instanceof TextView)) {
                continue;
            }
            TextView letter = (TextView) child;
            if (i == activeIndex) {
                styleCyanGlowText(letter);
            } else {
                styleDimPlainText(letter);
            }
        }
    }

    private View createMonitoredAppClearRow(boolean active, AlertDialog[] dialogHolder) {
        Drawable icon = getResources().getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
        return createMonitoredAppMenuRow(
                icon,
                tr("No monitored app", "不监听应用"),
                tr("Disable app-targeted monitor routing", "关闭面向指定应用的监听路由"),
                active,
                dialogHolder,
                () -> updateAdvancedModeConfig(advancedModeConfig.withMonitoredApp("", ""))
        );
    }

    private View createMonitoredAppMenuRow(Drawable iconDrawable,
                                           String titleText,
                                           String subtitleText,
                                           boolean active,
                                           AlertDialog[] dialogHolder,
                                           Runnable selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(active
                ? strokeGlowRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(170, 0, 245, 212), dp(10), dp(3), Color.argb(95, 0, 245, 212))
                : plainRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(38, 255, 255, 255), dp(10)));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(10);
        row.addView(textColumn, textParams);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        if (active) {
            styleCyanGlowText(title);
        } else {
            stylePlainWhiteText(title);
        }
        textColumn.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText(subtitleText);
        subtitle.setTextSize(11.5f);
        subtitle.setSingleLine(true);
        subtitle.setIncludeFontPadding(false);
        styleDimPlainText(subtitle);
        textColumn.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Runnable select = () -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            if (selected != null) {
                selected.run();
            }
        };
        row.setOnClickListener(v -> select.run());
        title.setOnClickListener(v -> select.run());
        subtitle.setOnClickListener(v -> select.run());
        return row;
    }

    private void updateMonitoredAppIcon() {
        if (monitoredAppIconView == null || topControlOverlay == null) {
            return;
        }
        String iconPackage = currentHomepageIconPackage();
        boolean visible = iconPackage != null && !iconPackage.isEmpty();
        Drawable icon = visible ? loadMonitoredAppDrawable() : null;
        if (!visible || icon == null) {
            topControlOverlay.getOverlay().remove(monitoredAppIconView);
            monitoredAppIconView.setImageDrawable(null);
            monitoredAppIconView.setVisibility(View.GONE);
            return;
        }
        monitoredAppIconView.setImageDrawable(icon);
        monitoredAppIconView.setAlpha(1f);
        monitoredAppIconView.setVisibility(View.VISIBLE);
        if (monitoredAppIconView.getParent() instanceof ViewGroup) {
            ((ViewGroup) monitoredAppIconView.getParent()).removeView(monitoredAppIconView);
        }
        int iconSize = monitoredAppIconHostSizePx();
        monitoredAppIconView.measure(
                View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(iconSize, View.MeasureSpec.EXACTLY));
        monitoredAppIconView.layout(0, 0, iconSize, iconSize);
        topControlOverlay.getOverlay().remove(monitoredAppIconView);
        topControlOverlay.getOverlay().add(monitoredAppIconView);
        applyIconGlowColor(icon);
        updateMonitoredAppIconPosition();
    }

    private void updateMonitoredAppIconPosition() {
        if (topControlOverlay == null
                || monitoredAppIconView == null
                || modeSpinner == null
                || autoSwitchOutputSwitch == null
                || monitoredAppIconView.getVisibility() != View.VISIBLE) {
            return;
        }
        topControlOverlay.post(() -> {
            if (topControlOverlay == null
                    || monitoredAppIconView == null
                    || modeSpinner == null
                    || autoSwitchOutputSwitch == null
                    || monitoredAppIconView.getVisibility() != View.VISIBLE
                    || topControlOverlay.getWidth() <= 0
                    || topControlOverlay.getHeight() <= 0) {
                return;
            }
            Rect overlayRect = screenRectOf(topControlOverlay);
            Rect titleRect = screenRectOf(modeSpinner);
            Rect switchRect = screenRectOf(autoSwitchOutputSwitch);
            if (overlayRect == null || titleRect == null || switchRect == null) {
                return;
            }
            int iconWidth = monitoredAppIconView.getWidth() > 0
                    ? monitoredAppIconView.getWidth()
                    : monitoredAppIconHostSizePx();
            int iconHeight = monitoredAppIconView.getHeight() > 0
                    ? monitoredAppIconView.getHeight()
                    : monitoredAppIconHostSizePx();
            float titleTextRight = (titleRect.left - overlayRect.left)
                    + modeSpinner.getTranslationX()
                    + modeSpinner.getCompoundPaddingLeft();
            Layout titleLayout = modeSpinner.getLayout();
            if (titleLayout != null && titleLayout.getLineCount() > 0) {
                titleTextRight += titleLayout.getLineRight(0);
            } else {
                titleTextRight = titleRect.right - overlayRect.left;
            }
            float switchLeft = switchRect.left - overlayRect.left;
            float centerX = (titleTextRight + switchLeft) * 0.5f;
            float x = centerX - iconWidth / 2f + dp(8);
            x = Math.max(0f, Math.min(x, topControlOverlay.getWidth() - iconWidth));
            float y = Math.max(0f, (topControlOverlay.getHeight() - iconHeight) * 0.5f);
            monitoredAppIconView.setX(x);
            monitoredAppIconView.setY(y);
        });
    }

    private Rect screenRectOf(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !view.isAttachedToWindow()) {
            return null;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(
                location[0],
                location[1],
                location[0] + view.getWidth(),
                location[1] + view.getHeight()
        );
    }

    private Drawable loadMonitoredAppDrawable() {
        String packageName = currentHomepageIconPackage();
        if (packageName != null && !packageName.isEmpty()) {
            try {
                return getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    private String currentHomepageIconPackage() {
        if (activePlaybackPackageName != null && !activePlaybackPackageName.isEmpty()) {
            return activePlaybackPackageName;
        }
        if (processingMode == ProcessingMode.SHIZUKU_MUTE
                && advancedModeConfig.monitoredAppPackage != null
                && !advancedModeConfig.monitoredAppPackage.isEmpty()) {
            return advancedModeConfig.monitoredAppPackage;
        }
        return "";
    }

    private void refreshActivePlaybackPackageFromRepository() {
        if (repository == null) {
            updateActivePlaybackPackage("");
            return;
        }
        ShizukuRuntimeState runtimeState = repository.loadShizukuRuntimeState();
        String packageName = runtimeState.activePlaybackPackage;
        if (packageName == null || packageName.trim().isEmpty()) {
            packageName = runtimeState.activeReplayPackage;
        }
        updateActivePlaybackPackage(packageName);
    }

    private void updateActivePlaybackPackage(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.equals(activePlaybackPackageName)) {
            return;
        }
        activePlaybackPackageName = normalized;
        uiHandler.post(this::updateMonitoredAppIcon);
    }

    private boolean isDestroyedCompat() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed();
    }

    private LinearLayout.LayoutParams presetButtonParams(int width, float weight, int leftDp, int rightDp) {
        LinearLayout.LayoutParams params = width > 0 
                ? new LinearLayout.LayoutParams(width, dp(34))
                : new LinearLayout.LayoutParams(0, dp(34), weight);
        params.leftMargin = dp(leftDp);
        params.rightMargin = dp(rightDp);
        return params;
    }

    private void renderAll() {
        updatingUi = true;
        boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
        refreshRuntimeStatusUi(hasClip);
        refreshDeviceSelectionUi();
        if (modeSpinner != null) {
            setTextIfChanged(modeSpinner, editingPreset.mode.label);
            styleModeText();
        }
        if (autoSwitchOutputSwitch != null) {
            autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        }
        if (processingModeButton != null) {
            setTextIfChanged(processingModeButton, engineStatusText());
            styleGradientTitle(processingModeButton);
        }
        if (advancedModeDetailButton != null) {
            advancedModeDetailButton.setVisibility(AudioProcessingPolicy.advancedModeEnabled(processingMode) ? View.VISIBLE : View.GONE);
        }
        if (advancedModeSummaryView != null) {
            setTextIfChanged(advancedModeSummaryView, advancedModeSummaryText());
        }
        if (shizukuRuntimeModeView != null) {
            setTextIfChanged(shizukuRuntimeModeView, shizukuRuntimeModeText());
        }
        if (shizukuRuntimePlaybackView != null) {
            setTextIfChanged(shizukuRuntimePlaybackView, shizukuRuntimePlaybackText());
        }
        if (shizukuRuntimeMuteView != null) {
            setTextIfChanged(shizukuRuntimeMuteView, shizukuRuntimeMuteText());
        }
        if (shizukuRuntimeReplayView != null) {
            setTextIfChanged(shizukuRuntimeReplayView, shizukuRuntimeReplayText());
        }
        if (monitorCaptureStatusView != null) {
            setTextIfChanged(monitorCaptureStatusView, monitorCaptureStatusText());
        }
        if (shizukuAccessButton != null) {
            setTextIfChanged(shizukuAccessButton, shizukuAccessButtonText());
            shizukuAccessButton.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (shizukuAccessStatusView != null) {
            setTextIfChanged(shizukuAccessStatusView, shizukuAccessStatusText());
            shizukuAccessStatusView.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (shizukuAccessLabelView != null) {
            shizukuAccessLabelView.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (advancedMonitorAppButton != null) {
            setTextIfChanged(advancedMonitorAppButton, advancedModeConfig.monitoredAppLabel.isEmpty()
                    ? chooseAppText()
                    : advancedModeConfig.monitoredAppLabel);
        }
        if (presetSelectButton != null) {
            setTextIfChanged(presetSelectButton, presetDisplayName(editingPreset));
        }
        setEnabledSwitchChecked(runningPreset.enabled);
        styleModeText();

        if (presetSelectButton != null) {
            styleAccentButton(presetSelectButton, supported);
        }
        if (undoButton != null) {
            styleButton(undoButton, false, !undoStack.isEmpty() && supported);
        }
        if (redoButton != null) {
            styleButton(redoButton, false, !redoStack.isEmpty() && supported);
        }
        if (savePresetButton != null) {
            styleButton(savePresetButton, false, supported);
        }

        if (pregainInput != null) {
            setEditTextIfChanged(pregainInput, formatDecimal(editingPreset.pregainMb / 100f));
        }
        if (virtualBassSlider != null) {
            virtualBassSlider.setValue(editingPreset.virtualBassAmountPercent, false);
        }
        if (cutoffKnob != null) {
            cutoffKnob.setValue(editingPreset.extraBassCutoffHz, false);
        }
        if (amountKnob != null) {
            amountKnob.setValue(editingPreset.extraBassAmountPercent, false);
        }
        renderSavedPresetSpinner();
        renderCurveButtons();
        if (curveView != null) {
            refreshCurveView();
        }
        renderHeader();
        renderRows();
        updateExtraControls();
        updatingUi = false;
    }

    private void refreshRuntimeStatusUi() {
        boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
        refreshRuntimeStatusUi(hasClip);
    }

    private void refreshRuntimeStatusUi(boolean hasClip) {
        updateMonitoredAppIcon();
        if (statusText != null) {
            setTextIfChanged(statusText, statusLabel(hasClip));
            styleStatusText(hasClip);
            statusText.postInvalidate();
        }
        if (engineStatusValueView != null) {
            setTextIfChanged(engineStatusValueView, engineStatusText());
            styleGradientTitle(engineStatusValueView);
        }
        if (processingModeButton != null) {
            setTextIfChanged(processingModeButton, engineStatusText());
            styleGradientTitle(processingModeButton);
        }
        if (advancedModeDetailButton != null) {
            advancedModeDetailButton.setVisibility(AudioProcessingPolicy.advancedModeEnabled(processingMode) ? View.VISIBLE : View.GONE);
        }
        if (advancedModeSummaryView != null) {
            setTextIfChanged(advancedModeSummaryView, advancedModeSummaryText());
        }
        if (monitorCaptureStatusView != null) {
            setTextIfChanged(monitorCaptureStatusView, monitorCaptureStatusText());
        }
        if (shizukuAccessButton != null) {
            setTextIfChanged(shizukuAccessButton, shizukuAccessButtonText());
            shizukuAccessButton.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (shizukuAccessStatusView != null) {
            setTextIfChanged(shizukuAccessStatusView, shizukuAccessStatusText());
            shizukuAccessStatusView.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (shizukuAccessLabelView != null) {
            shizukuAccessLabelView.setVisibility(processingMode == ProcessingMode.SHIZUKU_MUTE ? View.VISIBLE : View.GONE);
        }
        if (advancedMonitorAppButton != null) {
            setTextIfChanged(advancedMonitorAppButton, advancedModeConfig.monitoredAppLabel.isEmpty()
                    ? chooseAppText()
                    : advancedModeConfig.monitoredAppLabel);
        }
    }

    private void refreshDeviceSelectionUi() {
        renderDeviceSpinner();
        if (autoSwitchOutputSwitch != null) {
            autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        }
        renderSavedPresetSpinner();
    }

    private void refreshEditingPresetUi() {
        updatingUi = true;
        if (modeSpinner != null) {
            setTextIfChanged(modeSpinner, editingPreset.mode.label);
            styleModeText();
        }
        if (pregainInput != null) {
            setEditTextIfChanged(pregainInput, formatDecimal(editingPreset.pregainMb / 100f));
        }
        refreshDeviceSelectionUi();
        renderCurveButtons();
        refreshCurveView();
        renderHeader();
        renderRows();
        updateExtraControls();
        updateEditStateLabels();
        updatingUi = false;
    }

    private void refreshMonitorStatusViews() {
        refreshRuntimeStatusUi();
    }

    private Preset curveDisplayPreset() {
        if (editingPreset == null) {
            return Preset.flat(false);
        }
        return editingPreset.withEnabled(isMainEffectivelyEnabled());
    }

    private boolean isMainEffectivelyEnabled() {
        return isCurveVisualEnabled();
    }

    private boolean isModeVisualEnabled() {
        return supported && runningPreset != null && runningPreset.enabled && modeVisualEnabled;
    }

    private boolean isCurveVisualEnabled() {
        return supported && runningPreset != null && runningPreset.enabled && curveVisualEnabled;
    }

    private boolean isPeqBandVisualEnabled(int index) {
        if (editingPreset == null || index < 0 || index >= editingPreset.bands.length) {
            return false;
        }
        if (!supported || runningPreset == null || !runningPreset.enabled) {
            return false;
        }
        if (!editingPreset.bands[index].enabled) {
            return false;
        }
        if (peqVisualSequenceRunning && peqBandVisualEnabled.length == editingPreset.bands.length) {
            return peqBandVisualEnabled[index];
        }
        return true;
    }

    private void refreshCurveView() {
        if (curveView == null) {
            return;
        }
        curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
        curveView.setMaxDb(curveGraphMaxDb);
        curveView.setPreset(curveDisplayPreset());
        updateCurveAnimationState(false);
    }

    private void updateCurveAnimationState(boolean movingBetweenPages) {
        if (curveView == null) {
            return;
        }
        boolean suppress = movingBetweenPages || activeMainPageIndex != 0;
        curveView.setAnimationSuppressed(suppress);
    }

    private void renderCurveButtons() {
        if (deviceCurveButton != null) {
            deviceCurveButton.setText(shortCurveLabel(selectedDeviceCurveName, deviceCurveGainOffsetDb));
            styleCurveButton(deviceCurveButton);
        }
        if (targetCurveButton != null) {
            targetCurveButton.setText(shortCurveLabel(selectedTargetCurveName, targetCurveGainOffsetDb));
            styleCurveButton(targetCurveButton);
        }
    }

    private void renderHeader() {
        if (header == null || editingPreset == null) {
            return;
        }
        if (lastRenderedHeaderMode == editingPreset.mode && header.getChildCount() > 0) {
            return;
        }
        lastRenderedHeaderMode = editingPreset.mode;
        header.removeAllViews();
        if (editingPreset.mode == EqMode.GEQ) {
            return;
        }
        addHeaderCell(header, "", 0.35f);
        addHeaderCell(header, "Type", 1f);
        addHeaderCell(header, "Freq", 1f);
        addHeaderCell(header, "Gain", 1f);
        addHeaderCell(header, "Q", 1f);
        addHeaderCell(header, "", 0.35f);
    }

    private void renderDeviceSpinner() {
        if (deviceSpinner == null || currentDevice == null) {
            return;
        }

        deviceChoices = repository.loadKnownDevices();
        if (deviceMonitor != null) {
            List<AudioOutputDevice> liveDevices = deviceMonitor.availableOutputDevices();
            if (!liveDevices.isEmpty()) {
                List<AudioOutputDevice> mergedChoices = new ArrayList<>(deviceChoices);
                for (AudioOutputDevice liveDevice : liveDevices) {
                    boolean exists = false;
                    String liveLabelKey = liveDevice.label == null
                            ? ""
                            : liveDevice.label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
                    for (AudioOutputDevice knownDevice : mergedChoices) {
                        String knownLabelKey = knownDevice.label == null
                                ? ""
                                : knownDevice.label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
                        if (knownDevice.key.equals(liveDevice.key) || knownLabelKey.equals(liveLabelKey)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        mergedChoices.add(liveDevice);
                    }
                }
                deviceChoices = mergedChoices;
            }
        }
        boolean hasCurrent = false;
        for (AudioOutputDevice device : deviceChoices) {
            if (device.key.equals(currentDevice.key)) {
                hasCurrent = true;
                break;
            }
        }
        if (!hasCurrent) {
            deviceChoices = new ArrayList<>(deviceChoices);
            if (currentDevice.isDisplayable()) {
                deviceChoices.add(0, currentDevice);
            }
        }
        if (deviceChoices.isEmpty()) {
            deviceChoices = new ArrayList<>();
            deviceChoices.add(new AudioOutputDevice("none", "Default Output"));
        }

        String[] labels = new String[deviceChoices.size()];
        int selected = 0;
        for (int i = 0; i < deviceChoices.size(); i++) {
            AudioOutputDevice device = deviceChoices.get(i);
            labels[i] = device.label;
            if (device.key.equals(currentDevice.key)) {
                selected = i;
            }
        }

        String signature = joinStrings(labels);
        String selectedKey = currentDevice.key == null ? "" : currentDevice.key;
        if (!signature.equals(lastDeviceSpinnerSignature)) {
            SmallSpinnerAdapter adapter = new SmallSpinnerAdapter(labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            adapter.setSelectedPosition(selected);
            deviceSpinner.setAdapter(adapter);
            lastDeviceSpinnerSignature = signature;
        } else if (deviceSpinner.getAdapter() instanceof SmallSpinnerAdapter) {
            ((SmallSpinnerAdapter) deviceSpinner.getAdapter()).setSelectedPosition(selected);
        }
        if (!selectedKey.equals(lastDeviceSpinnerSelectedKey) || deviceSpinner.getSelectedItemPosition() != selected) {
            deviceSpinner.setSelection(selected);
            lastDeviceSpinnerSelectedKey = selectedKey;
        }
    }

    private void showDeviceChoiceMenu() {
        if (deviceSpinner == null || deviceChoices == null || deviceChoices.isEmpty()) {
            renderDeviceSpinner();
        }
        if (deviceChoices == null || deviceChoices.isEmpty()) {
            return;
        }
        String[] labels = new String[deviceChoices.size()];
        int selected = 0;
        for (int i = 0; i < deviceChoices.size(); i++) {
            AudioOutputDevice device = deviceChoices.get(i);
            labels[i] = device.label;
            if (currentDevice != null && device.key.equals(currentDevice.key)) {
                selected = i;
            }
        }
        showLimitedChoiceMenu(deviceSpinner, labels, selected, position -> {
            if (position >= 0 && position < deviceChoices.size()) {
                selectOutputDevice(deviceChoices.get(position));
            }
        });
    }

    private void showSavedPresetChoiceMenu() {
        if (savedPresetSpinner == null || runningPreset == null) {
            return;
        }
        List<String> names = repository.loadNamedPresetNames();
        String matchedName = presetDisplayName(runningPreset);
        if (!names.contains(matchedName)) {
            names = new ArrayList<>(names);
            names.add(0, matchedName);
        }
        String[] labels = names.toArray(new String[0]);
        int selected = Math.max(0, names.indexOf(matchedName));
        showLimitedChoiceMenu(savedPresetSpinner, labels, selected, position -> {
            if (position >= 0 && position < labels.length && !samePresetSelection(labels[position], matchedName)) {
                loadMatchedPreset(labels[position]);
            }
        });
    }

    private void showReverbTypeChoiceMenu() {
        if (reverbTypeButton == null || editingPreset == null) {
            return;
        }
        showLimitedChoiceMenu(reverbTypeButton, REVERB_TYPE_LABELS, reverbTypeIndex(editingPreset.reverbType), position -> {
            String nextType = REVERB_TYPE_LABELS[Math.max(0, Math.min(REVERB_TYPE_LABELS.length - 1, position))];
            if (!AudioProcessingPolicy.reverbAllowed(processingMode) && !"Default".equals(nextType)) {
                showModeLockedDialog("Reverb requires Shizuku Mode.");
                return;
            }
            if (!nextType.equals(editingPreset.reverbType)) {
                setEditingPreset(editingPreset.withReverbType(nextType), true);
            }
        });
    }

    private void showBassModeChoiceMenu() {
        if (bassModeButton == null) {
            return;
        }
        showLimitedChoiceMenu(bassModeButton, virtualBassModeDisplayLabels(), selectedBassModeIndex, position -> {
            int nextIndex = clamp(position, 0, VIRTUAL_BASS_MODE_LABELS.length - 1);
            if (!AudioProcessingPolicy.virtualBassModeAllowed(processingMode, nextIndex)) {
                showModeLockedDialog("DSP bass requires Shizuku Mode.");
                return;
            }
            if (selectedBassModeIndex == nextIndex) {
                return;
            }
            setEditingPreset(editingPreset.withVirtualBassModeIndex(nextIndex), true);
        });
    }

    private void showModeLockedDialog(String message) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(16), dp(18), dp(18));
        shell.setBackground(createGlassCard(24));

        TextView titleView = gradientTitleView("Mode locked");
        titleView.setText("Mode locked");
        titleView.setTextSize(18);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, dp(6));
        styleGradientTitle(titleView);
        shell.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(14);
        messageView.setTextColor(Color.rgb(200, 210, 230));
        messageView.setPadding(0, dp(6), 0, 0);
        shell.addView(messageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button okButton = new Button(this);
        okButton.setText("OK");
        styleAccentButton(okButton, true);
        okButton.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
        );
        buttonParams.topMargin = dp(16);
        buttonParams.gravity = android.view.Gravity.END;
        shell.addView(okButton, buttonParams);

        FrameLayout.LayoutParams shellParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
        );
        frame.addView(shell, shellParams);

        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(android.view.Gravity.CENTER);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    private void showProcessingModeChoiceMenu(View anchor) {
        if (anchor == null) {
            return;
        }
        showLimitedChoiceMenu(anchor, ProcessingMode.labels(), processingMode.ordinal(), position -> {
            ProcessingMode nextMode = ProcessingMode.values()[clamp(position, 0, ProcessingMode.values().length - 1)];
            if (processingMode != nextMode) {
                setProcessingMode(nextMode);
            }
        });
    }

    private void showLimitedChoiceMenu(View anchor, String[] labels, int selected, ChoiceCallback callback) {
        showLimitedChoiceMenu(anchor, labels, selected, callback, true);
    }

    private void showLimitedChoiceMenu(View anchor, String[] labels, int selected, ChoiceCallback callback, boolean focusable) {
        if (anchor == null || labels == null || labels.length == 0) {
            return;
        }
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(0, 0, 0, 0);
        GradientDrawable shellBg = new GradientDrawable();
        shellBg.setShape(GradientDrawable.RECTANGLE);
        shellBg.setColor(Color.rgb(18, 22, 34));
        shellBg.setStroke(dp(1), Color.argb(54, 255, 255, 255));
        shellBg.setCornerRadius(dp(16));
        shell.setBackground(shellBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            shell.setClipToOutline(true);
        }

        ListView list = new ListView(this);
        list.setDivider(solidColorDrawable(Color.argb(26, 255, 255, 255)));
        list.setDividerHeight(dp(1));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setPadding(0, 0, 0, 0);
        list.setSelector(solidColorDrawable(Color.TRANSPARENT));
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = convertView instanceof TextView
                        ? (TextView) convertView
                        : new TextView(MainActivity.this);
                view.setText(getItem(position));
                view.setTextSize(14);
                view.setSingleLine(true);
                view.setGravity(android.view.Gravity.CENTER);
                if (position == selected) {
                    styleCyanGlowText(view);
                } else {
                    styleDropdownInactiveText(view);
                }
                view.setPadding(dp(12), 0, dp(12), 0);
                view.setBackgroundColor(Color.TRANSPARENT);
                view.setLayoutParams(new ListView.LayoutParams(
                        ListView.LayoutParams.MATCH_PARENT,
                        dp(44)
                ));
                return view;
            }
        });

        int width = Math.max(anchor.getWidth(), dp(180));
        int listHeight = Math.min(dp(264), dp(44) * labels.length);
        shell.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                listHeight
        ));
        PopupWindow popup = new PopupWindow(shell, width, listHeight, focusable);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(solidColorDrawable(Color.TRANSPARENT));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(dpf(8f));
        }
        list.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            callback.onChoice(position);
        });
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void renderRows() {
        if (rows == null || editingPreset == null) {
            return;
        }
        if (editingPreset.mode == EqMode.GEQ) {
            lastRenderedRowsMode = EqMode.GEQ;
            lastRenderedRowsBandCount = Preset.GEQ_BAND_COUNT;
            boolean canReuseGeqPanel = rows.getChildCount() == 1 && hasReusableGeqPanel();
            if (!canReuseGeqPanel) {
                rows.removeAllViews();
                rows.addView(createGeqSliderPanel());
            } else {
                updateGeqSliderPanelInPlace((HorizontalScrollView) rows.getChildAt(0));
            }
            return;
        }
        boolean canReuseRows = lastRenderedRowsMode == EqMode.PEQ
                && lastRenderedRowsBandCount == editingPreset.bands.length
                && rows.getChildCount() == editingPreset.bands.length + 1
                && hasReusablePeqRows();
        lastRenderedRowsMode = EqMode.PEQ;
        lastRenderedRowsBandCount = editingPreset.bands.length;
        if (!canReuseRows) {
            rows.removeAllViews();
            for (int i = 0; i < editingPreset.bands.length; i++) {
                rows.addView(createBandRow(i));
            }
            rows.addView(createAddBandRow());
            return;
        }
        for (int i = 0; i < editingPreset.bands.length; i++) {
            updateBandRowInPlace((LinearLayout) rows.getChildAt(i), i);
        }
    }

    private View createGeqSliderPanel() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setTag("geq_panel");
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);

        LinearLayout strip = new LinearLayout(this);
        strip.setTag("geq_strip");
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(10), dp(2), dp(32), dp(2));

        for (int i = 0; i < Preset.GEQ_BAND_COUNT; i++) {
            final int index = i;
            GeqSliderView slider = new GeqSliderView(this);
            slider.setBand(
                    formatFrequency(Preset.GEQ_FREQUENCIES[index]),
                    editingPreset.geqGainsMb[index],
                    gainMb -> updateGeqBand(index, gainMb)
            );
            LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(dp(44), dp(292));
            sliderParams.leftMargin = 0;
            sliderParams.rightMargin = 0;
            strip.addView(slider, sliderParams);
        }

        scroll.addView(strip, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT
        ));
        return scroll;
    }

    private boolean hasReusableGeqPanel() {
        View panel = rows.getChildAt(0);
        if (!(panel instanceof HorizontalScrollView)) {
            return false;
        }
        HorizontalScrollView scroll = (HorizontalScrollView) panel;
        if (scroll.getChildCount() != 1 || !(scroll.getChildAt(0) instanceof LinearLayout)) {
            return false;
        }
        LinearLayout strip = (LinearLayout) scroll.getChildAt(0);
        if (strip.getChildCount() != Preset.GEQ_BAND_COUNT) {
            return false;
        }
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (!(strip.getChildAt(i) instanceof GeqSliderView)) {
                return false;
            }
        }
        return true;
    }

    private void updateGeqSliderPanelInPlace(HorizontalScrollView scroll) {
        if (scroll == null || scroll.getChildCount() != 1 || !(scroll.getChildAt(0) instanceof LinearLayout)) {
            return;
        }
        LinearLayout strip = (LinearLayout) scroll.getChildAt(0);
        for (int i = 0; i < Preset.GEQ_BAND_COUNT && i < strip.getChildCount(); i++) {
            View child = strip.getChildAt(i);
            if (!(child instanceof GeqSliderView)) {
                continue;
            }
            final int index = i;
            ((GeqSliderView) child).setBand(
                    formatFrequency(Preset.GEQ_FREQUENCIES[index]),
                    editingPreset.geqGainsMb[index],
                    gainMb -> updateGeqBand(index, gainMb)
            );
        }
    }

    private TextView createStaticCell(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(13);
        text.setTextColor(Color.WHITE);
        text.setGravity(android.view.Gravity.CENTER);
        text.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(30, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        bg.setCornerRadius(dp(6));
        text.setBackground(bg);
        return text;
    }

    private String formatFrequency(int frequencyHz) {
        return frequencyHz >= 1000 && frequencyHz % 1000 == 0
                ? (frequencyHz / 1000) + "k"
                : String.valueOf(frequencyHz);
    }

    private View createAddBandRow() {
        Button add = new Button(this);
        add.setText("+ Add EQ Band");
        add.setTextSize(13);
        styleAccentButton(add, supported);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
        );
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        add.setLayoutParams(params);
        add.setOnClickListener(v -> {
            setEditingPreset(editingPreset.withAddedBand(), true);
            refreshEditingPresetUi();
        });
        return add;
    }

    private LinearLayout createBandRow(int index) {
        ParametricBand band = editingPreset.bands[index];
        LinearLayout row = new LinearLayout(this);
        PeqBandRowHolder holder = new PeqBandRowHolder();
        row.setTag(holder);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        // 关键修复：关闭 EQ 条目行的子视图裁剪，确保条目左侧开关和右侧删除按钮的光晕能够完好展示，绝不被裁切！
        row.setClipChildren(false);
        row.setClipToPadding(false);
        if (PeqMath.bandMayClip(editingPreset, index, PeqMath.HEADROOM_LIMIT_MB)) {
            GradientDrawable warningBg = new GradientDrawable();
            warningBg.setShape(GradientDrawable.RECTANGLE);
            warningBg.setColor(Color.argb(45, 255, 100, 100));
            warningBg.setStroke(dp(1), Color.argb(100, 255, 100, 100));
            warningBg.setCornerRadius(dp(8));
            row.setBackground(warningBg);
        }

        holder.enable = circleButton(false);
        holder.enable.setOnClickListener(v -> {
            updateBand(index, editingPreset.bands[index].withEnabled(!editingPreset.bands[index].enabled));
        });
        row.addView(wrapCircularButton(holder.enable, 0.35f, 22, 0, 3, -1));

        holder.type = new TextView(this);
        holder.type.setText(band.type.label);
        holder.type.setTextSize(11);
        holder.type.setGravity(android.view.Gravity.CENTER);
        holder.type.setSingleLine(true);
        holder.type.setIncludeFontPadding(false);
        holder.type.setEnabled(supported);
        holder.type.setBackground(typeCellBackground(band.type, false));
        holder.type.setOnClickListener(v -> {
            if (!supported) {
                return;
            }
            showLimitedChoiceMenu(holder.type, FilterType.labels(), editingPreset.bands[index].type.ordinal(), position -> {
                if (position < 0 || position >= FilterType.values().length) {
                    return;
                }
                FilterType selectedType = FilterType.values()[position];
                holder.type.setText(selectedType.label);
                updateBand(index, editingPreset.bands[index].withType(selectedType));
                updatePeqBandVisuals();
            });
        });
        row.addView(holder.type, cellParams(1f, 36));

        holder.frequency = createNumberInput(String.valueOf(band.frequencyHz), "Hz", value -> {
            updateBand(index, editingPreset.bands[index].withFrequencyHz(clamp(Math.round(value), 20, 20000)));
        });
        attachEqEditFocus(holder.frequency, index, EQ_EDIT_FIELD_FREQ);
        row.addView(holder.frequency, cellParams(1f, 36));

        holder.gain = createNumberInput(formatDecimal(band.gainMb / 100f), "dB", value -> {
            int gainMb = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withGainMb(clamp(gainMb, -1800, 1800)));
        });
        attachEqEditFocus(holder.gain, index, EQ_EDIT_FIELD_GAIN);
        row.addView(holder.gain, cellParams(1f, 36));

        holder.q = createNumberInput(formatDecimal(band.qHundred / 100f), "Q", value -> {
            int qHundred = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withQHundred(clamp(qHundred, 0, ParametricBand.MAX_Q_HUNDRED)));
        });
        attachEqEditFocus(holder.q, index, EQ_EDIT_FIELD_Q);
        row.addView(holder.q, cellParams(1f, 36));

        View delete = circleButton(true);
        delete.setEnabled(supported && editingPreset.bands.length > 1);
        delete.setBackground(deleteSymbolDrawable(delete.isEnabled()));
        delete.setOnClickListener(v -> confirmDeleteBand(index));
        holder.delete = delete;
        row.addView(wrapCircularButton(delete, 0.35f, 22, 3, 0, 1));

        applyBandRowVisualState(row, index);
        return row;
    }

    private boolean hasReusablePeqRows() {
        for (int i = 0; i < editingPreset.bands.length; i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                return false;
            }
            if (!(((LinearLayout) child).getTag() instanceof PeqBandRowHolder)) {
                return false;
            }
        }
        return true;
    }

    private void updateBandRowInPlace(LinearLayout row, int index) {
        if (row == null || index < 0 || index >= editingPreset.bands.length) {
            return;
        }
        Object tag = row.getTag();
        if (!(tag instanceof PeqBandRowHolder)) {
            return;
        }
        PeqBandRowHolder holder = (PeqBandRowHolder) tag;
        ParametricBand band = editingPreset.bands[index];
        boolean warning = PeqMath.bandMayClip(editingPreset, index, PeqMath.HEADROOM_LIMIT_MB);
        if (warning) {
            GradientDrawable warningBg = new GradientDrawable();
            warningBg.setShape(GradientDrawable.RECTANGLE);
            warningBg.setColor(Color.argb(45, 255, 100, 100));
            warningBg.setStroke(dp(1), Color.argb(100, 255, 100, 100));
            warningBg.setCornerRadius(dp(8));
            row.setBackground(warningBg);
        } else {
            row.setBackground(null);
        }
        setTextIfChanged(holder.type, band.type.label);
        holder.type.setEnabled(supported);
        holder.type.setBackground(typeCellBackground(band.type, false));
        setEditTextIfChanged(holder.frequency, String.valueOf(band.frequencyHz));
        setEditTextIfChanged(holder.gain, formatDecimal(band.gainMb / 100f));
        setEditTextIfChanged(holder.q, formatDecimal(band.qHundred / 100f));
        if (holder.delete != null) {
            boolean deleteEnabled = supported && editingPreset.bands.length > 1;
            holder.delete.setEnabled(deleteEnabled);
            holder.delete.setBackground(deleteSymbolDrawable(deleteEnabled));
        }
        applyBandRowVisualState(row, index);
    }

    private EditText createNumberInput(String value, String hint, FloatChanged listener) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(13);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setEnabled(supported);
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterUp = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || enterUp) {
                flushDebouncedFloatInput(input, listener);
                closeKeyboard(view);
                return true;
            }
            return false;
        });
        attachDebouncedFloatInput(input, EQ_TEXT_INPUT_APPLY_DELAY_MS, listener);

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setShape(GradientDrawable.RECTANGLE);
        inputBg.setColor(Color.argb(24, 255, 255, 255));
        inputBg.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        inputBg.setCornerRadius(dp(8));
        input.setBackground(inputBg);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(100, 255, 255, 255));
        input.setPadding(dp(6), dp(4), dp(6), dp(4));
        input.setGravity(android.view.Gravity.CENTER);

        return input;
    }

    private void attachDebouncedFloatInput(EditText input, long delayMs, FloatChanged listener) {
        if (input == null || listener == null) {
            return;
        }
        final Runnable applyRunnable = () -> flushDebouncedFloatInput(input, listener);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingUi) {
                    return;
                }
                uiHandler.removeCallbacks(applyRunnable);
                uiHandler.postDelayed(applyRunnable, delayMs);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void flushDebouncedFloatInput(EditText input, FloatChanged listener) {
        if (input == null || listener == null || updatingUi) {
            return;
        }
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        if (raw.isEmpty() || "-".equals(raw) || ".".equals(raw) || "-.".equals(raw)) {
            return;
        }
        try {
            listener.onChanged(Float.parseFloat(raw));
        } catch (NumberFormatException ignored) {
        }
    }

    private EditText createDeferredIntegerInput(String value, String hint, int min, int max, IntChanged listener) {
        EditText input = new EditText(this);
        final Runnable commitRunnable = () -> commitDeferredIntegerInput(input, min, max, listener);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(13);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setEnabled(supported);
        try {
            input.setTag(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            input.setTag(min);
        }
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterUp = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || enterUp) {
                uiHandler.removeCallbacks(commitRunnable);
                commitRunnable.run();
                closeKeyboard(view);
                view.clearFocus();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                uiHandler.removeCallbacks(commitRunnable);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                uiHandler.removeCallbacks(commitRunnable);
                commitRunnable.run();
            } else {
                uiHandler.removeCallbacks(commitRunnable);
            }
        });

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setShape(GradientDrawable.RECTANGLE);
        inputBg.setColor(Color.argb(24, 255, 255, 255));
        inputBg.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        inputBg.setCornerRadius(dp(8));
        input.setBackground(inputBg);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(100, 255, 255, 255));
        input.setPadding(dp(6), dp(4), dp(6), dp(4));
        input.setGravity(android.view.Gravity.CENTER);

        return input;
    }

    private void commitDeferredIntegerInput(EditText input, int min, int max, IntChanged listener) {
        if (input == null || listener == null || updatingUi) {
            return;
        }
        Object tag = input.getTag();
        int lastCommitted = tag instanceof Integer ? (Integer) tag : min;
        String rawText = input.getText() == null ? "" : input.getText().toString().trim();
        int clampedValue = lastCommitted;
        if (!rawText.isEmpty()) {
            try {
                clampedValue = clamp(Integer.parseInt(rawText), min, max);
            } catch (NumberFormatException ignored) {
                clampedValue = lastCommitted;
            }
        }
        String normalizedText = String.valueOf(clampedValue);
        if (!normalizedText.contentEquals(input.getText())) {
            updatingUi = true;
            input.setText(normalizedText);
            input.setSelection(normalizedText.length());
            updatingUi = false;
        }
        if (!(tag instanceof Integer) || ((Integer) tag) != clampedValue) {
            input.setTag(clampedValue);
            listener.onChanged(clampedValue);
            return;
        }
        input.setTag(clampedValue);
    }

    private void updateBand(int index, ParametricBand band) {
        if (editingPreset == null || index < 0 || index >= editingPreset.bands.length || band == null) {
            return;
        }
        if (pendingPeqPreviewBand != null && pendingPeqPreviewIndex != index) {
            flushPendingPeqBandPreview();
        }
        ParametricBand current = editingPreset.bands[index];
        if (pendingPeqPreviewBand != null && pendingPeqPreviewIndex == index) {
            current = pendingPeqPreviewBand;
        }
        if (sameBandState(current, band)) {
            return;
        }
        if (isBandEnabledToggleOnly(current, band)) {
            applyImmediateBandUpdate(index, band);
            return;
        }
        schedulePeqBandUpdate(index, band);
    }

    private void schedulePeqBandUpdate(int index, ParametricBand band) {
        if (pendingPeqBandHistorySnapshot == null) {
            pendingPeqBandHistorySnapshot = editingPreset;
        }
        pendingPeqPreviewIndex = index;
        pendingPeqPreviewBand = band;
        uiHandler.removeCallbacks(previewPeqBandUpdateRunnable);
        uiHandler.postDelayed(previewPeqBandUpdateRunnable, LIVE_EQ_PREVIEW_DELAY_MS);
        uiHandler.removeCallbacks(commitPeqBandUpdateRunnable);
        uiHandler.postDelayed(commitPeqBandUpdateRunnable, PEQ_BAND_COMMIT_DELAY_MS);
    }

    private void flushPendingPeqBandPreview() {
        uiHandler.removeCallbacks(previewPeqBandUpdateRunnable);
        if (editingPreset == null
                || pendingPeqPreviewBand == null
                || pendingPeqPreviewIndex < 0
                || pendingPeqPreviewIndex >= editingPreset.bands.length) {
            pendingPeqPreviewIndex = -1;
            pendingPeqPreviewBand = null;
            return;
        }
        if (sameBandState(editingPreset.bands[pendingPeqPreviewIndex], pendingPeqPreviewBand)) {
            pendingPeqPreviewIndex = -1;
            pendingPeqPreviewBand = null;
            return;
        }
        editingPreset = editingPreset.withBand(pendingPeqPreviewIndex, pendingPeqPreviewBand);
        int updatedIndex = pendingPeqPreviewIndex;
        pendingPeqPreviewIndex = -1;
        pendingPeqPreviewBand = null;
        if (curveView != null) {
            refreshCurveView();
        }
        updatePeqBandVisual(updatedIndex);
    }

    private void commitPendingPeqBandUpdate() {
        uiHandler.removeCallbacks(commitPeqBandUpdateRunnable);
        flushPendingPeqBandPreview();
        if (pendingPeqBandHistorySnapshot == null) {
            return;
        }
        pushHistory(undoStack, pendingPeqBandHistorySnapshot);
        redoStack.clear();
        pendingPeqBandHistorySnapshot = null;
        syncRunningIfEditingPresetIsActive();
        updateEditStateLabels();
    }

    private void applyImmediateBandUpdate(int index, ParametricBand band) {
        if (pendingPeqToggleHistorySnapshot == null) {
            pendingPeqToggleHistorySnapshot = editingPreset;
        }
        editingPreset = editingPreset.withBand(index, band);
        if (curveView != null) {
            refreshCurveView();
        }
        updatePeqBandVisuals();
        updateEditStateLabels();
        schedulePeqToggleCommit();
    }

    private void schedulePeqToggleCommit() {
        uiHandler.removeCallbacks(commitPeqToggleRunnable);
        uiHandler.postDelayed(commitPeqToggleRunnable, PEQ_TOGGLE_COMMIT_DELAY_MS);
    }

    private void commitPendingPeqToggle() {
        uiHandler.removeCallbacks(commitPeqToggleRunnable);
        if (pendingPeqToggleHistorySnapshot == null) {
            return;
        }
        pushHistory(undoStack, pendingPeqToggleHistorySnapshot);
        redoStack.clear();
        pendingPeqToggleHistorySnapshot = null;
        syncRunningIfEditingPresetIsActive();
        updateEditStateLabels();
    }

    private boolean isBandEnabledToggleOnly(ParametricBand current, ParametricBand next) {
        return current != null
                && next != null
                && current.enabled != next.enabled
                && current.type == next.type
                && current.frequencyHz == next.frequencyHz
                && current.gainMb == next.gainMb
                && current.qHundred == next.qHundred;
    }

    private boolean sameBandState(ParametricBand left, ParametricBand right) {
        return left != null
                && right != null
                && left.type == right.type
                && left.enabled == right.enabled
                && left.frequencyHz == right.frequencyHz
                && left.gainMb == right.gainMb
                && left.qHundred == right.qHundred;
    }

    private void updateGeqBand(int index, int gainMb) {
        if (editingPreset == null || index < 0 || index >= editingPreset.geqGainsMb.length) {
            return;
        }
        if (pendingGeqPreviewIndex >= 0 && pendingGeqPreviewIndex != index) {
            flushPendingGeqPreview();
        }
        gainMb = clamp(gainMb, -1800, 1800);
        int currentGainMb = pendingGeqPreviewIndex == index
                ? pendingGeqPreviewGainMb
                : editingPreset.geqGainsMb[index];
        if (currentGainMb == gainMb) {
            return;
        }
        if (pendingGeqHistorySnapshot == null) {
            pendingGeqHistorySnapshot = editingPreset;
        }
        pendingGeqPreviewIndex = index;
        pendingGeqPreviewGainMb = gainMb;
        uiHandler.removeCallbacks(previewGeqUpdateRunnable);
        uiHandler.postDelayed(previewGeqUpdateRunnable, LIVE_EQ_PREVIEW_DELAY_MS);
        scheduleGeqCommit();
    }

    private void flushPendingGeqPreview() {
        uiHandler.removeCallbacks(previewGeqUpdateRunnable);
        if (editingPreset == null
                || pendingGeqPreviewIndex < 0
                || pendingGeqPreviewIndex >= editingPreset.geqGainsMb.length) {
            pendingGeqPreviewIndex = -1;
            return;
        }
        if (editingPreset.geqGainsMb[pendingGeqPreviewIndex] == pendingGeqPreviewGainMb) {
            pendingGeqPreviewIndex = -1;
            return;
        }
        editingPreset = editingPreset.withGeqGainMb(pendingGeqPreviewIndex, pendingGeqPreviewGainMb);
        pendingGeqPreviewIndex = -1;
        if (curveView != null) {
            refreshCurveView();
        }
    }

    private void scheduleGeqCommit() {
        uiHandler.removeCallbacks(commitGeqUpdateRunnable);
        uiHandler.postDelayed(commitGeqUpdateRunnable, GEQ_COMMIT_DELAY_MS);
    }

    private void commitPendingGeqUpdate() {
        uiHandler.removeCallbacks(commitGeqUpdateRunnable);
        flushPendingGeqPreview();
        if (pendingGeqHistorySnapshot == null) {
            return;
        }
        pushHistory(undoStack, pendingGeqHistorySnapshot);
        redoStack.clear();
        pendingGeqHistorySnapshot = null;
        syncRunningIfEditingPresetIsActive();
        updateEditStateLabels();
    }

    private Preset limitPresetForHeadroom(Preset preset) {
        return preset;
    }

    private void attachEqEditFocus(EditText input, int bandIndex, int field) {
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                activeEqEditBandIndex = bandIndex;
                activeEqEditField = field;
                showEqEditOverlay(bandIndex, field, true);
            } else if (activeEqEditBandIndex == bandIndex) {
                view.postDelayed(() -> {
                    View focused = getCurrentFocus();
                    if (!(focused instanceof EditText)) {
                        hideEqEditOverlay();
                    }
                }, 32L);
            }
        });
    }

    private void showEqEditOverlay(int bandIndex, int field, boolean focusField) {
        if (editingPreset == null || bandIndex < 0 || bandIndex >= editingPreset.bands.length || curveFrameView == null) {
            hideEqEditOverlay();
            return;
        }
        FrameLayout root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }

        removeEqEditOverlayView(false);
        ParametricBand band = editingPreset.bands[bandIndex];
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(android.view.Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(8), dp(6), dp(8), dp(6));
        overlay.setBackground(createGlassCard(88));
        overlay.setElevation(dp(12));
        // 关键修复：关闭输入参数弹窗 overlay 的子视图裁剪。
        // 开关和右侧小×作为该容器的直接子代，其自身发出的高斯模糊边缘可以直接突破容器边界溢出，展现高亮完整光效！
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        View enableSwitch = new View(this);
        enableSwitch.setFocusable(true);
        enableSwitch.setClickable(true);
        enableSwitch.setBackground(stateIndicatorDrawable(band.enabled));
        enableSwitch.setOnClickListener(v -> {
            ParametricBand current = editingPreset.bands[bandIndex];
            boolean nextEnabled = !current.enabled;
            setBandFromEqOverlay(bandIndex, current.withEnabled(nextEnabled));
            v.setBackground(stateIndicatorDrawable(nextEnabled));
        });
        overlay.addView(wrapEqOverlaySwitch(enableSwitch, 0.38f));
        addEqOverlayCell(overlay, band.type.label, 1.2f, Color.WHITE);
        EditText frequencyInput = createEqOverlayInput(String.valueOf(band.frequencyHz), "Hz", 1f, value -> {
            int frequencyHz = clamp(Math.round(value), 20, 20000);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withFrequencyHz(frequencyHz));
        });
        attachEqOverlayNumberScrub(frequencyInput, band.frequencyHz, 20f, 20000f, 0, this::stepFrequencyScrub, false, value -> {
            int frequencyHz = clamp(Math.round(value), 20, 20000);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withFrequencyHz(frequencyHz));
        });
        overlay.addView(frequencyInput);
        EditText gainInput = createEqOverlayInput(formatDecimal(band.gainMb / 100f), "dB", 1f, value -> {
            int gainMb = clamp(Math.round(value * 100f), -1800, 1800);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withGainMb(gainMb));
        });
        attachEqOverlayNumberScrub(gainInput, band.gainMb / 100f, -18f, 18f, 2, current -> 0.1f, true, value -> {
            int gainMb = clamp(Math.round(value * 100f), -1800, 1800);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withGainMb(gainMb));
        });
        overlay.addView(gainInput);
        EditText qInput = createEqOverlayInput(formatDecimal(band.qHundred / 100f), "Q", 1f, value -> {
            int qHundred = clamp(Math.round(value * 100f), 0, ParametricBand.MAX_Q_HUNDRED);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withQHundred(qHundred));
        });
        attachEqOverlayNumberScrub(qInput, band.qHundred / 100f, 0f, ParametricBand.MAX_Q_HUNDRED / 100f, 2, current -> current < 1f ? 0.02f : 0.05f, true, value -> {
            int qHundred = clamp(Math.round(value * 100f), 0, ParametricBand.MAX_Q_HUNDRED);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withQHundred(qHundred));
        });
        overlay.addView(qInput);

        EditText focusTarget = field == EQ_EDIT_FIELD_GAIN ? gainInput : field == EQ_EDIT_FIELD_Q ? qInput : frequencyInput;

        activeEqEditOverlay = overlay;
        curveFrameView.post(() -> {
            if (activeEqEditOverlay == null || activeEqEditOverlay != overlay) {
                return;
            }
            int[] rootLocation = new int[2];
            int[] curveLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            curveFrameView.getLocationOnScreen(curveLocation);

            int rootWidth = root.getWidth();
            int left = dp(18);
            int top = Math.max(dp(8), curveLocation[1] - rootLocation[1] + curveFrameView.getHeight() + dp(6));
            int width = Math.max(dp(240), rootWidth - dp(36));
            int height = dp(48);

            showEqEditDimExceptCurve();

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            params.leftMargin = left;
            params.topMargin = top;
            overlay.setAlpha(0f);
            overlay.setTranslationY(dp(5));
            root.addView(overlay, params);
            overlay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(EQ_EDIT_FADE_IN_MS)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            if (focusField) {
                focusTarget.requestFocus();
                focusTarget.selectAll();
                InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void addEqOverlayCell(LinearLayout row, String text, float weight, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setGravity(android.view.Gravity.CENTER);
        row.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight));
    }

    private View wrapEqOverlaySwitch(View button, float weight) {
        FrameLayout container = new FrameLayout(this);
        // 关键修复：关闭裁剪，允许内部具有高斯模糊（MaskFilter、shadowLayer）的开关按钮的扩散边缘自由绘制不被截断！
        container.setClipChildren(false);
        container.setClipToPadding(false);
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        switchParams.gravity = android.view.Gravity.CENTER;
        container.addView(button, switchParams);
        container.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight));
        return container;
    }

    private EditText createEqOverlayInput(String value, String hint, float weight, FloatChanged listener) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(13);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setGravity(android.view.Gravity.CENTER);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(145, 220, 230, 245));
        input.setPadding(dp(4), 0, dp(4), 0);
        input.setBackground(createOverlayInputBackground());
        input.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterUp = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_DONE || enterUp) {
                flushDebouncedFloatInput(input, listener);
                closeKeyboard(view);
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                flushDebouncedFloatInput(input, listener);
            }
        });
        attachDebouncedFloatInput(input, EQ_TEXT_INPUT_APPLY_DELAY_MS, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        input.setLayoutParams(params);
        return input;
    }

    private interface EqOverlayStepProvider {
        float stepFor(float currentValue);
    }

    private void attachEqOverlayNumberScrub(
            EditText input,
            float initialValue,
            float min,
            float max,
            int decimals,
            EqOverlayStepProvider stepProvider,
            boolean forceFixedDecimals,
            FloatChanged listener
    ) {
        if (input == null || stepProvider == null || listener == null) {
            return;
        }
        final float[] startValue = new float[]{initialValue};
        final float[] startX = new float[1];
        final float[] startY = new float[1];
        final boolean[] scrubbing = new boolean[1];
        final int scrubTouchSlop = dp(10);
        final float pixelsPerStep = Math.max(12f, dpf(14f));

        input.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getX();
                    startY[0] = event.getY();
                    scrubbing[0] = false;
                    startValue[0] = parseFloatOrFallback(input.getText(), initialValue);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(event.getX() - startX[0]);
                    float dy = Math.abs(event.getY() - startY[0]);
                    if (!scrubbing[0]) {
                        if (dy <= scrubTouchSlop || dy < dx * 1.15f) {
                            break;
                        }
                        scrubbing[0] = true;
                        ViewParent moveParent = view.getParent();
                        if (moveParent != null) {
                            moveParent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    float step = Math.max(0.0001f, stepProvider.stepFor(startValue[0]));
                    float deltaSteps = (startY[0] - event.getY()) / pixelsPerStep;
                    float nextValue = clampFloat(startValue[0] + deltaSteps * step, min, max);
                    String text = formatScrubValue(nextValue, decimals, forceFixedDecimals);
                    if (!text.contentEquals(input.getText())) {
                        updatingUi = true;
                        input.setText(text);
                        input.setSelection(text.length());
                        updatingUi = false;
                    }
                    listener.onChanged(nextValue);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (scrubbing[0]) {
                        listener.onChanged(parseFloatOrFallback(input.getText(), startValue[0]));
                        ViewParent releaseParent = view.getParent();
                        if (releaseParent != null) {
                            releaseParent.requestDisallowInterceptTouchEvent(false);
                        }
                        return true;
                    }
                    ViewParent releaseParent = view.getParent();
                    if (releaseParent != null) {
                        releaseParent.requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                }
                default:
                    break;
            }
            return false;
        });
    }

    private float stepFrequencyScrub(float currentValue) {
        int rounded = Math.max(20, Math.round(currentValue));
        if (rounded < 100) {
            return 1;
        }
        if (rounded < 1000) {
            return 5;
        }
        if (rounded < 5000) {
            return 25;
        }
        return 100;
    }

    private float parseFloatOrFallback(CharSequence text, float fallback) {
        if (text == null) {
            return fallback;
        }
        String raw = text.toString().trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatScrubValue(float value, int decimals, boolean forceFixedDecimals) {
        if (decimals <= 0) {
            return String.valueOf(Math.round(value));
        }
        if (forceFixedDecimals) {
            return String.format(Locale.US, "%." + decimals + "f", value);
        }
        float scaled = Math.round(value * (float) Math.pow(10, decimals)) / (float) Math.pow(10, decimals);
        return formatDecimal(scaled);
    }

    private void setBandFromEqOverlay(int index, ParametricBand band) {
        updateBand(index, band);
    }

    private void hideEqEditOverlay() {
        boolean hadOverlay = activeEqEditOverlay != null || !eqEditDimViews.isEmpty();
        activeEqEditBandIndex = -1;
        removeEqEditOverlayView(true);
        removeEqEditDim(true);
        if (hadOverlay && rows != null && editingPreset != null && editingPreset.mode == EqMode.PEQ) {
            rows.postDelayed(this::renderRows, EQ_EDIT_FADE_OUT_MS + 40L);
        }
    }

    private void removeEqEditOverlayView(boolean animated) {
        if (activeEqEditOverlay == null) {
            return;
        }
        View overlay = activeEqEditOverlay;
        activeEqEditOverlay = null;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) {
            if (animated) {
                overlay.animate()
                        .alpha(0f)
                        .translationY(dp(5))
                        .setDuration(EQ_EDIT_FADE_OUT_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> {
                            ViewGroup currentParent = (ViewGroup) overlay.getParent();
                            if (currentParent != null) {
                                currentParent.removeView(overlay);
                            }
                        })
                        .start();
            } else {
                parent.removeView(overlay);
            }
        }
    }

    private void confirmDeleteBand(int index) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView("Delete EQ band"))
                .setMessage("Delete this EQ band?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
                    setEditingPreset(editingPreset.withoutBand(index), true);
                    refreshEditingPresetUi();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void showDeviceCurveMenu() {
        List<String> items = new ArrayList<>();
        items.add("+ Add");
        items.add("Default");
        List<String> imported = repository.loadDeviceCurveNames();
        items.addAll(imported);
        showCurveMenu(tr("Device curve", "Device curve"), items, which -> {
            String item = items.get(which);
            if (which == 0) {
                openCurveImport(REQUEST_IMPORT_DEVICE_CURVE);
                return;
            }
            selectedDeviceCurveName = item;
            deviceCurveGainOffsetDb = 0f;
            deviceCurveSmoothing = "Default";
            refreshDeviceCurveCache();
            syncCurrentCurveSettingsToEditingPreset(true);
            refreshCurveSettingsUi();
        }, item -> imported.contains(item), item -> {
            repository.deleteDeviceCurve(item);
            if (item.equals(selectedDeviceCurveName)) {
                selectedDeviceCurveName = "Default";
                deviceCurveGainOffsetDb = 0f;
                deviceCurveSmoothing = "Default";
                refreshDeviceCurveCache();
                syncCurrentCurveSettingsToEditingPreset(true);
                refreshCurveSettingsUi();
            }
        });
    }

    private void showTargetCurveMenu() {
        List<String> items = new ArrayList<>();
        items.add("+ Add");
        items.add("Default");
        items.add("JM-1");
        items.add("WoodenEarsTarget");
        List<String> imported = repository.loadTargetCurveNames();
        items.addAll(imported);
        showCurveMenu(tr("Target curve", "Target curve"), items, which -> {
            String item = items.get(which);
            if (which == 0) {
                openCurveImport(REQUEST_IMPORT_TARGET_CURVE);
                return;
            }
            selectedTargetCurveName = item;
            targetCurveGainOffsetDb = 0f;
            targetCurveSmoothing = "Default";
            refreshTargetCurveCache();
            syncCurrentCurveSettingsToEditingPreset(true);
            refreshCurveSettingsUi();
        }, item -> imported.contains(item), item -> {
            repository.deleteTargetCurve(item);
            if (item.equals(selectedTargetCurveName)) {
                selectedTargetCurveName = "Default";
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                refreshTargetCurveCache();
                syncCurrentCurveSettingsToEditingPreset(true);
                refreshCurveSettingsUi();
            }
        });
    }

    private FrequencyCurve applyCurveGainOffset(FrequencyCurve curve, float offsetDb) {
        if (curve == null) {
            return FrequencyCurve.DEFAULT;
        }
        return curve.withGainOffset(offsetDb);
    }

    private void refreshDeviceCurveCache() {
        if (selectedDeviceCurveSource == null
                || !curveNameMatches(selectedDeviceCurveSource.name, selectedDeviceCurveName)) {
            selectedDeviceCurveSource = resolveCurveSource(selectedDeviceCurveName,
                    editingPreset == null ? FrequencyCurve.DEFAULT : editingPreset.deviceCurveData, false);
        }
        selectedDeviceCurveBase = applyCurveSmoothing(selectedDeviceCurveSource, deviceCurveSmoothing);
        selectedDeviceCurve = applyCurveGainOffset(selectedDeviceCurveBase, deviceCurveGainOffsetDb);
    }

    private void refreshTargetCurveCache() {
        if (selectedTargetCurveSource == null
                || !curveNameMatches(selectedTargetCurveSource.name, selectedTargetCurveName)) {
            selectedTargetCurveSource = resolveCurveSource(selectedTargetCurveName,
                    editingPreset == null ? FrequencyCurve.DEFAULT : editingPreset.targetCurveData, true);
        }
        selectedTargetCurveBase = applyCurveSmoothing(selectedTargetCurveSource, targetCurveSmoothing);
        selectedTargetCurve = applyCurveGainOffset(selectedTargetCurveBase, targetCurveGainOffsetDb);
    }

    private void applyPresetCurveSettings(Preset preset) {
        if (preset == null) {
            return;
        }
        selectedDeviceCurveName = preset.deviceCurveName;
        selectedTargetCurveName = preset.targetCurveName;
        selectedDeviceCurveSource = resolveCurveSource(selectedDeviceCurveName, preset.deviceCurveData, false);
        selectedTargetCurveSource = resolveCurveSource(selectedTargetCurveName, preset.targetCurveData, true);
        deviceCurveGainOffsetDb = preset.deviceCurveGainOffsetDb;
        targetCurveGainOffsetDb = preset.targetCurveGainOffsetDb;
        deviceCurveSmoothing = preset.deviceCurveSmoothing;
        targetCurveSmoothing = preset.targetCurveSmoothing;
        persistCurrentCurveSettings();
        refreshDeviceCurveCache();
        refreshTargetCurveCache();
    }

    private Preset withCurrentCurveSettings(Preset preset) {
        if (preset == null) {
            return null;
        }
        return preset.withCurveSettings(
                selectedDeviceCurveName,
                selectedTargetCurveName,
                deviceCurveGainOffsetDb,
                targetCurveGainOffsetDb,
                deviceCurveSmoothing,
                targetCurveSmoothing,
                embeddedCurveForPreset(selectedDeviceCurveName, selectedDeviceCurveSource),
                embeddedCurveForPreset(selectedTargetCurveName, selectedTargetCurveSource)
        );
    }

    private FrequencyCurve resolveCurveSource(String curveName, FrequencyCurve embeddedCurve, boolean targetCurve) {
        if (embeddedCurve != null && !embeddedCurve.isDefault() && curveNameMatches(embeddedCurve.name, curveName)) {
            return embeddedCurve.withName(curveName);
        }
        return targetCurve ? repository.loadTargetCurve(curveName) : repository.loadDeviceCurve(curveName);
    }

    private FrequencyCurve embeddedCurveForPreset(String curveName, FrequencyCurve curve) {
        if (curve == null || curve.isDefault() || "Default".equals(curveName)) {
            return FrequencyCurve.DEFAULT;
        }
        return curve.withName(curveName);
    }

    private boolean curveNameMatches(String left, String right) {
        String normalizedLeft = left == null ? "" : left.trim();
        String normalizedRight = right == null ? "" : right.trim();
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private void syncCurrentCurveSettingsToEditingPreset(boolean recordHistory) {
        if (editingPreset == null) {
            return;
        }
        Preset nextEditingPreset = withCurrentCurveSettings(editingPreset);
        Preset nextRunningPreset = isEditingPresetActive() ? withCurrentCurveSettings(runningPreset) : runningPreset;
        boolean editingChanged = nextEditingPreset != null && !nextEditingPreset.toJson().equals(editingPreset.toJson());
        boolean runningChanged = nextRunningPreset != null && runningPreset != null
                && !nextRunningPreset.toJson().equals(runningPreset.toJson());
        if (!editingChanged && !runningChanged) {
            persistCurrentCurveSettings();
            return;
        }
        if (recordHistory && editingChanged) {
            pushHistory(undoStack, editingPreset);
            redoStack.clear();
        }
        if (editingChanged && nextEditingPreset != null) {
            editingPreset = nextEditingPreset;
        }
        if (runningChanged && nextRunningPreset != null) {
            runningPreset = nextRunningPreset;
            scheduleRunningPresetPersistence();
        }
        scheduleEditingPresetPersistence();
        persistCurrentCurveSettings();
        updateEditStateLabels();
    }

    private void persistCurrentCurveSettings() {
        repository.saveSelectedDeviceCurveName(selectedDeviceCurveName);
        repository.saveSelectedTargetCurveName(selectedTargetCurveName);
        repository.saveDeviceCurveGainOffsetDb(deviceCurveGainOffsetDb);
        repository.saveTargetCurveGainOffsetDb(targetCurveGainOffsetDb);
        repository.saveDeviceCurveSmoothing(deviceCurveSmoothing);
        repository.saveTargetCurveSmoothing(targetCurveSmoothing);
    }

    private void refreshCurvePreviewOnly() {
        renderCurveButtons();
        if (curveView != null) {
            curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
            curveView.setMaxDb(curveGraphMaxDb);
            refreshCurveView();
        }
    }

    private void refreshCurveSettingsUi() {
        refreshCurvePreviewOnly();
        updateEditStateLabels();
    }

    private int curveRangeIndex(int maxDb) {
        if (maxDb == 6) {
            return 0;
        }
        if (maxDb == 12) {
            return 1;
        }
        return 2;
    }

    private void setCurveGraphRange(int maxDb) {
        int next = maxDb == 6 || maxDb == 12 ? maxDb : 18;
        if (curveGraphMaxDb == next) {
            return;
        }
        curveGraphMaxDb = next;
        if (curveView != null) {
            curveView.setMaxDb(curveGraphMaxDb);
        }
    }

    private FrequencyCurve applyCurveSmoothing(FrequencyCurve curve, String smoothing) {
        if (curve == null || curve.isDefault()) {
            return FrequencyCurve.DEFAULT;
        }
        return curve.withFractionalOctaveSmoothing(curveSmoothingWindow(smoothing));
    }

    private double curveSmoothingWindow(String smoothing) {
        if ("1/3".equals(smoothing)) {
            return 1.0 / 3.0;
        }
        if ("1/6".equals(smoothing)) {
            return 1.0 / 6.0;
        }
        if ("1/12".equals(smoothing)) {
            return 1.0 / 12.0;
        }
        if ("1/24".equals(smoothing)) {
            return 1.0 / 24.0;
        }
        return 0.0;
    }

    private int curveSmoothingIndex(String smoothing) {
        for (int i = 0; i < CURVE_SMOOTHING_LABELS.length; i++) {
            if (CURVE_SMOOTHING_LABELS[i].equals(smoothing)) {
                return i;
            }
        }
        return 0;
    }

    private void setCurveSmoothing(boolean targetCurve, String smoothing) {
        smoothing = smoothing == null || smoothing.trim().isEmpty() ? "Default" : smoothing.trim();
        if (targetCurve) {
            if (smoothing.equals(targetCurveSmoothing)) {
                return;
            }
            targetCurveSmoothing = smoothing;
            refreshTargetCurveCache();
        } else {
            if (smoothing.equals(deviceCurveSmoothing)) {
                return;
            }
            deviceCurveSmoothing = smoothing;
            refreshDeviceCurveCache();
        }
        syncCurrentCurveSettingsToEditingPreset(false);
        refreshCurvePreviewOnly();
    }

    private void showCurveGainDialog(boolean targetCurve) {
        String title = targetCurve
                ? tr("Target curve gain", "Target curve 增益")
                : tr("Device curve gain", "Device curve 增益");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
        ));

        TextView name = new TextView(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && h > 0) {
                    getPaint().setShader(new LinearGradient(
                            0, 0, w, 0,
                            new int[]{Color.rgb(0, 255, 255), Color.rgb(180, 100, 255)},
                            null, Shader.TileMode.CLAMP));
                }
            }
        };
        name.setText(targetCurve ? selectedTargetCurveName : selectedDeviceCurveName);
        name.setTextSize(13);
        name.setTextColor(Color.rgb(185, 196, 212));
        name.setSingleLine(true);
        name.setGravity(android.view.Gravity.CENTER_VERTICAL);
        name.setClickable(true);
        name.setFocusable(true);
        name.setOnClickListener(v -> showCurveRenameDialog(targetCurve, name));
        topRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        int smoothingIndex = curveSmoothingIndex(targetCurve ? targetCurveSmoothing : deviceCurveSmoothing);
        Button smoothingButton = new MarqueeButton(this);
        smoothingButton.setAllCaps(false);
        smoothingButton.setText(CURVE_SMOOTHING_LABELS[smoothingIndex]);
        smoothingButton.setTextSize(10);
        smoothingButton.setMinWidth(0);
        smoothingButton.setMinHeight(0);
        smoothingButton.setPadding(dp(6), 0, dp(6), 0);
        configureCenteredMarquee(smoothingButton);
        styleCurveButton(smoothingButton);
        smoothingButton.setOnClickListener(v -> showLimitedChoiceMenu(
                smoothingButton,
                CURVE_SMOOTHING_LABELS,
                curveSmoothingIndex(targetCurve ? targetCurveSmoothing : deviceCurveSmoothing),
                position -> {
                    int nextIndex = Math.max(0, Math.min(CURVE_SMOOTHING_LABELS.length - 1, position));
                    String nextLabel = CURVE_SMOOTHING_LABELS[nextIndex];
                    smoothingButton.setText(nextLabel);
                    setCurveSmoothing(targetCurve, nextLabel);
                },
                false
        ));
        LinearLayout.LayoutParams smoothingParams = new LinearLayout.LayoutParams(dp(84), dp(28));
        smoothingParams.leftMargin = dp(8);
        topRow.addView(smoothingButton, smoothingParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(formatDecimal(targetCurve ? targetCurveGainOffsetDb : deviceCurveGainOffsetDb));
        input.setTextSize(24);
        input.setGravity(android.view.Gravity.CENTER);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(100, 255, 255, 255));
        input.setBackground(createFieldBackground(20, 40, 8));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
        );
        inputParams.topMargin = dp(12);
        layout.addView(input, inputParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Float value = parseCurveGainOffsetOrNull(s == null ? "" : s.toString());
                if (value != null) {
                    setCurveGainOffset(targetCurve, value);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        TextView unit = new TextView(this);
        unit.setText("dB");
        unit.setTextSize(12);
        unit.setTextColor(Color.rgb(142, 154, 168));
        unit.setGravity(android.view.Gravity.CENTER);
        layout.addView(unit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        steps.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams stepsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        stepsParams.topMargin = dp(14);
        layout.addView(steps, stepsParams);

        addGainStepButton(steps, "-1", input, -1f);
        addGainStepButton(steps, "-0.1", input, -0.1f);
        addGainStepButton(steps, "0", input, Float.NaN);
        addGainStepButton(steps, "+0.1", input, 0.1f);
        addGainStepButton(steps, "+1", input, 1f);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(title))
                .setView(layout)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        dialog.setOnDismissListener(d -> removeCurveGainDim());
        dialog.show();
        styleDialog(dialog);
        showCurveGainDimExceptCurve();
        positionCurveGainDialog(dialog);
    }

    private void positionCurveGainDialog(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        android.view.Window window = dialog.getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(420));
        params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
        params.y = dp(18);
        window.setAttributes(params);
    }

    private void addGainStepButton(LinearLayout row, String text, EditText input, float deltaDb) {
        Button button = new MarqueeButton(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        styleButton(button, false, true);
        button.setOnClickListener(v -> {
            float current = parseCurveGainOffset(input.getText().toString());
            float next = Float.isNaN(deltaDb) ? 0f : current + deltaDb;
            input.setText(formatDecimal(clampCurveGainOffset(next)));
            input.setSelection(input.getText().length());
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        row.addView(button, params);
    }

    private float parseCurveGainOffset(String text) {
        try {
            return clampCurveGainOffset(Float.parseFloat(text == null ? "" : text.trim()));
        } catch (NumberFormatException ex) {
            return 0f;
        }
    }

    private Float parseCurveGainOffsetOrNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed) || "+".equals(trimmed) || ".".equals(trimmed)
                || "-.".equals(trimmed) || "+.".equals(trimmed)) {
            return null;
        }
        try {
            return clampCurveGainOffset(Float.parseFloat(trimmed));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private float clampCurveGainOffset(float value) {
        return Math.max(-24f, Math.min(24f, value));
    }

    private void showCurveRenameDialog(boolean targetCurve, TextView nameView) {
        String currentName = targetCurve ? selectedTargetCurveName : selectedDeviceCurveName;
        if ("Default".equals(currentName)) {
            Toast.makeText(this, tr("Default curve can't be renamed", "Default 曲线不能重命名"), Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint(targetCurve
                ? tr("Target curve name", "Target curve 名称")
                : tr("Device curve name", "Device curve 名称"));
        input.setBackground(createFieldBackground(20, 40, 8));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(4), dp(20), dp(8));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(targetCurve
                        ? tr("Rename target curve", "重命名 Target curve")
                        : tr("Rename device curve", "重命名 Device curve")))
                .setView(container)
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setPositiveButton(tr("Rename", "重命名"), null)
                .create();
        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setOnClickListener(v -> {
                    if (renameCurrentCurve(targetCurve, input.getText().toString(), nameView)) {
                        dialog.dismiss();
                    }
                });
            }
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        dialog.show();
        styleDialog(dialog);
    }

    private boolean renameCurrentCurve(boolean targetCurve, String rawName, TextView nameView) {
        String oldName = targetCurve ? selectedTargetCurveName : selectedDeviceCurveName;
        String nextName = rawName == null ? "" : rawName.trim();
        if (nextName.isEmpty()) {
            Toast.makeText(this, tr("Curve name required", "需要填写曲线名称"), Toast.LENGTH_SHORT).show();
            return false;
        }
        if ("Default".equals(nextName)) {
            Toast.makeText(this, tr("Default is reserved", "Default 是保留名称"), Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean sameName = oldName.equals(nextName);
        boolean exists = targetCurve ? repository.hasTargetCurveName(nextName) : repository.hasDeviceCurveName(nextName);
        if (!sameName && exists) {
            Toast.makeText(this, tr("Curve name already exists", "曲线名称已存在"), Toast.LENGTH_SHORT).show();
            return false;
        }

        FrequencyCurve currentSource = targetCurve ? selectedTargetCurveSource : selectedDeviceCurveSource;
        if (currentSource == null || currentSource.isDefault()) {
            Toast.makeText(this, tr("Curve rename failed", "曲线重命名失败"), Toast.LENGTH_SHORT).show();
            return false;
        }

        if (targetCurve) {
            selectedTargetCurveName = nextName;
            selectedTargetCurveSource = currentSource.withName(nextName);
            refreshTargetCurveCache();
        } else {
            selectedDeviceCurveName = nextName;
            selectedDeviceCurveSource = currentSource.withName(nextName);
            refreshDeviceCurveCache();
        }
        syncCurrentCurveSettingsToEditingPreset(true);
        if (nameView != null) {
            nameView.setText(nextName);
        }
        refreshCurveSettingsUi();
        Toast.makeText(this, tr("Curve renamed", "曲线已重命名"), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void setCurveGainOffset(boolean targetCurve, float offsetDb) {
        offsetDb = clampCurveGainOffset(offsetDb);
        if (targetCurve) {
            if (Math.abs(targetCurveGainOffsetDb - offsetDb) < 0.001f) {
                return;
            }
            targetCurveGainOffsetDb = offsetDb;
            selectedTargetCurve = applyCurveGainOffset(selectedTargetCurveBase, targetCurveGainOffsetDb);
        } else {
            if (Math.abs(deviceCurveGainOffsetDb - offsetDb) < 0.001f) {
                return;
            }
            deviceCurveGainOffsetDb = offsetDb;
            selectedDeviceCurve = applyCurveGainOffset(selectedDeviceCurveBase, deviceCurveGainOffsetDb);
        }
        syncCurrentCurveSettingsToEditingPreset(false);
        refreshCurvePreviewOnly();
    }

    private void showCurveGainDimExceptCurve() {
        removeCurveGainDim();
        if (curveFrameView == null) {
            return;
        }
        ViewGroup content = findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            return;
        }
        FrameLayout root = (FrameLayout) content;
        curveFrameView.post(() -> {
            if (curveFrameView == null || curveFrameView.getWidth() <= 0 || curveFrameView.getHeight() <= 0) {
                return;
            }
            int[] rootLocation = new int[2];
            int[] curveLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            curveFrameView.getLocationOnScreen(curveLocation);

            int rootWidth = root.getWidth();
            int rootHeight = root.getHeight();
            int left = Math.max(0, curveLocation[0] - rootLocation[0]);
            int top = Math.max(0, curveLocation[1] - rootLocation[1]);
            int right = Math.min(rootWidth, left + curveFrameView.getWidth());
            int bottom = Math.min(rootHeight, top + curveFrameView.getHeight());
            int color = Color.argb(160, 18, 18, 25);

            addCurveGainDimView(root, 0, 0, rootWidth, top, color);
            addCurveGainDimView(root, 0, bottom, rootWidth, rootHeight - bottom, color);
            addCurveGainDimView(root, 0, top, left, bottom - top, color);
            addCurveGainDimView(root, right, top, rootWidth - right, bottom - top, color);
        });
    }

    private void addCurveGainDimView(FrameLayout root, int left, int top, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        View dim = new View(this);
        dim.setBackgroundColor(color);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        root.addView(dim, params);
        curveGainDimViews.add(dim);
    }

    private void removeCurveGainDim() {
        for (View view : curveGainDimViews) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }
        curveGainDimViews.clear();
    }

    private void showEqEditDimExceptCurve() {
        removeEqEditDim(false);
        if (curveFrameView == null) {
            return;
        }
        ViewGroup content = findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            return;
        }
        FrameLayout root = (FrameLayout) content;
        curveFrameView.post(() -> {
            if (curveFrameView == null || curveFrameView.getWidth() <= 0 || curveFrameView.getHeight() <= 0) {
                return;
            }
            int[] rootLocation = new int[2];
            int[] curveLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            curveFrameView.getLocationOnScreen(curveLocation);

            int rootWidth = root.getWidth();
            int rootHeight = root.getHeight();
            int left = Math.max(0, curveLocation[0] - rootLocation[0]);
            int top = Math.max(0, curveLocation[1] - rootLocation[1]);
            int right = Math.min(rootWidth, left + curveFrameView.getWidth());
            int bottom = Math.min(rootHeight, top + curveFrameView.getHeight());
            int color = Color.argb(190, 18, 18, 25);

            addEqEditDimView(root, 0, 0, rootWidth, top, color);
            addEqEditDimView(root, 0, bottom, rootWidth, rootHeight - bottom, color);
            addEqEditDimView(root, 0, top, left, bottom - top, color);
            addEqEditDimView(root, right, top, rootWidth - right, bottom - top, color);
        });
    }

    private void addEqEditDimView(FrameLayout root, int left, int top, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        View dim = new View(this);
        dim.setBackgroundColor(color);
        dim.setOnClickListener(v -> closeKeyboard(v));
        dim.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        root.addView(dim, params);
        eqEditDimViews.add(dim);
        dim.animate()
                .alpha(1f)
                .setDuration(EQ_EDIT_FADE_IN_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void removeEqEditDim(boolean animated) {
        List<View> removing = new ArrayList<>(eqEditDimViews);
        eqEditDimViews.clear();
        for (View view : removing) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                if (animated) {
                    view.animate().cancel();
                    view.animate()
                            .alpha(0f)
                            .setDuration(EQ_EDIT_FADE_OUT_MS)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> {
                                ViewGroup currentParent = (ViewGroup) view.getParent();
                                if (currentParent != null) {
                                    currentParent.removeView(view);
                                }
                            })
                            .start();
                } else {
                    parent.removeView(view);
                }
            }
        }
    }

    private void showCurveMenu(String title, List<String> items, CurveMenuSelected selected,
                               CurveMenuCanDelete canDelete, CurveMenuDeleted deleted) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(10));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            int index = i;
            if (item.startsWith("+")) {
                Button add = new Button(this);
                add.setText(item);
                add.setTextSize(14);
                add.setAllCaps(false);
                styleAccentButton(add, true);
                add.setOnClickListener(v -> {
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                    selected.onSelected(index);
                });
                list.addView(add, curveMenuRowParams(i == 0 ? 0 : 6));
                continue;
            }
            boolean active = item.equals(title.toLowerCase(Locale.US).contains("target")
                    ? selectedTargetCurveName
                    : selectedDeviceCurveName);
            list.addView(createCurveMenuRow(item, active, canDelete.canDelete(item), dialogHolder, () -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                selected.onSelected(index);
            }, () -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                confirmDeleteCurve(item, () -> deleted.onDeleted(item));
            }), curveMenuRowParams(i == 0 ? 0 : 6));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(title))
                .setView(scroll)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
        styleDialog(dialog);
    }

    private View createCurveMenuRow(String name, boolean active, boolean canDelete, AlertDialog[] dialogHolder, Runnable selected, Runnable deleted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.setBackground(active
                ? strokeGlowRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(170, 0, 245, 212), dp(10), dp(3), Color.argb(95, 0, 245, 212))
                : plainRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(38, 255, 255, 255), dp(10)));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        if (active) {
            title.getPaint().setShader(null);
            styleCyanGlowText(title);
        } else {
            stylePlainWhiteText(title);
        }
        title.setSingleLine(true);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        title.setOnClickListener(v -> selected.run());
        row.setOnClickListener(v -> selected.run());
        row.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        if (canDelete) {
            View delete = circleButton(true);
            delete.setBackground(deleteSymbolDrawable(true));
            delete.setOnClickListener(v -> deleted.run());
            row.addView(wrapCircularButton(delete, 0.18f, 24));
        }
        return row;
    }

    private LinearLayout.LayoutParams curveMenuRowParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void confirmDeleteCurve(String name, Runnable deleted) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView("Delete curve"))
                .setMessage("Delete curve \"" + name + "\"?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
                    deleted.run();
                    Toast.makeText(this, "Curve deleted", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void openCurveImport(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/*",
                "text/plain",
                "text/csv",
                "text/comma-separated-values",
                "application/csv",
                "application/vnd.ms-excel",
                "application/octet-stream"
        });
        startActivityForResult(intent, requestCode);
    }

    private void openJsonImport(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json",
                "text/json",
                "text/plain",
                "*/*"
        });
        startActivityForResult(intent, requestCode);
    }

    private void openJsonExport(String suggestedName, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        startActivityForResult(intent, requestCode);
    }

    private FrequencyCurve readCurveFromUri(Uri uri) throws IOException {
        StringBuilder text = new StringBuilder();
        InputStream rawStream = getContentResolver().openInputStream(uri);
        if (rawStream == null) {
            throw new IOException("Unable to open curve input stream");
        }
        try (InputStream stream = rawStream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return FrequencyCurve.fromText(curveNameFromUri(uri), text.toString());
    }

    private String readTextFromUri(Uri uri) throws IOException {
        StringBuilder text = new StringBuilder();
        InputStream rawStream = getContentResolver().openInputStream(uri);
        if (rawStream == null) {
            throw new IOException("Unable to open text input stream");
        }
        try (InputStream stream = rawStream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString().trim();
    }

    private void writeTextToUri(Uri uri, String text) throws IOException {
        OutputStream rawStream = getContentResolver().openOutputStream(uri, "wt");
        if (rawStream == null) {
            throw new IOException("Unable to open output stream");
        }
        try (OutputStream stream = rawStream;
             OutputStreamWriter writer = new OutputStreamWriter(stream)) {
            writer.write(text == null ? "" : text);
            writer.flush();
        }
    }

    private String curveNameFromUri(Uri uri) {
        String path = uri == null ? "" : uri.getLastPathSegment();
        if (path == null || path.trim().isEmpty()) {
            return "Imported";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim();
        return name.isEmpty() ? "Imported" : name;
    }

    private void importPresetJsonFromUri(Uri uri) throws IOException {
        String json = readTextFromUri(uri);
        if (json.isEmpty()) {
            Toast.makeText(this, tr("Preset file is empty", "预设文件为空"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Preset imported = PresetFile.fromJson(json).preset;
            if (imported != null) {
                handleImportedPreset(imported, true);
                return;
            }
        } catch (JSONException ex) {
            Toast.makeText(this, tr("Invalid JSON file", "JSON 文件无效"), Toast.LENGTH_SHORT).show();
        }
    }

    private void importDeviceConfigJsonFromUri(Uri uri) throws IOException {
        String json = readTextFromUri(uri);
        if (json.isEmpty()) {
            Toast.makeText(this, tr("Global config file is empty", "全局配置文件为空"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DeviceConfigFile config = DeviceConfigFile.fromJson(json);
            applyImportedDeviceConfig(config);
            Toast.makeText(this, tr("Global config imported", "全局配置已导入"), Toast.LENGTH_SHORT).show();
        } catch (JSONException ex) {
            Toast.makeText(this, tr("Unrecognized global config JSON", "无法识别的全局配置 JSON"), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportCurrentPresetJson() {
        Preset preset = repository.loadNamedPreset(presetName(editingPreset));
        if (preset == null) {
            Toast.makeText(this, tr("No preset to export", "没有可导出的预设"), Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportJson = new PresetFile(preset).toJson();
        pendingExportSuccessMessage = tr("Preset exported", "预设已导出");
        openJsonExport(safeJsonFileName(preset.name, "preset"), REQUEST_EXPORT_PRESET_JSON);
    }

    private void showExportPresetChoiceDialog() {
        List<String> names = repository.loadNamedPresetNames();
        if (names.isEmpty()) {
            Toast.makeText(this, tr("No saved presets to export", "没有可导出的已保存预设"), Toast.LENGTH_SHORT).show();
            return;
        }
        String currentName = presetName(editingPreset);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(4), dp(14), dp(12));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        for (String name : names) {
            list.addView(createExportPresetMenuRow(name, samePresetSelection(name, currentName), dialogHolder),
                    presetMenuRowParams(list.getChildCount() == 0 ? 0 : 8));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Export preset", "导出预设")))
                .setView(scroll)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
        styleDialog(dialog);
    }

    private void exportPresetJsonForName(String name) {
        Preset preset = repository.loadNamedPreset(name);
        if (preset == null) {
            Toast.makeText(this, tr("No preset to export", "没有可导出的预设"), Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportJson = new PresetFile(preset).toJson();
        pendingExportSuccessMessage = tr("Preset exported", "预设已导出");
        openJsonExport(safeJsonFileName(preset.name, "preset"), REQUEST_EXPORT_PRESET_JSON);
    }

    private void handleImportedPreset(Preset imported, boolean applyLive) {
        if (imported == null) {
            Toast.makeText(this, tr("Invalid preset file", "预设文件无效"), Toast.LENGTH_SHORT).show();
            return;
        }
        String importedName = presetName(imported);
        Preset existing = importedName == null ? null : repository.loadNamedPreset(importedName);
        if (existing != null) {
            showImportedPresetConflictDialog(imported, existing, applyLive);
            return;
        }
        applyImportedPreset(imported, applyLive);
        Toast.makeText(this, tr("Preset imported", "预设已导入"), Toast.LENGTH_SHORT).show();
    }

    private void showImportedPresetConflictDialog(Preset imported, Preset existing, boolean applyLive) {
        String importedName = presetDisplayName(imported);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Preset already exists", "预设名称已存在")))
                .setMessage(tr(
                        "A preset named \"" + importedName + "\" already exists. Replace it or rename the existing preset first?",
                        "名为“" + importedName + "”的预设已存在。要直接替换，还是先重命名现有预设？"))
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setNeutralButton(tr("Rename current", "重命名当前预设"), (d, which) -> showRenameExistingPresetDialog(imported, existing, applyLive))
                .setPositiveButton(tr("Replace", "直接替换"), (d, which) -> {
                    applyImportedPreset(imported, applyLive);
                    Toast.makeText(this, tr("Preset replaced", "预设已替换"), Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void showRenameExistingPresetDialog(Preset imported, Preset existing, boolean applyLive) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(nextConflictingPresetName(presetDisplayName(existing)));
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint(tr("Preset name", "预设名称"));
        input.setBackground(createFieldBackground(20, 40, 8));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(4), dp(20), dp(8));
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Rename existing preset", "重命名现有预设")))
                .setView(container)
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setPositiveButton(tr("Rename and import", "重命名后导入"), null)
                .create();
        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setOnClickListener(v -> {
                    String renamed = input.getText() == null ? "" : input.getText().toString().trim();
                    if (renamed.isEmpty()) {
                        Toast.makeText(this, tr("Preset name required", "需要填写预设名称"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (repository.hasNamedPreset(renamed)) {
                        Toast.makeText(this, tr("Preset name already exists", "预设名称已存在"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    repository.renameNamedPreset(existing.name, existing.withName(renamed));
                    dialog.dismiss();
                    applyImportedPreset(imported, applyLive);
                    Toast.makeText(this, tr("Preset imported", "预设已导入"), Toast.LENGTH_SHORT).show();
                });
            }
        });
        dialog.show();
        styleDialog(dialog);
    }

    private void applyImportedPreset(Preset imported, boolean applyLive) {
        Preset limited = limitPresetForHeadroom(imported);
        runningPreset = limited.withEnabled(currentMasterEnabled());
        activateEditingPreset(limited, true);
        repository.saveDraftPreset(editingPreset);
        if (editingPreset.name != null && !editingPreset.name.trim().isEmpty()) {
            repository.saveNamedPreset(editingPreset);
        }
        renderAll();
        if (applyLive) {
            applyRunningPreset(true);
        }
    }

    private void exportCurrentDeviceConfigJson() {
        Preset exportEditingPreset = withCurrentCurveSettings(editingPreset != null ? editingPreset : runningPreset);
        Preset exportRunningPreset = withCurrentCurveSettings(runningPreset);
        if (currentDevice == null || exportRunningPreset == null) {
            Toast.makeText(this, tr("No global config to export", "没有可导出的全局配置"), Toast.LENGTH_SHORT).show();
            return;
        }
        Preset systemDevicePreset = processingMode == ProcessingMode.SYSTEM_EQ
                ? exportRunningPreset
                : loadScopedPreset(currentDevice, ProcessingMode.SYSTEM_EQ);
        Preset shizukuDevicePreset = processingMode == ProcessingMode.SHIZUKU_MUTE
                ? exportRunningPreset
                : loadScopedPreset(currentDevice, ProcessingMode.SHIZUKU_MUTE);
        DeviceConfigFile config = new DeviceConfigFile(
                currentDevice,
                processingMode,
                autoSwitchOutput,
                new DeviceConfigFile.ModeState(
                        ProcessingMode.SYSTEM_EQ,
                        AdvancedModeConfig.DEFAULT,
                        systemDevicePreset,
                        activePresetName(processingMode == ProcessingMode.SYSTEM_EQ ? exportEditingPreset : null, systemDevicePreset)),
                new DeviceConfigFile.ModeState(
                        ProcessingMode.SHIZUKU_MUTE,
                        advancedModeConfig,
                        shizukuDevicePreset,
                        activePresetName(processingMode == ProcessingMode.SHIZUKU_MUTE ? exportEditingPreset : null, shizukuDevicePreset)),
                exportPresetLibrary(exportEditingPreset)
        );
        pendingExportJson = config.toJson();
        pendingExportSuccessMessage = tr("Global config exported", "全局配置已导出");
        openJsonExport(safeJsonFileName(currentDevice.label, "global-config"), REQUEST_EXPORT_DEVICE_CONFIG_JSON);
    }

    private void applyImportedDeviceConfig(DeviceConfigFile config) {
        flushPendingPresetPersistence();
        boolean masterEnabled = currentMasterEnabled();
        currentDevice = config.device;
        processingMode = config.processingMode;
        autoSwitchOutput = config.autoSwitchOutput;
        repository.saveKnownDevice(currentDevice);
        repository.saveSelectedDevice(currentDevice);
        repository.saveProcessingMode(processingMode);
        repository.saveAutoSwitchOutput(autoSwitchOutput);
        for (Preset preset : config.presets) {
            if (preset == null || preset.name == null || preset.name.trim().isEmpty()) {
                continue;
            }
            repository.saveNamedPreset(preset);
        }
        repository.savePreset(currentDevice, ProcessingMode.SYSTEM_EQ, config.systemEqState.devicePreset);
        repository.savePreset(currentDevice, ProcessingMode.SHIZUKU_MUTE, config.shizukuState.devicePreset);
        advancedModeConfig = config.shizukuState.advancedModeConfig;
        repository.saveAdvancedModeConfig(advancedModeConfig);
        if (processingMode != ProcessingMode.SHIZUKU_MUTE) {
            stopShizukuCaptureNow();
        }
        Preset activeDevicePreset = loadScopedPreset(currentDevice, processingMode);
        runningPreset = activeDevicePreset.withEnabled(masterEnabled);
        activateEditingPreset(resolveImportedEditingPreset(config.stateFor(processingMode), runningPreset), true);
        repository.saveDraftPreset(editingPreset);
        renderDeviceSpinner();
        renderAll();
        applyRunningPreset(shouldForceFullResetForCurrentMode());
        if (processingMode == ProcessingMode.SHIZUKU_MUTE && runningPreset.enabled) {
            ensureShizukuModeReady(true);
        }
    }

    private String activePresetName(Preset activePreset, Preset fallbackPreset) {
        if (activePreset != null && activePreset.name != null && !activePreset.name.trim().isEmpty()) {
            return activePreset.name;
        }
        return fallbackPreset == null ? "Default" : fallbackPreset.name;
    }

    private Preset resolveImportedEditingPreset(DeviceConfigFile.ModeState state, Preset fallbackPreset) {
        if (state == null || state.activePresetName == null || state.activePresetName.trim().isEmpty()) {
            return fallbackPreset;
        }
        if (fallbackPreset != null && state.activePresetName.equalsIgnoreCase(fallbackPreset.name)) {
            return fallbackPreset;
        }
        Preset selected = repository.loadNamedPreset(state.activePresetName);
        if (selected == null) {
            return fallbackPreset;
        }
        return limitPresetForHeadroom(selected);
    }

    private List<Preset> exportPresetLibrary(Preset currentPreset) {
        List<Preset> presets = new ArrayList<>();
        List<String> names = repository.loadNamedPresetNames();
        for (String name : names) {
            Preset savedPreset = repository.loadNamedPreset(name);
            if (savedPreset != null) {
                presets.add(savedPreset);
            }
        }
        if (currentPreset != null && currentPreset.name != null && !currentPreset.name.trim().isEmpty()) {
            boolean replaced = false;
            for (int i = 0; i < presets.size(); i++) {
                Preset preset = presets.get(i);
                if (preset != null && currentPreset.name.equalsIgnoreCase(preset.name)) {
                    presets.set(i, currentPreset);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                presets.add(currentPreset);
            }
        }
        return presets;
    }

    private String safeJsonFileName(String baseName, String fallback) {
        String normalized = baseName == null ? "" : baseName.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        return normalized + ".json";
    }

    private void showSavePresetDialog() {
        List<String> names = repository.loadNamedPresetNames();
        List<String> targets = new ArrayList<>();
        targets.add("+ New preset");
        targets.addAll(names);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView targetLabel = new TextView(this);
        targetLabel.setText(tr("Save to", "保存到"));
        targetLabel.setTextSize(12);
        targetLabel.setTextColor(Color.rgb(142, 154, 168));
        targetLabel.setPadding(dp(2), 0, dp(2), dp(6));
        layout.addView(targetLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        String[] targetLabels = targets.toArray(new String[0]);
        int[] selectedTargetIndex = {Math.max(0, names.contains(editingPreset.name) ? targets.indexOf(editingPreset.name) : 0)};
        TextView targetChoice = new TextView(this);
        targetChoice.setText(targetLabels[selectedTargetIndex[0]]);
        targetChoice.setTextSize(13);
        styleCyanGlowText(targetChoice);
        targetChoice.setSingleLine(true);
        targetChoice.setGravity(android.view.Gravity.CENTER);
        targetChoice.setIncludeFontPadding(false);
        targetChoice.setBackground(createFieldBackground(30, 60, 8));
        layout.addView(targetChoice, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
        ));

        TextView nameLabel = new TextView(this);
        nameLabel.setText(tr("Preset name", "预设名称"));
        nameLabel.setTextSize(12);
        nameLabel.setTextColor(Color.rgb(142, 154, 168));
        nameLabel.setPadding(dp(2), 0, dp(2), dp(6));
        LinearLayout.LayoutParams nameLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameLabelParams.topMargin = dp(14);
        layout.addView(nameLabel, nameLabelParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(names.contains(editingPreset.name) ? editingPreset.name : nextPresetName());
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint(tr("Preset name", "预设名称"));
        input.setBackground(createFieldBackground(20, 40, 8));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        layout.addView(input, inputParams);

        input.setText(selectedTargetIndex[0] == 0 ? nextPresetName() : targetLabels[selectedTargetIndex[0]]);
        input.selectAll();
        targetChoice.setOnClickListener(v -> showLimitedChoiceMenu(targetChoice, targetLabels, selectedTargetIndex[0], position -> {
            if (position < 0 || position >= targetLabels.length) {
                return;
            }
            selectedTargetIndex[0] = position;
            String target = targetLabels[position];
            targetChoice.setText(target);
            input.setText(position == 0 ? nextPresetName() : target);
            input.selectAll();
        }));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Save preset", "保存预设")))
                .setView(layout)
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setPositiveButton(tr("Save", "保存"), (d, which) -> {
                    int selected = selectedTargetIndex[0];
                    String oldTargetName = selected <= 0 ? null : targets.get(selected);
                    saveDraftToPreset(oldTargetName, input.getText().toString());
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void showPresetMenu() {
        List<String> names = repository.loadNamedPresetNames();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(4), dp(14), dp(12));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        Button add = new Button(this);
        add.setText(tr("+ Add new preset", "+ 新增预设"));
        add.setTextSize(14);
        add.setAllCaps(false);
        styleAccentButton(add, true);
        add.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            showAddPresetDialog();
        });
        list.addView(add, presetMenuRowParams(0));

        for (String name : names) {
            list.addView(createPresetMenuRow(name, dialogHolder), presetMenuRowParams(8));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Select preset", "选择预设")))
                .setView(scroll)
                .setNegativeButton(tr("Close", "关闭"), null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
        styleDialog(dialog);
    }

    private View createPresetMenuRow(String name, AlertDialog[] dialogHolder) {
        boolean active = samePresetSelection(name, presetName(editingPreset));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        row.setBackground(active
                ? strokeGlowRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(170, 0, 245, 212), dp(10), dp(3), Color.argb(95, 0, 245, 212))
                : plainRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(38, 255, 255, 255), dp(10)));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        title.setSingleLine(true);
        if (active) {
            title.getPaint().setShader(null);
            styleCyanGlowText(title);
        } else {
            stylePlainWhiteText(title);
        }
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        title.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            loadPresetForEditing(name);
        });
        row.setOnClickListener(v -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            loadPresetForEditing(name);
        });
        row.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        View delete = circleButton(true);
        delete.setBackground(deleteSymbolDrawable(true));
        delete.setOnClickListener(v -> confirmDeletePreset(name, dialogHolder));
        row.addView(wrapCircularButton(delete, 0.16f, 24));

        return row;
    }

    private View createExportPresetMenuRow(String name, boolean active, AlertDialog[] dialogHolder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        row.setBackground(active
                ? strokeGlowRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(170, 0, 245, 212), dp(10), dp(3), Color.argb(95, 0, 245, 212))
                : plainRoundRectDrawable(Color.argb(24, 255, 255, 255), Color.argb(38, 255, 255, 255), dp(10)));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        if (active) {
            styleCyanGlowText(title);
        } else {
            stylePlainWhiteText(title);
        }

        Runnable export = () -> {
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            exportPresetJsonForName(name);
        };
        title.setOnClickListener(v -> export.run());
        row.setOnClickListener(v -> export.run());
        row.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
        ));

        return row;
    }

    private LinearLayout.LayoutParams presetMenuRowParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void confirmDeletePreset(String name, AlertDialog[] menuDialogHolder) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Delete preset", "删除预设")))
                .setMessage(tr("Delete preset \"", "删除预设“") + name + tr("\"?", "”？"))
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setPositiveButton(tr("Delete", "删除"), (d, which) -> {
                    deletePreset(name);
                    if (menuDialogHolder[0] != null) {
                        menuDialogHolder[0].dismiss();
                    }
                    showPresetMenu();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void deletePreset(String name) {
        boolean deletedEditing = editingPreset != null && editingPreset.name.equals(name);
        boolean deletedRunning = runningPreset != null && runningPreset.name.equals(name);
        repository.deleteNamedPreset(name);

        if (!deletedEditing && !deletedRunning) {
            renderAll();
            return;
        }

        List<String> names = repository.loadNamedPresetNames();
        Preset fallback = names.isEmpty()
                ? Preset.flat(runningPreset != null && runningPreset.enabled).withName("Default")
                : repository.loadNamedPreset(names.get(0));
        fallback = limitPresetForHeadroom(fallback);
        activateEditingPreset(fallback, true);
        if (deletedRunning) {
            runningPreset = editingPreset.withEnabled(runningPreset != null && runningPreset.enabled && supported);
            applyRunningPreset();
        }
        renderAll();
    }

    private void saveDraftToPreset(String oldTargetName, String rawName) {
        String finalName = rawName == null ? "" : rawName.trim();
        if (finalName.isEmpty()) {
            Toast.makeText(this, tr("Preset name required", "需要填写预设名称"), Toast.LENGTH_SHORT).show();
            return;
        }

        Preset savedPreset = withCurrentCurveSettings(editingPreset).withName(finalName);

        if (oldTargetName != null && !oldTargetName.equals(finalName)) {
            repository.renameNamedPreset(oldTargetName, savedPreset);
        } else {
            repository.saveNamedPreset(savedPreset);
        }

        activateEditingPreset(savedPreset, true);
        repository.saveDraftPreset(editingPreset);
        refreshEditingPresetUi();
        Toast.makeText(this, tr("Preset saved", "预设已保存"), Toast.LENGTH_SHORT).show();
    }

    private void loadMatchedPreset(String name) {
        flushPendingPresetPersistence();
        Preset selected = repository.loadNamedPreset(name);
        selected = limitPresetForHeadroom(selected);
        activateMatchedPreset(selected, true);
        repository.saveDraftPreset(editingPreset);
        refreshEditingPresetUi();
        applyRunningPreset(true);
    }

    private void showAddPresetDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(nextPresetName());
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint(tr("Preset name", "预设名称"));
        input.setBackground(createFieldBackground(20, 40, 8));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(4), dp(20), dp(8));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        container.addView(input, inputParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView(tr("Add new preset", "新增预设")))
                .setView(container)
                .setNegativeButton(tr("Cancel", "取消"), null)
                .setPositiveButton(tr("Add", "新增"), (d, which) -> {
                    String name = input.getText().toString() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, tr("Preset name required", "需要填写预设名称"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    editingPreset = withCurrentCurveSettings(Preset.flat(runningPreset != null && runningPreset.enabled)).withName(name);
                    syncEditingStateFromPreset();
                    repository.saveNamedPreset(editingPreset);
                    clearPresetHistory();
                    persistEditingPreset();
                    refreshEditingPresetUi();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void loadPresetForEditing(String name) {
        flushPendingPresetPersistence();
        Preset selected = repository.loadNamedPreset(name);
        selected = limitPresetForHeadroom(selected);
        activateEditingPreset(selected, true);
        refreshEditingPresetUi();
        persistEditingPreset();
    }

    private String nextPresetName() {
        List<String> names = repository.loadNamedPresetNames();
        int index = names.size() + 1;
        String candidate = "Preset " + index;
        while (names.contains(candidate)) {
            index++;
            candidate = "Preset " + index;
        }
        return candidate;
    }

    private String nextConflictingPresetName(String baseName) {
        String normalizedBase = (baseName == null || baseName.trim().isEmpty()) ? "Preset" : baseName.trim();
        String candidate = normalizedBase + " 2";
        int index = 3;
        while (repository.hasNamedPreset(candidate)) {
            candidate = normalizedBase + " " + index;
            index++;
        }
        return candidate;
    }

    private String nextImportedPresetName(Preset imported) {
        String baseName = presetDisplayName(imported);
        String candidate = baseName + " Imported";
        int index = 2;
        while (repository.hasNamedPreset(candidate)) {
            candidate = baseName + " Imported " + index;
            index++;
        }
        return candidate;
    }

    private void onEnabledChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi) {
            return;
        }
        if (enabledToggleInteractionLocked) {
            setEnabledSwitchChecked(runningPreset != null && runningPreset.enabled);
            return;
        }
        lockEnabledToggleInteraction();
        boolean enabling = isChecked && supported;
        repository.saveMasterEnabled(enabling);
        runningPreset = runningPreset.withEnabled(enabling);
        if (isEditingPresetActive()) {
            editingPreset = editingPreset.withEnabled(runningPreset.enabled);
            if (enabling) {
                editingPreset = limitPresetForHeadroom(editingPreset);
                runningPreset = editingPreset.withEnabled(true);
            }
            pendingEnabledPersistPreset = editingPreset;
        }
        if (runningPreset.enabled) {
            startEnabledNeonSequence();
        } else {
            applyDisabledEnabledVisuals();
        }
        scheduleEnabledToggleCommit();
    }

    private void onAutoSwitchOutputChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi) {
            return;
        }
        autoSwitchOutput = isChecked;
        repository.saveAutoSwitchOutput(autoSwitchOutput);
        if (autoSwitchOutput) {
            handleDetectedOutputDevice(deviceMonitor.currentOutputDevice());
        } else {
            refreshDeviceSelectionUi();
        }
    }

    private void onExtraBassEnabledChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi || editingPreset == null) {
            return;
        }
        extraBassEnabledState = isChecked;
        pendingExtraBassEnabledState = isChecked;
        updateExtraControls();
        updateEditStateLabels();
        uiHandler.removeCallbacks(commitExtraBassToggleRunnable);
        uiHandler.postDelayed(commitExtraBassToggleRunnable, EXTRA_BASS_TOGGLE_COMMIT_DELAY_MS);
    }

    private void syncExtraBassEnabledFromPreset() {
        pendingExtraBassEnabledState = null;
        if (editingPreset == null) {
            extraBassEnabledState = false;
            return;
        }
        extraBassEnabledState = editingPreset.extraBassEnabled;
    }

    private void syncSelectedVirtualBassModeFromPreset() {
        if (editingPreset == null) {
            selectedBassModeIndex = 0;
            return;
        }
        selectedBassModeIndex = editingPreset.virtualBassModeIndex;
    }

    private boolean currentMasterEnabled() {
        boolean enabled = repository != null
                ? repository.loadMasterEnabled()
                : runningPreset != null && runningPreset.enabled;
        return enabled && supported;
    }

    private void setEnabledSwitchChecked(boolean checked) {
        if (enabledSwitch == null) {
            return;
        }
        enabledSwitch.setOnCheckedChangeListener(null);
        enabledSwitch.setChecked(checked);
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        updateEnabledSwitchInteractivity();
    }

    private void lockEnabledToggleInteraction() {
        enabledToggleInteractionLocked = true;
        updateEnabledSwitchInteractivity();
        uiHandler.removeCallbacks(unlockEnabledToggleInteractionRunnable);
        uiHandler.postDelayed(unlockEnabledToggleInteractionRunnable, computeEnabledToggleInteractionLockMs());
    }

    private void unlockEnabledToggleInteraction() {
        enabledToggleInteractionLocked = false;
        updateEnabledSwitchInteractivity();
    }

    private void updateEnabledSwitchInteractivity() {
        if (enabledSwitch == null) {
            return;
        }
        boolean interactive = supported && !enabledToggleInteractionLocked;
        enabledSwitch.setEnabled(interactive);
        enabledSwitch.setClickable(interactive);
        enabledSwitch.setAlpha(interactive ? 1f : 0.78f);
    }

    private Preset loadScopedPreset(AudioOutputDevice device, ProcessingMode mode) {
        return limitPresetForHeadroom(repository.loadPreset(device, mode));
    }

    private void adoptDevicePresetForCurrentMode(AudioOutputDevice device, boolean clearHistory) {
        Preset loadedPreset = loadScopedPreset(device, processingMode);
        activateMatchedPreset(loadedPreset, clearHistory);
    }

    private boolean shouldForceFullResetForCurrentMode() {
        return processingMode != ProcessingMode.SHIZUKU_MUTE;
    }

    private void applyRunningPreset() {
        applyRunningPreset(false);
    }

    private void applyRunningPreset(boolean forceFullReset) {
        applyRunningPreset(forceFullReset, true);
    }

    private void applyRunningPreset(boolean forceFullReset, boolean notifyService) {
        if (currentDevice == null || runningPreset == null) {
            return;
        }
        try {
            prepareRunningPresetForApply();
            applyRunningPresetToEngine(forceFullReset);
            if (notifyService) {
                notifyServiceAboutRunningPreset();
            }
        } catch (Throwable ignored) {
        }
    }

    private void prepareRunningPresetForApply() {
        Preset persistedRunningPreset = withCurrentCurveSettings(runningPreset);
        if (persistedRunningPreset != null) {
            runningPreset = persistedRunningPreset.withEnabled(runningPreset.enabled);
        }
        scheduleRunningPresetPersistence();
    }

    private void applyRunningPresetToEngine(boolean forceFullReset) {
        Preset effectivePreset = effectiveRunningPreset();
        if (forceFullReset && effectivePreset.enabled && shouldForceFullResetForCurrentMode()) {
            engine.applyWithFullReset(effectivePreset);
            return;
        }
        engine.apply(effectivePreset);
    }

    private Preset effectiveRunningPreset() {
        return AudioProcessingPolicy.effectiveSystemPreset(
                runningPreset,
                processingMode,
                runningPreset.virtualBassModeIndex);
    }

    private void notifyServiceAboutRunningPreset() {
        Intent service = buildRunningPresetServiceIntent(GlobalEqForegroundService.ACTION_APPLY);
        startCompatibleForegroundService(service);
    }

    private Intent buildRunningPresetServiceIntent(String action) {
        Intent service = buildServiceIntent(action);
        if (runningPreset != null) {
            service.putExtra(
                    GlobalEqForegroundService.EXTRA_PRESET_JSON,
                    runningPreset.withEnabled(currentMasterEnabled()).toJson());
        }
        if (currentDevice != null) {
            service.putExtra(GlobalEqForegroundService.EXTRA_DEVICE_KEY, currentDevice.key);
            service.putExtra(GlobalEqForegroundService.EXTRA_DEVICE_LABEL, currentDevice.label);
        }
        if (processingMode != null) {
            service.putExtra(GlobalEqForegroundService.EXTRA_PROCESSING_MODE, processingMode.key);
        }
        if (advancedModeConfig != null) {
            service.putExtra(GlobalEqForegroundService.EXTRA_ADVANCED_MODE_CONFIG_JSON, advancedModeConfig.toJson());
        }
        return service;
    }

    private Intent buildServiceIntent(String action) {
        Intent service = new Intent(this, GlobalEqForegroundService.class);
        service.setAction(action);
        return service;
    }

    private void startCompatibleForegroundService(Intent service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
            return;
        }
        startService(service);
    }

    private void scheduleEnabledToggleCommit() {
        pendingEnabledApplyPreset = runningPreset;
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.postDelayed(commitEnabledToggleRunnable, computeEnabledToggleCommitDelayMs());
    }

    private void commitPendingEnabledToggle() {
        Preset persistPreset = pendingEnabledPersistPreset;
        Preset applyPreset = pendingEnabledApplyPreset;
        pendingEnabledPersistPreset = null;
        pendingEnabledApplyPreset = null;
        if (persistPreset != null && editingPreset != null && persistPreset.name.equals(editingPreset.name)) {
            persistEditingPreset();
        }
        if (applyPreset != null && runningPreset != null && applyPreset.name.equals(runningPreset.name)
                && applyPreset.enabled == runningPreset.enabled) {
            if (processingMode == ProcessingMode.SHIZUKU_MUTE) {
                if (applyPreset.enabled) {
                    applyRunningPreset(false, false);
                    scheduleDelayedShizukuReady();
                } else {
                    applyRunningPreset(false, false);
                    stopShizukuCaptureNow();
                }
            } else {
                applyRunningPreset(applyPreset.enabled);
            }
        }
    }

    private void maybeEnsureProcessingActive() {
        if (repository == null || runningPreset == null || !currentMasterEnabled()) {
            startupProcessingRecoveryPending = false;
            return;
        }
        boolean serviceActive = syncRuntimeStateWithServiceProcess();
        if (!startupProcessingRecoveryPending) {
            return;
        }
        startupProcessingRecoveryPending = false;
        if (processingMode == ProcessingMode.SHIZUKU_MUTE) {
            if (serviceActive && repository.loadMonitorCaptureAuthorized()) {
                return;
            }
            ensureShizukuModeReady(true);
            return;
        }
        if (serviceActive) {
            return;
        }
        notifyServiceAboutRunningPreset();
    }

    private boolean syncRuntimeStateWithServiceProcess() {
        boolean active = GlobalEqForegroundService.isRunningInProcess();
        if (!active) {
            repository.clearRuntimeAudioState(ShizukuCompat.describeState(this));
        } else {
            repository.saveServiceActive(true);
        }
        return active;
    }

    private void scheduleDelayedShizukuReady() {
        uiHandler.removeCallbacks(delayedShizukuReadyRunnable);
        uiHandler.postDelayed(delayedShizukuReadyRunnable, computeShizukuEnableDelayMs());
    }

    private long computeEnabledToggleCommitDelayMs() {
        if (processingMode == ProcessingMode.SHIZUKU_MUTE && runningPreset != null && runningPreset.enabled) {
            return ENABLE_TOGGLE_SHIZUKU_COMMIT_DELAY_MS;
        }
        return ENABLE_TOGGLE_COMMIT_DELAY_MS;
    }

    private long computeEnabledToggleInteractionLockMs() {
        if (processingMode == ProcessingMode.SHIZUKU_MUTE) {
            return ENABLE_TOGGLE_SHIZUKU_INTERACTION_LOCK_MS;
        }
        return ENABLE_TOGGLE_INTERACTION_LOCK_MS;
    }

    private long computeShizukuEnableDelayMs() {
        if (editingPreset == null || editingPreset.mode == EqMode.GEQ) {
            return Math.max(ENABLE_NEON_CURVE_DELAY_MS, ENABLE_NEON_PEQ_START_DELAY_MS)
                    + ENABLE_CAPTURE_REQUEST_EXTRA_DELAY_MS;
        }
        int enabledBandCount = 0;
        for (ParametricBand band : editingPreset.bands) {
            if (band != null && band.enabled) {
                enabledBandCount++;
            }
        }
        long peqSequenceDelay = ENABLE_NEON_PEQ_START_DELAY_MS;
        if (enabledBandCount > 1) {
            peqSequenceDelay += (long) (enabledBandCount - 1) * ENABLE_NEON_PEQ_STEP_DELAY_MS;
        }
        return Math.max(ENABLE_NEON_CURVE_DELAY_MS, peqSequenceDelay)
                + ENABLE_CAPTURE_REQUEST_EXTRA_DELAY_MS;
    }

    private void stopShizukuCaptureNow() {
        uiHandler.removeCallbacks(delayedShizukuReadyRunnable);
        try {
            startCompatibleForegroundService(buildServiceIntent(GlobalEqForegroundService.ACTION_PAUSE_SHIZUKU));
        } catch (Throwable ignored) {
        }
    }

    private void refreshPendingEnabledToggleUi() {
        if (!pendingEnabledUiRefresh) {
            return;
        }
        pendingEnabledUiRefresh = false;
        refreshCurveView();
        updateEditStateLabels();
    }

    private void commitPendingExtraBassToggle() {
        if (editingPreset == null || pendingExtraBassEnabledState == null) {
            return;
        }
        boolean targetState = pendingExtraBassEnabledState;
        pendingExtraBassEnabledState = null;
        if (editingPreset.extraBassEnabled == targetState) {
            return;
        }
        setEditingPreset(editingPreset.withExtraBassEnabled(targetState), true);
    }

    private void startEnabledNeonSequence() {
        cancelEnabledNeonSequence();
        modeVisualEnabled = false;
        curveVisualEnabled = false;
        preparePeqVisualSequence(false);
        if (modeSpinner != null) {
            modeSpinner.animate().cancel();
            modeSpinner.setAlpha(0.68f);
        }
        styleModeText();
        if (curveView != null) {
            curveView.setVisualEnabled(false, false);
        }
        updateEditStateLabels();
        updatePeqBandVisuals();
        uiHandler.postDelayed(enableNeonHeaderRunnable, ENABLE_NEON_HEADER_DELAY_MS);
        uiHandler.postDelayed(enableNeonCurveRunnable, ENABLE_NEON_CURVE_DELAY_MS);
        uiHandler.postDelayed(enablePeqBandStepRunnable, ENABLE_NEON_PEQ_START_DELAY_MS);
    }

    private void activateEnabledNeonHeader() {
        if (!isAudioEnabledNow()) {
            return;
        }
        modeVisualEnabled = true;
        styleModeText();
        if (modeSpinner != null) {
            modeSpinner.animate().cancel();
            modeSpinner.setAlpha(0.38f);
            modeSpinner.animate().alpha(1f).setDuration(85).withEndAction(() -> {
                if (modeSpinner == null || !isAudioEnabledNow()) {
                    return;
                }
                modeSpinner.setAlpha(0.6f);
                modeSpinner.animate().alpha(1f).setDuration(120).start();
            }).start();
        }
        updateEditStateLabels();
    }

    private void activateEnabledNeonCurve() {
        if (!isAudioEnabledNow()) {
            return;
        }
        curveVisualEnabled = true;
        refreshCurveView();
        if (curveView != null) {
            curveView.setVisualEnabled(true, true);
        }
    }

    private void applyDisabledEnabledVisuals() {
        cancelEnabledNeonSequence();
        modeVisualEnabled = false;
        curveVisualEnabled = false;
        preparePeqVisualSequence(false);
        if (modeSpinner != null) {
            modeSpinner.animate().cancel();
            modeSpinner.setAlpha(1f);
        }
        styleModeText();
        updateEditStateLabels();
        updatePeqBandVisuals();
        uiHandler.postDelayed(disableNeonCurveRunnable, DISABLE_NEON_CURVE_DELAY_MS);
    }

    private void activateDisabledNeonCurve() {
        refreshCurveView();
        if (curveView != null) {
            curveView.setVisualEnabled(false, true);
        }
    }

    private void cancelEnabledNeonSequence() {
        uiHandler.removeCallbacks(enableNeonHeaderRunnable);
        uiHandler.removeCallbacks(enableNeonCurveRunnable);
        uiHandler.removeCallbacks(disableNeonCurveRunnable);
        uiHandler.removeCallbacks(enablePeqBandStepRunnable);
    }

    private boolean isAudioEnabledNow() {
        return supported && runningPreset != null && runningPreset.enabled;
    }

    private void preparePeqVisualSequence(boolean enabled) {
        if (editingPreset == null || editingPreset.mode == EqMode.GEQ) {
            peqBandVisualEnabled = new boolean[0];
            peqVisualSequenceRunning = false;
            pendingPeqVisualIndex = 0;
            return;
        }
        peqBandVisualEnabled = new boolean[editingPreset.bands.length];
        // 总开关打开前先由延迟点亮序列接管，这样不会先整排亮一帧再暗下去。
        peqVisualSequenceRunning = !enabled;
        pendingPeqVisualIndex = 0;
        if (enabled) {
            for (int i = 0; i < editingPreset.bands.length; i++) {
                peqBandVisualEnabled[i] = editingPreset.bands[i].enabled;
            }
            peqVisualSequenceRunning = false;
            pendingPeqVisualIndex = editingPreset.bands.length;
        }
    }

    private void activateNextPeqBandVisual() {
        if (!isAudioEnabledNow() || editingPreset == null || editingPreset.mode == EqMode.GEQ) {
            peqVisualSequenceRunning = false;
            return;
        }
        if (peqBandVisualEnabled.length != editingPreset.bands.length) {
            peqBandVisualEnabled = new boolean[editingPreset.bands.length];
        }
        peqVisualSequenceRunning = true;
        while (pendingPeqVisualIndex < editingPreset.bands.length && !editingPreset.bands[pendingPeqVisualIndex].enabled) {
            pendingPeqVisualIndex++;
        }
        if (pendingPeqVisualIndex >= editingPreset.bands.length) {
            peqVisualSequenceRunning = false;
            updatePeqBandVisuals();
            return;
        }
        peqBandVisualEnabled[pendingPeqVisualIndex] = true;
        pendingPeqVisualIndex++;
        updatePeqBandVisuals();
        uiHandler.postDelayed(enablePeqBandStepRunnable, ENABLE_NEON_PEQ_STEP_DELAY_MS);
    }

    private void updatePeqBandVisuals() {
        if (rows == null || editingPreset == null || editingPreset.mode == EqMode.GEQ) {
            return;
        }
        int bandCount = editingPreset.bands.length;
        for (int i = 0; i < bandCount && i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            applyBandRowVisualState((LinearLayout) child, i);
        }
    }

    private void updatePeqBandVisual(int index) {
        if (rows == null || editingPreset == null || editingPreset.mode == EqMode.GEQ) {
            return;
        }
        if (index < 0 || index >= rows.getChildCount()) {
            return;
        }
        View child = rows.getChildAt(index);
        if (child instanceof LinearLayout) {
            applyBandRowVisualState((LinearLayout) child, index);
        }
    }

    private void applyBandRowVisualState(LinearLayout row, int index) {
        if (row == null || editingPreset == null || index < 0 || index >= editingPreset.bands.length) {
            return;
        }
        ParametricBand band = editingPreset.bands[index];
        boolean visualActive = isPeqBandVisualEnabled(index);
        row.setAlpha(1f);
        Object tag = row.getTag();
        if (!(tag instanceof PeqBandRowHolder)) {
            return;
        }
        PeqBandRowHolder holder = (PeqBandRowHolder) tag;
        holder.enable.setBackground(stateIndicatorDrawable(visualActive));
        holder.type.setText(band.type.label);
        styleEqBandText(holder.type, visualActive);
        holder.type.setBackground(typeCellBackground(band.type, visualActive));
        applyBandInputVisual(holder.frequency, visualActive);
        applyBandInputVisual(holder.gain, visualActive);
        applyBandInputVisual(holder.q, visualActive);
    }

    private void applyBandInputVisual(View view, boolean visualActive) {
        if (!(view instanceof EditText)) {
            return;
        }
        EditText input = (EditText) view;
        styleEqBandText(input, visualActive);
        attachNumberWatermark(input, visualActive);
    }

    private void selectOutputDevice(AudioOutputDevice selected) {
        flushPendingPresetPersistence();
        repository.saveSelectedDevice(selected);
        currentDevice = selected;
        adoptDevicePresetForCurrentMode(currentDevice, true);
        renderAll();
        applyRunningPreset(shouldForceFullResetForCurrentMode());
    }

    private void renderSavedPresetSpinner() {
        if (savedPresetSpinner == null) {
            return;
        }
        String activeName = presetDisplayName(runningPreset);
        List<String> names = repository.loadNamedPresetNames();
        if (!names.contains(activeName)) {
            names = new ArrayList<>(names);
            names.add(0, activeName);
        }
        int selected = Math.max(0, names.indexOf(activeName));
        String[] labels = names.toArray(new String[0]);
        String signature = joinStrings(labels);
        if (!signature.equals(lastSavedPresetSpinnerSignature)) {
            SmallSpinnerAdapter adapter = new SmallSpinnerAdapter(labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            adapter.setSelectedPosition(selected);
            savedPresetSpinner.setAdapter(adapter);
            lastSavedPresetSpinnerSignature = signature;
        } else if (savedPresetSpinner.getAdapter() instanceof SmallSpinnerAdapter) {
            ((SmallSpinnerAdapter) savedPresetSpinner.getAdapter()).setSelectedPosition(selected);
        }
        if (!activeName.equals(lastSavedPresetSpinnerSelectedName)
                || savedPresetSpinner.getSelectedItemPosition() != selected) {
            savedPresetSpinner.setSelection(selected);
            lastSavedPresetSpinnerSelectedName = activeName;
        }
    }

    private void syncRunningIfEditingPresetIsActive() {
        scheduleEditingPresetPersistence();
        if (!isEditingPresetActive()) {
            return;
        }
        runningPreset = editingPreset.withEnabled(runningPreset.enabled && supported);
        if (enabledSwitch != null) {
            updatingUi = true;
            try {
                setEnabledSwitchChecked(runningPreset.enabled);
            } finally {
                updatingUi = false;
            }
        }
        applyRunningPreset();
    }

    private boolean isEditingPresetActive() {
        return samePresetSelection(runningPreset, editingPreset);
    }

    private void setEditingPreset(Preset nextPreset, boolean recordHistory) {
        if (pendingGeqHistorySnapshot != null) {
            commitPendingGeqUpdate();
        }
        if (pendingPeqBandHistorySnapshot != null) {
            commitPendingPeqBandUpdate();
        }
        if (pendingPeqToggleHistorySnapshot != null) {
            commitPendingPeqToggle();
        }
        if (nextPreset.toJson().equals(editingPreset.toJson())) {
            return;
        }
        if (recordHistory) {
            pushHistory(undoStack, editingPreset);
            redoStack.clear();
        }
        editingPreset = nextPreset;
        syncEditingStateFromPreset();
        if (curveView != null) {
            refreshCurveView();
        }
        syncRunningIfEditingPresetIsActive();
        updateExtraControls();
        updateEditStateLabels();
    }

    private void activateMatchedPreset(Preset preset, boolean clearHistory) {
        runningPreset = preset.withEnabled(currentMasterEnabled());
        activateEditingPreset(runningPreset, clearHistory);
    }

    private void activateEditingPreset(Preset preset, boolean clearHistory) {
        editingPreset = preset;
        syncEditingStateFromPreset();
        if (clearHistory) {
            clearPresetHistory();
        }
    }

    private void syncEditingStateFromPreset() {
        applyPresetCurveSettings(editingPreset);
        syncSelectedVirtualBassModeFromPreset();
        syncExtraBassEnabledFromPreset();
    }

    private void clearPresetHistory() {
        undoStack.clear();
        redoStack.clear();
    }

    private boolean samePresetSelection(Preset first, Preset second) {
        return samePresetSelection(presetName(first), presetName(second));
    }

    private boolean samePresetSelection(String firstName, String secondName) {
        if (firstName == null || secondName == null) {
            return false;
        }
        return firstName.equals(secondName);
    }

    private String presetName(Preset preset) {
        if (preset == null || preset.name == null) {
            return null;
        }
        String trimmed = preset.name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String presetDisplayName(Preset preset) {
        String name = presetName(preset);
        return name == null ? "Default" : name;
    }

    private void undoEdit() {
        commitPendingGeqUpdate();
        commitPendingPeqBandUpdate();
        commitPendingPeqToggle();
        if (undoStack.isEmpty()) {
            return;
        }
        pushHistory(redoStack, editingPreset);
        activateEditingPreset(undoStack.remove(undoStack.size() - 1), false);
        syncRunningIfEditingPresetIsActive();
        refreshEditingPresetUi();
    }

    private void redoEdit() {
        commitPendingGeqUpdate();
        commitPendingPeqBandUpdate();
        commitPendingPeqToggle();
        if (redoStack.isEmpty()) {
            return;
        }
        pushHistory(undoStack, editingPreset);
        activateEditingPreset(redoStack.remove(redoStack.size() - 1), false);
        syncRunningIfEditingPresetIsActive();
        refreshEditingPresetUi();
    }

    private void persistEditingPreset() {
        pendingEditingPresetPersistence = false;
        if (!pendingRunningPresetPersistence) {
            uiHandler.removeCallbacks(persistPresetStateRunnable);
        }
        persistEditingPresetNow();
    }

    private void scheduleEditingPresetPersistence() {
        pendingEditingPresetPersistence = true;
        schedulePresetPersistence();
    }

    private void scheduleRunningPresetPersistence() {
        pendingRunningPresetPersistence = true;
        schedulePresetPersistence();
    }

    private void schedulePresetPersistence() {
        uiHandler.removeCallbacks(persistPresetStateRunnable);
        uiHandler.postDelayed(persistPresetStateRunnable, PRESET_PERSIST_DELAY_MS);
    }

    private void flushPendingPresetPersistence() {
        uiHandler.removeCallbacks(persistPresetStateRunnable);
        boolean shouldPersistRunning = pendingRunningPresetPersistence;
        boolean shouldPersistEditing = pendingEditingPresetPersistence;
        pendingRunningPresetPersistence = false;
        pendingEditingPresetPersistence = false;
        if (shouldPersistRunning) {
            persistRunningPresetNow();
        }
        if (shouldPersistEditing) {
            persistEditingPresetNow();
        }
    }

    private void persistRunningPresetNow() {
        if (currentDevice == null || runningPreset == null) {
            return;
        }
        Preset persistedPreset = withCurrentCurveSettings(runningPreset);
        if (persistedPreset != null && !persistedPreset.toJson().equals(runningPreset.toJson())) {
            runningPreset = persistedPreset.withEnabled(runningPreset.enabled);
        }
        repository.savePreset(currentDevice, processingMode, runningPreset);
        repository.saveGlobalPreset(runningPreset);
    }

    private void persistEditingPresetNow() {
        Preset persistedPreset = withCurrentCurveSettings(editingPreset);
        if (persistedPreset == null) {
            return;
        }
        if (!persistedPreset.toJson().equals(editingPreset.toJson())) {
            editingPreset = persistedPreset;
        }
        repository.saveDraftPreset(persistedPreset);
        if (isNamedPreset(persistedPreset.name)) {
            repository.saveNamedPreset(persistedPreset);
        }
    }

    private boolean isNamedPreset(String name) {
        return name != null && repository.hasNamedPreset(name);
    }

    private String joinStrings(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value == null ? "" : value);
        }
        return builder.toString();
    }

    private void setTextIfChanged(TextView view, String text) {
        if (view == null) {
            return;
        }
        String next = text == null ? "" : text;
        CharSequence current = view.getText();
        if (current == null || !next.contentEquals(current)) {
            view.setText(next);
        }
    }

    private void setEditTextIfChanged(EditText input, String text) {
        if (input == null) {
            return;
        }
        String next = text == null ? "" : text;
        Editable editable = input.getText();
        String current = editable == null ? "" : editable.toString();
        if (!next.equals(current)) {
            input.setText(next);
        }
    }

    private void updateEditStateLabels() {
        if (presetSelectButton != null) {
            setTextIfChanged(presetSelectButton, presetDisplayName(editingPreset));
        }
        if (undoButton != null) {
            styleButton(undoButton, false, !undoStack.isEmpty() && supported);
        }
        if (redoButton != null) {
            styleButton(redoButton, false, !redoStack.isEmpty() && supported);
        }
        boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
        if (statusText != null) {
            setTextIfChanged(statusText, statusLabel(hasClip));
            styleStatusText(hasClip);
        }
        styleModeText();
    }

    private String statusLabel(boolean hasClip) {
        if (!supported) {
            return "OFF";
        }
        if (hasClip) {
            return "CLIP";
        }
        return isEditingPresetActive() ? "Live" : "Edit";
    }

    private void updateExtraControls() {
        boolean virtualBassModeAllowed = AudioProcessingPolicy.virtualBassModeAllowed(processingMode, selectedBassModeIndex);
        boolean virtualBassEnabled = supported && virtualBassModeAllowed && selectedBassModeIndex > 0;
        boolean dspVirtualBassMode = AudioProcessingPolicy.dspVirtualBassAllowed(processingMode, selectedBassModeIndex);
        boolean extraBassEnabled = supported && extraBassEnabledState;
        boolean reverbAllowed = supported && AudioProcessingPolicy.reverbAllowed(processingMode);
        boolean reverbEnabled = reverbAllowed && !"Default".equals(editingPreset.reverbType);
        if (virtualBassSlider != null) {
            virtualBassSlider.setValue(editingPreset.virtualBassAmountPercent, false);
            virtualBassSlider.setLabel(dspVirtualBassMode ? "Amount" : "Boost");
            virtualBassSlider.setEnabled(virtualBassEnabled);
            virtualBassSlider.setAlpha(virtualBassEnabled ? 1f : 0.55f);
        }
        if (extraBassSwitch != null) {
            updatingUi = true;
            extraBassSwitch.setChecked(extraBassEnabledState);
            extraBassSwitch.setEnabled(supported);
            extraBassSwitch.setAlpha(supported ? 1f : 0.55f);
            updatingUi = false;
        }
        updateExtraBassControl(cutoffKnob, editingPreset.extraBassCutoffHz, extraBassEnabled);
        updateExtraBassControl(amountKnob, editingPreset.extraBassAmountPercent, extraBassEnabled);
        if (reverbTypeButton != null) {
            reverbTypeButton.setText(editingPreset.reverbType);
            reverbTypeButton.setEnabled(reverbAllowed);
            reverbTypeButton.setAlpha(reverbAllowed ? 1f : 0.5f);
        }
        if (bassModeButton != null) {
            bassModeButton.setText(virtualBassModeDisplayLabel(VIRTUAL_BASS_MODE_LABELS[clamp(selectedBassModeIndex, 0, VIRTUAL_BASS_MODE_LABELS.length - 1)]));
            bassModeButton.setAlpha(1f);
        }
        if (virtualBassCutoffInput != null) {
            virtualBassCutoffInput.setHint(dspVirtualBassMode ? "Cutoff Hz" : "Boost");
            virtualBassCutoffInput.setVisibility(dspVirtualBassMode ? View.VISIBLE : View.GONE);
            virtualBassCutoffInput.setEnabled(virtualBassEnabled && dspVirtualBassMode);
            String cutoffText = String.valueOf(editingPreset.virtualBassCutoffHz);
            if (!virtualBassCutoffInput.hasFocus() && !cutoffText.contentEquals(virtualBassCutoffInput.getText())) {
                updatingUi = true;
                virtualBassCutoffInput.setText(cutoffText);
                virtualBassCutoffInput.setTag(editingPreset.virtualBassCutoffHz);
                updatingUi = false;
            }
            virtualBassCutoffInput.setAlpha(dspVirtualBassMode ? 1f : 0.55f);
        }
        syncExtraSectionTitleVisual(reverbTitleView);
        syncExtraSectionTitleVisual(virtualBassTitleView);
        syncExtraSectionTitleVisual(extraBassTitleView);
        updateReverbControl(reverbMainSlider, editingPreset.reverbMainMb, reverbAllowed);
        updateReverbControl(reverbDecaySlider, editingPreset.reverbDecayPercent, reverbEnabled);
        updateReverbControl(reverbPredelaySlider, editingPreset.reverbPredelayMs, reverbEnabled);
        updateReverbControl(reverbSizeSlider, editingPreset.reverbSizePercent, reverbEnabled);
        updateReverbControl(reverbMixSlider, editingPreset.reverbMixPercent, reverbEnabled);
    }

    private void updateExtraBassControl(KnobView knob, int value, boolean enabled) {
        if (knob != null) {
            knob.setEnabled(enabled);
            knob.setValue(value, false);
        }
    }

    private void updateReverbControl(VerticalReverbSlider slider, int value, boolean enabled) {
        if (slider != null) {
            slider.setEnabled(enabled);
            slider.setValue(value, false);
        }
    }

    private void pushHistory(List<Preset> stack, Preset snapshot) {
        if (snapshot == null) {
            return;
        }
        if (!stack.isEmpty() && stack.get(stack.size() - 1).toJson().equals(snapshot.toJson())) {
            return;
        }
        stack.add(snapshot);
        while (stack.size() > HISTORY_LIMIT) {
            stack.remove(0);
        }
    }

    private View buildBottomNav() {
        SlidingTabBar nav = new SlidingTabBar(this);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));

        GradientDrawable navBg = new GradientDrawable();
        navBg.setShape(GradientDrawable.RECTANGLE);
        navBg.setColor(Color.argb(45, 20, 24, 38));
        navBg.setStroke(dp(1), Color.argb(35, 255, 255, 255));
        navBg.setCornerRadius(dp(16));
        nav.setBackground(navBg);

        bottomTabIndicator = new View(this);
        bottomTabIndicator.setBackground(strokeGlowRoundRectDrawable(
                Color.argb(24, 255, 255, 255),
                Color.argb(160, 0, 245, 212),
                dp(12),
                dp(3),
                Color.argb(85, 0, 245, 212)
        ));
        nav.addView(bottomTabIndicator, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));

        bottomTabStrip = new LinearLayout(this);
        bottomTabStrip.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabStrip.setGravity(android.view.Gravity.CENTER);
        nav.addView(bottomTabStrip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        eqTabButton = createBottomTabText("EQ");
        normalizeBottomTab(eqTabButton);
        eqTabButton.setOnClickListener(v -> showEqPage());
        bottomTabStrip.addView(eqTabButton, bottomTabParams());

        extraTabButton = createBottomTabText("EXTRA");
        normalizeBottomTab(extraTabButton);
        extraTabButton.setOnClickListener(v -> showExtraPage());
        bottomTabStrip.addView(extraTabButton, bottomTabParams());

        settingsTabButton = createBottomTabText("Settings");
        normalizeBottomTab(settingsTabButton);
        settingsTabButton.setOnClickListener(v -> showSettingsPage());
        bottomTabStrip.addView(settingsTabButton, bottomTabParams());

        TextView[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        for (TextView tab : tabs) {
            tab.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                        break;
                }
                return false;
            });
        }

        updateBottomNavSelection(activeMainPageIndex);
        bottomTabStrip.post(() -> updateBottomNavSelection(activeMainPageIndex));

        return nav;
    }

    private LinearLayout.LayoutParams bottomTabParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private TextView createBottomTabText(String text) {
        TextView tab = gradientTitleView(text);
        if (tab instanceof GlowTitleTextView) {
            ((GlowTitleTextView) tab).setAutoRegisterShimmer(false);
        }
        tab.setText(text);
        tab.setTextSize(13);
        tab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tab.setGravity(android.view.Gravity.CENTER);
        tab.setIncludeFontPadding(false);
        tab.setSingleLine(true);
        tab.setPadding(dp(18), 0, dp(18), 0);
        styleInactiveTabText(tab);
        return tab;
    }

    private void normalizeBottomTab(TextView button) {
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setSingleLine(true);
        button.setGravity(android.view.Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setClickable(true);
        button.setFocusable(true);
    }

    private void showEqPage() {
        switchToMainPage(0, true);
    }

    private void showExtraPage() {
        switchToMainPage(1, true);
    }

    private void showSettingsPage() {
        switchToMainPage(2, true);
    }

    private void buildExtraPage(LinearLayout page) {
        page.setPadding(dp(12), dp(16), dp(12), dp(12));

        LinearLayout reverbPanel = createExtraPanelShell();
        page.addView(reverbPanel, extraPanelParams(0));
        LinearLayout reverbHeader = createExtraHeaderRow("Reverb");
        reverbTitleView = (TextView) reverbHeader.getChildAt(0);
        reverbTypeButton = createExtraChoiceButton();
        reverbTypeButton.setOnClickListener(v -> showReverbTypeChoiceMenu());
        reverbHeader.addView(reverbTypeButton, new LinearLayout.LayoutParams(dp(120), dp(30)));
        reverbPanel.addView(reverbHeader, blockParams(4));
        LinearLayout reverbKnobs = createExtraKnobRow(reverbPanel);
        reverbKnobs.addView(createReverbSlider("Main", -12000, 300, editingPreset.reverbMainMb, "dB", 0.01f, 1, true, REVERB_MAIN_SLIDER_MAPPER, value ->
                setEditingPreset(editingPreset.withReverbSettings(value, editingPreset.reverbDecayPercent, editingPreset.reverbPredelayMs, editingPreset.reverbSizePercent, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbSlider("Decay", 0, 1200, editingPreset.reverbDecayPercent, "s", 0.01f, 2, false, REVERB_DECAY_SLIDER_MAPPER, value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbMainMb, value, editingPreset.reverbPredelayMs, editingPreset.reverbSizePercent, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbSlider("Predelay", 0, 250, editingPreset.reverbPredelayMs, "ms", 1f, 0, false, REVERB_PREDELAY_SLIDER_MAPPER, value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbMainMb, editingPreset.reverbDecayPercent, value, editingPreset.reverbSizePercent, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbSlider("Size", 0, 100, editingPreset.reverbSizePercent, "%", false, value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbMainMb, editingPreset.reverbDecayPercent, editingPreset.reverbPredelayMs, value, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbSlider("Mix", 0, 100, editingPreset.reverbMixPercent, "%", 1f, 0, false, REVERB_MIX_SLIDER_MAPPER, value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbMainMb, editingPreset.reverbDecayPercent, editingPreset.reverbPredelayMs, editingPreset.reverbSizePercent, value), true)), knobColumnParams());

        LinearLayout bassPanel = createExtraPanelShell();
        page.addView(bassPanel, extraPanelParams(12));
        LinearLayout bassHeader = createExtraHeaderRow("Virtual Bass");
        virtualBassTitleView = (TextView) bassHeader.getChildAt(0);
        bassModeButton = createExtraChoiceButton();
        bassModeButton.setOnClickListener(v -> showBassModeChoiceMenu());
        // UI 占位选择框，system/dsp 切换功能后续接入
        bassHeader.addView(bassModeButton, new LinearLayout.LayoutParams(dp(120), dp(30)));
        bassPanel.addView(bassHeader, blockParams(4));
        virtualBassSlider = new HorizontalBassSlider(this);
        virtualBassSlider.configure(0, 100, editingPreset.virtualBassAmountPercent, "%", "Boost",
                value -> setEditingPreset(editingPreset.withVirtualBassAmountPercent(value), true));
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        sliderParams.topMargin = dp(8);
        bassPanel.addView(virtualBassSlider, sliderParams);

        virtualBassCutoffInput = createDeferredIntegerInput(
                String.valueOf(editingPreset.virtualBassCutoffHz),
                "Cutoff Hz",
                20,
                250,
                value -> setEditingPreset(editingPreset.withVirtualBassCutoffHz(value), true));
        virtualBassCutoffInput.setTextSize(13);
        virtualBassCutoffInput.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams cutoffParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
        );
        cutoffParams.topMargin = dp(10);
        bassPanel.addView(virtualBassCutoffInput, cutoffParams);

        LinearLayout extraBassPanel = createExtraPanelShell();
        page.addView(extraBassPanel, extraPanelParams(12));
        LinearLayout extraBassHeader = createExtraHeaderRow("Extra Bass");
        extraBassTitleView = (TextView) extraBassHeader.getChildAt(0);
        extraBassSwitch = new Switch(this);
        extraBassSwitch.setText("");
        extraBassSwitch.setShowText(false);
        extraBassSwitch.setOnCheckedChangeListener(this::onExtraBassEnabledChanged);
        styleTopSwitch(extraBassSwitch, false);
        extraBassHeader.addView(extraBassSwitch, new LinearLayout.LayoutParams(dp(60), dp(30)));
        extraBassPanel.addView(extraBassHeader, blockParams(4));
        LinearLayout extraBassKnobs = createExtraKnobRow(extraBassPanel);
        extraBassKnobs.addView(createExtraBassControl("Cutoff", true), knobColumnParams());
        extraBassKnobs.addView(createExtraBassControl("Boost", false), knobColumnParams());
    }

    private LinearLayout createExtraPanelShell() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        return panel;
    }

    private LinearLayout createExtraHeaderRow(String titleText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        TextView title = new GlowTitleTextView(this);
        if (title instanceof GlowTitleTextView) {
            ((GlowTitleTextView) title).setAutoRegisterShimmer(false);
        }
        title.setText(titleText);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setPadding(dp(22), dp(5), dp(22), dp(5));
        // 标题视觉中心上移，与右侧复选框/开关中心点对齐
        title.setTranslationY(-dp(1));
        applyInactiveExtraSectionTitleStyle(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        // 抵消 gradientTitleView 的左 padding(22dp) 后再额外右移 2dp，让文字视觉左缘距 panel 内容区左边 2dp，
        // 使标题距 panel 左外边(dp18) 与右侧复选框背景距 panel 右外边(dp16) 视觉近似对称（标题侧多 2dp 留白）。
        // title view 左移进入 panel padding 区的 20dp 正好是空白 leftPadding，裁剪不影响文字与 shimmer。
        titleParams.leftMargin = -dp(20);
        row.addView(title, titleParams);
        return row;
    }

    private TextView createExtraChoiceButton() {
        TextView button = new TextView(this);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setGravity(android.view.Gravity.CENTER);
        button.setTextColor(Color.argb(210, 240, 244, 255));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(createFieldBackground(12, 35, 8));
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return button;
    }

    private void styleMonitorActionButton(TextView button, int minWidthDp) {
        if (button == null) {
            return;
        }
        button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        if (minWidthDp > 0) {
            button.setMinWidth(dp(minWidthDp));
            button.setMinimumWidth(dp(minWidthDp));
        }
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
    }

    private LinearLayout.LayoutParams extraPanelParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout createExtraKnobRow(LinearLayout panel) {
        LinearLayout knobs = new LinearLayout(this);
        knobs.setOrientation(LinearLayout.HORIZONTAL);
        knobs.setGravity(android.view.Gravity.CENTER);
        knobs.setClipChildren(false);
        knobs.setClipToPadding(false);
        LinearLayout.LayoutParams knobsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        knobsParams.topMargin = dp(10);
        panel.addView(knobs, knobsParams);
        return knobs;
    }

    // 旋钮列参数：等宽 weight=1，带水平间距让旋钮间有呼吸感
    private LinearLayout.LayoutParams knobColumnParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.leftMargin = dp(6);
        p.rightMargin = dp(6);
        return p;
    }


        // BassBoost 控件已迁移为 HorizontalBassSlider，此方法仅作占位避免调用方断裂
    private LinearLayout createExtraBassControl(String label, boolean cutoff) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        KnobView knob = new KnobView(this);
        if (cutoff) {
            cutoffKnob = knob;
            knob.configure(60, 250, editingPreset.extraBassCutoffHz, "Hz", value -> setEditingPreset(editingPreset.withExtraBassCutoffHz(value), true));
        } else {
            amountKnob = knob;
            knob.configure(0, 100, editingPreset.extraBassAmountPercent, "%", value ->
                    setEditingPreset(editingPreset.withExtraBassAmountPercent(value), true));
        }
        // 旋钮中间数字可点击：弹出数值输入对话框，写入新值
        knob.setTapListener(this::showStyledKnobInputDialog);
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(6);
        knobParams.bottomMargin = dp(6);
        column.addView(knob, knobParams);

        // 标签移到旋钮下方，视觉重心在旋钮弧形
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(13);
        title.setTextColor(Color.rgb(200, 210, 230));
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(2);
        column.addView(title, titleParams);
        return column;
    }

    private LinearLayout createReverbSlider(String label, int min, int max, int value, String suffix, IntChanged listener) {
        return createReverbSlider(label, min, max, value, suffix, 1f, 0, false, LINEAR_SLIDER_MAPPER, listener);
    }

    private LinearLayout createReverbSlider(String label, int min, int max, int value, String suffix, float displayScale, int displayDecimals, IntChanged listener) {
        return createReverbSlider(label, min, max, value, suffix, displayScale, displayDecimals, false, LINEAR_SLIDER_MAPPER, listener);
    }

    private LinearLayout createReverbSlider(String label, int min, int max, int value, String suffix, boolean negativeInfinityAtMin, IntChanged listener) {
        return createReverbSlider(label, min, max, value, suffix, 1f, 0, negativeInfinityAtMin, LINEAR_SLIDER_MAPPER, listener);
    }

    private LinearLayout createReverbSlider(String label, int min, int max, int value, String suffix, float displayScale, int displayDecimals, boolean negativeInfinityAtMin, IntChanged listener) {
        return createReverbSlider(label, min, max, value, suffix, displayScale, displayDecimals, negativeInfinityAtMin, LINEAR_SLIDER_MAPPER, listener);
    }

    private LinearLayout createReverbSlider(String label,
                                            int min,
                                            int max,
                                            int value,
                                            String suffix,
                                            float displayScale,
                                            int displayDecimals,
                                            boolean negativeInfinityAtMin,
                                            SliderValueMapper mapper,
                                            IntChanged listener) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        VerticalReverbSlider slider = new VerticalReverbSlider(this);
        slider.configure(label, min, max, value, suffix, displayScale, displayDecimals, negativeInfinityAtMin,
                mapper == null ? LINEAR_SLIDER_MAPPER : mapper, listener::onChanged);
        // 强制方形：弧形填满 view，标题紧贴弧形下方
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(4);
        knobParams.bottomMargin = dp(2);
        column.addView(slider, knobParams);

        if ("Main".equals(label)) {
            reverbMainSlider = slider;
        } else if ("Decay".equals(label)) {
            reverbDecaySlider = slider;
        } else if ("Predelay".equals(label)) {
            reverbPredelaySlider = slider;
        } else if ("Size".equals(label)) {
            reverbSizeSlider = slider;
        } else {
            reverbMixSlider = slider;
        }

        // 标签紧贴旋钮下方
        return column;
    }

    // 旋钮数字点击：弹出数值输入对话框，确认后写入 knob（触发 listener 链路）
    private void showKnobInputDialog(KnobView knob) {
        if (knob == null) return;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.valueOf(knob.getValue()));
        input.setHint(knob.getMin() + " ~ " + knob.getMax() + knob.getSuffix());
        input.setSelectAllOnFocus(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setGravity(android.view.Gravity.CENTER);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("数值输入")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int v = Math.round(Float.parseFloat(input.getText().toString()));
                        knob.setValue(clamp(v, knob.getMin(), knob.getMax()), true);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(d -> {
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dlg.show();
    }

    private void showStyledKnobInputDialog(KnobView knob) {
        if (knob == null) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Range: " + knob.getMin() + " - " + knob.getMax() + knob.getSuffix());
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(150, 165, 185));
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(10));
        content.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.valueOf(knob.getValue()));
        input.setHint(knob.getMin() + " ~ " + knob.getMax() + knob.getSuffix());
        input.setSelectAllOnFocus(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setGravity(android.view.Gravity.CENTER);
        input.setTextSize(18);
        input.setTextColor(Color.rgb(235, 245, 255));
        input.setHintTextColor(Color.rgb(120, 135, 155));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(createFieldBackground(26, 90, 10));
        content.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView("Value Input"))
                .setView(content)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        int v = Math.round(Float.parseFloat(input.getText().toString()));
                        knob.setValue(clamp(v, knob.getMin(), knob.getMax()), true);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();
        styleDialog(dlg);
        uiHandler.post(() -> {
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
    }

    private void showStyledReverbSliderInputDialog(VerticalReverbSlider slider) {
        if (slider == null) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(8));

        TextView hint = new TextView(this);
        hint.setText(slider.getLabel() + "  " + slider.displayRangeText());
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(150, 165, 185));
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(10));
        content.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(slider.displayValueText());
        input.setHint(slider.displayRangeHint());
        input.setSelectAllOnFocus(true);
        input.setInputType(slider.acceptsNegativeInfinity()
                ? android.text.InputType.TYPE_CLASS_TEXT
                : android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setGravity(android.view.Gravity.CENTER);
        input.setTextSize(18);
        input.setTextColor(Color.rgb(235, 245, 255));
        input.setHintTextColor(Color.rgb(120, 135, 155));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(createFieldBackground(26, 90, 10));
        content.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView("Value Input"))
                .setView(content)
                .setPositiveButton("Apply", (d, w) -> {
                    try {
                        slider.setValue(slider.rawValueFromText(input.getText().toString()), true);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();
        styleDialog(dlg);
        uiHandler.post(() -> {
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
    }

    private int reverbTypeIndex(String type) {
        for (int i = 0; i < REVERB_TYPE_LABELS.length; i++) {
            if (REVERB_TYPE_LABELS[i].equals(type)) {
                return i;
            }
        }
        return 0;
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 100);
        }
    }

    private void addHeaderCell(LinearLayout row, String text, float weight) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(Color.rgb(142, 154, 168));
        view.setGravity(android.view.Gravity.CENTER);
        row.addView(view, cellParams(weight, 18));
    }

    private Button createCurveButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        configureCenteredMarquee(button);
        styleCurveButton(button);
        return button;
    }

    private void configureCenteredMarquee(TextView view) {
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        view.setMarqueeRepeatLimit(-1);
        view.setSelected(true);
        view.setHorizontallyScrolling(true);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
    }

    private void normalizeSpinnerSurface(Spinner spinner) {
        spinner.setPadding(0, 0, 0, 0);
        spinner.setMinimumWidth(0);
        spinner.setMinimumHeight(0);
        spinner.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            spinner.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
    }

    private void styleCurveButton(TextView button) {
        button.setBackground(curveControlBackground());
        button.setTextColor(Color.rgb(232, 248, 246));
    }

    private Drawable curveControlBackground() {
        return verticalGradientStrokeGlowDrawable(
                Color.argb(232, 21, 34, 43),
                Color.argb(232, 12, 18, 28),
                Color.argb(150, 76, 220, 205),
                dp(7),
                dp(2),
                Color.argb(70, 0, 245, 212)
        );
    }

    private String shortCurveLabel(String name, float offsetDb) {
        if (name == null || name.trim().isEmpty()) {
            name = "Default";
        }
        String cleaned = name.trim();
        String label = cleaned.length() <= 7 ? cleaned : cleaned.substring(0, 6) + ".";
        if (Math.abs(offsetDb) < 0.05f) {
            return label;
        }
        return label + " " + String.format(Locale.US, "%+.1f", offsetDb);
    }

    private View circleButton(boolean isDanger) {
        View button = new View(this);
        button.setFocusable(true);
        button.setClickable(true);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);

        if (isDanger) {
            background.setColor(Color.argb(30, 255, 100, 100));
            background.setStroke(Math.round(dpf(1f)), Color.argb(100, 255, 100, 100));
        } else {
            background.setColor(Color.argb(20, 255, 255, 255));
            background.setStroke(Math.round(dpf(1f)), Color.argb(60, 255, 255, 255));
        }
        button.setBackground(background);

        button.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    break;
            }
            return false;
        });

        return button;
    }

    private float dpf(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private Drawable stateIndicatorDrawable(boolean active) {
        return new Drawable() {
            private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) {
                    return;
                }
                float cx = b.exactCenterX();
                float cy = b.exactCenterY();
                
                float strokeWidth = dpf(1.2f);
                float radius = Math.min(b.width(), b.height()) / 2f - strokeWidth - dpf(0.8f);

                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setShader(null);
                fillPaint.clearShadowLayer();
                fillPaint.setColor(active
                        ? Color.argb(238, 14, 20, 28)
                        : Color.argb(220, 14, 18, 26));
                canvas.drawCircle(cx, cy, radius * 0.98f, fillPaint);

                if (active) {
                    dotPaint.setStyle(Paint.Style.FILL);
                    dotPaint.setShader(new RadialGradient(
                            cx, cy, radius * 1.12f,
                            new int[]{
                                    Color.argb(54, 0, 245, 212),
                                    Color.argb(18, 0, 180, 255),
                                    Color.TRANSPARENT
                            },
                            new float[]{0f, 0.64f, 1f},
                            Shader.TileMode.CLAMP
                    ));
                    dotPaint.clearShadowLayer();
                    canvas.drawCircle(cx, cy, radius * 1.12f, dotPaint);
                    dotPaint.setShader(null);
                }

                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(strokeWidth);
                ringPaint.clearShadowLayer();
                // 圆形 blur 光晕：BlurMaskFilter 高斯模糊产生平滑圆形光晕，抗锯齿
                // 先画一层粗描边光晕底（大半径 FILL 圆 + BlurMaskFilter），再画 ring 描边
                float glowRadius = radius + dpf(0.9f);
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(dpf(2.1f));
                dotPaint.setShader(null);
                if (active) {
                    dotPaint.setColor(Color.argb(120, 0, 245, 212));
                    dotPaint.setMaskFilter(new BlurMaskFilter(dpf(2f), BlurMaskFilter.Blur.NORMAL));
                } else {
                    dotPaint.setColor(Color.argb(36, 120, 180, 220));
                    dotPaint.setMaskFilter(new BlurMaskFilter(dpf(1.4f), BlurMaskFilter.Blur.NORMAL));
                }
                canvas.drawCircle(cx, cy, glowRadius, dotPaint);
                dotPaint.setMaskFilter(null);

                ringPaint.setColor(active ? Color.argb(220, 0, 245, 212) : Color.argb(72, 255, 255, 255));
                canvas.drawCircle(cx, cy, radius, ringPaint);

                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setShader(null);
                if (active) {
                    dotPaint.setColor(Color.rgb(0, 245, 212));
                    canvas.drawCircle(cx, cy, radius * 0.45f, dotPaint);
                } else {
                    dotPaint.setColor(Color.argb(72, 255, 255, 255));
                    canvas.drawCircle(cx, cy, radius * 0.30f, dotPaint);
                }
            }

            @Override public void setAlpha(int alpha) {
                ringPaint.setAlpha(alpha);
                dotPaint.setAlpha(alpha);
            }

            @Override public void setColorFilter(ColorFilter colorFilter) {
                ringPaint.setColorFilter(colorFilter);
                dotPaint.setColorFilter(colorFilter);
            }

            @Override
            @SuppressWarnings("deprecation")
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private Drawable deleteSymbolDrawable(boolean enabled) {
        return new Drawable() {
            private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) {
                    return;
                }
                float cx = b.exactCenterX();
                float cy = b.exactCenterY();
                
                float strokeWidth = dpf(1.2f);
                float radius = Math.min(b.width(), b.height()) / 2f - strokeWidth - dpf(0.8f);

                ringPaint.setStyle(Paint.Style.FILL);
                ringPaint.clearShadowLayer();
                ringPaint.setColor(Color.argb(enabled ? 35 : 12, 255, 100, 100));
                canvas.drawCircle(cx, cy, radius, ringPaint);

                // 圆形 blur 光晕：BlurMaskFilter 高斯模糊产生平滑圆形光晕，抗锯齿
                float glowRadius = radius + dpf(0.9f);
                crossPaint.setStyle(Paint.Style.STROKE);
                crossPaint.setStrokeWidth(dpf(2.1f));
                crossPaint.setShader(null);
                if (enabled) {
                    crossPaint.setColor(Color.argb(120, 255, 90, 90));
                    crossPaint.setMaskFilter(new BlurMaskFilter(dpf(2f), BlurMaskFilter.Blur.NORMAL));
                } else {
                    crossPaint.setColor(Color.argb(34, 255, 90, 90));
                    crossPaint.setMaskFilter(new BlurMaskFilter(dpf(1.4f), BlurMaskFilter.Blur.NORMAL));
                }
                canvas.drawCircle(cx, cy, glowRadius, crossPaint);
                crossPaint.setMaskFilter(null);

                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(strokeWidth);
                ringPaint.setColor(Color.argb(enabled ? 160 : 50, 255, 100, 100));
                canvas.drawCircle(cx, cy, radius, ringPaint);

                crossPaint.setStyle(Paint.Style.STROKE);
                crossPaint.setStrokeWidth(dpf(1.25f));
                crossPaint.setColor(enabled ? Color.rgb(255, 130, 130) : Color.argb(90, 255, 130, 130));
                crossPaint.setStrokeCap(Paint.Cap.ROUND);
                
                float arm = radius * 0.35f;
                canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint);
                canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, crossPaint);
            }

            @Override public void setAlpha(int alpha) {
                ringPaint.setAlpha(alpha);
                crossPaint.setAlpha(alpha);
            }

            @Override public void setColorFilter(ColorFilter colorFilter) {
                ringPaint.setColorFilter(colorFilter);
                crossPaint.setColorFilter(colorFilter);
            }

            @Override
            @SuppressWarnings("deprecation")
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private LinearLayout.LayoutParams cellParams(float weight) {
        return cellParams(weight, 32);
    }

    private LinearLayout.LayoutParams cellParams(float weight, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp), weight);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        params.gravity = android.view.Gravity.CENTER_VERTICAL;
        return params;
    }

    private View wrapCircularButton(View button, float weight, int sizeDp) {
        return wrapCircularButton(button, weight, sizeDp, 2, 2);
    }

    private View wrapCircularButton(View button, float weight, int sizeDp, int leftDp, int rightDp) {
        return wrapCircularButton(button, weight, sizeDp, leftDp, rightDp, 0);
    }

    private View wrapCircularButton(View button, float weight, int sizeDp, int leftDp, int rightDp, int translationXDp) {
        FrameLayout container = new FrameLayout(this);
        // 关键修复：关闭该容器的子视图裁剪，否则内部圆形按钮画出的高阶模糊霓虹光晕阴影会在 22dp 或 24dp 边框外边缘直接切平，看起来十分不美观。
        container.setClipChildren(false);
        container.setClipToPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.gravity = android.view.Gravity.CENTER;
        button.setTranslationX(dp(translationXDp));
        container.addView(button, lp);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(32), weight);
        cp.gravity = android.view.Gravity.CENTER_VERTICAL;
        cp.leftMargin = dp(leftDp);
        cp.rightMargin = dp(rightDp);
        container.setLayoutParams(cp);
        return container;
    }

    private LinearLayout.LayoutParams blockParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private String formatDecimal(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void closeKeyboard(View view) {
        suppressEqOverlayHideOnKeyboardDismiss = false;
        hideEqEditOverlay();
        dismissKeyboard(view, true);
    }

    private void dismissKeyboard(View view, boolean clearFocus) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        View tokenView = focused != null ? focused : view;
        if (manager != null) {
            uiHandler.post(() -> manager.hideSoftInputFromWindow(tokenView.getWindowToken(), 0));
        }
        if (!clearFocus) {
            return;
        }
        if (focused != null) {
            focused.clearFocus();
        } else if (view != null) {
            view.clearFocus();
        }
    }

    private void clearEditingFocusAfterKeyboardDismiss() {
        View focused = getCurrentFocus();
        if (focused instanceof EditText) {
            focused.clearFocus();
        }
    }

    private boolean shouldDismissKeyboardOnTouch(View focused, MotionEvent event) {
        if (!(focused instanceof EditText) || event == null) {
            return false;
        }
        if (activeEqEditOverlay != null) {
            return false;
        }
        return isTouchOutsideView(focused, event);
    }

    private boolean isTouchOutsideView(View view, MotionEvent event) {
        if (view == null || event == null) {
            return false;
        }
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds)) {
            return false;
        }
        int rawX = Math.round(event.getRawX());
        int rawY = Math.round(event.getRawY());
        return !bounds.contains(rawX, rawY);
    }

    private interface FloatChanged {
        void onChanged(float value);
    }

    private interface SliderValueMapper {
        float valueToFraction(int value, int min, int max);
        int fractionToValue(float fraction, int min, int max);
    }

    private interface ChoiceCallback {
        void onChoice(int position);
    }

    private interface CurveMenuSelected {
        void onSelected(int which);
    }

    private interface CurveMenuCanDelete {
        boolean canDelete(String name);
    }

    private interface CurveMenuDeleted {
        void onDeleted(String name);
    }

    private interface IntChanged {
        void onChanged(int value);
    }

    private final class GeqSliderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF thumbRect = new android.graphics.RectF();
        private String frequencyLabel = "";
        private int gainMb;
        private IntChanged listener;
        private float touchStartX;
        private float touchStartY;
        private boolean adjustingGain;

        GeqSliderView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setBand(String frequencyLabel, int gainMb, IntChanged listener) {
            this.frequencyLabel = frequencyLabel == null ? "" : frequencyLabel;
            this.gainMb = clamp(gainMb, -1800, 1800);
            this.listener = listener;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float centerX = width / 2f;
            float trackTop = dpf(10f);
            float trackBottom = height - dpf(58f);
            
            float thumbCenterY = gainToY(trackTop, trackBottom);

            // 1. Draw horizontal tick marks/guidelines for dB reference levels
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            
            float centerTop = trackTop + dpf(29f);
            float centerBottom = trackBottom - dpf(29f);
            float zeroY = (centerTop + centerBottom) / 2f;
            float step = (centerBottom - centerTop) / 6f; // +18, +12, +6, 0, -6, -12, -18 dB
            boolean hasActiveGain = hasActiveGain();

            for (int j = 0; j <= 6; j++) {
                float tickY = centerTop + j * step;
                if (j == 3) {
                    // 0 dB center guideline
                    paint.setStrokeWidth(dpf(1.5f));
                    paint.setColor(Color.argb(70, 255, 255, 255));
                    canvas.drawLine(centerX - dpf(12f), tickY, centerX + dpf(12f), tickY, paint);
                } else {
                    // Other dB reference tick lines
                    paint.setStrokeWidth(dpf(1f));
                    paint.setColor(Color.argb(20, 255, 255, 255));
                    canvas.drawLine(centerX - dpf(6f), tickY, centerX + dpf(6f), tickY, paint);
                }
            }

            // 2. Draw track background capsule path
            android.graphics.RectF trackRect = new android.graphics.RectF(
                centerX - dpf(2f), 
                trackTop, 
                centerX + dpf(2f), 
                trackBottom
            );
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(25, 255, 255, 255));
            canvas.drawRoundRect(trackRect, dpf(2f), dpf(2f), paint);

            // 3. Draw active neon glowing track from 0 dB Y to current thumb center Y
            if (hasActiveGain) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(4f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.rgb(0, 245, 212)); // Neon Cyan
                paint.setShadowLayer(dpf(6f), 0, 0, Color.argb(180, 0, 245, 212)); // Neon Glow
                canvas.drawLine(centerX, zeroY, centerX, thumbCenterY, paint);
                paint.clearShadowLayer();
            }

            // 4. Draw sleek capsule fader thumb
            float thumbW = dpf(20f);
            float thumbH = dpf(30f);
            thumbRect.set(
                centerX - thumbW / 2f,
                thumbCenterY - thumbH / 2f,
                centerX + thumbW / 2f,
                thumbCenterY + thumbH / 2f
            );
            
            // Fader background
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(240, 22, 26, 38));
            canvas.drawRoundRect(thumbRect, dpf(6f), dpf(6f), paint);

            // Fader outline stroke (glows cyan when touched)
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(adjustingGain || hasActiveGain ? dpf(1.6f) : dpf(1.0f));
            paint.setColor(adjustingGain || hasActiveGain ? Color.rgb(0, 245, 212) : Color.argb(100, 255, 255, 255));
            if (adjustingGain || hasActiveGain) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(150, 0, 245, 212));
            }
            canvas.drawRoundRect(thumbRect, dpf(6f), dpf(6f), paint);
            paint.clearShadowLayer();

            // Draw glowing cyan led indicator slit on the fader center
            float indW = dpf(10f);
            float indH = dpf(3f);
            android.graphics.RectF indRect = new android.graphics.RectF(
                centerX - indW / 2f,
                thumbCenterY - indH / 2f,
                centerX + indW / 2f,
                thumbCenterY + indH / 2f
            );
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(0, 245, 212));
            if (hasActiveGain) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(220, 0, 245, 212));
            }
            canvas.drawRoundRect(indRect, dpf(1.5f), dpf(1.5f), paint);
            paint.clearShadowLayer();

            // 5. Draw text labels (Frequency and Gain)
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(dpf(13f));
            paint.setColor(Color.WHITE);
            canvas.drawText(frequencyLabel, centerX, height - dpf(24f), paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(dpf(11f));
            if (hasActiveGain) {
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setFakeBoldText(true);
                paint.setShadowLayer(dpf(5f), 0, 0, Color.argb(150, 0, 245, 212));
            } else {
                paint.setColor(Color.argb(130, 255, 255, 255));
            }
            canvas.drawText(formatDecimal(gainMb / 100f), centerX, height - dpf(6f), paint);
            paint.clearShadowLayer();
            paint.setFakeBoldText(false);
        }

        private boolean hasActiveGain() {
            return gainMb != 0;
        }

        private long lastTapTime = 0;
        private float lastTapX = 0;
        private float lastTapY = 0;

        private void showManualGainInputDialog() {
            android.content.Context context = getContext();
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(20), dp(16), dp(20), dp(8));

            TextView label = new TextView(context);
            label.setText("Enter gain in dB for " + frequencyLabel + " (-18.00 to 18.00):");
            label.setTextSize(13);
            label.setTextColor(Color.rgb(142, 154, 168));
            label.setPadding(dp(2), 0, dp(2), dp(8));
            layout.addView(label);

            EditText input = new EditText(context);
            input.setSingleLine(true);
            input.setText(formatDecimal(gainMb / 100f));
            input.setTextSize(14);
            input.setSelectAllOnFocus(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            
            GradientDrawable inputBg = new GradientDrawable();
            inputBg.setShape(GradientDrawable.RECTANGLE);
            inputBg.setColor(Color.argb(20, 255, 255, 255));
            inputBg.setStroke(dp(1), Color.argb(40, 255, 255, 255));
            inputBg.setCornerRadius(dp(6));
            input.setBackground(inputBg);
            input.setTextColor(Color.WHITE);
            input.setPadding(dp(10), dp(8), dp(10), dp(8));
            input.setGravity(android.view.Gravity.CENTER);
            
            layout.addView(input);

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setCustomTitle(dialogTitleView("Edit Gain"))
                    .setView(layout)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("OK", (d, which) -> {
                        try {
                            String text = input.getText().toString();
                            float value = Float.parseFloat(text);
                            int nextGainMb = Math.round(value * 100f);
                            nextGainMb = clamp(nextGainMb, -1800, 1800);
                            gainMb = nextGainMb;
                            invalidate();
                            if (listener != null) {
                                listener.onChanged(gainMb);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    })
                    .create();
            dialog.show();
            styleDialog(dialog);
            
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (!supported) {
                return false;
            }
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    adjustingGain = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - touchStartX);
                    float dy = Math.abs(event.getY() - touchStartY);
                    if (!adjustingGain && dx > dpf(10f) && dx > dy) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                    if (adjustingGain || (dy > dpf(10f) && dy >= dx * 1.25f)) {
                        adjustingGain = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        updateFromTouch(event.getY());
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (adjustingGain) {
                        updateFromTouch(event.getY());
                    } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        float endX = event.getX();
                        float endY = event.getY();
                        float totalDist = (float) Math.hypot(endX - touchStartX, endY - touchStartY);
                        
                        if (totalDist < dpf(15f)) {
                            long now = android.os.SystemClock.uptimeMillis();
                            float tapDist = (float) Math.hypot(endX - lastTapX, endY - lastTapY);
                            
                            if (now - lastTapTime < 300 && tapDist < dpf(30f)) {
                                gainMb = 0;
                                invalidate();
                                if (listener != null) {
                                    listener.onChanged(gainMb);
                                }
                                lastTapTime = 0;
                            } else {
                                if (endY >= getHeight() - dpf(30f)) {
                                    showManualGainInputDialog();
                                }
                                lastTapTime = now;
                                lastTapX = endX;
                                lastTapY = endY;
                            }
                        }
                    }
                    adjustingGain = false;
                    return true;
                default:
                    return true;
            }
        }

        private void updateFromTouch(float y) {
            float trackTop = dpf(10f);
            float trackBottom = getHeight() - dpf(58f);
            float centerTop = trackTop + dpf(29f);
            float centerBottom = trackBottom - dpf(29f);
            float t = (centerBottom - clampFloat(y, centerTop, centerBottom)) / (centerBottom - centerTop);
            int next = Math.round((-1800 + 3600 * t) / 10f) * 10;
            next = clamp(next, -1800, 1800);
            if (next == gainMb) {
                return;
            }
            gainMb = next;
            invalidate();
            if (listener != null) {
                listener.onChanged(gainMb);
            }
        }

        private float gainToY(float trackTop, float trackBottom) {
            float centerTop = trackTop + dpf(29f);
            float centerBottom = trackBottom - dpf(29f);
            float t = (gainMb + 1800) / 3600f;
            return centerBottom - (centerBottom - centerTop) * t;
        }

        private float clampFloat(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /**
     * BassBoost 横向推子：水平轨道 + 左右滑动的 thumb。
     * 视觉风格与 GeqSliderView 一致（neon 发光、胶囊 thumb、LED 指示），
     * 但方向为水平，用于节省垂直空间。
     */
    private final class VerticalReverbSlider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF thumbRect = new android.graphics.RectF();
        private int min;
        private int max;
        private int value;
        private float displayScale = 1f;
        private int displayDecimals;
        private boolean negativeInfinityAtMin;
        private int resetValue;
        private String suffix = "";
        private String label = "";
        private KnobView.Listener listener;
        private SliderValueMapper mapper = LINEAR_SLIDER_MAPPER;
        private float touchStartX;
        private float touchStartY;
        private boolean adjusting;
        private long lastTapTime;
        private float lastTapX;
        private float lastTapY;

        VerticalReverbSlider(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void configure(String label, int min, int max, int value, String suffix, KnobView.Listener listener) {
            configure(label, min, max, value, suffix, 1f, 0, false, LINEAR_SLIDER_MAPPER, listener);
        }

        void configure(String label, int min, int max, int value, String suffix, float displayScale, int displayDecimals, KnobView.Listener listener) {
            configure(label, min, max, value, suffix, displayScale, displayDecimals, false, LINEAR_SLIDER_MAPPER, listener);
        }

        void configure(String label, int min, int max, int value, String suffix, float displayScale, int displayDecimals, boolean negativeInfinityAtMin, KnobView.Listener listener) {
            configure(label, min, max, value, suffix, displayScale, displayDecimals, negativeInfinityAtMin, LINEAR_SLIDER_MAPPER, listener);
        }

        void configure(String label, int min, int max, int value, String suffix, float displayScale, int displayDecimals, boolean negativeInfinityAtMin, SliderValueMapper mapper, KnobView.Listener listener) {
            this.label = label == null ? "" : label;
            this.min = min;
            this.max = max;
            this.suffix = suffix == null ? "" : suffix;
            this.displayScale = Math.abs(displayScale) < 0.0001f ? 1f : displayScale;
            this.displayDecimals = Math.max(0, displayDecimals);
            this.negativeInfinityAtMin = negativeInfinityAtMin;
            this.resetValue = clamp(negativeInfinityAtMin ? 0 : min, min, max);
            this.mapper = mapper == null ? LINEAR_SLIDER_MAPPER : mapper;
            this.listener = listener;
            setValue(value, false);
        }

        int getMin() { return min; }
        int getMax() { return max; }
        int getValue() { return value; }
        String getSuffix() { return suffix; }
        String getLabel() { return label; }
        boolean acceptsNegativeInfinity() { return negativeInfinityAtMin; }
        String displayValueText() { return formatDisplayValue(value); }
        String displayRangeText() { return formatDisplayNumber(min) + " - " + formatDisplayNumber(max) + suffix; }
        String displayRangeHint() { return formatDisplayNumber(min) + " ~ " + formatDisplayNumber(max) + suffix; }

        int rawValueFromDisplay(float displayValue) {
            float scaled = displayValue / displayScale;
            return clamp(Math.round(scaled), min, max);
        }

        int rawValueFromText(String text) {
            String normalized = text == null ? "" : text.trim();
            if (negativeInfinityAtMin) {
                String lower = normalized.toLowerCase(Locale.US);
                if (lower.isEmpty() || "-inf".equals(lower) || "inf".equals(lower) || "off".equals(lower) || "mute".equals(lower)) {
                    return min;
                }
            }
            String compact = normalized;
            if (!suffix.isEmpty() && compact.toLowerCase(Locale.US).endsWith(suffix.toLowerCase(Locale.US))) {
                compact = compact.substring(0, compact.length() - suffix.length()).trim();
            }
            return rawValueFromDisplay(Float.parseFloat(compact));
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

        private boolean isActive() {
            return value != min;
        }

        private float trackTop() {
            return dpf(26f);
        }

        private float trackBottom() {
            return getHeight() - dpf(28f);
        }

        private float trackCenterX() {
            return getWidth() / 2f;
        }

        private float valueToY() {
            float t = Math.max(0f, Math.min(1f, mapper.valueToFraction(value, min, max)));
            return trackBottom() - (trackBottom() - trackTop()) * t;
        }

        private int yToValue(float y) {
            float t = (trackBottom() - clampFloat(y, trackTop(), trackBottom()))
                    / Math.max(1f, trackBottom() - trackTop());
            return mapper.fractionToValue(t, min, max);
        }

        private void commitCurrentValue() {
            if (listener != null) {
                listener.onValueChanged(value);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            float cx = trackCenterX();
            float top = trackTop();
            float bottom = trackBottom();
            float thumbY = valueToY();
            boolean active = isActive();

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dpf(12f));
            if (active) {
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setShadowLayer(dpf(4f), 0, 0, Color.argb(150, 0, 245, 212));
            } else {
                paint.setColor(Color.argb(150, 255, 255, 255));
            }
            canvas.drawText(displayValueText(), cx, dpf(14f), paint);
            paint.clearShadowLayer();

            android.graphics.RectF trackRect = new android.graphics.RectF(
                    cx - dpf(2f), top, cx + dpf(2f), bottom);
            paint.setColor(Color.argb(25, 255, 255, 255));
            canvas.drawRoundRect(trackRect, dpf(2f), dpf(2f), paint);

            if (active) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(4f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setShadowLayer(dpf(6f), 0, 0, Color.argb(180, 0, 245, 212));
                canvas.drawLine(cx, bottom, cx, thumbY, paint);
                paint.clearShadowLayer();
            }

            float thumbW = dpf(28f);
            float thumbH = dpf(18f);
            thumbRect.set(cx - thumbW / 2f, thumbY - thumbH / 2f,
                    cx + thumbW / 2f, thumbY + thumbH / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(240, 22, 26, 38));
            canvas.drawRoundRect(thumbRect, dpf(5f), dpf(5f), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(adjusting || active ? dpf(1.6f) : dpf(1.0f));
            paint.setColor(adjusting || active ? Color.rgb(0, 245, 212) : Color.argb(100, 255, 255, 255));
            if (adjusting || active) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(150, 0, 245, 212));
            }
            canvas.drawRoundRect(thumbRect, dpf(5f), dpf(5f), paint);
            paint.clearShadowLayer();

            float indW = dpf(10f);
            float indH = dpf(3f);
            android.graphics.RectF indRect = new android.graphics.RectF(
                    cx - indW / 2f, thumbY - indH / 2f,
                    cx + indW / 2f, thumbY + indH / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(0, 245, 212));
            if (active) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(220, 0, 245, 212));
            }
            canvas.drawRoundRect(indRect, dpf(1.5f), dpf(1.5f), paint);
            paint.clearShadowLayer();

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(false);
            paint.setTextSize(dpf(11f));
            paint.setColor(Color.rgb(200, 210, 230));
            canvas.drawText(label, width / 2f, getHeight() - dpf(8f), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    adjusting = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - touchStartX);
                    float dy = Math.abs(event.getY() - touchStartY);
                    if (!adjusting && dy > dpf(10f) && dy >= dx) {
                        adjusting = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (adjusting) {
                        setValue(yToValue(event.getY()), false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (adjusting) {
                        setValue(yToValue(event.getY()), false);
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            commitCurrentValue();
                        }
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        float endX = event.getX();
                        float endY = event.getY();
                        float totalDist = (float) Math.hypot(endX - touchStartX, endY - touchStartY);
                        if (totalDist < dpf(15f)) {
                            long now = android.os.SystemClock.uptimeMillis();
                            float tapDist = (float) Math.hypot(endX - lastTapX, endY - lastTapY);
                            if (now - lastTapTime < 300 && tapDist < dpf(30f)) {
                                setValue(resetValue, false);
                                commitCurrentValue();
                                lastTapTime = 0L;
                            } else if (endY <= dpf(24f) || endY >= getHeight() - dpf(22f)) {
                                lastTapTime = now;
                                lastTapX = endX;
                                lastTapY = endY;
                                showStyledReverbSliderInputDialog(this);
                            } else {
                                lastTapTime = now;
                                lastTapX = endX;
                                lastTapY = endY;
                            }
                        } else if (event.getY() <= dpf(24f) || event.getY() >= getHeight() - dpf(22f)) {
                            showStyledReverbSliderInputDialog(this);
                        }
                    }
                    adjusting = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float clampFloat(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private String formatDisplayValue(int rawValue) {
            if (negativeInfinityAtMin && rawValue <= min) {
                return "-inf";
            }
            return formatDisplayNumber(rawValue) + suffix;
        }

        private String formatDisplayNumber(int rawValue) {
            if (negativeInfinityAtMin && rawValue <= min) {
                return "-inf";
            }
            float displayValue = rawValue * displayScale;
            if (displayDecimals <= 0) {
                return String.valueOf(Math.round(displayValue));
            }
            return String.format(Locale.US, "%." + displayDecimals + "f", displayValue);
        }
    }

    private final class HorizontalBassSlider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF thumbRect = new android.graphics.RectF();
        private int min;
        private int max;
        private int value;
        private String suffix = "";
        private String label = "";
        private KnobView.Listener listener;
        private float touchStartX;
        private float touchStartY;
        private boolean adjusting;

        HorizontalBassSlider(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void configure(int min, int max, int value, String suffix, String label, KnobView.Listener listener) {
            this.min = min;
            this.max = max;
            this.suffix = suffix == null ? "" : suffix;
            this.label = label == null ? "" : label;
            this.listener = listener;
            setValue(value, false);
        }

        private void commitCurrentValue() {
            if (listener != null) {
                listener.onValueChanged(value);
            }
        }

        void setLabel(String label) {
            this.label = label == null ? "" : label;
            invalidate();
        }

        int getValue() {
            return value;
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

        private boolean isActive() {
            return value != min;
        }

        private float trackLeft() {
            return dpf(24f);
        }

        private float trackRight() {
            return getWidth() - dpf(24f);
        }

        private float trackCenterY() {
            return (getHeight() - dpf(30f)) / 2f;
        }

        private float valueToX() {
            float t = (value - min) / Math.max(1f, max - min);
            return trackLeft() + (trackRight() - trackLeft()) * t;
        }

        private int xToValue(float x) {
            float t = (clampFloat(x, trackLeft(), trackRight()) - trackLeft())
                    / Math.max(1f, trackRight() - trackLeft());
            return Math.round(min + (max - min) * t);
        }

        boolean isSwipeHandleHit(float rawX, float rawY) {
            int[] location = new int[2];
            getLocationOnScreen(location);
            float localX = rawX - location[0];
            float localY = rawY - location[1];
            float cy = trackCenterY();
            float touchTop = cy - dpf(22f);
            float touchBottom = cy + dpf(22f);
            float touchLeft = trackLeft() - dpf(12f);
            float touchRight = trackRight() + dpf(12f);
            return localX >= touchLeft && localX <= touchRight
                    && localY >= touchTop && localY <= touchBottom;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float left = trackLeft();
            float right = trackRight();
            float cy = trackCenterY();
            float thumbX = valueToX();
            boolean active = isActive();

            // 1. 轨道背景胶囊
            android.graphics.RectF trackRect = new android.graphics.RectF(
                    left, cy - dpf(2f), right, cy + dpf(2f));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(25, 255, 255, 255));
            canvas.drawRoundRect(trackRect, dpf(2f), dpf(2f), paint);

            // 2. 活动段发光（从 trackLeft 到 thumb）
            if (active) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(4f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setShadowLayer(dpf(6f), 0, 0, Color.argb(180, 0, 245, 212));
                canvas.drawLine(left, cy, thumbX, cy, paint);
                paint.clearShadowLayer();
            }

            // 3. thumb 胶囊
            float thumbW = dpf(18f);
            float thumbH = dpf(28f);
            thumbRect.set(thumbX - thumbW / 2f, cy - thumbH / 2f,
                    thumbX + thumbW / 2f, cy + thumbH / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(240, 22, 26, 38));
            canvas.drawRoundRect(thumbRect, dpf(5f), dpf(5f), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(adjusting || active ? dpf(1.6f) : dpf(1.0f));
            paint.setColor(adjusting || active ? Color.rgb(0, 245, 212) : Color.argb(100, 255, 255, 255));
            if (adjusting || active) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(150, 0, 245, 212));
            }
            canvas.drawRoundRect(thumbRect, dpf(5f), dpf(5f), paint);
            paint.clearShadowLayer();

            // 4. thumb 中心 LED 指示
            float indW = dpf(3f);
            float indH = dpf(10f);
            android.graphics.RectF indRect = new android.graphics.RectF(
                    thumbX - indW / 2f, cy - indH / 2f,
                    thumbX + indW / 2f, cy + indH / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(0, 245, 212));
            if (active) {
                paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(220, 0, 245, 212));
            }
            canvas.drawRoundRect(indRect, dpf(1.5f), dpf(1.5f), paint);
            paint.clearShadowLayer();

            // 5. 标签与数值
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(true);
            paint.setTextSize(dpf(13f));
            paint.setColor(Color.WHITE);
            canvas.drawText(label, dpf(4f), height - dpf(8f), paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setFakeBoldText(false);
            paint.setTextSize(dpf(13f));
            if (active) {
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setShadowLayer(dpf(4f), 0, 0, Color.argb(150, 0, 245, 212));
            } else {
                paint.setColor(Color.argb(150, 255, 255, 255));
            }
            canvas.drawText(value + suffix, width - dpf(4f), height - dpf(8f), paint);
            paint.clearShadowLayer();
            paint.setFakeBoldText(false);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (!isEnabled()) {
                return false;
            }
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                    adjusting = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - touchStartX);
                    float dy = Math.abs(event.getY() - touchStartY);
                    if (!adjusting && dx > dpf(10f) && dx > dy) {
                        adjusting = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (adjusting) {
                        setValue(xToValue(event.getX()), false);
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            commitCurrentValue();
                        }
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (adjusting) {
                        setValue(xToValue(event.getX()), false);
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            commitCurrentValue();
                        }
                    } else {
                        // tap thumb 区域直接跳转
                        float thumbX = valueToX();
                        if (Math.abs(event.getX() - thumbX) < dpf(20f)) {
                            setValue(xToValue(event.getX()), false);
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                commitCurrentValue();
                            }
                        }
                    }
                    adjusting = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float clampFloat(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private class SmallSpinnerAdapter extends ArrayAdapter<String> {
        private final boolean cyanGlowText;
        private int selectedPosition = AdapterView.INVALID_POSITION;

        SmallSpinnerAdapter(String[] labels) {
            this(labels, true);
        }

        SmallSpinnerAdapter(String[] labels, boolean cyanGlowText) {
            super(MainActivity.this, android.R.layout.simple_spinner_item, labels);
            this.cyanGlowText = cyanGlowText;
        }

        void setSelectedPosition(int selectedPosition) {
            this.selectedPosition = selectedPosition;
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = spinnerTextView(convertView);
            view.setText(getItem(position));
            view.setTextSize(11);
            view.setPadding(dp(6), 0, dp(6), 0);
            if (cyanGlowText && position == selectedPosition) {
                styleCyanGlowText(view);
            } else {
                styleDropdownInactiveText(view);
            }
            view.setLayoutParams(new AdapterView.LayoutParams(
                    AdapterView.LayoutParams.MATCH_PARENT,
                    AdapterView.LayoutParams.MATCH_PARENT
            ));
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = convertView instanceof TextView && !(convertView instanceof MarqueeTextView)
                    ? (TextView) convertView
                    : null;
            if (view == null) {
                view = (TextView) android.view.LayoutInflater.from(getContext()).inflate(android.R.layout.simple_spinner_dropdown_item, null);
            }
            view.setText(getItem(position));
            view.setTextSize(13);
            if (cyanGlowText && position == selectedPosition) {
                styleCyanGlowText(view);
            } else {
                stylePlainWhiteText(view);
            }
            view.setGravity(android.view.Gravity.CENTER);
            view.setSingleLine(true);
            view.setEllipsize(android.text.TextUtils.TruncateAt.END);
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setIncludeFontPadding(false);
            view.setBackgroundColor(Color.rgb(22, 26, 38));
            view.setPadding(dp(16), dp(12), dp(16), dp(12));
            return view;
        }

        private TextView spinnerTextView(View convertView) {
            TextView view = convertView instanceof MarqueeTextView ? (TextView) convertView : new MarqueeTextView(MainActivity.this);
            view.setIncludeFontPadding(false);
            stylePlainWhiteText(view);
            configureCenteredMarquee(view);
            
            return view;
        }
    }

    private final class CompactSpinnerAdapter extends SmallSpinnerAdapter {
        CompactSpinnerAdapter(String[] labels) {
            super(labels);
        }

        CompactSpinnerAdapter(String[] labels, boolean cyanGlowText) {
            super(labels, cyanGlowText);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            view.setTextSize(10);
            view.setPadding(0, 0, 0, 0);
            configureCenteredMarquee(view);
            view.setLayoutParams(new AdapterView.LayoutParams(
                    AdapterView.LayoutParams.MATCH_PARENT,
                    AdapterView.LayoutParams.MATCH_PARENT
            ));
            
            return view;
        }
    }

    private static final class MarqueeTextView extends TextView {
        MarqueeTextView(Context context) {
            super(context);
        }

        @Override
        public boolean isFocused() {
            return true;
        }
    }

    private static final class MarqueeButton extends Button {
        MarqueeButton(Context context) {
            super(context);
        }

        @Override
        public boolean isFocused() {
            return true;
        }
    }

    private GradientDrawable createGlassCard(int alphaPercent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(Color.argb((int)(alphaPercent * 2.55f), 18, 22, 34));
        gd.setStroke(dp(1), Color.argb(35, 255, 255, 255));
        gd.setCornerRadius(dp(14));
        return gd;
    }

    private GradientDrawable createFieldBackground(int fillAlpha, int strokeAlpha, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(fillAlpha, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(strokeAlpha, 255, 255, 255));
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private GradientDrawable createEqControlBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(24, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        bg.setCornerRadius(dp(10));
        return bg;
    }

    private Drawable createOverlayInputBackground() {
        return strokeGlowRoundRectDrawable(
                Color.argb(235, 23, 37, 42),
                Color.argb(170, 0, 245, 212),
                dp(8),
                dp(2),
                Color.argb(70, 0, 245, 212)
        );
    }

    private void styleTopSwitch(Switch switchView, boolean autoSwitch) {
        switchView.setShowText(false);
        switchView.setText("");
        switchView.setPadding(0, 0, 0, 0);
        switchView.setMinWidth(dp(60));
        switchView.setMinimumWidth(dp(60));
        switchView.setMinHeight(dp(30));
        switchView.setMinimumHeight(dp(30));
        switchView.setSwitchMinWidth(dp(60));
        // 去掉开关外围的圈圈动画：清除默认 background 和 stateListAnimator
        switchView.setBackground(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            switchView.setStateListAnimator(null);
        }
        // thumb 颜色：关闭时浅灰偏冷，开启时青色亮光（带柔光）
        switchView.setThumbDrawable(switchThumbDrawable(
                autoSwitch ? Color.rgb(150, 168, 198) : Color.rgb(162, 180, 208),
                Color.rgb(76, 210, 228)
        ));
        // track 颜色：关闭时半透明白，开启时半透明青蓝（与标题流光同色系）
        switchView.setTrackDrawable(labeledSwitchTrackDrawable(
                autoSwitch ? "AUTO" : "OFF",
                autoSwitch ? "AUTO" : "ON",
                autoSwitch ? Color.argb(52, 126, 152, 196) : Color.argb(60, 118, 144, 188),
                autoSwitch ? Color.argb(112, 52, 168, 196) : Color.argb(124, 48, 176, 208)
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            switchView.setSplitTrack(false);
        }
    }

    private Drawable labeledSwitchTrackDrawable(String uncheckedLabel, String checkedLabel, int uncheckedColor, int checkedColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rect = new android.graphics.RectF();
            private android.animation.ValueAnimator labelAnimator;
            private float labelProgress;
            private boolean labelProgressReady;

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                boolean checked = drawableStateChecked(getState());
                if (!labelProgressReady) {
                    labelProgress = checked ? 1f : 0f;
                    labelProgressReady = true;
                }
                rect.set(b.left + dpf(0.5f), b.top + dpf(2f), b.right - dpf(0.5f), b.bottom - dpf(2f));

                float radius = rect.height() / 2f;
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                if (checked) {
                    float outerGlow = dpf(2.4f);
                    float midGlow = dpf(1.5f);
                    float innerGlow = dpf(0.8f);
                    paint.setColor(Color.argb(16, 84, 212, 228));
                    canvas.drawRoundRect(
                            rect.left - outerGlow,
                            rect.top - outerGlow,
                            rect.right + outerGlow,
                            rect.bottom + outerGlow,
                            radius + outerGlow,
                            radius + outerGlow,
                            paint);
                    paint.setColor(Color.argb(28, 84, 212, 228));
                    canvas.drawRoundRect(
                            rect.left - midGlow,
                            rect.top - midGlow,
                            rect.right + midGlow,
                            rect.bottom + midGlow,
                            radius + midGlow,
                            radius + midGlow,
                            paint);
                    paint.setColor(Color.argb(40, 84, 212, 228));
                    canvas.drawRoundRect(
                            rect.left - innerGlow,
                            rect.top - innerGlow,
                            rect.right + innerGlow,
                            rect.bottom + innerGlow,
                            radius + innerGlow,
                            radius + innerGlow,
                            paint);
                } else {
                    float offGlow = dpf(0.9f);
                    paint.setColor(Color.argb(10, 116, 142, 176));
                    canvas.drawRoundRect(
                            rect.left - offGlow,
                            rect.top - offGlow,
                            rect.right + offGlow,
                            rect.bottom + offGlow,
                            radius + offGlow,
                            radius + offGlow,
                            paint);
                }

                paint.setColor(checked ? checkedColor : uncheckedColor);
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(checked ? Color.argb(124, 176, 244, 248) : Color.argb(84, 194, 220, 255));
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(dpf(8.5f));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(checked ? Color.rgb(214, 235, 255) : Color.argb(136, 226, 236, 248));
                if (checked) {
                    paint.setShadowLayer(dpf(2.2f), 0, 0, Color.argb(110, 96, 220, 234));
                } else {
                    paint.setShadowLayer(dpf(1.4f), 0, 0, Color.argb(54, 150, 186, 214));
                }
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float textY = rect.centerY() - (metrics.ascent + metrics.descent) / 2f + dpf(4f);
                float leftTextX = rect.left + rect.width() * 0.32f;
                float rightTextX = rect.left + rect.width() * 0.68f;
                float textX = rightTextX + (leftTextX - rightTextX) * labelProgress;
                canvas.drawText(checked ? checkedLabel : uncheckedLabel, textX, textY, paint);
                paint.clearShadowLayer();
                paint.setTypeface(android.graphics.Typeface.DEFAULT);
            }

            @Override
            protected boolean onStateChange(int[] state) {
                float target = drawableStateChecked(state) ? 1f : 0f;
                if (!labelProgressReady) {
                    labelProgress = target;
                    labelProgressReady = true;
                    invalidateSelf();
                    return true;
                }
                if (labelAnimator != null) {
                    labelAnimator.cancel();
                }
                labelAnimator = android.animation.ValueAnimator.ofFloat(labelProgress, target);
                labelAnimator.setDuration(300);
                labelAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                labelAnimator.addUpdateListener(animation -> {
                    labelProgress = (float) animation.getAnimatedValue();
                    invalidateSelf();
                });
                labelAnimator.start();
                invalidateSelf();
                return true;
            }

            @Override
            public boolean isStateful() {
                return true;
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return dp(54);
            }

            @Override
            public int getIntrinsicHeight() {
                return dp(32);
            }
        };
    }

    private Drawable switchThumbDrawable(int uncheckedColor, int checkedColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int currentColor = uncheckedColor;
            private boolean colorReady = false;
            private float glowAlpha = 0f;
            private android.animation.ValueAnimator colorAnimator;

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                boolean checked = drawableStateChecked(getState());
                if (!colorReady) {
                    currentColor = checked ? checkedColor : uncheckedColor;
                    glowAlpha = checked ? 1f : 0f;
                    colorReady = true;
                }
                float inset = dpf(2f);
                float left = b.left + inset;
                float top = b.top + inset;
                float right = b.right - inset;
                float bottom = b.bottom - inset;
                float cx = (left + right) * 0.5f - dpf(1f) + (checked ? 0f : dpf(2f));
                float cy = (top + bottom) * 0.5f;
                float radius = Math.min(right - left, bottom - top) * 0.5f;
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(currentColor);
                canvas.drawCircle(cx, cy, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                int strokeAlpha = (int) (78 + (126 - 78) * glowAlpha);
                paint.setColor(Color.argb(strokeAlpha, 238, 246, 255));
                canvas.drawCircle(cx, cy, radius - dpf(0.1f), paint);
            }

            @Override
            protected boolean onStateChange(int[] state) {
                boolean checked = drawableStateChecked(state);
                int targetColor = checked ? checkedColor : uncheckedColor;
                float targetGlow = checked ? 1f : 0f;
                if (colorAnimator != null) {
                    colorAnimator.cancel();
                }
                float startGlow = glowAlpha;
                int startColor = currentColor;
                colorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
                colorAnimator.setDuration(300);
                colorAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                colorAnimator.addUpdateListener(animation -> {
                    float t = (float) animation.getAnimatedValue();
                    currentColor = (int) new android.animation.ArgbEvaluator()
                            .evaluate(t, startColor, targetColor);
                    glowAlpha = startGlow + (targetGlow - startGlow) * t;
                    invalidateSelf();
                });
                colorAnimator.start();
                invalidateSelf();
                return true;
            }

            @Override
            public boolean isStateful() {
                return true;
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return dp(26);
            }

            @Override
            public int getIntrinsicHeight() {
                return dp(26);
            }
        };
    }

    private boolean drawableStateChecked(int[] states) {
        if (states == null) {
            return false;
        }
        for (int state : states) {
            if (state == android.R.attr.state_checked) {
                return true;
            }
        }
        return false;
    }

    private void attachNumberWatermark(EditText input, boolean active) {
        updateNumberWatermark(input, active);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateNumberWatermark(input, active);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void updateNumberWatermark(EditText input, boolean active) {
        input.setBackground(numberCellBackground(integerWatermark(input.getText().toString()), active));
    }

    private String integerWatermark(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        try {
            return String.valueOf(Math.round(Float.parseFloat(value.trim())));
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private Drawable numberCellBackground(String watermark, boolean active) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clipPath = new Path();
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float inset = dpf(0.75f);
                rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float radius = dpf(10f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0f);
                paint.setColor(active ? Color.argb(13, 255, 255, 255) : Color.argb(11, 255, 255, 255));
                paint.clearShadowLayer();
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();

                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(active ? Color.argb(150, 0, 245, 212) : Color.argb(72, 255, 255, 255));
                if (active) {
                    paint.setShadowLayer(dpf(2.5f), 0, 0, Color.argb(105, 0, 245, 212));
                } else {
                    paint.clearShadowLayer();
                }
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private Drawable typeCellBackground(FilterType filterType, boolean active) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final Path clipPath = new Path();
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float inset = dpf(0.75f);
                rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float radius = dpf(10f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0f);
                paint.setColor(active ? Color.argb(13, 255, 255, 255) : Color.argb(11, 255, 255, 255));
                paint.clearShadowLayer();
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();

                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(active ? Color.argb(150, 0, 245, 212) : Color.argb(72, 255, 255, 255));
                if (active) {
                    paint.setShadowLayer(dpf(2.5f), 0, 0, Color.argb(105, 0, 245, 212));
                } else {
                    paint.clearShadowLayer();
                }
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    // 图标光晕：以图标自身 alpha 为蒙版做高斯外发光，视觉与 GlowTitleTextView
    // 的标题光晕同源（模糊后的剪影 + 取色），图标本体由 ImageView 锐利绘制在上层，
    // 保持清晰度。光晕只在图标 / 尺寸变化时重建，避免每帧 GC。
    private class IconGlowDrawable extends Drawable {
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF dstRect = new android.graphics.RectF();
        private Drawable sourceIcon;
        private int glowColor;
        private android.graphics.Bitmap haloBitmap;
        private int cachedKey = 0;

        IconGlowDrawable(int glowColor) {
            this.glowColor = glowColor;
            haloPaint.setDither(true);
            haloPaint.setFilterBitmap(true);
        }

        void setIcon(Drawable icon) {
            if (sourceIcon == icon) {
                return;
            }
            sourceIcon = icon;
            if (icon != null) {
                glowColor = extractIconGlowColor(icon);
            }
            dropHalo();
            invalidateSelf();
        }

        private void dropHalo() {
            if (haloBitmap != null) {
                haloBitmap.recycle();
                haloBitmap = null;
            }
            cachedKey = 0;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0 || sourceIcon == null) {
                return;
            }
            // 图标显示区 = bounds 减去宿主 ImageView 的 padding（FIT_CENTER 居中）
            int pl = 0, pt = 0, pr = 0, pb = 0;
            android.graphics.drawable.Drawable.Callback cb = getCallback();
            if (cb instanceof View) {
                View host = (View) cb;
                pl = host.getPaddingLeft();
                pt = host.getPaddingTop();
                pr = host.getPaddingRight();
                pb = host.getPaddingBottom();
            }
            int aw = Math.max(1, b.width() - pl - pr);
            int ah = Math.max(1, b.height() - pt - pb);
            // 光晕半径：稍大，让光晕更饱满、扩散更明显；
            // pad 需 >= 3*blurPx 才能完整容纳 3 趟盒式模糊的扩散，避免边缘硬切。
            int blurPx = Math.max(1, (int) Math.ceil(dpf(1.9f)));
            int pad = blurPx * 3 + 2;
            int key = (aw << 16) | (ah & 0xFFFF);
            if (haloBitmap == null || key != cachedKey) {
                buildHalo(aw, ah, pad, blurPx);
                cachedKey = key;
            }
            if (haloBitmap == null) {
                return;
            }
            // haloBitmap 中图标区域居于 [pad, pad+aw]，与 ImageView 绘制的图标重合；
            // 整张光晕按此偏移贴齐，模糊溢出部分自然环绕图标四周。
            float left = b.left + pl - pad;
            float top = b.top + pt - pad;
            dstRect.set(left, top, left + haloBitmap.getWidth(), top + haloBitmap.getHeight());
            haloPaint.setAlpha(255);
            canvas.drawBitmap(haloBitmap, null, dstRect, haloPaint);
        }

        private void buildHalo(int aw, int ah, int pad, int blurPx) {
            int bw = aw + 2 * pad;
            int bh = ah + 2 * pad;
            if (bw <= 0 || bh <= 0) {
                return;
            }
            // 1. 把图标 FIT_CENTER 渲染到画布中心区域（四周留 pad 给模糊溢出）
            android.graphics.Bitmap src = android.graphics.Bitmap.createBitmap(
                    bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(src);
            android.graphics.Rect fit = fitCenter(sourceIcon, aw, ah);
            fit.offset(pad, pad);
            // 保存并恢复宿主 ImageView 共享的 bounds，避免影响上层锐利绘制
            android.graphics.Rect saved = new android.graphics.Rect(sourceIcon.getBounds());
            sourceIcon.setBounds(fit);
            sourceIcon.draw(c);
            sourceIcon.setBounds(saved);
            // 2. 形态学边缘提取：图标是实心矩形，直接着色会把整块填成实心色块。
            //    先腐蚀（min 滤波）得到缩小的 alpha，再 edge = alpha - 腐蚀alpha：
            //    图标内部 edge=0（不发光）、只在图标轮廓一圈 edge>0、外部 edge=0。
            //    对这"轮廓环"做高斯模糊，得到与标题文字光晕同源的轮廓光晕，
            //    而不是一坨实心色块；图标本体由上层 ImageView 锐利覆盖。
            int[] px = new int[bw * bh];
            src.getPixels(px, 0, bw, 0, 0, bw, bh);
            src.recycle();
            int[] alpha = new int[bw * bh];
            for (int i = 0; i < px.length; i++) {
                alpha[i] = (px[i] >>> 24) & 0xFF;
            }
            int edgeK = Math.max(1, (int) Math.ceil(dpf(0.9f)));
            int[] eroded = new int[bw * bh];
            minFilterH(alpha, eroded, bw, bh, edgeK);
            minFilterV(eroded, eroded, bw, bh, edgeK);
            int[] edge = new int[bw * bh];
            for (int i = 0; i < alpha.length; i++) {
                int e = alpha[i] - eroded[i];
                edge[i] = e < 0 ? 0 : e;
            }
            // 对轮廓环做 3 趟可分离盒式模糊（近似高斯），向外柔和扩散成光晕
            int[] tmp = new int[bw * bh];
            for (int p = 0; p < 3; p++) {
                boxBlurH(edge, tmp, bw, bh, blurPx);
                boxBlurV(tmp, edge, bw, bh, blurPx);
            }
            // 3. 用图标取色给光晕上色（RGB=取色，A=模糊后轮廓 alpha），图标本体由上层锐利覆盖。
            //    gamma=1.0 不提亮，保留模糊本身的快速衰减；尾端用 C∞ 软乘子
            //    M(a)=1-(1-a)^p 平滑吻接到 0，中高 alpha 区 M≈1 忠实保留衰减形状。
            int r = (glowColor >> 16) & 0xFF;
            int g = (glowColor >> 8) & 0xFF;
            int bl = glowColor & 0xFF;
            int ga = (glowColor >>> 24) & 0xFF;
            final float baseGain = 0.34f;    // 继续降低亮度，避免贴边形成实色圈
            final float gamma = 1.35f;       // 再压一档中高亮，让光晕更像柔光
            final float fadeSharp = 3.0f;    // 收边略放软，减少边界感
            for (int i = 0; i < edge.length; i++) {
                float nf = edge[i] / 255f;
                float a = (float) Math.pow(nf, gamma);
                // 软乘子 M = 1 - (1-a)^p，全程 C∞ 单条函数，无分段无折点
                float m = 1f - (float) Math.pow(1f - a, fadeSharp);
                float contentMask = 1f - (alpha[i] / 255f);
                float tail = a * (0.18f + 0.82f * m) * contentMask * contentMask;
                int out = (int) (tail * ga * baseGain * 255f + 0.5f);
                if (out > 255) out = 255;
                if (out < 0) out = 0;
                px[i] = (out << 24) | (r << 16) | (g << 8) | bl;
            }
            android.graphics.Bitmap halo = android.graphics.Bitmap.createBitmap(
                    px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            if (haloBitmap != null) {
                haloBitmap.recycle();
            }
            haloBitmap = halo;
        }

        @Override
        public void setAlpha(int alpha) {
            haloPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // 保留图标取色逻辑，忽略外部 colorFilter 以维持光晕配色稳定
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---- 图标光晕辅助：取色 / 居中 / 盒式模糊 ----
    // 取软件图标平均色并把最亮通道拉满，保留色相的同时让光晕通透鲜亮
    private int extractIconGlowColor(Drawable icon) {
        int width = icon.getIntrinsicWidth();
        int height = icon.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = 64;
            height = 64;
        }
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            icon.setBounds(0, 0, width, height);
            icon.draw(canvas);
            long sumR = 0, sumG = 0, sumB = 0, count = 0;
            int stride = Math.max(1, Math.min(width, height) / 16);
            for (int y = 0; y < height; y += stride) {
                for (int x = 0; x < width; x += stride) {
                    int pixel = bmp.getPixel(x, y);
                    if (((pixel >>> 24) & 0xFF) < 100) {
                        continue;
                    }
                    sumR += (pixel >> 16) & 0xFF;
                    sumG += (pixel >> 8) & 0xFF;
                    sumB += pixel & 0xFF;
                    count++;
                }
            }
            bmp.recycle();
            if (count == 0) {
                return Color.argb(172, 120, 220, 255);
            }
            int r = (int) (sumR / count);
            int g = (int) (sumG / count);
            int b = (int) (sumB / count);
            r = clamp((int) (r * 0.88f + 28f), 0, 255);
            g = clamp((int) (g * 0.90f + 26f), 0, 255);
            b = clamp((int) (b * 0.86f + 18f), 0, 255);
            return Color.argb(172, r, g, b);
        } catch (Exception ignored) {
            return Color.argb(172, 120, 220, 255);
        }
    }

    private android.graphics.Rect fitCenter(Drawable icon, int aw, int ah) {
        int iw = icon.getIntrinsicWidth();
        int ih = icon.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            iw = aw;
            ih = ah;
        }
        float scale = Math.min((float) aw / iw, (float) ah / ih);
        int dw = Math.max(1, (int) (iw * scale));
        int dh = Math.max(1, (int) (ih * scale));
        int left = (aw - dw) / 2;
        int top = (ah - dh) / 2;
        return new android.graphics.Rect(left, top, left + dw, top + dh);
    }

    private void boxBlurH(int[] src, int[] dst, int w, int h, int r) {
        int window = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sum = 0;
            for (int x = -r; x <= r; x++) {
                sum += src[row + Math.max(0, Math.min(w - 1, x))];
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = sum / window;
                int xOut = Math.max(0, Math.min(w - 1, x - r));
                int xIn = Math.max(0, Math.min(w - 1, x + r + 1));
                sum += src[row + xIn] - src[row + xOut];
            }
        }
    }

    private void boxBlurV(int[] src, int[] dst, int w, int h, int r) {
        int window = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -r; y <= r; y++) {
                sum += src[Math.max(0, Math.min(h - 1, y)) * w + x];
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = sum / window;
                int yOut = Math.max(0, Math.min(h - 1, y - r));
                int yIn = Math.max(0, Math.min(h - 1, y + r + 1));
                sum += src[yIn * w + x] - src[yOut * w + x];
            }
        }
    }

    // 最小值滤波（腐蚀）：用于形态学边缘提取，把实心图标内部吃掉只留轮廓
    private void minFilterH(int[] src, int[] dst, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int mn = 255;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0) xx = 0;
                    else if (xx >= w) xx = w - 1;
                    int v = src[row + xx];
                    if (v < mn) mn = v;
                }
                dst[row + x] = mn;
            }
        }
    }

    private void minFilterV(int[] src, int[] dst, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int mn = 255;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0) yy = 0;
                    else if (yy >= h) yy = h - 1;
                    int v = src[yy * w + x];
                    if (v < mn) mn = v;
                }
                dst[y * w + x] = mn;
            }
        }
    }

    private class AppIconBloomDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF glowRect = new android.graphics.RectF();
        private final android.graphics.RectF coreRect = new android.graphics.RectF();
        private Drawable sourceIcon;
        private int glowColor;
        private int drawableAlpha = 255;

        AppIconBloomDrawable(int glowColor) {
            this.glowColor = glowColor;
            fillPaint.setStyle(Paint.Style.FILL);
            bloomPaint.setStyle(Paint.Style.FILL);
            bloomPaint.setDither(true);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setDither(true);
        }

        void setIcon(Drawable icon) {
            sourceIcon = icon;
            if (icon != null) {
                glowColor = extractMonitoredAppGlowColor(icon);
            }
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0 || sourceIcon == null) {
                return;
            }

            int inset = monitoredAppIconGlowInsetPx();
            float bloomInset = inset * 0.06f;
            glowRect.set(b.left + bloomInset, b.top + bloomInset, b.right - bloomInset, b.bottom - bloomInset);
            coreRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);

            float coreRadius = Math.min(coreRect.width(), coreRect.height()) * 0.30f;
            bloomPaint.setShader(null);
            int glowLayers = 10;
            float maxInset = inset * 0.78f;
            for (int layer = 0; layer < glowLayers; layer++) {
                float t = glowLayers <= 1 ? 1f : layer / (float) (glowLayers - 1);
                float logFalloff = (float) (Math.log1p((1f - t) * 7f) / Math.log(8f));
                float tailLift = 1f - t * t * t * t;
                float blendedFalloff = logFalloff * 0.35f + tailLift * 0.65f;
                float layerInset = maxInset * t;
                float layerRadius = coreRadius + inset * (1.12f - 0.42f * t);
                float saturationScale = 1.01f - 0.07f * t;
                float valueScale = 1.07f - 0.06f * t;
                float alpha = (0.010f + 0.028f * blendedFalloff) * drawableAlpha / 255f;
                if (layer >= glowLayers - 3) {
                    float tailT = (layer - (glowLayers - 3)) / 2f;
                    float tailAlphaScale = 1f - 0.78f * tailT;
                    alpha *= Math.max(0.10f, tailAlphaScale);
                    valueScale -= 0.05f * tailT;
                }
                bloomPaint.setColor(withMonitoredAppGlowAlpha(
                        shiftMonitoredAppGlowColor(glowColor, saturationScale, valueScale),
                        alpha));
                canvas.drawRoundRect(
                        glowRect.left + layerInset,
                        glowRect.top + layerInset,
                        glowRect.right - layerInset,
                        glowRect.bottom - layerInset,
                        layerRadius,
                        layerRadius,
                        bloomPaint);
            }

            fillPaint.setShader(null);
            fillPaint.setColor(withMonitoredAppGlowAlpha(shiftMonitoredAppGlowColor(glowColor, 0.20f, 1.03f), 0.012f * drawableAlpha / 255f));
            canvas.drawRoundRect(coreRect, coreRadius, coreRadius, fillPaint);

            ringPaint.setShader(new LinearGradient(
                    coreRect.left,
                    coreRect.top,
                    coreRect.right,
                    coreRect.bottom,
                    withMonitoredAppGlowAlpha(shiftMonitoredAppGlowColor(glowColor, 1.04f, 1.10f), 0.08f * drawableAlpha / 255f),
                    withMonitoredAppGlowAlpha(shiftMonitoredAppGlowColor(glowColor, 0.94f, 1.00f), 0.028f * drawableAlpha / 255f),
                    Shader.TileMode.CLAMP));
            ringPaint.setStrokeWidth(dpf(0.8f));
            canvas.drawRoundRect(coreRect, coreRadius, coreRadius, ringPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = clamp(alpha, 0, 255);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private int monitoredAppIconVisualSizePx() {
        return dp(24);
    }

    private int monitoredAppIconGlowInsetPx() {
        return dp(6);
    }

    private int monitoredAppIconHostSizePx() {
        return monitoredAppIconVisualSizePx() + monitoredAppIconGlowInsetPx() * 2;
    }

    private int extractMonitoredAppGlowColor(Drawable icon) {
        int width = icon.getIntrinsicWidth();
        int height = icon.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = 64;
            height = 64;
        }
        android.graphics.Rect savedBounds = new android.graphics.Rect(icon.getBounds());
        float[] sampleHsv = new float[3];
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            icon.setBounds(0, 0, width, height);
            icon.draw(canvas);
            long sumR = 0;
            long sumG = 0;
            long sumB = 0;
            int count = 0;
            float bestScore = -1f;
            int bestColor = Color.rgb(235, 242, 140);
            int stride = Math.max(1, Math.min(width, height) / 18);
            for (int y = 0; y < height; y += stride) {
                for (int x = 0; x < width; x += stride) {
                    int pixel = bmp.getPixel(x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < 90) {
                        continue;
                    }
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    sumR += r;
                    sumG += g;
                    sumB += b;
                    count++;
                    Color.RGBToHSV(r, g, b, sampleHsv);
                    float score = sampleHsv[1] * 1.45f + sampleHsv[2] * 0.55f;
                    if (score > bestScore) {
                        bestScore = score;
                        bestColor = Color.rgb(r, g, b);
                    }
                }
            }
            bmp.recycle();
            if (count == 0) {
                return Color.argb(176, 120, 220, 255);
            }
            int avgColor = Color.rgb(
                    clamp((int) (sumR / count), 0, 255),
                    clamp((int) (sumG / count), 0, 255),
                    clamp((int) (sumB / count), 0, 255));
            int mixed = mixMonitoredAppGlowColors(bestColor, avgColor, 0.28f);
            Color.colorToHSV(mixed, sampleHsv);
            sampleHsv[1] = clampUnit(sampleHsv[1] * 1.08f + 0.10f);
            sampleHsv[2] = clampUnit(sampleHsv[2] * 1.04f + 0.08f);
            return Color.HSVToColor(176, sampleHsv);
        } catch (Exception ignored) {
            return Color.argb(176, 120, 220, 255);
        } finally {
            icon.setBounds(savedBounds);
        }
    }

    private int shiftMonitoredAppGlowColor(int color, float saturationScale, float valueScale) {
        float[] localHsv = new float[3];
        Color.colorToHSV(color, localHsv);
        localHsv[1] = clampUnit(localHsv[1] * saturationScale);
        localHsv[2] = clampUnit(localHsv[2] * valueScale);
        return Color.HSVToColor(localHsv);
    }

    private int withMonitoredAppGlowAlpha(int color, float alphaFraction) {
        return Color.argb(
                clamp((int) (255f * clampUnit(alphaFraction) + 0.5f), 0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private int mixMonitoredAppGlowColors(int first, int second, float secondWeight) {
        float w = clampUnit(secondWeight);
        float inv = 1f - w;
        return Color.rgb(
                clamp((int) (Color.red(first) * inv + Color.red(second) * w + 0.5f), 0, 255),
                clamp((int) (Color.green(first) * inv + Color.green(second) * w + 0.5f), 0, 255),
                clamp((int) (Color.blue(first) * inv + Color.blue(second) * w + 0.5f), 0, 255));
    }

    private float clampUnit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private class GlowTitleTextView extends TextView {
        private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private boolean glowEnabled = true;
        private boolean autoRegisterShimmer = true;
        private int glowColor = Color.argb(210, 120, 220, 255);
        private float glowRadiusPx = dpf(7.4f);

        GlowTitleTextView(Context context) {
            super(context);
            glowPaint.setDither(true);
            glowPaint.setLinearText(true);
            glowPaint.setSubpixelText(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        }

        void setGlowState(boolean enabled, int color, float radiusPx) {
            if (glowEnabled == enabled
                    && glowColor == color
                    && Math.abs(glowRadiusPx - radiusPx) < 0.001f) {
                return;
            }
            glowEnabled = enabled;
            glowColor = color;
            glowRadiusPx = radiusPx;
            invalidate();
        }

        void clearGlowState() {
            if (!glowEnabled) {
                return;
            }
            glowEnabled = false;
            invalidate();
        }

        void setAutoRegisterShimmer(boolean enabled) {
            autoRegisterShimmer = enabled;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (autoRegisterShimmer) {
                registerShimmerView(this);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            disposeShimmerView(this);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getPaint().clearShadowLayer();
            drawTitleGlow(canvas);
            super.onDraw(canvas);
        }

        private void drawTitleGlow(Canvas canvas) {
            if (!glowEnabled || glowRadiusPx <= 0f || Color.alpha(glowColor) <= 0) {
                return;
            }
            Layout layout = getLayout();
            CharSequence text = getText();
            if (layout == null || text == null || text.length() == 0) {
                return;
            }
            glowPaint.set(getPaint());
            glowPaint.setShader(null);
            glowPaint.setColor(glowColor);
            glowPaint.setStyle(Paint.Style.FILL);
            glowPaint.setMaskFilter(new BlurMaskFilter(glowRadiusPx, BlurMaskFilter.Blur.NORMAL));

            String content = text.toString();
            int availableWidth = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
            int availableHeight = getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
            float layoutLeft = getCompoundPaddingLeft();
            float layoutTop = getExtendedPaddingTop();
            int absoluteGravity = Gravity.getAbsoluteGravity(getGravity(), getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (absoluteGravity == Gravity.CENTER_HORIZONTAL) {
                layoutLeft += Math.max(0, availableWidth - layout.getWidth()) * 0.5f;
            } else if (absoluteGravity == Gravity.RIGHT) {
                layoutLeft += Math.max(0, availableWidth - layout.getWidth());
            }
            int verticalGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
            if (verticalGravity == Gravity.CENTER_VERTICAL) {
                layoutTop += Math.max(0, availableHeight - layout.getHeight()) * 0.5f;
            } else if (verticalGravity == Gravity.BOTTOM) {
                layoutTop += Math.max(0, availableHeight - layout.getHeight());
            }
            int save = canvas.save();
            canvas.translate(layoutLeft, layoutTop);
            for (int line = 0; line < layout.getLineCount(); line++) {
                int start = layout.getLineStart(line);
                int end = layout.getLineEnd(line);
                canvas.drawText(content, start, end, layout.getLineLeft(line), layout.getLineBaseline(line), glowPaint);
            }
            canvas.restoreToCount(save);
            glowPaint.setMaskFilter(null);
        }
    }

    private TextView gradientTitleView(String text) {
        TextView title = new GlowTitleTextView(this);
        title.setText(text);
        // 关键修复：大半径模糊/光晕被截断的原因是 TextView 本身没有足够的水平边距和垂直边距。
        // 因为高斯模糊阴影是以文字像素边缘向外扩散的，如果 TextView 贴紧边缘（或宽度恰好包紧文字），超出部分就会被硬生生截断，显得极其割裂。
        // 通过设置充足的水平 Padding (左右 16dp) 和垂直 Padding (上下 4dp)，为精细的高斯模糊光晕留出完美的溢出和衰减空间！
        title.setPadding(dp(22), dp(5), dp(22), dp(5));
        styleGradientTitle(title);
        return title;
    }

    private void reserveStartGlowWithoutMoving(TextView view, int extraLeftDp) {
        if (view == null || extraLeftDp <= 0) {
            return;
        }
        int extraPx = dp(extraLeftDp);
        view.setPadding(view.getPaddingLeft() + extraPx, view.getPaddingTop(),
                view.getPaddingRight(), view.getPaddingBottom());
        view.setTranslationX(-extraPx);
    }

    private void styleGradientTitle(TextView view) {
        styleSettingsTitleText(view);
    }

    private void syncExtraSectionTitleVisual(TextView view) {
        if (view == null) {
            return;
        }
        boolean active = isExtraSectionTitleActive(view);
        Boolean previous = titleVisualStates.get(view);
        if (previous != null && previous == active) {
            return;
        }
        titleVisualStates.put(view, active);
        if (active) {
            registerShimmerView(view);
            applyActiveExtraSectionTitleStyle(view);
        } else {
            applyInactiveExtraSectionTitleStyle(view);
        }
    }

    private void applyActiveExtraSectionTitleStyle(TextView view) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            if (view instanceof GlowTitleTextView) {
                if (view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            }
        }
        view.setTextColor(Color.WHITE);
        applyGlowToTextView(view, Color.argb(210, 120, 220, 255), 7.4f);
        applyShimmerFrame(view, settingsTitleGradientWidth(view), currentShimmerPhaseForView(view));
    }

    private void applyInactiveExtraSectionTitleStyle(TextView view) {
        if (view == null) {
            return;
        }
        titleVisualStates.put(view, false);
        bumpTextStyleVersion(view);
        unregisterShimmerView(view);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                && (view instanceof GlowTitleTextView)
                && view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        view.getPaint().setShader(null);
        view.setTextColor(Color.rgb(150, 165, 185));
        clearGlowFromTextView(view);
        view.invalidate();
    }

    private boolean isExtraSectionTitle(TextView view) {
        if (view == null) {
            return false;
        }
        return view == reverbTitleView || view == virtualBassTitleView || view == extraBassTitleView;
    }

    private boolean isExtraSectionTitleActive(TextView view) {
        if (view == null || editingPreset == null) {
            return false;
        }
        if (view == reverbTitleView) {
            return supported
                    && AudioProcessingPolicy.reverbAllowed(processingMode)
                    && !"Default".equals(editingPreset.reverbType);
        }
        if (view == virtualBassTitleView) {
            return supported
                    && AudioProcessingPolicy.virtualBassModeAllowed(processingMode, selectedBassModeIndex)
                    && selectedBassModeIndex > 0;
        }
        if (view == extraBassTitleView) {
            return supported && extraBassEnabledState;
        }
        return false;
    }

    private void registerShimmerView(TextView view) {
        if (view == null || shimmerTargetViews.contains(view)) {
            return;
        }
        if (isExtraSectionTitle(view) && !isExtraSectionTitleActive(view)) {
            return;
        }
        if (view == modeSpinner && !isModeVisualEnabled()) {
            return;
        }
        if (view == statusText) {
            boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
            if (!supported || hasClip) {
                return;
            }
        }
        currentShimmerPhaseForView(view);
        shimmerTargetViews.add(view);
        if (shimmerTargetViews.size() == 1) {
            lastShimmerTime = 0L;
            uiHandler.postDelayed(shimmerAnimationRunnable, SHIMMER_FPS_DELAY);
        }
    }

    private void unregisterShimmerView(TextView view) {
        if (view == null) {
            return;
        }
        shimmerTargetViews.remove(view);
        if (!(view == statusText || view == modeSpinner || isExtraSectionTitle(view))) {
            clearAnimatedTextStyle(view);
        }
        if (shimmerTargetViews.isEmpty()) {
            uiHandler.removeCallbacks(shimmerAnimationRunnable);
        }
    }

    private void disposeShimmerView(TextView view) {
        if (view == null) {
            return;
        }
        unregisterShimmerView(view);
        shimmerViewPhases.remove(view);
        textStyleVersion.remove(view);
        titleVisualStates.remove(view);
        clearAnimatedTextStyle(view);
    }

    private int bumpTextStyleVersion(TextView view) {
        if (view == null) {
            return 0;
        }
        int next = textStyleVersion.containsKey(view) ? textStyleVersion.get(view) + 1 : 1;
        textStyleVersion.put(view, next);
        return next;
    }

    private boolean isCurrentTextStyleVersion(TextView view, int version) {
        if (view == null) {
            return false;
        }
        Integer current = textStyleVersion.get(view);
        return current != null && current == version;
    }

    private void applyGlowToTextView(TextView view, int glowColor, float glowRadiusDp) {
        if (view instanceof GlowTitleTextView) {
            ((GlowTitleTextView) view).setGlowState(true, glowColor, dpf(glowRadiusDp));
            view.getPaint().clearShadowLayer();
            return;
        }
        view.getPaint().setShadowLayer(dpf(glowRadiusDp), 0, 0, glowColor);
    }

    private void clearGlowFromTextView(TextView view) {
        if (view instanceof GlowTitleTextView) {
            ((GlowTitleTextView) view).clearGlowState();
        }
        view.getPaint().clearShadowLayer();
    }

    private void clearAnimatedTextStyle(TextView view) {
        if (view == null) {
            return;
        }
        view.getPaint().setShader(null);
        clearGlowFromTextView(view);
        view.invalidate();
    }

    private void styleSettingsTitleText(TextView view) {
        if (view == null) {
            return;
        }
        final int styleVersion = bumpTextStyleVersion(view);
        // 关键优化：为了能够让完美丝滑的 shadowLayer (大半径高斯模糊) 在静态状态下同样发挥效果，
        // 同样将文字样式设置切换为精密的 SOFTWARE 图层，消除 GPU 渲染产生的颗粒伪影。
        boolean usesCustomGlow = view instanceof GlowTitleTextView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            if (usesCustomGlow) {
                // Inactive bottom tabs drop back to a hardware layer. Restore software
                // text rendering when re-activating them so animated shimmer frames
                // repaint correctly instead of being flattened by glyph caching.
                if (view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            } else if (view.getLayerType() != View.LAYER_TYPE_NONE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }
        applyAnimatedTitleGradientShader(view, settingsTitleGradientWidth(view), currentShimmerPhaseForView(view),
                Color.rgb(230, 245, 255), Color.rgb(160, 230, 255), Color.rgb(220, 180, 255));
        view.setTextColor(Color.WHITE);
        // 缩小一圈，但保留完整衰减空间
        view.getPaint().setShadowLayer(dpf(5.5f), 0, 0, Color.argb(138, 120, 220, 255));
        view.invalidate();
        if (view.getWidth() <= 0) {
            view.post(() -> {
                if (!isCurrentTextStyleVersion(view, styleVersion)) {
                    return;
                }
                applyAnimatedTitleGradientShader(view, settingsTitleGradientWidth(view), currentShimmerPhaseForView(view),
                        Color.rgb(230, 245, 255), Color.rgb(160, 230, 255), Color.rgb(220, 180, 255));
                view.setTextColor(Color.WHITE);
                view.getPaint().setShadowLayer(dpf(5.5f), 0, 0, Color.argb(138, 120, 220, 255));
                view.invalidate();
            });
        }
        if (usesCustomGlow) {
            applyGlowToTextView(view, Color.argb(210, 120, 220, 255), 7.4f);
            if (view.getWidth() <= 0) {
                view.post(() -> {
                    if (!isCurrentTextStyleVersion(view, styleVersion)) {
                        return;
                    }
                    applyGlowToTextView(view, Color.argb(210, 120, 220, 255), 7.4f);
                });
            }
        }
    }

    private void styleStatusTextShimmer(TextView view, int baseColorStart, int baseColorEnd) {
        applyAnimatedStatusShimmerShader(view, settingsTitleGradientWidth(view), currentShimmerPhaseForView(view), baseColorStart, baseColorEnd);
        view.setTextColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                && !(view instanceof GlowTitleTextView)) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        view.getPaint().setShadowLayer(dpf(8f), 0, 0, Color.argb(195, 0, 245, 212));
        if (view instanceof GlowTitleTextView) {
            applyGlowToTextView(view, Color.argb(188, 0, 245, 212), 5.25f);
        }
        view.invalidate();
    }

    private void applyTitleGradientShader(TextView view, int width, int startColor, int midColor, int endColor) {
        if (width <= 0) {
            return;
        }
        applyAnimatedTitleGradientShader(view, width, 0f, startColor, midColor, endColor);
    }

    private void applyAnimatedTitleGradientShader(TextView view, int width, float phase, int startColor, int midColor, int endColor) {
        if (width <= 0) {
            return;
        }
        // 色阶重构：超高亮炽白冰蓝流光——去绿，浅亮蓝+超白，亮度拉满
        int highGlowCyan = Color.rgb(150, 235, 255);  // 浅亮蓝白
        int iceCyan = Color.rgb(50, 210, 255);        // 极亮电光冰蓝
        int superHotCore = Color.rgb(255, 255, 255);  // 超亮炽白核心

        float normalizedPhase = phase - (float) Math.floor(phase);
        float offset = normalizedPhase * width;
        view.getPaint().setShader(new LinearGradient(
                offset, 0, width + offset, 0,
                new int[]{
                        highGlowCyan,
                        iceCyan,
                        superHotCore,
                        iceCyan,
                        highGlowCyan
                },
                new float[]{0.0f, 0.28f, 0.5f, 0.72f, 1.0f},
                Shader.TileMode.REPEAT));
    }

    private void applyAnimatedStatusShimmerShader(TextView view, int width, float phase, int startColor, int endColor) {
        if (width <= 0) {
            return;
        }
        // 状态文字统一精简5色标超炽白冰蓝色阶（去绿，浅亮蓝+超白）
        int highGlowCyan = Color.rgb(150, 235, 255);  // 浅亮蓝白
        int iceCyan = Color.rgb(50, 210, 255);        // 极亮电光冰蓝
        int superHotCore = Color.rgb(255, 255, 255);  // 超亮炽白核心

        float normalizedPhase = phase - (float) Math.floor(phase);
        float offset = normalizedPhase * width;
        view.getPaint().setShader(new LinearGradient(
                offset, 0, width + offset, 0,
                new int[]{
                        highGlowCyan,
                        iceCyan,
                        superHotCore,
                        iceCyan,
                        highGlowCyan
                },
                new float[]{0.0f, 0.28f, 0.5f, 0.72f, 1.0f},
                Shader.TileMode.REPEAT));
    }

    private int settingsTitleGradientWidth(TextView view) {
        if (view == null) {
            return 1;
        }
        int viewWidth = Math.max(1, view.getWidth());
        CharSequence text = view.getText();
        if (text != null && text.length() > 0) {
            // 关键修复：计算渐变宽度时，应减去我们为了预留光晕空间所增加的 padding，
            // 否则渐变计算会以增加了 Padding 后的总宽度为准，导致流光渐变视觉中心发生偏移。
            int textWidth = (int) Math.ceil(view.getPaint().measureText(text.toString()));
            return Math.max(1, Math.min(viewWidth, textWidth));
        }
        return viewWidth;
    }

    private TextView dialogTitleView(String text) {
        TextView title = gradientTitleView(text);
        title.setText(text);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(title);
        title.setPadding(dp(20), dp(16), dp(20), dp(6));
        return title;
    }

    private void styleDialog(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(Color.rgb(18, 22, 34));
            bg.setStroke(dp(1), Color.argb(50, 255, 255, 255));
            bg.setCornerRadius(dp(20));
            window.setBackgroundDrawable(bg);
            window.setGravity(android.view.Gravity.CENTER);
            android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            params.x = 0;
            params.y = 0;
            window.setAttributes(params);
            window.setLayout(params.width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        TextView title = (TextView) dialog.findViewById(android.R.id.title);
        if (title != null) {
            styleGradientTitle(title);
        }
        TextView message = (TextView) dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(Color.rgb(200, 210, 230));
            message.setPadding(dp(20), dp(8), dp(20), dp(8));
        }
        Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (neg != null) {
            neg.setTextColor(Color.rgb(160, 170, 190));
            neg.setAllCaps(false);
        }
        Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) {
            styleCyanGlowText(pos);
            pos.setAllCaps(false);
        }
        Button neu = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neu != null) {
            neu.setTextColor(Color.rgb(160, 170, 190));
        }
    }

    private ColorDrawable solidColorDrawable(int color) {
        return new ColorDrawable(color);
    }

    private void stylePlainWhiteText(TextView view) {
        view.getPaint().setShader(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        view.setTextColor(Color.WHITE);
        view.getPaint().clearShadowLayer();
        view.invalidate();
    }

    private void styleInactiveTabText(TextView view) {
        view.getPaint().setShader(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        view.setTextColor(Color.argb(140, 255, 255, 255));
        clearGlowFromTextView(view);
        view.getPaint().clearShadowLayer();
        view.invalidate();
    }

    private void styleActiveBottomTabText(TextView view) {
        if (view == null) {
            return;
        }
        styleSettingsTitleText(view);
    }

    private void styleDimPlainText(TextView view) {
        view.getPaint().setShader(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        view.setTextColor(Color.rgb(180, 190, 205));
        view.getPaint().clearShadowLayer();
        view.invalidate();
    }

    private void styleDropdownInactiveText(TextView view) {
        view.getPaint().setShader(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        view.setTextColor(Color.rgb(158, 168, 184));
        view.getPaint().clearShadowLayer();
        view.invalidate();
    }

    private void styleEqBandText(TextView view, boolean active) {
        if (active) {
            styleCyanGlowText(view);
        } else {
            styleDimPlainText(view);
        }
    }

    private void styleStatusText(boolean hasClip) {
        if (statusText == null) {
            return;
        }
        statusText.setPadding(dp(10), dp(4), dp(10), dp(4));
        if (!supported) {
            unregisterShimmerView(statusText);
            statusText.setTextColor(Color.rgb(150, 158, 172));
            statusText.getPaint().clearShadowLayer();
        } else if (hasClip) {
            unregisterShimmerView(statusText);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                statusText.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
            statusText.setTextColor(Color.rgb(255, 100, 100));
            statusText.setShadowLayer(dp(5), 0, 0, Color.argb(160, 255, 100, 100));
        } else {
            registerShimmerView(statusText);
            applyShimmerFrame(statusText, settingsTitleGradientWidth(statusText), currentShimmerPhaseForView(statusText));
        }
        statusText.invalidate();
    }

    private void styleModeText() {
        if (modeSpinner == null) {
            return;
        }
        boolean active = isModeVisualEnabled();
        if (!active) {
            unregisterShimmerView(modeSpinner);
            modeSpinner.getPaint().setShader(null);
            modeSpinner.setTextColor(Color.rgb(122, 145, 160));
            clearGlowFromTextView(modeSpinner);
            modeSpinner.invalidate();
            return;
        }
        registerShimmerView(modeSpinner);
        applyShimmerFrame(modeSpinner, settingsTitleGradientWidth(modeSpinner), currentShimmerPhaseForView(modeSpinner));
    }

    private void styleCyanGlowText(TextView view) {
        view.getPaint().setShader(null);
        view.setTextColor(Color.rgb(220, 255, 250));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        view.setShadowLayer(dp(5), 0, 0, Color.argb(135, 0, 245, 212));
    }

    private Drawable plainRoundRectDrawable(int fillColor, int strokeColor, int radiusPx) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fillColor);
        background.setStroke(dp(1), strokeColor);
        background.setCornerRadius(radiusPx);
        return background;
    }

    /**
     * 绘制上一步/下一步用的圆弧箭头图标。isRedo 为 true 时水平镜像得到下一步箭头。
     * 仅绘制白色描边 + 填充主体（避免宽描边光晕在硬件加速下产生锯齿）；
     * 通过 ColorFilter 在禁用时整体变暗。
     */
    private Drawable makeCurvedArrowDrawable(boolean isRedo) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path arcPath = new Path();
            private final Path headPath = new Path();
            private final android.graphics.RectF arcRect = new android.graphics.RectF();
            private final int sizePx = dp(18);

            @Override
            public int getIntrinsicWidth() { return sizePx; }
            @Override
            public int getIntrinsicHeight() { return sizePx; }

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                canvas.save();
                canvas.translate(b.exactCenterX(), b.exactCenterY());
                if (isRedo) canvas.scale(-1f, 1f);

                float r = sizePx * 0.40f;
                float strokeW = dpf(1.4f);
                arcRect.set(-r, -r, r, r);
                // 上一步箭头：从 165° 逆时针扫过 300°，缺口位于左侧，箭尖收在 225°（左上）并指向左侧。
                float startAngle = 165f;
                float sweepAngle = -300f;
                arcPath.reset();
                arcPath.arcTo(arcRect, startAngle, sweepAngle);

                double endRad = Math.toRadians(startAngle + sweepAngle);
                float tipX = r * (float) Math.cos(endRad);
                float tipY = r * (float) Math.sin(endRad);
                // 逆时针运动在角度 θ 处的切向：(sin θ, -cos θ)
                float tanX = (float) Math.sin(endRad);
                float tanY = -(float) Math.cos(endRad);
                float arrowLen = r * 0.62f;
                float halfW = r * 0.34f;
                float apexX = tipX + tanX * arrowLen;
                float apexY = tipY + tanY * arrowLen;
                float perpX = -tanY;
                float perpY = tanX;
                headPath.reset();
                headPath.moveTo(tipX, tipY);
                headPath.lineTo(tipX + perpX * halfW, tipY + perpY * halfW);
                headPath.lineTo(apexX, apexY);
                headPath.lineTo(tipX - perpX * halfW, tipY - perpY * halfW);
                headPath.close();

                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);

                // 白色主体（描边 + 填充）
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeW);
                paint.setColor(Color.WHITE);
                canvas.drawPath(arcPath, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(headPath, paint);

                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) { paint.setAlpha(alpha); }

            @Override
            public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }

            @Override
            public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private Drawable strokeGlowRoundRectDrawable(int fillColor, int strokeColor, int radiusPx, int glowRadiusPx, int glowColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float inset = dpf(0.75f);
                rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float radius = Math.max(1f, radiusPx - inset * 0.35f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fillColor);
                paint.clearShadowLayer();
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1.15f));
                paint.setColor(strokeColor);
                paint.setShadowLayer(glowRadiusPx, 0, 0, glowColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private Drawable verticalGradientStrokeGlowDrawable(int topColor, int bottomColor, int strokeColor,
                                                       int radiusPx, int glowRadiusPx, int glowColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float inset = dpf(0.75f);
                rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float radius = Math.max(1f, radiusPx - inset * 0.35f);

                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        topColor, bottomColor, Shader.TileMode.CLAMP));
                paint.clearShadowLayer();
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(strokeColor);
                paint.setShadowLayer(glowRadiusPx, 0, 0, glowColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private Drawable iconGlowDrawable(int glowColor) {
        return new AppIconBloomDrawable(glowColor);
    }

    private void applyIconGlowColor(Drawable icon) {
        Drawable bg = monitoredAppIconView.getBackground();
        if (!(bg instanceof AppIconBloomDrawable)) {
            return;
        }
        ((AppIconBloomDrawable) bg).setIcon(icon);
    }

    private void styleButton(Button button, boolean isPrimary, boolean isEnabled) {
        button.setEnabled(isEnabled);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        if (isPrimary) {
            if (isEnabled) {
                gd.setColors(new int[]{Color.rgb(0, 160, 255), Color.rgb(0, 229, 255)});
                gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                styleCyanGlowText(button);
            } else {
                gd.setColor(Color.argb(40, 80, 90, 100));
                button.setTextColor(Color.argb(100, 255, 255, 255));
                button.getPaint().clearShadowLayer();
            }
        } else {
            if (isEnabled) {
                gd.setColor(Color.argb(24, 255, 255, 255));
                gd.setStroke(dp(1), Color.argb(52, 255, 255, 255));
                if (button != undoButton && button != redoButton) {
                    styleCyanGlowText(button);
                }
            } else {
                gd.setColor(Color.argb(10, 255, 255, 255));
                gd.setStroke(dp(1), Color.argb(20, 255, 255, 255));
                button.setTextColor(Color.argb(80, 255, 255, 255));
                button.getPaint().clearShadowLayer();
            }
        }
        button.setBackground(gd);
        button.setAllCaps(false);
        if (button == undoButton || button == redoButton) {
            button.setPadding(0, 0, 0, 0);
            button.setForegroundGravity(android.view.Gravity.CENTER);
            Drawable icon = button.getForeground();
            if (icon != null) {
                icon.mutate();
                if (isEnabled) {
                    icon.setColorFilter(null);
                } else {
                    icon.setColorFilter(new android.graphics.PorterDuffColorFilter(
                            Color.argb(95, 255, 255, 255),
                            android.graphics.PorterDuff.Mode.SRC_IN));
                }
            }
        } else {
            button.setPadding(dp(8), 0, dp(8), 0);
        }
        
        button.setOnTouchListener((view, event) -> {
            if (!isEnabled) return false;
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    break;
            }
            return false;
        });
    }

    private void styleAccentButton(Button button, boolean isEnabled) {
        button.setEnabled(isEnabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            button.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        if (isEnabled) {
            button.setBackground(strokeGlowRoundRectDrawable(
                    Color.argb(24, 255, 255, 255),
                    Color.argb(160, 0, 245, 212),
                    dp(10),
                    dp(3),
                    Color.argb(85, 0, 245, 212)
            ));
            button.setTextColor(Color.argb(255, 220, 255, 250));
            button.setShadowLayer(dp(5), 0, 0, Color.argb(130, 0, 245, 212));
        } else {
            gd.setColor(Color.argb(15, 0, 245, 212));
            gd.setStroke(dp(1), Color.argb(40, 0, 245, 212));
            button.setTextColor(Color.argb(120, 220, 255, 250));
            button.getPaint().clearShadowLayer();
            button.setBackground(gd);
        }
        button.setAllCaps(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnTouchListener((view, event) -> {
            if (!isEnabled) return false;
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    break;
            }
            return false;
        });
    }

    private void switchToMainPage(int targetIndex, boolean animate) {
        View[] pages = mainPages();
        if (pages[0] == null || pages[1] == null || pages[2] == null) {
            activeMainPageIndex = clamp(targetIndex, 0, 2);
            return;
        }

        int previousIndex = clamp(activeMainPageIndex, 0, pages.length - 1);
        int nextIndex = clamp(targetIndex, 0, pages.length - 1);
        View current = pages[previousIndex];
        View target = pages[nextIndex];
        activeMainPageIndex = nextIndex;

        if (!animate || current == target) {
            for (int i = 0; i < pages.length; i++) {
                View page = pages[i];
                boolean visible = i == nextIndex;
                page.animate().cancel();
                page.setVisibility(visible ? View.VISIBLE : View.GONE);
                page.setAlpha(1f);
                page.setTranslationX(0f);
                page.setTranslationY(0f);
            }
            updateBottomNavSelection(nextIndex);
            updateCurveAnimationState(false);
            return;
        }
        updateCurveAnimationState(true);

        int width = mainPageHost != null ? mainPageHost.getWidth() : 0;
        if (width <= 0) {
            width = getWindow().getDecorView().getWidth();
        }
        if (width <= 0) {
            width = dp(320);
        }
        int direction = nextIndex > previousIndex ? 1 : -1;
        for (View page : pages) {
            if (page != current && page != target) {
                page.animate().cancel();
                page.setVisibility(View.GONE);
                page.setAlpha(1f);
                page.setTranslationX(0f);
                page.setTranslationY(0f);
            }
        }

        current.animate().cancel();
        target.animate().cancel();
        target.setVisibility(View.VISIBLE);
        target.setAlpha(1f);
        target.setTranslationY(0f);
        target.setTranslationX(direction * width);
        current.setVisibility(View.VISIBLE);
        current.setTranslationY(0f);
        current.setAlpha(1f);

        current.animate()
                .translationX(-direction * width)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    current.setVisibility(View.GONE);
                    current.setAlpha(1f);
                    current.setTranslationX(0f);
                })
                .start();
        target.animate()
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        updateBottomNavSelection(nextIndex);
        uiHandler.postDelayed(() -> updateCurveAnimationState(false), 210L);
    }

    private void updateBottomNavSelection(int activeIndex) {
        if (eqTabButton == null || extraTabButton == null || settingsTabButton == null) return;

        TextView[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        activeIndex = clamp(activeIndex, 0, tabs.length - 1);
        for (int i = 0; i < tabs.length; i++) {
            TextView tab = tabs[i];
            boolean active = (i == activeIndex);
            tab.setBackgroundColor(Color.TRANSPARENT);
            if (active) {
                unregisterShimmerView(tab);
                styleActiveBottomTabText(tab);
                tab.invalidate();
                registerShimmerView(tab);
                final TextView tabRef = tab;
                tab.post(() -> {
                    int w = tabRef.getWidth();
                    if (w > 0) {
                        styleActiveBottomTabText(tabRef);
                        tabRef.invalidate();
                    }
                });
            } else {
                unregisterShimmerView(tab);
                styleInactiveTabText(tab);
            }
        }
        updateBottomTabIndicator(activeIndex, true);
    }

    private void updateBottomTabIndicator(int activeIndex, boolean animate) {
        if (bottomTabIndicator == null) {
            return;
        }
        View parent = (View) bottomTabIndicator.getParent();
        if (parent == null) {
            return;
        }
        parent.post(() -> {
            if (bottomTabIndicator == null) {
                return;
            }
            float[] metrics = bottomTabIndicatorMetrics(Math.max(0f, Math.min(2f, activeIndex)));
            if (metrics == null) {
                return;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bottomTabIndicator.getLayoutParams();
            int targetWidth = Math.round(metrics[1]);
            int targetLeft = Math.round(metrics[0]);
            if (params.width != targetWidth || params.leftMargin != targetLeft) {
                params.width = targetWidth;
                params.leftMargin = targetLeft;
                bottomTabIndicator.setLayoutParams(params);
            }
            if (animate) {
                bottomTabIndicator.animate()
                        .translationX(0f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            } else {
                bottomTabIndicator.animate().cancel();
                bottomTabIndicator.setTranslationX(0f);
            }
        });
    }

    private void updateBottomTabIndicatorProgress(float pagePosition) {
        if (bottomTabIndicator == null) {
            return;
        }
        View parent = (View) bottomTabIndicator.getParent();
        if (parent == null) {
            return;
        }
        float[] metrics = bottomTabIndicatorMetrics(pagePosition);
        if (metrics == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bottomTabIndicator.getLayoutParams();
        int targetWidth = Math.round(metrics[1]);
        int targetLeft = Math.round(metrics[0]);
        if (params.width != targetWidth || params.leftMargin != targetLeft) {
            params.width = targetWidth;
            params.leftMargin = targetLeft;
            bottomTabIndicator.setLayoutParams(params);
        }
        bottomTabIndicator.animate().cancel();
        bottomTabIndicator.setTranslationX(0f);
    }

    private float[] bottomTabIndicatorMetrics(float pagePosition) {
        if (bottomTabIndicator == null || bottomTabStrip == null || eqTabButton == null || extraTabButton == null || settingsTabButton == null) {
            return null;
        }
        View parent = (View) bottomTabIndicator.getParent();
        if (parent == null || parent.getWidth() <= 0 || bottomTabStrip.getWidth() <= 0) {
            return null;
        }
        TextView[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        float clamped = Math.max(0f, Math.min(2f, pagePosition));
        int leftIndex = (int) Math.floor(clamped);
        int rightIndex = Math.min(tabs.length - 1, leftIndex + 1);
        float t = clamped - leftIndex;

        float leftStart = tabIndicatorLeftForTab(tabs[leftIndex]);
        float widthStart = tabIndicatorWidthForTab(tabs[leftIndex]);
        float leftEnd = tabIndicatorLeftForTab(tabs[rightIndex]);
        float widthEnd = tabIndicatorWidthForTab(tabs[rightIndex]);
        if (widthStart <= 0f || widthEnd <= 0f) {
            return null;
        }
        float left = leftStart + (leftEnd - leftStart) * t;
        float width = widthStart + (widthEnd - widthStart) * t;
        return new float[]{left, width};
    }

    private float tabIndicatorLeftForTab(TextView tab) {
        if (tab == null || bottomTabStrip == null) {
            return 0f;
        }
        // indicator 与 strip 同为 nav(FrameLayout)子 view，leftMargin 相对 nav 内容区(已含 padding)。
        // tab.getLeft() 已是相对 strip 的位置，直接用即可，不能再加 strip.getLeft()，
        // 否则会多偏移一个 nav 的 padding，导致 indicator 框整体右移与 tab 不对齐。
        float buttonLeftInStrip = tab.getLeft();
        float indicatorWidth = tabIndicatorWidthForTab(tab);
        return buttonLeftInStrip + (tab.getWidth() - indicatorWidth) / 2f;
    }

    private float tabIndicatorWidthForTab(TextView tab) {
        if (tab == null || tab.getWidth() <= 0) {
            return 0f;
        }
        // 统一为 tab 宽度的固定比例，保证三个 tab 的 indicator 框宽度完全一致，
        // 视觉对齐整齐。基本占据 1/3 位置（留少量边距）。
        return tab.getWidth() * 0.92f;
    }

    private View[] mainPages() {
        return new View[]{eqPage, extraPage, settingsPage};
    }

    private View activeMainPageView() {
        View[] pages = mainPages();
        return pages[clamp(activeMainPageIndex, 0, pages.length - 1)];
    }

    private boolean shouldBlockPageSwipe(View view, float rawX, float rawY) {
        if (monitorSettingsOpen) {
            return true;
        }
        View current = view;
        while (current != null) {
            if (current instanceof VerticalReverbSlider) {
                return current.isEnabled();
            }
            if (current instanceof HorizontalBassSlider) {
                return current.isEnabled() && ((HorizontalBassSlider) current).isSwipeHandleHit(rawX, rawY);
            }
            if (current instanceof HorizontalScrollView
                    || current instanceof KnobView) {
                return current.isEnabled();
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private View findDeepestChildUnder(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (rawX < location[0] || rawX > location[0] + view.getWidth()
                || rawY < location[1] || rawY > location[1] + view.getHeight()) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = findDeepestChildUnder(group.getChildAt(i), rawX, rawY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return view;
    }

    private final class MainPageHost extends FrameLayout {
        private float downX;
        private float downY;
        private boolean trackSwipe;
        private boolean handlingSwipe;
        private float dragOffset;
        private int dragTargetIndex = -1;

        MainPageHost(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (monitorSettingsOpen) {
                trackSwipe = false;
                handlingSwipe = false;
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    handlingSwipe = false;
                    dragOffset = 0f;
                    dragTargetIndex = -1;
                    View hit = findDeepestChildUnder(activeMainPageView(), downX, downY);
                    trackSwipe = hit == null || !shouldBlockPageSwipe(hit, downX, downY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (trackSwipe) {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > dpf(8f) && Math.abs(dx) > Math.abs(dy) * 0.85f) {
                            handlingSwipe = true;
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    trackSwipe = false;
                    handlingSwipe = false;
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (monitorSettingsOpen) {
                return false;
            }
            if (!trackSwipe && !handlingSwipe) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    handlingSwipe = true;
                    updatePageDrag(event.getRawX() - downX);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - downX;
                    if (handlingSwipe) {
                        finishPageDrag(dx);
                    }
                    trackSwipe = false;
                    handlingSwipe = false;
                    dragOffset = 0f;
                    dragTargetIndex = -1;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelPageDrag();
                    trackSwipe = false;
                    handlingSwipe = false;
                    dragOffset = 0f;
                    dragTargetIndex = -1;
                    return true;
                default:
                    return true;
            }
        }

        private void updatePageDrag(float rawOffset) {
            int width = getWidth();
            if (width <= 0) {
                return;
            }

            int proposedTarget = activeMainPageIndex + (rawOffset < 0 ? 1 : -1);
            if (Math.abs(rawOffset) < 1f || proposedTarget < 0 || proposedTarget >= mainPages().length) {
                proposedTarget = -1;
            }
            dragTargetIndex = proposedTarget;
            float appliedOffset = rawOffset;
            if (dragTargetIndex == -1) {
                appliedOffset *= 0.22f;
            }
            dragOffset = appliedOffset;
            float pagePosition = activeMainPageIndex;
            if (dragTargetIndex != -1) {
                pagePosition += -appliedOffset / width;
            }
            applyPagePosition(pagePosition);
        }

        private void finishPageDrag(float rawOffset) {
            int width = getWidth();
            if (width <= 0 || dragTargetIndex == -1) {
                cancelPageDrag();
                return;
            }

            float progress = Math.abs(dragOffset) / width;
            boolean shouldAdvance = progress > 0.16f || Math.abs(rawOffset) > dpf(42f);
            if (shouldAdvance) {
                settleToPage(dragTargetIndex);
            } else {
                cancelPageDrag();
            }
        }

        private void cancelPageDrag() {
            settleToPage(activeMainPageIndex);
        }

        private void applyPagePosition(float pagePosition) {
            View[] pages = mainPages();
            int width = getWidth();
            if (width <= 0) {
                return;
            }
            float clampedPosition = Math.max(0f, Math.min(pages.length - 1, pagePosition));
            for (int i = 0; i < pages.length; i++) {
                View page = pages[i];
                page.animate().cancel();
                page.setAlpha(1f);
                float translation = (i - clampedPosition) * width;
                page.setTranslationX(translation);
                if (Math.abs(i - clampedPosition) <= 1.05f) {
                    page.setVisibility(View.VISIBLE);
                } else {
                    page.setVisibility(View.GONE);
                }
            }
            updateBottomTabIndicatorProgress(clampedPosition);
            updateCurveAnimationState(Math.abs(clampedPosition - Math.round(clampedPosition)) > 0.001f);
        }

        private void settleToPage(int pageIndex) {
            View[] pages = mainPages();
            int width = getWidth();
            if (width <= 0) {
                activeMainPageIndex = clamp(pageIndex, 0, pages.length - 1);
                updateBottomNavSelection(activeMainPageIndex);
                updateCurveAnimationState(false);
                return;
            }
            int nextIndex = clamp(pageIndex, 0, pages.length - 1);
            updateCurveAnimationState(true);
            View activePage = pages[nextIndex];
            for (int i = 0; i < pages.length; i++) {
                View page = pages[i];
                View pageRef = page;
                page.animate().cancel();
                pageRef.setVisibility(View.VISIBLE);
                pageRef.animate()
                        .translationX((i - nextIndex) * width)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> {
                            if (pageRef != activePage) {
                                pageRef.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
            activeMainPageIndex = nextIndex;
            updateBottomNavSelection(nextIndex);
            uiHandler.postDelayed(() -> updateCurveAnimationState(false), 210L);
        }

        private void updatePagePositionFromTab(float pagePosition) {
            dragTargetIndex = clamp(Math.round(pagePosition), 0, mainPages().length - 1);
            dragOffset = (activeMainPageIndex - pagePosition) * getWidth();
            applyPagePosition(pagePosition);
        }

        private void settleToTabPosition(float pagePosition) {
            settleToPage(clamp(Math.round(pagePosition), 0, mainPages().length - 1));
        }
    }

    private final class SlidingTabBar extends FrameLayout {
        private float downX;
        private float downY;
        private float dragStartPagePosition;
        private boolean dragging;

        SlidingTabBar(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    dragStartPagePosition = activeMainPageIndex;
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > dpf(10f) && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (bottomTabIndicator == null) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    dragStartPagePosition = activeMainPageIndex;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragging = true;
                    updateDragFromTouch(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        settleDragFromTouch(event.getX());
                        dragging = false;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (mainPageHost != null) {
                        mainPageHost.cancelPageDrag();
                    } else {
                        updateBottomTabIndicator(activeMainPageIndex, true);
                    }
                    dragging = false;
                    return true;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        private void updateDragFromTouch(float x) {
            if (mainPageHost == null) {
                return;
            }
            int trackWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            if (trackWidth <= 0) {
                return;
            }
            float tabWidth = trackWidth / 3f;
            if (tabWidth <= 0f) {
                return;
            }
            float deltaPages = (x - downX) / tabWidth;
            float desiredPosition = Math.max(0f, Math.min(2f, dragStartPagePosition + deltaPages));
            mainPageHost.updatePagePositionFromTab(desiredPosition);
        }

        private void settleDragFromTouch(float x) {
            if (mainPageHost == null) {
                return;
            }
            int trackWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            if (trackWidth <= 0) {
                return;
            }
            float tabWidth = trackWidth / 3f;
            if (tabWidth <= 0f) {
                return;
            }
            float deltaPages = (x - downX) / tabWidth;
            float desiredPosition = Math.max(0f, Math.min(2f, dragStartPagePosition + deltaPages));
            mainPageHost.settleToTabPosition(desiredPosition);
        }
    }

    private void updateBottomNavSelectionLegacy(int activeIndex) {
        if (eqTabButton == null || extraTabButton == null || settingsTabButton == null) return;
        
        TextView[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        for (int i = 0; i < tabs.length; i++) {
            TextView tab = tabs[i];
            boolean active = (i == activeIndex);
            if (active) {
                tab.setBackground(strokeGlowRoundRectDrawable(
                        Color.argb(24, 255, 255, 255),
                        Color.argb(160, 0, 245, 212),
                        dp(12),
                        dp(3),
                        Color.argb(85, 0, 245, 212)
                ));
                // 底部 Tab 流光：与所有标题共享蓝绿亮色 + 深蓝点缀 + 白热核心的统一主题
                applyTitleGradientShader(tab, settingsTitleGradientWidth(tab),
                        Color.rgb(0, 255, 230), Color.rgb(120, 220, 255), Color.rgb(180, 100, 255));
                tab.setTextColor(Color.WHITE);
                // tab 不设 shadowLayer：硬件加速下 shadowLayer 会触发 TextView 走 software
                // path 渲染文字，导致 paint shader 不生效（流光不动）+ GPU blur 锯齿。
                // hotCore 白热核心已作视觉焦点。
                tab.getPaint().clearShadowLayer();
                tab.invalidate();
                registerShimmerView(tab);
            } else {
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.RECTANGLE);
                gd.setCornerRadius(dp(12));
                gd.setColor(Color.TRANSPARENT);
                tab.setBackground(gd);
                unregisterShimmerView(tab);
                styleInactiveTabText(tab);
            }
        }
    }

    private String shizukuAccessLabelText() {
        return tr("Shizuku access", "Shizuku \u6388\u6743");
    }

    private String shizukuAccessButtonText() {
        return ShizukuCompat.hasPermission()
                ? tr("Refresh Shizuku", "\u5237\u65b0 Shizuku")
                : tr("Authorize Shizuku", "\u6388\u6743 Shizuku");
    }

    private String shizukuAccessStatusText() {
        String status = repository == null ? "Shizuku mute is idle." : repository.loadShizukuMuteStatus();
        if (!isChineseUi()) {
            return status;
        }
        if ("Shizuku mute is idle.".equals(status)) return "Shizuku \u9759\u97f3\u7a7a\u95f2\u4e2d\u3002";
        if ("Shizuku mute requires Android 9 or later.".equals(status)) return "Shizuku \u9759\u97f3\u9700\u8981 Android 9 \u6216\u66f4\u9ad8\u7248\u672c\u3002";
        if ("Shizuku mute ready. Enable EQ to start.".equals(status)) return "Shizuku \u9759\u97f3\u5df2\u5c31\u7eea\uff0c\u5f00\u542f EQ \u540e\u542f\u52a8\u3002";
        if ("Install Shizuku first.".equals(status)) return "\u8bf7\u5148\u5b89\u88c5 Shizuku\u3002";
        if ("Start the Shizuku service first.".equals(status)) return "\u8bf7\u5148\u542f\u52a8 Shizuku \u670d\u52a1\u3002";
        if ("Authorize Shizuku for session mute.".equals(status)) return "\u8bf7\u5148\u6388\u6743 Shizuku\uff0c\u4ee5\u9759\u97f3\u5176\u4ed6 App \u7684 session\u3002";
        if ("Shizuku is ready.".equals(status)) return "Shizuku \u5df2\u5c31\u7eea\u3002";
        if ("Unable to resolve the selected app.".equals(status)) return "\u65e0\u6cd5\u89e3\u6790\u5f53\u524d\u9009\u62e9\u7684 App\u3002";
        if ("Waiting for active playback sessions.".equals(status)) return "\u6b63\u5728\u7b49\u5f85\u6d3b\u8dc3\u7684\u64ad\u653e session\u3002";
        if (status.startsWith("Waiting for ") && status.endsWith(" sessions.")) {
            return "\u6b63\u5728\u7b49\u5f85 " + status.substring("Waiting for ".length(), status.length() - " sessions.".length()) + " \u7684\u64ad\u653e session\u3002";
        }
        if (status.startsWith("Muted ") && status.endsWith(".")) {
            return "\u5df2\u4e3a " + status.substring("Muted ".length(), status.length() - 1) + " \u6267\u884c\u9759\u97f3\u3002";
        }
        if ("Shizuku mute poll failed. Re-open Shizuku and try again.".equals(status)) {
            return "Shizuku \u8f6e\u8be2\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u6253\u5f00 Shizuku \u540e\u518d\u8bd5\u3002";
        }
        return status;
    }

    private void handleShizukuAccessAction() {
        if (processingMode != ProcessingMode.SHIZUKU_MUTE) {
            Toast.makeText(this, tr("Switch to Shizuku Mode first", "请先切换到 Shizuku Mode 模式"), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean granted = ShizukuCompat.requestPermissionOrOpenManager(this, REQUEST_SHIZUKU_PERMISSION);
        String status = ShizukuCompat.describeState(this);
        if (granted) {
            ShizukuCompat.grantPermissionsAndAppOps(this);
        }
        repository.saveShizukuMuteStatus(status, granted);
        if (!granted) {
            Toast.makeText(this, tr("Open Shizuku and grant access, then return here", "\u8bf7\u6253\u5f00 Shizuku \u5b8c\u6210\u6388\u6743\u540e\u518d\u56de\u5230\u8fd9\u91cc"), Toast.LENGTH_SHORT).show();
        } else {
            ensureShizukuModeReady(true);
        }
        refreshRuntimeStatusUi();
    }

    private void handleShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQUEST_SHIZUKU_PERMISSION || repository == null) {
            return;
        }
        boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            ShizukuCompat.grantPermissionsAndAppOps(this);
        }
        repository.saveShizukuMuteStatus(ShizukuCompat.describeState(this), granted);
        if (granted) {
            ensureShizukuModeReady(true);
            Toast.makeText(this, tr("Shizuku access granted", "Shizuku \u6388\u6743\u5df2\u6210\u529f"), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, tr("Shizuku access was denied", "Shizuku \u6388\u6743\u88ab\u62d2\u7edd"), Toast.LENGTH_SHORT).show();
        }
        refreshRuntimeStatusUi();
    }

    private void handleShizukuStateChanged() {
        if (repository == null) {
            return;
        }
        repository.saveShizukuMuteStatus(
                ShizukuCompat.describeState(this),
                ShizukuCompat.hasPermission());
        uiHandler.post(this::refreshRuntimeStatusUi);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
