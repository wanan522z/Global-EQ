package com.example.globalpeq;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int HISTORY_LIMIT = 30;
    private static final int REQUEST_IMPORT_DEVICE_CURVE = 4101;
    private static final int REQUEST_IMPORT_TARGET_CURVE = 4102;
    private static final int EQ_EDIT_FIELD_FREQ = 0;
    private static final int EQ_EDIT_FIELD_GAIN = 1;
    private static final int EQ_EDIT_FIELD_Q = 2;
    private static final int GEQ_COMMIT_DELAY_MS = 160;
    private static final long ENABLE_TOGGLE_COMMIT_DELAY_MS = 110L;
    private static final long ENABLE_TOGGLE_UI_DELAY_MS = 48L;
    private static final long ENABLE_NEON_HEADER_DELAY_MS = 90L;
    private static final long ENABLE_NEON_CURVE_DELAY_MS = 460L;
    private static final long DISABLE_NEON_CURVE_DELAY_MS = 280L;
    private static final long ENABLE_NEON_PEQ_START_DELAY_MS = 660L;
    private static final long ENABLE_NEON_PEQ_STEP_DELAY_MS = 60L;
    private static final long EQ_EDIT_FADE_IN_MS = 180L;
    private static final long EQ_EDIT_FADE_OUT_MS = 160L;
    private static final String[] CURVE_RANGE_LABELS = {"±6", "±12", "±18"};
    private static final String[] CURVE_SMOOTHING_LABELS = {"Default", "1/3", "1/6", "1/12", "1/24"};
    private static final String[] REVERB_TYPE_LABELS = {"Default", "Hall", "Plate", "Chamber", "Room", "Studio"};
    private static final String[] BASS_MODE_LABELS = {"Default", "system", "dsp"};

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
    private MainPageHost mainPageHost;
    private LinearLayout rows;
    private KnobView cutoffKnob;
    private KnobView amountKnob;
    private HorizontalBassSlider bassBoostSlider;
    private KnobView reverbDecayKnob;
    private KnobView reverbPredelayKnob;
    private KnobView reverbSizeKnob;
    private KnobView reverbMixKnob;
    private TextView reverbTypeButton;
    private TextView bassModeButton;
    private EditText dspBassCutoffInput;
    private TextView reverbTitleView;
    private TextView bassBoostTitleView;
    private TextView virtualBassTitleView;
    private TextView statusText;
    private TextView engineStatusValueView;
    private ImageView monitoredAppIconView;
    private Spinner deviceSpinner;
    private Spinner savedPresetSpinner;
    private TextView modeSpinner;
    private TextView processingModeButton;
    private TextView advancedModeDetailButton;
    private TextView advancedMonitorAppButton;
    private TextView advancedModeSummaryView;
    private LinearLayout settingsRootContent;
    private LinearLayout advancedModeSettingsPage;
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
    private Switch virtualBassSwitch;
    private LinearLayout header;
    private Preset runningPreset;
    private Preset editingPreset;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // Slow-moving shimmer does not need 60fps; throttling it cuts a large amount of
    // software text redraw cost while keeping the motion visually smooth.
    private static final int SHIMMER_FPS_DELAY = 66;
    private long lastShimmerTime = 0L;
    // 记录每个 view 上次构建 shader 时所用的宽度；仅在尺寸变化时重建，避免每帧 GC 与重分配
    private final java.util.Map<TextView, Integer> shimmerLastWidth = new java.util.HashMap<>();
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
                int width;
                if (view == eqTabButton || view == extraTabButton || view == settingsTabButton || view == modeSpinner) {
                    width = settingsTitleGradientWidth(view);
                } else {
                    width = view.getWidth();
                }
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
            return;
        }

        boolean usesCustomGlow = view instanceof GlowTitleTextView || view instanceof GlowShimmerButton;
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
        long now = System.currentTimeMillis();
        for (int i = 0; i < shimmerTargetViews.size(); i++) {
            TextView view = shimmerTargetViews.get(i);
            Long freezeUntil = shimmerPhaseFreezeUntil.get(view);
            if (freezeUntil != null) {
                if (freezeUntil > now) {
                    continue;
                }
                shimmerPhaseFreezeUntil.remove(view);
            }
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

    private String shimmerViewName(TextView view) {
        if (view == null) {
            return "null";
        }
        if (view == statusText) {
            return "statusText";
        }
        if (view == modeSpinner) {
            return "modeSpinner";
        }
        if (view == reverbTitleView) {
            return "reverbTitle";
        }
        if (view == bassBoostTitleView) {
            return "bassBoostTitle";
        }
        if (view == virtualBassTitleView) {
            return "virtualBassTitle";
        }
        if (view == eqTabButton) {
            return "eqTab";
        }
        if (view == extraTabButton) {
            return "extraTab";
        }
        if (view == settingsTabButton) {
            return "settingsTab";
        }
        return view.getClass().getSimpleName() + "#" + Integer.toHexString(System.identityHashCode(view));
    }

    private void logShimmerEvent(String event, TextView view, String extra) {
        Log.d(SHIMMER_DEBUG_TAG,
                event
                        + " view=" + shimmerViewName(view)
                        + " t=" + SystemClock.uptimeMillis()
                        + " phase=" + currentShimmerPhaseForView(view)
                        + " width=" + settingsTitleGradientWidth(view)
                        + " extra=" + extra);
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
    // modeSpinner disabled：暗调优雅灰蓝（去绿，偏蓝，微亮流光，平缓静谧）
    private static final int[] SHIMMER_MODE_OFF_COLORS = {
            Color.rgb(105, 145, 175),  // 暗灰蓝
            Color.rgb(95, 140, 175),   // 暗灰冰蓝
            Color.rgb(190, 215, 235),  // 优雅灰白蓝核心
            Color.rgb(95, 140, 175),   // 暗灰冰蓝
            Color.rgb(105, 145, 175)   // 暗灰蓝
    };

    private final List<Preset> undoStack = new ArrayList<>();
    private final List<Preset> redoStack = new ArrayList<>();
    private Preset pendingGeqHistorySnapshot;
    private final Runnable commitGeqUpdateRunnable = this::commitPendingGeqUpdate;
    private final Runnable commitEnabledToggleRunnable = this::commitPendingEnabledToggle;
    private final Runnable refreshEnabledToggleUiRunnable = this::refreshPendingEnabledToggleUi;
    private final Runnable enableNeonHeaderRunnable = this::activateEnabledNeonHeader;
    private final Runnable enableNeonCurveRunnable = this::activateEnabledNeonCurve;
    private final Runnable disableNeonCurveRunnable = this::activateDisabledNeonCurve;
    private final Runnable enablePeqBandStepRunnable = this::activateNextPeqBandVisual;
    private boolean supported;
    private boolean updatingUi;
    private boolean autoSwitchOutput;
    private int curveGraphMaxDb = 18;
    private String selectedDeviceCurveName = "Default";
    private String selectedTargetCurveName = "Default";
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
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private boolean virtualBassEnabledState;
    private int activeMainPageIndex;
    private int selectedBassModeIndex;
    private ProcessingMode processingMode = ProcessingMode.SYSTEM_EQ;
    private AdvancedModeConfig advancedModeConfig = AdvancedModeConfig.DEFAULT;
    private Preset pendingEnabledApplyPreset;
    private Preset pendingEnabledPersistPreset;
    private boolean pendingEnabledUiRefresh;
    private boolean modeVisualEnabled = true;
    private boolean curveVisualEnabled = true;
    private boolean[] peqBandVisualEnabled = new boolean[0];
    private boolean peqVisualSequenceRunning;
    private int pendingPeqVisualIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new PresetRepository(this);
        engine = GlobalEqRuntime.engine();
        supported = true;
        autoSwitchOutput = repository.loadAutoSwitchOutput();
        processingMode = repository.loadProcessingMode();
        advancedModeConfig = repository.loadAdvancedModeConfig();
        selectedBassModeIndex = repository.loadBassBoostModeIndex();
        if (processingMode == ProcessingMode.SYSTEM_EQ && selectedBassModeIndex != 0) {
            selectedBassModeIndex = 0;
            repository.saveBassBoostModeIndex(0);
        }
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
        Preset loadedPreset = repository.loadPreset(currentDevice);
        Preset limitedPreset = limitPresetForHeadroom(loadedPreset);
        boolean loadedWasLimited = !limitedPreset.toJson().equals(loadedPreset.toJson());
        loadedPreset = limitedPreset;
        runningPreset = loadedPreset.withEnabled(loadedPreset.enabled && supported);
        modeVisualEnabled = runningPreset.enabled;
        curveVisualEnabled = runningPreset.enabled;
        Preset draftPreset = repository.loadDraftPreset();
        editingPreset = draftPreset == null ? runningPreset : limitPresetForHeadroom(draftPreset);
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();

        requestRuntimePermissions();
        setContentView(buildContent());
        installKeyboardVisibilityListener();
        renderAll();
        if (loadedWasLimited && runningPreset.enabled) {
            applyRunningPreset();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        deviceMonitor.start(this::handleDetectedOutputDevice);
    }

    @Override
    protected void onStop() {
        commitPendingGeqUpdate();
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.removeCallbacks(refreshEnabledToggleUiRunnable);
        cancelEnabledNeonSequence();
        refreshPendingEnabledToggleUi();
        commitPendingEnabledToggle();
        deviceMonitor.stop();
        super.onStop();
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
            shimmerLastWidth.remove(modeSpinner);
            registerShimmerView(modeSpinner);
        }
        updateBottomNavSelection(activeMainPageIndex);
    }

    @Override
    protected void onDestroy() {
        shimmerTargetViews.clear();
        shimmerLastWidth.clear();
        uiHandler.removeCallbacks(shimmerAnimationRunnable);
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.removeCallbacks(refreshEnabledToggleUiRunnable);
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
            if (keyboardVisible && !visibleNow && activeEqEditOverlay != null) {
                hideEqEditOverlay();
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
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
                deviceCurveGainOffsetDb = 0f;
                deviceCurveSmoothing = "Default";
                refreshDeviceCurveCache();
            } else {
                repository.saveTargetCurve(curve);
                selectedTargetCurveName = curve.name;
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                refreshTargetCurveCache();
            }
            syncCurrentCurveSettingsToEditingPreset(true);
            renderAll();
        } catch (IOException ex) {
            Toast.makeText(this, "Curve import failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDetectedOutputDevice(AudioOutputDevice device) {
        repository.saveKnownDevice(device);
        if (!autoSwitchOutput) {
            renderDeviceSpinner();
            return;
        }

        if (currentDevice != null && currentDevice.key.equals(device.key)) {
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            renderDeviceSpinner();
            if (runningPreset != null && runningPreset.enabled) {
                engine.reapplyForRouteChange(runningPreset);
            }
            return;
        }

        currentDevice = device;
        repository.saveSelectedDevice(currentDevice);
        Preset loadedPreset = repository.loadPreset(device);
        loadedPreset = limitPresetForHeadroom(loadedPreset);
        runningPreset = loadedPreset.withEnabled(loadedPreset.enabled && supported);
        Preset draftPreset = repository.loadDraftPreset();
        editingPreset = draftPreset == null ? runningPreset : limitPresetForHeadroom(draftPreset);
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();
        renderAll();
        applyRunningPreset();
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

        LinearLayout controlCard = new LinearLayout(this);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        controlCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        controlCard.setBackground(createGlassCard(35));
        eqPage.addView(controlCard, blockParams(0));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        controlCard.setClipChildren(false);
        controlCard.setClipToPadding(false);
        controlCard.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        modeSpinner = new GlowTitleTextView(this);
        modeSpinner.setTextSize(16);
        modeSpinner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        modeSpinner.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        modeSpinner.setSingleLine(true);
        modeSpinner.setIncludeFontPadding(false);
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
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
        );
        modeParams.leftMargin = dp(6);
        modeParams.rightMargin = dp(12);
        top.addView(modeSpinner, modeParams);

        monitoredAppIconView = new ImageView(this);
        monitoredAppIconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        monitoredAppIconView.setPadding(dp(4), dp(4), dp(4), dp(4));
        monitoredAppIconView.setBackground(createFieldBackground(11, 28, 6));
        monitoredAppIconView.setOnClickListener(v -> {
            if (processingMode == ProcessingMode.ADVANCED_DSP) {
                showAdvancedSettingsSubpage();
            }
        });
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.rightMargin = dp(10);
        top.addView(monitoredAppIconView, iconParams);

        View switchSpacer = new View(this);
        top.addView(switchSpacer, new LinearLayout.LayoutParams(
                0,
                1,
                1f
        ));

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("");
        enabledSwitch.setShowText(false);
        enabledSwitch.setEnabled(supported);
        enabledSwitch.setOnCheckedChangeListener(this::onEnabledChanged);
        styleTopSwitch(enabledSwitch, false);
        autoSwitchOutputSwitch = new Switch(this);
        autoSwitchOutputSwitch.setText("");
        autoSwitchOutputSwitch.setShowText(false);
        autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        autoSwitchOutputSwitch.setOnCheckedChangeListener(this::onAutoSwitchOutputChanged);
        styleTopSwitch(autoSwitchOutputSwitch, true);
        statusText = gradientTitleView("");
        statusText.setTextSize(12);
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(dp(10), dp(4), dp(10), dp(4));
        styleStatusText(false);
        int controlGap = 12;
        LinearLayout.LayoutParams autoSwitchParams = new LinearLayout.LayoutParams(
                dp(60),
                dp(30)
        );
        autoSwitchParams.rightMargin = dp(2);
        top.addView(autoSwitchOutputSwitch, autoSwitchParams);

        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.rightMargin = dp(2);
        top.addView(statusText, statusParams);

        top.addView(enabledSwitch, new LinearLayout.LayoutParams(dp(60), dp(30)));

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
                if (!name.equals(runningPreset.name)) {
                    loadPresetLive(name);
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
        listCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        listCard.setBackground(createGlassCard(30));

        LinearLayout.LayoutParams listCardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                2f
        );
        listCardParams.topMargin = dp(12);
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
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
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
        root.addView(buildBottomNav(), bottomNavParams);

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

        TextView title = gradientTitleView("Engine Status");
        title.setText("Engine Status");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(title);
        LinearLayout.LayoutParams engineTitleParams = blockParams(0);
        // 抵消 gradientTitleView 的左 padding(22dp)，让标题文字左缘对齐下方 detail 正文（都从 panel 内容区左边开始）。
        // title view 左移进入 panel padding 区的 22dp 正好是空白 leftPadding，shadow 半径 5.5dp 仍落在 panel 16dp padding 内，不裁剪。
        engineTitleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(title, 12);
        panel.addView(title, engineTitleParams);

        TextView detail = new TextView(this);
        detail.setText("Displays the real-time status of your Global PEQ processor.");
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(160, 170, 190));
        panel.addView(detail, blockParams(2));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel.addView(statusRow, blockParams(12));

        TextView statusLabel = new TextView(this);
        statusLabel.setText("System Processing:");
        statusLabel.setTextSize(14);
        statusLabel.setTextColor(Color.rgb(200, 210, 230));
        statusRow.addView(statusLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        engineStatusValueView = new TextView(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && h > 0 && supported) {
                    getPaint().setShader(new LinearGradient(
                            0, 0, w, 0,
                            new int[]{Color.rgb(0, 255, 255), Color.rgb(120, 200, 255)},
                            null, Shader.TileMode.CLAMP));
                } else if (w > 0 && h > 0) {
                    getPaint().setShader(new LinearGradient(
                            0, 0, w, 0,
                            new int[]{Color.rgb(255, 100, 100), Color.rgb(255, 160, 160)},
                            null, Shader.TileMode.CLAMP));
                }
            }
        };
        engineStatusValueView.setText(engineStatusText());
        engineStatusValueView.setTextSize(14);
        engineStatusValueView.setTextColor(supported ? Color.rgb(0, 255, 255) : Color.rgb(255, 100, 100));
        engineStatusValueView.postInvalidate();
        engineStatusValueView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusRow.addView(engineStatusValueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel.addView(modeRow, blockParams(4));

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Processing mode");
        modeLabel.setTextSize(14);
        modeLabel.setTextColor(Color.rgb(200, 210, 230));
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        processingModeButton = createExtraChoiceButton();
        processingModeButton.setText(processingMode.label);
        processingModeButton.setOnClickListener(v -> showLimitedChoiceMenu(processingModeButton, ProcessingMode.labels(), processingMode.ordinal(), position -> {
            ProcessingMode nextMode = ProcessingMode.values()[clamp(position, 0, ProcessingMode.values().length - 1)];
            if (processingMode != nextMode) {
                setProcessingMode(nextMode);
            }
        }));
        modeRow.addView(processingModeButton, new LinearLayout.LayoutParams(dp(152), dp(30)));

        advancedModeDetailButton = createExtraChoiceButton();
        advancedModeDetailButton.setText("Monitor DSP Settings");
        advancedModeDetailButton.setOnClickListener(v -> showAdvancedSettingsSubpage());
        panel.addView(advancedModeDetailButton, blockParams(12));

        advancedModeSummaryView = new TextView(this);
        advancedModeSummaryView.setText(advancedModeSummaryText());
        advancedModeSummaryView.setTextSize(12);
        advancedModeSummaryView.setTextColor(Color.rgb(180, 190, 210));
        panel.addView(advancedModeSummaryView, blockParams(4));

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

        TextView aboutTitle = gradientTitleView("About Global PEQ");
        aboutTitle.setText("About Global PEQ");
        aboutTitle.setTextSize(18);
        aboutTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(aboutTitle);
        LinearLayout.LayoutParams aboutTitleParams = blockParams(0);
        // 抵消 gradientTitleView 的左 padding(22dp)，让标题文字左缘对齐下方 aboutText 正文（都从 panel 内容区左边开始）。
        aboutTitleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(aboutTitle, 12);
        aboutPanel.addView(aboutTitle, aboutTitleParams);

        TextView aboutText = new TextView(this);
        aboutText.setText("Global PEQ is a low-latency, audiophile-grade Parametric Equalizer running natively on Android's high-performance audio routing engine. Enjoy tailored, professional equalization for all your playback devices.");
        aboutText.setTextSize(13);
        aboutText.setTextColor(Color.rgb(180, 190, 210));
        aboutPanel.addView(aboutText, blockParams(8));

        TextView footerText = new TextView(this);
        footerText.setText("Version 0.1 - Powered by Gemini 3.5");
        footerText.setTextSize(11);
        footerText.setTextColor(Color.rgb(100, 110, 130));
        footerText.setGravity(android.view.Gravity.CENTER);
        
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerParams.topMargin = dp(32);
        settingsRootContent.addView(footerText, footerParams);

        advancedModeSettingsPage = new LinearLayout(this);
        advancedModeSettingsPage.setOrientation(LinearLayout.VERTICAL);
        advancedModeSettingsPage.setVisibility(View.GONE);
        advancedModeSettingsPage.setPadding(0, 0, 0, dp(12));
        page.addView(advancedModeSettingsPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        buildAdvancedSettingsPage(advancedModeSettingsPage);
    }

    private void buildAdvancedSettingsPage(LinearLayout page) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));
        page.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView backButton = createExtraChoiceButton();
        backButton.setText("Back");
        backButton.setOnClickListener(v -> hideAdvancedSettingsSubpage());
        panel.addView(backButton, blockParams(0));

        TextView title = gradientTitleView("Monitor DSP Settings");
        title.setText("Monitor DSP Settings");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        styleGradientTitle(title);
        LinearLayout.LayoutParams titleParams = blockParams(10);
        titleParams.leftMargin = -dp(22);
        reserveStartGlowWithoutMoving(title, 12);
        panel.addView(title, titleParams);

        TextView detail = new TextView(this);
        detail.setText("Pick the app to monitor and tune latency-oriented parameters for the second backend.");
        detail.setTextSize(12);
        detail.setTextColor(Color.rgb(160, 170, 190));
        panel.addView(detail, blockParams(2));

        advancedMonitorAppButton = createExtraChoiceButton();
        advancedMonitorAppButton.setText(advancedModeConfig.monitoredAppLabel.isEmpty()
                ? "Choose app"
                : advancedModeConfig.monitoredAppLabel);
        advancedMonitorAppButton.setOnClickListener(v -> showMonitoredAppChoiceDialog());
        panel.addView(labeledSettingsRow("Monitored app", advancedMonitorAppButton), blockParams(12));

        panel.addView(createAdvancedNumberRow("Latency (ms)", String.valueOf(advancedModeConfig.latencyMs), "20-400", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLatencyMs(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow("Buffer (frames)", String.valueOf(advancedModeConfig.bufferSizeFrames), "128-4096", value ->
                updateAdvancedModeConfig(advancedModeConfig.withBufferSizeFrames(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow("Poll interval (ms)", String.valueOf(advancedModeConfig.monitorIntervalMs), "100-5000", value ->
                updateAdvancedModeConfig(advancedModeConfig.withMonitorIntervalMs(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow("Lookahead (ms)", String.valueOf(advancedModeConfig.lookaheadMs), "0-120", value ->
                updateAdvancedModeConfig(advancedModeConfig.withLookaheadMs(value))), blockParams(6));
        panel.addView(createAdvancedNumberRow("DSP wet mix (%)", String.valueOf(advancedModeConfig.wetMixPercent), "0-100", value ->
                updateAdvancedModeConfig(advancedModeConfig.withWetMixPercent(value))), blockParams(6));
    }

    private View labeledSettingsRow(String labelText, View trailingView) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(14);
        label.setTextColor(Color.rgb(200, 210, 230));
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

    private String engineStatusText() {
        if (!supported) {
            return "UNSUPPORTED";
        }
        return processingMode == ProcessingMode.ADVANCED_DSP ? "MONITOR DSP" : "SYSTEM EQ";
    }

    private String advancedModeSummaryText() {
        if (processingMode != ProcessingMode.ADVANCED_DSP) {
            return "Default mode keeps the existing system EQ and virtual bass path unchanged.";
        }
        String appLabel = advancedModeConfig.monitoredAppLabel.isEmpty() ? "No app selected" : advancedModeConfig.monitoredAppLabel;
        return String.format(Locale.US,
                "App: %s  |  %d ms  |  %d frames  |  poll %d ms",
                appLabel,
                advancedModeConfig.latencyMs,
                advancedModeConfig.bufferSizeFrames,
                advancedModeConfig.monitorIntervalMs);
    }

    private void setProcessingMode(ProcessingMode nextMode) {
        processingMode = nextMode == null ? ProcessingMode.SYSTEM_EQ : nextMode;
        repository.saveProcessingMode(processingMode);
        if (processingMode == ProcessingMode.SYSTEM_EQ) {
            selectedBassModeIndex = 0;
            repository.saveBassBoostModeIndex(selectedBassModeIndex);
            hideAdvancedSettingsSubpage();
            if (editingPreset != null && !"Default".equals(editingPreset.reverbType)) {
                setEditingPreset(editingPreset.withReverbType("Default"), true);
            } else {
                applyRunningPreset();
            }
        } else {
            applyRunningPreset();
        }
        renderAll();
    }

    private void showAdvancedSettingsSubpage() {
        if (advancedModeSettingsPage == null || settingsRootContent == null) {
            return;
        }
        settingsRootContent.setVisibility(View.GONE);
        advancedModeSettingsPage.setVisibility(View.VISIBLE);
    }

    private void hideAdvancedSettingsSubpage() {
        if (advancedModeSettingsPage == null || settingsRootContent == null) {
            return;
        }
        advancedModeSettingsPage.setVisibility(View.GONE);
        settingsRootContent.setVisibility(View.VISIBLE);
    }

    private void updateAdvancedModeConfig(AdvancedModeConfig nextConfig) {
        advancedModeConfig = nextConfig == null ? AdvancedModeConfig.DEFAULT : nextConfig;
        repository.saveAdvancedModeConfig(advancedModeConfig);
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

    private void showMonitoredAppChoiceDialog() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(launcherIntent, 0);
        List<ResolveInfo> unique = new ArrayList<>();
        List<String> seenPackages = new ArrayList<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            if (seenPackages.contains(info.activityInfo.packageName)) {
                continue;
            }
            seenPackages.add(info.activityInfo.packageName);
            unique.add(info);
        }
        unique.sort((left, right) -> {
            CharSequence leftLabel = left.loadLabel(getPackageManager());
            CharSequence rightLabel = right.loadLabel(getPackageManager());
            String l = leftLabel == null ? "" : leftLabel.toString();
            String r = rightLabel == null ? "" : rightLabel.toString();
            return l.compareToIgnoreCase(r);
        });
        if (unique.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[unique.size()];
        int selected = 0;
        for (int i = 0; i < unique.size(); i++) {
            ResolveInfo info = unique.get(i);
            String packageName = info.activityInfo.packageName;
            CharSequence label = info.loadLabel(getPackageManager());
            labels[i] = label == null ? packageName : label.toString();
            if (packageName.equals(advancedModeConfig.monitoredAppPackage)) {
                selected = i;
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose monitored app")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    ResolveInfo info = unique.get(which);
                    String packageName = info.activityInfo.packageName;
                    CharSequence label = info.loadLabel(getPackageManager());
                    updateAdvancedModeConfig(advancedModeConfig.withMonitoredApp(
                            packageName,
                            label == null ? packageName : label.toString()));
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog));
        dialog.show();
    }

    private void updateMonitoredAppIcon() {
        if (monitoredAppIconView == null) {
            return;
        }
        monitoredAppIconView.setImageDrawable(loadMonitoredAppDrawable());
        monitoredAppIconView.setAlpha(processingMode == ProcessingMode.ADVANCED_DSP ? 1f : 0.6f);
    }

    private Drawable loadMonitoredAppDrawable() {
        if (advancedModeConfig.monitoredAppPackage != null && !advancedModeConfig.monitoredAppPackage.isEmpty()) {
            try {
                return getPackageManager().getApplicationIcon(advancedModeConfig.monitoredAppPackage);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return getDrawable(android.R.drawable.sym_def_app_icon);
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
        updateMonitoredAppIcon();
        if (statusText != null) {
            statusText.setText(statusLabel(hasClip));
            styleStatusText(hasClip);
            statusText.postInvalidate();
        }
        if (engineStatusValueView != null) {
            engineStatusValueView.setText(engineStatusText());
        }
        renderDeviceSpinner();
        if (modeSpinner != null) {
            modeSpinner.setText(editingPreset.mode.label);
            styleModeText();
        }
        if (autoSwitchOutputSwitch != null) {
            autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        }
        if (processingModeButton != null) {
            processingModeButton.setText(processingMode.label);
        }
        if (advancedModeDetailButton != null) {
            advancedModeDetailButton.setVisibility(processingMode == ProcessingMode.ADVANCED_DSP ? View.VISIBLE : View.GONE);
        }
        if (advancedModeSummaryView != null) {
            advancedModeSummaryView.setText(advancedModeSummaryText());
        }
        if (advancedMonitorAppButton != null) {
            advancedMonitorAppButton.setText(advancedModeConfig.monitoredAppLabel.isEmpty()
                    ? "Choose app"
                    : advancedModeConfig.monitoredAppLabel);
        }
        if (presetSelectButton != null) {
            presetSelectButton.setText(editingPreset.name);
        }
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(runningPreset.enabled);
        }
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
            pregainInput.setText(formatDecimal(editingPreset.pregainMb / 100f));
        }
        if (bassBoostSlider != null) {
            bassBoostSlider.setValue(editingPreset.systemBassBoostPercent, false);
        }
        if (cutoffKnob != null) {
            cutoffKnob.setValue(editingPreset.virtualBassCutoffHz, false);
        }
        if (amountKnob != null) {
            amountKnob.setValue(editingPreset.virtualBassAmountPercent, false);
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
        // peqBandVisualEnabled 只用于总开关开启时的逐个点亮动画，动画结束后
        // 统一回到 band.enabled 这一套真实状态，避免手动开关 band 时被旧动画状态压暗。
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

        SmallSpinnerAdapter adapter = new SmallSpinnerAdapter(labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter.setSelectedPosition(selected);
        deviceSpinner.setAdapter(adapter);
        deviceSpinner.setSelection(selected);
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
        if (!names.contains(runningPreset.name)) {
            names = new ArrayList<>(names);
            names.add(0, runningPreset.name);
        }
        String[] labels = names.toArray(new String[0]);
        int selected = Math.max(0, names.indexOf(runningPreset.name));
        showLimitedChoiceMenu(savedPresetSpinner, labels, selected, position -> {
            if (position >= 0 && position < labels.length && !labels[position].equals(runningPreset.name)) {
                loadPresetLive(labels[position]);
            }
        });
    }

    private void showReverbTypeChoiceMenu() {
        if (reverbTypeButton == null || editingPreset == null) {
            return;
        }
        showLimitedChoiceMenu(reverbTypeButton, REVERB_TYPE_LABELS, reverbTypeIndex(editingPreset.reverbType), position -> {
            String nextType = REVERB_TYPE_LABELS[Math.max(0, Math.min(REVERB_TYPE_LABELS.length - 1, position))];
            if (processingMode == ProcessingMode.SYSTEM_EQ && !"Default".equals(nextType)) {
                showModeLockedDialog("Reverb requires Monitor DSP mode.");
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
        showLimitedChoiceMenu(bassModeButton, BASS_MODE_LABELS, selectedBassModeIndex, position -> {
            int nextIndex = clamp(position, 0, BASS_MODE_LABELS.length - 1);
            if (processingMode == ProcessingMode.SYSTEM_EQ && nextIndex != 0) {
                showModeLockedDialog("BassBoost beyond Default requires Monitor DSP mode.");
                return;
            }
            selectedBassModeIndex = nextIndex;
            repository.saveBassBoostModeIndex(selectedBassModeIndex);
            updateExtraControls();
        });
    }

    private void showModeLockedDialog(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mode locked")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .create();
        dialog.setOnShowListener(d -> styleDialog(dialog));
        dialog.show();
    }

    private void showLimitedChoiceMenu(View anchor, String[] labels, int selected, ChoiceCallback callback) {
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
        PopupWindow popup = new PopupWindow(shell, width, listHeight, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(solidColorDrawable(Color.TRANSPARENT));
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
        rows.removeAllViews();
        if (editingPreset.mode == EqMode.GEQ) {
            rows.addView(createGeqSliderPanel());
            return;
        }
        for (int i = 0; i < editingPreset.bands.length; i++) {
            rows.addView(createBandRow(i));
        }
        rows.addView(createAddBandRow());
    }

    private View createGeqSliderPanel() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);

        LinearLayout strip = new LinearLayout(this);
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

    private LinearLayout createGeqRow(int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (PeqMath.bandMayClip(editingPreset, index, PeqMath.HEADROOM_LIMIT_MB)) {
            GradientDrawable warningBg = new GradientDrawable();
            warningBg.setShape(GradientDrawable.RECTANGLE);
            warningBg.setColor(Color.argb(45, 255, 100, 100));
            warningBg.setStroke(dp(1), Color.argb(100, 255, 100, 100));
            warningBg.setCornerRadius(dp(8));
            row.setBackground(warningBg);
        }

        row.addView(createStaticCell(formatFrequency(Preset.GEQ_FREQUENCIES[index])), cellParams(1f, 34));
        row.addView(createNumberInput(formatDecimal(editingPreset.geqGainsMb[index] / 100f), "dB", value -> {
            int gainMb = Math.round(value * 100f);
            updateGeqBand(index, clamp(gainMb, -1800, 1800));
        }), cellParams(1f, 34));
        return row;
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
            renderAll();
        });
        return add;
    }

    private LinearLayout createBandRow(int index) {
        ParametricBand band = editingPreset.bands[index];
        boolean visualActive = isPeqBandVisualEnabled(index);
        LinearLayout row = new LinearLayout(this);
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

        View enable = circleButton("", visualActive, false);
        enable.setBackground(stateIndicatorDrawable(visualActive));
        enable.setOnClickListener(v -> {
            updateBand(index, editingPreset.bands[index].withEnabled(!editingPreset.bands[index].enabled));
            renderRows();
        });
        row.addView(wrapCircularButton(enable, 0.35f, 22, 0, 3, -1));

        TextView type = new TextView(this);
        type.setText(band.type.label);
        type.setTextSize(11);
        styleEqBandText(type, visualActive);
        type.setGravity(android.view.Gravity.CENTER);
        type.setSingleLine(true);
        type.setIncludeFontPadding(false);
        type.setEnabled(supported);
        type.setBackground(typeCellBackground(band.type, visualActive));
        type.setOnClickListener(v -> {
            if (!supported) {
                return;
            }
            showLimitedChoiceMenu(type, FilterType.labels(), editingPreset.bands[index].type.ordinal(), position -> {
                if (position < 0 || position >= FilterType.values().length) {
                    return;
                }
                FilterType selectedType = FilterType.values()[position];
                type.setText(selectedType.label);
                type.setBackground(typeCellBackground(selectedType, isPeqBandVisualEnabled(index)));
                updateBand(index, editingPreset.bands[index].withType(selectedType));
            });
        });
        row.addView(type, cellParams(1f, 36));

        EditText frequencyInput = createNumberInput(String.valueOf(band.frequencyHz), "Hz", value -> {
            updateBand(index, editingPreset.bands[index].withFrequencyHz(clamp(Math.round(value), 20, 20000)));
        });
        styleEqBandText(frequencyInput, visualActive);
        attachNumberWatermark(frequencyInput, visualActive);
        attachEqEditFocus(frequencyInput, index, EQ_EDIT_FIELD_FREQ);
        row.addView(frequencyInput, cellParams(1f, 36));

        EditText gainInput = createNumberInput(formatDecimal(band.gainMb / 100f), "dB", value -> {
            int gainMb = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withGainMb(clamp(gainMb, -1800, 1800)));
        });
        styleEqBandText(gainInput, visualActive);
        attachNumberWatermark(gainInput, visualActive);
        attachEqEditFocus(gainInput, index, EQ_EDIT_FIELD_GAIN);
        row.addView(gainInput, cellParams(1f, 36));

        EditText qInput = createNumberInput(formatDecimal(band.qHundred / 100f), "Q", value -> {
            int qHundred = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withQHundred(clamp(qHundred, 20, 1000)));
        });
        styleEqBandText(qInput, visualActive);
        attachNumberWatermark(qInput, visualActive);
        attachEqEditFocus(qInput, index, EQ_EDIT_FIELD_Q);
        row.addView(qInput, cellParams(1f, 36));

        View delete = circleButton("", false, true);
        delete.setEnabled(supported && editingPreset.bands.length > 1);
        delete.setBackground(deleteSymbolDrawable(delete.isEnabled()));
        delete.setOnClickListener(v -> confirmDeleteBand(index));
        row.addView(wrapCircularButton(delete, 0.35f, 22, 3, 0, 1));

        return row;
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
                closeKeyboard(view);
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
                if (updatingUi) {
                    return;
                }
                try {
                    listener.onChanged(Float.parseFloat(s.toString()));
                } catch (NumberFormatException ignored) {
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
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

    private void updateBand(int index, ParametricBand band) {
        setEditingPreset(editingPreset.withBand(index, band), true);
    }

    private void updateGeqBand(int index, int gainMb) {
        if (editingPreset == null || index < 0 || index >= editingPreset.geqGainsMb.length) {
            return;
        }
        gainMb = clamp(gainMb, -1800, 1800);
        if (editingPreset.geqGainsMb[index] == gainMb) {
            return;
        }
        if (pendingGeqHistorySnapshot == null) {
            pendingGeqHistorySnapshot = editingPreset;
        }
        editingPreset = editingPreset.withGeqGainMb(index, gainMb);
        if (curveView != null) {
            refreshCurveView();
        }
        updateEditStateLabels();
        scheduleGeqCommit();
    }

    private void scheduleGeqCommit() {
        uiHandler.removeCallbacks(commitGeqUpdateRunnable);
        uiHandler.postDelayed(commitGeqUpdateRunnable, GEQ_COMMIT_DELAY_MS);
    }

    private void commitPendingGeqUpdate() {
        uiHandler.removeCallbacks(commitGeqUpdateRunnable);
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
                view.post(() -> {
                    View focused = getCurrentFocus();
                    if (!(focused instanceof EditText)) {
                        hideEqEditOverlay();
                    }
                });
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
        overlay.addView(frequencyInput);
        EditText gainInput = createEqOverlayInput(formatDecimal(band.gainMb / 100f), "dB", 1f, value -> {
            int gainMb = clamp(Math.round(value * 100f), -1800, 1800);
            setBandFromEqOverlay(bandIndex, editingPreset.bands[bandIndex].withGainMb(gainMb));
        });
        overlay.addView(gainInput);
        EditText qInput = createEqOverlayInput(formatDecimal(band.qHundred / 100f), "Q", 1f, value -> {
            int qHundred = clamp(Math.round(value * 100f), 20, 1000);
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
            int rootHeight = root.getHeight();
            int curveLeft = Math.max(0, curveLocation[0] - rootLocation[0]);
            int curveTop = Math.max(0, curveLocation[1] - rootLocation[1]);
            int curveRight = Math.min(rootWidth, curveLeft + curveFrameView.getWidth());
            int curveBottom = Math.min(rootHeight, curveTop + curveFrameView.getHeight());
            int dimColor = Color.argb(180, 18, 18, 25);
            int left = dp(18);
            int top = Math.max(dp(8), curveLocation[1] - rootLocation[1] + curveFrameView.getHeight() + dp(6));
            int width = Math.max(dp(240), rootWidth - dp(36));

            removeEqEditDim(false);
            addEqEditDimView(root, 0, 0, rootWidth, curveTop, dimColor);
            addEqEditDimView(root, 0, curveBottom, rootWidth, rootHeight - curveBottom, dimColor);
            addEqEditDimView(root, 0, curveTop, curveLeft, curveBottom - curveTop, dimColor);
            addEqEditDimView(root, curveRight, curveTop, rootWidth - curveRight, curveBottom - curveTop, dimColor);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, dp(48));
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
                closeKeyboard(view);
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
                if (updatingUi) {
                    return;
                }
                try {
                    listener.onChanged(Float.parseFloat(s.toString()));
                } catch (NumberFormatException ignored) {
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        input.setLayoutParams(params);
        return input;
    }

    private void setBandFromEqOverlay(int index, ParametricBand band) {
        if (editingPreset == null || index < 0 || index >= editingPreset.bands.length) {
            return;
        }
        setEditingPreset(editingPreset.withBand(index, band), true);
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
                    renderAll();
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
        showCurveMenu("Device curve", items, which -> {
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
            renderAll();
        }, item -> imported.contains(item), item -> {
            repository.deleteDeviceCurve(item);
            if (item.equals(selectedDeviceCurveName)) {
                selectedDeviceCurveName = "Default";
                deviceCurveGainOffsetDb = 0f;
                deviceCurveSmoothing = "Default";
                refreshDeviceCurveCache();
                syncCurrentCurveSettingsToEditingPreset(true);
                renderAll();
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
        showCurveMenu("Target curve", items, which -> {
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
            renderAll();
        }, item -> imported.contains(item), item -> {
            repository.deleteTargetCurve(item);
            if (item.equals(selectedTargetCurveName)) {
                selectedTargetCurveName = "Default";
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                refreshTargetCurveCache();
                syncCurrentCurveSettingsToEditingPreset(true);
                renderAll();
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
        selectedDeviceCurveBase = applyCurveSmoothing(repository.loadDeviceCurve(selectedDeviceCurveName), deviceCurveSmoothing);
        selectedDeviceCurve = applyCurveGainOffset(selectedDeviceCurveBase, deviceCurveGainOffsetDb);
    }

    private void refreshTargetCurveCache() {
        selectedTargetCurveBase = applyCurveSmoothing(repository.loadTargetCurve(selectedTargetCurveName), targetCurveSmoothing);
        selectedTargetCurve = applyCurveGainOffset(selectedTargetCurveBase, targetCurveGainOffsetDb);
    }

    private void applyPresetCurveSettings(Preset preset) {
        if (preset == null) {
            return;
        }
        selectedDeviceCurveName = preset.deviceCurveName;
        selectedTargetCurveName = preset.targetCurveName;
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
                targetCurveSmoothing
        );
    }

    private void syncCurrentCurveSettingsToEditingPreset(boolean recordHistory) {
        if (editingPreset == null) {
            return;
        }
        Preset next = withCurrentCurveSettings(editingPreset);
        if (next == null || next.toJson().equals(editingPreset.toJson())) {
            persistCurrentCurveSettings();
            return;
        }
        setEditingPreset(next, recordHistory);
        persistCurrentCurveSettings();
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
        String title = targetCurve ? "Target curve gain" : "Device curve gain";
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
                }
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
                .setNegativeButton("Close", null)
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
            Toast.makeText(this, "Default curve can't be renamed", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint(targetCurve ? "Target curve name" : "Device curve name");
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
                .setCustomTitle(dialogTitleView(targetCurve ? "Rename target curve" : "Rename device curve"))
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", null)
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
            Toast.makeText(this, "Curve name required", Toast.LENGTH_SHORT).show();
            return false;
        }
        if ("Default".equals(nextName)) {
            Toast.makeText(this, "Default is reserved", Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean sameName = oldName.equals(nextName);
        boolean exists = targetCurve ? repository.hasTargetCurveName(nextName) : repository.hasDeviceCurveName(nextName);
        if (!sameName && exists) {
            Toast.makeText(this, "Curve name already exists", Toast.LENGTH_SHORT).show();
            return false;
        }

        boolean renamed = targetCurve
                ? repository.renameTargetCurve(oldName, nextName)
                : repository.renameDeviceCurve(oldName, nextName);
        if (!renamed) {
            Toast.makeText(this, "Curve rename failed", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (targetCurve) {
            selectedTargetCurveName = nextName;
            refreshTargetCurveCache();
        } else {
            selectedDeviceCurveName = nextName;
            refreshDeviceCurveCache();
        }
        syncCurrentCurveSettingsToEditingPreset(true);
        if (nameView != null) {
            nameView.setText(nextName);
        }
        renderAll();
        Toast.makeText(this, "Curve renamed", Toast.LENGTH_SHORT).show();
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
                .setNegativeButton("Close", null)
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
            View delete = circleButton("", false, true);
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

    private FrequencyCurve readCurveFromUri(Uri uri) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream stream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return FrequencyCurve.fromText(curveNameFromUri(uri), text.toString());
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

    private void showSavePresetDialog() {
        List<String> names = repository.loadNamedPresetNames();
        List<String> targets = new ArrayList<>();
        targets.add("+ New preset");
        targets.addAll(names);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView targetLabel = new TextView(this);
        targetLabel.setText("Save to");
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
        nameLabel.setText("Preset name");
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
        input.setHint("Preset name");
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
                .setCustomTitle(dialogTitleView("Save preset"))
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, which) -> {
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
        add.setText("+ Add new preset");
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
                .setCustomTitle(dialogTitleView("Select preset"))
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        dialogHolder[0] = dialog;
        dialog.show();
        styleDialog(dialog);
    }

    private View createPresetMenuRow(String name, AlertDialog[] dialogHolder) {
        boolean active = name.equals(editingPreset.name);
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

        View delete = circleButton("", false, true);
        delete.setBackground(deleteSymbolDrawable(true));
        delete.setOnClickListener(v -> confirmDeletePreset(name, dialogHolder));
        row.addView(wrapCircularButton(delete, 0.16f, 24));

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
                .setCustomTitle(dialogTitleView("Delete preset"))
                .setMessage("Delete preset \"" + name + "\"?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
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
        editingPreset = fallback;
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();
        if (deletedRunning) {
            runningPreset = editingPreset.withEnabled(runningPreset != null && runningPreset.enabled && supported);
            applyRunningPreset();
        }
        undoStack.clear();
        redoStack.clear();
        renderAll();
    }

    private void saveDraftToPreset(String oldTargetName, String rawName) {
        String finalName = rawName == null ? "" : rawName.trim();
        if (finalName.isEmpty()) {
            Toast.makeText(this, "Preset name required", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean updatesRunningPreset = runningPreset != null
                && (runningPreset.name.equals(finalName)
                || (oldTargetName != null && runningPreset.name.equals(oldTargetName)));
        Preset savedPreset = withCurrentCurveSettings(editingPreset).withName(finalName);

        if (oldTargetName != null && !oldTargetName.equals(finalName)) {
            repository.renameNamedPreset(oldTargetName, savedPreset);
        } else {
            repository.saveNamedPreset(savedPreset);
        }

        editingPreset = savedPreset;
        repository.saveDraftPreset(editingPreset);
        if (updatesRunningPreset) {
            runningPreset = editingPreset.withEnabled(runningPreset.enabled && supported);
            applyRunningPreset();
        }
        undoStack.clear();
        redoStack.clear();
        renderAll();
        Toast.makeText(this, "Preset saved", Toast.LENGTH_SHORT).show();
    }

    private void loadPresetLive(String name) {
        Preset selected = repository.loadNamedPreset(name);
        selected = limitPresetForHeadroom(selected);
        runningPreset = selected.withEnabled(supported);
        editingPreset = runningPreset;
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();
        repository.saveDraftPreset(editingPreset);
        undoStack.clear();
        redoStack.clear();
        renderAll();
        applyRunningPreset();
    }

    private void showAddPresetDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(nextPresetName());
        input.setSelectAllOnFocus(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 255, 255, 255));
        input.setHint("Preset name");
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
                .setCustomTitle(dialogTitleView("Add new preset"))
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, which) -> {
                    String name = input.getText().toString() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Preset name required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    editingPreset = withCurrentCurveSettings(Preset.flat(runningPreset != null && runningPreset.enabled)).withName(name);
                    repository.saveNamedPreset(editingPreset);
                    undoStack.clear();
                    redoStack.clear();
                    persistEditingPreset();
                    renderAll();
                })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    private void loadPresetForEditing(String name) {
        Preset selected = repository.loadNamedPreset(name);
        selected = limitPresetForHeadroom(selected);
        editingPreset = selected;
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();
        undoStack.clear();
        redoStack.clear();
        renderAll();
        persistEditingPreset();
        updateEditStateLabels();
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

    private void onEnabledChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi) {
            return;
        }
        runningPreset = runningPreset.withEnabled(isChecked && supported);
        if (isEditingPresetActive()) {
            editingPreset = editingPreset.withEnabled(runningPreset.enabled);
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
            renderDeviceSpinner();
        }
    }

    private void onVirtualBassEnabledChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi || editingPreset == null) {
            return;
        }
        logShimmerEvent("onVirtualBassEnabledChanged", virtualBassTitleView, "checked=" + isChecked);
        virtualBassEnabledState = isChecked;
        setEditingPreset(editingPreset.withVirtualBassEnabled(isChecked), true);
    }

    private void syncVirtualBassEnabledFromPreset() {
        if (editingPreset == null) {
            virtualBassEnabledState = false;
            return;
        }
        virtualBassEnabledState = editingPreset.virtualBassEnabled;
    }

    private void applyRunningPreset() {
        if (currentDevice == null || runningPreset == null) {
            return;
        }
        try {
            repository.saveSelectedDevice(currentDevice);
            repository.savePreset(currentDevice, runningPreset);
            repository.saveGlobalPreset(runningPreset);
            engine.apply(AudioProcessingPolicy.effectiveSystemPreset(runningPreset, processingMode, selectedBassModeIndex));
            Intent service = new Intent(this, GlobalEqForegroundService.class);
            service.setAction(GlobalEqForegroundService.ACTION_APPLY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleEnabledToggleCommit() {
        pendingEnabledApplyPreset = runningPreset;
        uiHandler.removeCallbacks(commitEnabledToggleRunnable);
        uiHandler.postDelayed(commitEnabledToggleRunnable, ENABLE_TOGGLE_COMMIT_DELAY_MS);
    }

    private void scheduleEnabledToggleUiRefresh() {
        pendingEnabledUiRefresh = true;
        uiHandler.removeCallbacks(refreshEnabledToggleUiRunnable);
        uiHandler.postDelayed(refreshEnabledToggleUiRunnable, ENABLE_TOGGLE_UI_DELAY_MS);
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
            applyRunningPreset();
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

    private void startEnabledNeonSequence() {
        cancelEnabledNeonSequence();
        logShimmerEvent("startEnabledNeonSequence", modeSpinner, "runningEnabled=" + isAudioEnabledNow());
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
        logShimmerEvent("activateEnabledNeonHeader", modeSpinner, "runningEnabled=" + isAudioEnabledNow());
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
        logShimmerEvent("applyDisabledEnabledVisuals", modeSpinner, "runningEnabled=" + isAudioEnabledNow());
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
        peqVisualSequenceRunning = enabled;
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

    private void applyBandRowVisualState(LinearLayout row, int index) {
        if (row == null || editingPreset == null || index < 0 || index >= editingPreset.bands.length) {
            return;
        }
        ParametricBand band = editingPreset.bands[index];
        boolean visualActive = isPeqBandVisualEnabled(index);
        row.setAlpha(visualActive ? 1f : 0.58f);
        if (row.getChildCount() < 6) {
            return;
        }

        View enableWrap = row.getChildAt(0);
        if (enableWrap instanceof ViewGroup && ((ViewGroup) enableWrap).getChildCount() > 0) {
            View enable = ((ViewGroup) enableWrap).getChildAt(0);
            enable.setBackground(stateIndicatorDrawable(visualActive));
        }

        View typeView = row.getChildAt(1);
        if (typeView instanceof TextView) {
            TextView type = (TextView) typeView;
            styleEqBandText(type, visualActive);
            type.setBackground(typeCellBackground(band.type, visualActive));
        }

        applyBandInputVisual(row.getChildAt(2), visualActive);
        applyBandInputVisual(row.getChildAt(3), visualActive);
        applyBandInputVisual(row.getChildAt(4), visualActive);
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
        repository.saveSelectedDevice(selected);
        currentDevice = selected;
        Preset loadedPreset = repository.loadPreset(currentDevice);
        loadedPreset = limitPresetForHeadroom(loadedPreset);
        runningPreset = loadedPreset.withEnabled(loadedPreset.enabled && supported);
        editingPreset = runningPreset;
        applyPresetCurveSettings(editingPreset);
        syncVirtualBassEnabledFromPreset();
        undoStack.clear();
        redoStack.clear();
        renderAll();
        applyRunningPreset();
    }

    private void renderSavedPresetSpinner() {
        List<String> names = repository.loadNamedPresetNames();
        if (!names.contains(runningPreset.name)) {
            names = new ArrayList<>(names);
            names.add(0, runningPreset.name);
        }
        SmallSpinnerAdapter adapter = new SmallSpinnerAdapter(names.toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        int selected = Math.max(0, names.indexOf(runningPreset.name));
        adapter.setSelectedPosition(selected);
        savedPresetSpinner.setAdapter(adapter);
        savedPresetSpinner.setSelection(selected);
    }

    private void syncRunningIfEditingPresetIsActive() {
        persistEditingPreset();
        if (!isEditingPresetActive()) {
            return;
        }
        runningPreset = editingPreset.withEnabled(runningPreset.enabled && supported);
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(runningPreset.enabled);
        }
        applyRunningPreset();
    }

    private boolean isEditingPresetActive() {
        return runningPreset != null && editingPreset != null && runningPreset.name.equals(editingPreset.name);
    }

    private void setEditingPreset(Preset nextPreset, boolean recordHistory) {
        if (pendingGeqHistorySnapshot != null) {
            commitPendingGeqUpdate();
        }
        if (nextPreset.toJson().equals(editingPreset.toJson())) {
            return;
        }
        if (recordHistory) {
            pushHistory(undoStack, editingPreset);
            redoStack.clear();
        }
        editingPreset = nextPreset;
        if (curveView != null) {
            refreshCurveView();
        }
        syncRunningIfEditingPresetIsActive();
        updateExtraControls();
        updateEditStateLabels();
    }

    private void undoEdit() {
        commitPendingGeqUpdate();
        if (undoStack.isEmpty()) {
            return;
        }
        pushHistory(redoStack, editingPreset);
        editingPreset = undoStack.remove(undoStack.size() - 1);
        syncVirtualBassEnabledFromPreset();
        syncRunningIfEditingPresetIsActive();
        renderAll();
    }

    private void redoEdit() {
        commitPendingGeqUpdate();
        if (redoStack.isEmpty()) {
            return;
        }
        pushHistory(undoStack, editingPreset);
        editingPreset = redoStack.remove(redoStack.size() - 1);
        syncVirtualBassEnabledFromPreset();
        syncRunningIfEditingPresetIsActive();
        renderAll();
    }

    private void persistEditingPreset() {
        repository.saveDraftPreset(editingPreset);
        if (isNamedPreset(editingPreset.name)) {
            repository.saveNamedPreset(editingPreset);
        }
    }

    private boolean isNamedPreset(String name) {
        return name != null && repository.loadNamedPresetNames().contains(name);
    }

    private void updateEditStateLabels() {
        if (presetSelectButton != null) {
            presetSelectButton.setText(editingPreset.name);
        }
        if (undoButton != null) {
            styleButton(undoButton, false, !undoStack.isEmpty() && supported);
        }
        if (redoButton != null) {
            styleButton(redoButton, false, !redoStack.isEmpty() && supported);
        }
        boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
        if (statusText != null) {
            statusText.setText(statusLabel(hasClip));
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
        boolean bassBoostEnabled = supported && processingMode == ProcessingMode.ADVANCED_DSP && selectedBassModeIndex > 0;
        if (bassBoostSlider != null) {
            bassBoostSlider.setValue(editingPreset.systemBassBoostPercent, false);
            bassBoostSlider.setEnabled(bassBoostEnabled);
            bassBoostSlider.setAlpha(bassBoostEnabled ? 1f : 0.55f);
        }
        boolean virtualBassEnabled = supported && virtualBassEnabledState;
        if (virtualBassSwitch != null) {
            updatingUi = true;
            virtualBassSwitch.setChecked(virtualBassEnabled);
            virtualBassSwitch.setEnabled(supported);
            updatingUi = false;
        }
        updateVirtualBassControl(cutoffKnob, editingPreset.virtualBassCutoffHz, virtualBassEnabled);
        updateVirtualBassControl(amountKnob, editingPreset.virtualBassAmountPercent, virtualBassEnabled);
        boolean reverbEnabled = supported && processingMode == ProcessingMode.ADVANCED_DSP && !"Default".equals(editingPreset.reverbType);
        if (reverbTypeButton != null) {
            reverbTypeButton.setText(editingPreset.reverbType);
            reverbTypeButton.setEnabled(supported);
            reverbTypeButton.setAlpha(supported ? 1f : 0.5f);
        }
        if (bassModeButton != null) {
            bassModeButton.setText(BASS_MODE_LABELS[clamp(selectedBassModeIndex, 0, BASS_MODE_LABELS.length - 1)]);
            bassModeButton.setAlpha(1f);
        }
        if (dspBassCutoffInput != null) {
            dspBassCutoffInput.setVisibility(selectedBassModeIndex == 2 ? View.VISIBLE : View.GONE);
            dspBassCutoffInput.setEnabled(bassBoostEnabled && selectedBassModeIndex == 2);
            String cutoffText = String.valueOf(editingPreset.dspBassCutoffHz);
            if (!cutoffText.contentEquals(dspBassCutoffInput.getText())) {
                updatingUi = true;
                dspBassCutoffInput.setText(cutoffText);
                updatingUi = false;
            }
            dspBassCutoffInput.setAlpha(selectedBassModeIndex == 2 ? 1f : 0.55f);
        }
        syncExtraSectionTitleVisual(reverbTitleView);
        syncExtraSectionTitleVisual(bassBoostTitleView);
        syncExtraSectionTitleVisual(virtualBassTitleView);
        updateReverbControl(reverbDecayKnob, editingPreset.reverbDecayPercent, reverbEnabled);
        updateReverbControl(reverbPredelayKnob, editingPreset.reverbPredelayMs, reverbEnabled);
        updateReverbControl(reverbSizeKnob, editingPreset.reverbSizePercent, reverbEnabled);
        updateReverbControl(reverbMixKnob, editingPreset.reverbMixPercent, reverbEnabled);
    }

    private void updateVirtualBassControl(KnobView knob, int value, boolean enabled) {
        if (knob != null) {
            knob.setEnabled(enabled);
            knob.setValue(value, false);
        }
    }

    private void updateReverbControl(KnobView knob, int value, boolean enabled) {
        if (knob != null) {
            knob.setEnabled(enabled);
            knob.setValue(value, false);
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
        reverbKnobs.addView(createReverbControl("Decay", 0, 100, editingPreset.reverbDecayPercent, "%", value ->
                setEditingPreset(editingPreset.withReverbSettings(value, editingPreset.reverbPredelayMs, editingPreset.reverbSizePercent, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbControl("Predelay", 0, 250, editingPreset.reverbPredelayMs, "ms", value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbDecayPercent, value, editingPreset.reverbSizePercent, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbControl("Size", 0, 100, editingPreset.reverbSizePercent, "%", value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbDecayPercent, editingPreset.reverbPredelayMs, value, editingPreset.reverbMixPercent), true)), knobColumnParams());
        reverbKnobs.addView(createReverbControl("Mix", 0, 100, editingPreset.reverbMixPercent, "%", value ->
                setEditingPreset(editingPreset.withReverbSettings(editingPreset.reverbDecayPercent, editingPreset.reverbPredelayMs, editingPreset.reverbSizePercent, value), true)), knobColumnParams());

        LinearLayout bassPanel = createExtraPanelShell();
        page.addView(bassPanel, extraPanelParams(12));
        LinearLayout bassHeader = createExtraHeaderRow("BassBoost");
        bassBoostTitleView = (TextView) bassHeader.getChildAt(0);
        bassModeButton = createExtraChoiceButton();
        bassModeButton.setOnClickListener(v -> showBassModeChoiceMenu());
        // UI 占位选择框，system/dsp 切换功能后续接入
        bassHeader.addView(bassModeButton, new LinearLayout.LayoutParams(dp(120), dp(30)));
        bassPanel.addView(bassHeader, blockParams(4));
        bassBoostSlider = new HorizontalBassSlider(this);
        bassBoostSlider.configure(0, 100, editingPreset.systemBassBoostPercent, "%", "Boost",
                value -> setEditingPreset(editingPreset.withSystemBassBoostPercent(value), true));
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        sliderParams.topMargin = dp(8);
        bassPanel.addView(bassBoostSlider, sliderParams);

        dspBassCutoffInput = createNumberInput(String.valueOf(editingPreset.dspBassCutoffHz), "DSP Cutoff", value -> {
            int cutoffHz = clamp(Math.round(value), 45, 220);
            setEditingPreset(editingPreset.withDspBassCutoffHz(cutoffHz), true);
        });
        dspBassCutoffInput.setTextSize(13);
        dspBassCutoffInput.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams cutoffParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
        );
        cutoffParams.topMargin = dp(10);
        bassPanel.addView(dspBassCutoffInput, cutoffParams);

        LinearLayout virtualPanel = createExtraPanelShell();
        page.addView(virtualPanel, extraPanelParams(12));
        LinearLayout virtualHeader = createExtraHeaderRow("Virtual Bass");
        virtualBassTitleView = (TextView) virtualHeader.getChildAt(0);
        virtualBassSwitch = new Switch(this);
        virtualBassSwitch.setText("");
        virtualBassSwitch.setShowText(false);
        virtualBassSwitch.setOnCheckedChangeListener(this::onVirtualBassEnabledChanged);
        styleTopSwitch(virtualBassSwitch, false);
        virtualHeader.addView(virtualBassSwitch, new LinearLayout.LayoutParams(dp(60), dp(30)));
        virtualPanel.addView(virtualHeader, blockParams(4));
        LinearLayout virtualKnobs = createExtraKnobRow(virtualPanel);
        virtualKnobs.addView(createVirtualBassControl("Cutoff", true), knobColumnParams());
        virtualKnobs.addView(createVirtualBassControl("Boost", false), knobColumnParams());
    }

    private LinearLayout createExtraPanel(String titleText) {
        LinearLayout panel = createExtraPanelShell();
        TextView title = gradientTitleView(titleText);
        if (title instanceof GlowTitleTextView) {
            ((GlowTitleTextView) title).setAutoRegisterShimmer(false);
        }
        title.setText(titleText);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title, blockParams(0));
        return panel;
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

    private LinearLayout createSystemBassBoostControl() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("Boost");
        title.setTextSize(15);
        title.setTextColor(Color.rgb(200, 210, 230));
        title.setGravity(android.view.Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // BassBoost 控件已迁移为 HorizontalBassSlider，此方法仅作占位避免调用方断裂
        LinearLayout placeholder = new LinearLayout(this);
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(8);
        knobParams.bottomMargin = dp(8);
        column.addView(placeholder, knobParams);

        return column;
    }

    private LinearLayout createVirtualBassControl(String label, boolean cutoff) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        KnobView knob = new KnobView(this);
        if (cutoff) {
            cutoffKnob = knob;
            knob.configure(60, 250, editingPreset.virtualBassCutoffHz, "Hz", value -> setEditingPreset(editingPreset.withVirtualBassCutoffHz(value), true));
        } else {
            amountKnob = knob;
            knob.configure(0, 100, editingPreset.virtualBassAmountPercent, "%", value ->
                    setEditingPreset(editingPreset.withVirtualBassAmountPercent(value), true));
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

    private LinearLayout createReverbControl(String label, int min, int max, int value, String suffix, IntChanged listener) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        KnobView knob = new KnobView(this);
        knob.configure(min, max, value, suffix, listener::onChanged);
        knob.setTapListener(this::showStyledKnobInputDialog);
        // 强制方形：弧形填满 view，标题紧贴弧形下方
        knob.setForceSquare(true);
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(6);
        knobParams.bottomMargin = dp(6);
        column.addView(knob, knobParams);

        if ("Decay".equals(label)) {
            reverbDecayKnob = knob;
        } else if ("Predelay".equals(label)) {
            reverbPredelayKnob = knob;
        } else if ("Size".equals(label)) {
            reverbSizeKnob = knob;
        } else {
            reverbMixKnob = knob;
        }

        // 标签紧贴旋钮下方
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(12);
        title.setTextColor(Color.rgb(180, 195, 215));
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(1);
        column.addView(title, titleParams);
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
        dlg.setOnShowListener(d -> {
            styleDialog(dlg);
            input.requestFocus();
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dlg.show();
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

    private View circleButton(String text, boolean isActive, boolean isDanger) {
        View button = new View(this);
        button.setFocusable(true);
        button.setClickable(true);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);

        if (isDanger) {
            background.setColor(Color.argb(30, 255, 100, 100));
            background.setStroke(Math.round(dpf(1f)), Color.argb(100, 255, 100, 100));
        } else if (isActive) {
            background.setColor(Color.argb(40, 0, 255, 255));
            background.setStroke(Math.round(dpf(1f)), Color.argb(150, 0, 255, 255));
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
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        View tokenView = focused != null ? focused : view;
        hideEqEditOverlay();
        if (manager != null) {
            uiHandler.post(() -> manager.hideSoftInputFromWindow(tokenView.getWindowToken(), 0));
        }
        if (focused != null) {
            focused.clearFocus();
        } else if (view != null) {
            view.clearFocus();
        }
    }

    private interface FloatChanged {
        void onChanged(float value);
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
                        setValue(xToValue(event.getX()), true);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (adjusting) {
                        setValue(xToValue(event.getX()), true);
                    } else {
                        // tap thumb 区域直接跳转
                        float thumbX = valueToX();
                        if (Math.abs(event.getX() - thumbX) < dpf(20f)) {
                            setValue(xToValue(event.getX()), true);
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
                autoSwitch ? Color.rgb(170, 180, 200) : Color.rgb(190, 200, 215),
                Color.rgb(120, 240, 220)
        ));
        // track 颜色：关闭时半透明白，开启时半透明青蓝（与标题流光同色系）
        switchView.setTrackDrawable(labeledSwitchTrackDrawable(
                autoSwitch ? "AUTO" : "OFF",
                autoSwitch ? "AUTO" : "ON",
                autoSwitch ? Color.argb(38, 255, 255, 255) : Color.argb(45, 255, 255, 255),
                autoSwitch ? Color.argb(85, 120, 240, 220) : Color.argb(105, 120, 240, 220)
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            switchView.setSplitTrack(false);
        }
    }

    private Drawable labeledSwitchTrackDrawable(String uncheckedLabel, String checkedLabel, int uncheckedColor, int checkedColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clipPath = new Path();
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
                rect.set(b.left, b.top + dpf(2f), b.right, b.bottom - dpf(2f));

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(checked ? checkedColor : uncheckedColor);
                float radius = rect.height() / 2f;
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(checked ? Color.argb(90, 120, 240, 220) : Color.argb(42, 255, 255, 255));
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(dpf(8.5f));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(checked ? Color.rgb(140, 250, 230) : Color.argb(88, 255, 255, 255));
                if (checked) {
                    paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(150, 120, 240, 220));
                } else {
                    paint.clearShadowLayer();
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
                // 300ms + AccelerateDecelerateInterpolator：与系统 thumb 滑动节奏接近，
                // 让 OFF→ON 文字过渡丝滑（auto 开关文字相同，不受影响）
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
            // thumb 颜色 + 柔光透明度跟随状态切换做 300ms argb 渐变，
            // 与 track label 动画同步，消除 thumb 颜色跳变
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
                float radius = Math.min(b.width(), b.height()) / 2f - dpf(1f);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(currentColor);
                if (glowAlpha > 0.01f) {
                    // 开启时 thumb 带柔光，与流光同色系；透明度随渐变过渡
                    paint.setShadowLayer(dpf(3f), 0, 0, Color.argb((int)(140 * glowAlpha), 120, 240, 220));
                } else {
                    paint.clearShadowLayer();
                }
                canvas.drawCircle(b.centerX(), b.centerY(), radius, paint);
                paint.clearShadowLayer();

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                int strokeAlpha = (int)(70 + (120 - 70) * glowAlpha);
                paint.setColor(Color.argb(strokeAlpha, 255, 255, 255));
                canvas.drawCircle(b.centerX(), b.centerY(), radius, paint);
            }

            @Override
            protected boolean onStateChange(int[] state) {
                boolean checked = drawableStateChecked(state);
                int targetColor = checked ? checkedColor : uncheckedColor;
                float targetGlow = checked ? 1f : 0f;
                if (colorAnimator != null) {
                    colorAnimator.cancel();
                }
                // 颜色与柔光透明度同步 300ms 渐变，与 track 文字动画节奏一致
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

    private void attachNumberWatermark(EditText input) {
        attachNumberWatermark(input, false);
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

    private void updateNumberWatermark(EditText input) {
        updateNumberWatermark(input, false);
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

    private Drawable numberCellBackground(String watermark) {
        return numberCellBackground(watermark, false);
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

    private Drawable typeCellBackground(FilterType filterType) {
        return typeCellBackground(filterType, false);
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

    private final class GlowShimmerButton extends Button {
        private final TextPaint glowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private boolean glowEnabled = true;
        private boolean shimmerActive;
        private int glowColor = Color.argb(210, 120, 220, 255);
        private float glowRadiusPx = dpf(7.4f);

        GlowShimmerButton(Context context) {
            super(context);
            glowPaint.setDither(true);
            glowPaint.setLinearText(true);
            glowPaint.setSubpixelText(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        }

        void setGlowState(boolean enabled, int color, float radiusPx) {
            glowEnabled = enabled;
            glowColor = color;
            glowRadiusPx = radiusPx;
            invalidate();
        }

        void clearGlowState() {
            glowEnabled = false;
            invalidate();
        }

        void setBottomTabShimmerActive(boolean active) {
            shimmerActive = active;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable background = getBackground();
            if (background != null) {
                background.setState(getDrawableState());
                background.setBounds(0, 0, getWidth(), getHeight());
                background.draw(canvas);
            }
            drawButtonGlow(canvas);
            drawButtonLabel(canvas);
        }

        private void drawButtonGlow(Canvas canvas) {
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

        private void drawButtonLabel(Canvas canvas) {
            Layout layout = getLayout();
            CharSequence text = getText();
            if (layout == null || text == null || text.length() == 0) {
                return;
            }
            labelPaint.set(getPaint());
            if (shimmerActive) {
                int width = Math.max(1, settingsTitleGradientWidth(this));
                float offset = currentShimmerPhaseForView(this) * width;
                labelPaint.setShader(new LinearGradient(
                        offset, 0, width + offset, 0,
                        SHIMMER_BRIGHT_COLORS,
                        SHIMMER_POSITIONS,
                        Shader.TileMode.REPEAT));
                labelPaint.setColor(Color.WHITE);
            } else {
                labelPaint.setShader(null);
                labelPaint.setColor(getCurrentTextColor());
            }
            labelPaint.setMaskFilter(null);

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
                canvas.drawText(content, start, end, layout.getLineLeft(line), layout.getLineBaseline(line), labelPaint);
            }
            canvas.restoreToCount(save);
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
            if (view instanceof GlowTitleTextView || view instanceof GlowShimmerButton) {
                if (view.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }
            } else if (view.getLayerType() != View.LAYER_TYPE_NONE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
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
                && view.getLayerType() != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
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
        return view == reverbTitleView || view == bassBoostTitleView || view == virtualBassTitleView;
    }

    private boolean isExtraSectionTitleActive(TextView view) {
        if (view == null || editingPreset == null) {
            return false;
        }
        if (view == reverbTitleView) {
            return supported && !"Default".equals(editingPreset.reverbType);
        }
        if (view == bassBoostTitleView) {
            return supported && selectedBassModeIndex > 0;
        }
        if (view == virtualBassTitleView) {
            return supported && virtualBassEnabledState;
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
        shimmerPhaseFreezeUntil.put(view, System.currentTimeMillis() + SHIMMER_ACTIVATION_FREEZE_MS);
        shimmerTargetViews.add(view);
        shimmerLastWidth.remove(view);
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
        shimmerLastWidth.remove(view);
        shimmerPhaseFreezeUntil.remove(view);
        view.getPaint().setShader(null);
        clearGlowFromTextView(view);
        view.invalidate();
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
        shimmerPhaseFreezeUntil.remove(view);
        textStyleVersion.remove(view);
        titleVisualStates.remove(view);
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
        if (view instanceof GlowShimmerButton) {
            ((GlowShimmerButton) view).setGlowState(true, glowColor, dpf(glowRadiusDp));
            view.getPaint().clearShadowLayer();
            return;
        }
        view.getPaint().setShadowLayer(dpf(glowRadiusDp), 0, 0, glowColor);
    }

    private void clearGlowFromTextView(TextView view) {
        if (view instanceof GlowTitleTextView) {
            ((GlowTitleTextView) view).clearGlowState();
        }
        if (view instanceof GlowShimmerButton) {
            ((GlowShimmerButton) view).clearGlowState();
        }
        view.getPaint().clearShadowLayer();
    }

    private void styleSettingsTitleText(TextView view) {
        if (view == null) {
            return;
        }
        final int styleVersion = bumpTextStyleVersion(view);
        // 关键优化：为了能够让完美丝滑的 shadowLayer (大半径高斯模糊) 在静态状态下同样发挥效果，
        // 同样将文字样式设置切换为精密的 SOFTWARE 图层，消除 GPU 渲染产生的颗粒伪影。
        boolean usesCustomGlow = view instanceof GlowTitleTextView || view instanceof GlowShimmerButton;
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
                && !(view instanceof GlowTitleTextView)
                && !(view instanceof GlowShimmerButton)) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        view.getPaint().setShadowLayer(dpf(8f), 0, 0, Color.argb(195, 0, 245, 212));
        if (view instanceof GlowTitleTextView || view instanceof GlowShimmerButton) {
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

    private void applyStatusShimmerShader(TextView view, int width, int startColor, int endColor) {
        if (width <= 0) {
            return;
        }
        applyAnimatedStatusShimmerShader(view, width, 0f, startColor, endColor);
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

    private void applySettingsPageTitleShader(TextView view, int width) {
        applyTitleGradientShader(view, width, 
                Color.rgb(0, 245, 212), Color.rgb(80, 220, 255), Color.rgb(180, 100, 255));
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
            stylePlainWhiteText(view);
        }
    }

    private void styleStatusText(boolean hasClip) {
        if (statusText == null) {
            return;
        }
        // 确保状态文本也设置左右Padding预留光晕展示，不被视图边界截断
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
        } else if (isEditingPresetActive()) {
            // Live 模式：迷人动感的青绿色至翡翠色流光
            registerShimmerView(statusText);
            applyShimmerFrame(statusText, settingsTitleGradientWidth(statusText), currentShimmerPhaseForView(statusText));
        } else {
            // Edit 模式：平稳高贵的浅白至淡黄流光
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

    private Drawable glowRoundRectDrawable(int fillColor, int strokeColor, int radiusPx, int glowRadiusPx, int glowColor) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float inset = Math.max(dpf(1f), glowRadiusPx * 0.45f);
                rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                float radius = Math.max(1f, radiusPx - inset * 0.35f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fillColor);
                paint.setShadowLayer(glowRadiusPx, 0, 0, glowColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1.15f));
                paint.setColor(strokeColor);
                canvas.drawRoundRect(rect, radius, radius, paint);
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
                        shimmerLastWidth.remove(tabRef);
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

    private boolean isActiveBottomTab(View view) {
        if (view == null) {
            return false;
        }
        switch (clamp(activeMainPageIndex, 0, 2)) {
            case 0:
                return view == eqTabButton;
            case 1:
                return view == extraTabButton;
            default:
                return view == settingsTabButton;
        }
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
        View current = view;
        while (current != null) {
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
