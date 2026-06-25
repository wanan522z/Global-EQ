package com.example.globalpeq;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.RenderEffect;
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
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
    private static final String[] CURVE_RANGE_LABELS = {"±6", "±12", "±18"};
    private static final String[] CURVE_SMOOTHING_LABELS = {"Default", "1/3", "1/6", "1/12", "1/24"};

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
    private LinearLayout rows;
    private KnobView cutoffKnob;
    private KnobView amountKnob;
    private KnobView systemBassBoostKnob;
    private EditText cutoffInput;
    private EditText amountInput;
    private EditText systemBassBoostInput;
    private TextView statusText;
    private Spinner deviceSpinner;
    private Spinner savedPresetSpinner;
    private Spinner modeSpinner;
    private Button presetSelectButton;
    private Button undoButton;
    private Button redoButton;
    private Button savePresetButton;
    private Button eqTabButton;
    private Button extraTabButton;
    private Button settingsTabButton;
    private Switch enabledSwitch;
    private Switch autoSwitchOutputSwitch;
    private LinearLayout header;
    private Preset runningPreset;
    private Preset editingPreset;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<Preset> undoStack = new ArrayList<>();
    private final List<Preset> redoStack = new ArrayList<>();
    private Preset pendingGeqHistorySnapshot;
    private final Runnable commitGeqUpdateRunnable = this::commitPendingGeqUpdate;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new PresetRepository(this);
        engine = GlobalEqRuntime.engine();
        supported = true;
        autoSwitchOutput = repository.loadAutoSwitchOutput();
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
        Preset draftPreset = repository.loadDraftPreset();
        editingPreset = draftPreset == null ? runningPreset : limitPresetForHeadroom(draftPreset);

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
        deviceMonitor.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
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
                repository.saveDeviceCurveGainOffsetDb(deviceCurveGainOffsetDb);
                repository.saveDeviceCurveSmoothing(deviceCurveSmoothing);
                repository.saveSelectedDeviceCurveName(curve.name);
                refreshDeviceCurveCache();
            } else {
                repository.saveTargetCurve(curve);
                selectedTargetCurveName = curve.name;
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                repository.saveTargetCurveGainOffsetDb(targetCurveGainOffsetDb);
                repository.saveTargetCurveSmoothing(targetCurveSmoothing);
                repository.saveSelectedTargetCurveName(curve.name);
                refreshTargetCurveCache();
            }
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
            return;
        }

        currentDevice = device;
        repository.saveSelectedDevice(currentDevice);
        Preset loadedPreset = repository.loadPreset(device);
        loadedPreset = limitPresetForHeadroom(loadedPreset);
        runningPreset = loadedPreset.withEnabled(loadedPreset.enabled && supported);
        Preset draftPreset = repository.loadDraftPreset();
        editingPreset = draftPreset == null ? runningPreset : limitPresetForHeadroom(draftPreset);
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

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        eqPage = new LinearLayout(this);
        eqPage.setOrientation(LinearLayout.VERTICAL);
        content.addView(eqPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        extraPage = new LinearLayout(this);
        extraPage.setOrientation(LinearLayout.VERTICAL);
        extraPage.setVisibility(View.GONE);
        extraPage.setPadding(0, dp(16), 0, 0);
        content.addView(extraPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        buildExtraPage(extraPage);

        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        settingsPage.setVisibility(View.GONE);
        content.addView(settingsPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        buildSettingsPage(settingsPage);

        LinearLayout controlCard = new LinearLayout(this);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        controlCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        controlCard.setBackground(createGlassCard(35));
        eqPage.addView(controlCard, blockParams(0));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT);
        controlCard.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        modeSpinner = new Spinner(this);
        normalizeSpinnerSurface(modeSpinner);
        ArrayAdapter<String> modeAdapter = new SmallSpinnerAdapter(EqMode.labels());
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        modeSpinner.setBackground(solidColorDrawable(Color.TRANSPARENT));
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingUi || editingPreset == null) {
                    return;
                }
                EqMode nextMode = EqMode.values()[Math.max(0, Math.min(EqMode.values().length - 1, position))];
                if (editingPreset.mode != nextMode) {
                    setEditingPreset(editingPreset.withMode(nextMode), true);
                    renderAll();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        top.addView(modeSpinner, new LinearLayout.LayoutParams(
                dp(122),
                dp(36)
        ));

        View switchSpacer = new View(this);
        top.addView(switchSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

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
        statusText = new TextView(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && h > 0) {
                    boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
                    if (hasClip || !supported) {
                        getPaint().setShader(new LinearGradient(
                                0, 0, w, 0,
                                new int[]{Color.rgb(255, 100, 100), Color.rgb(255, 160, 160)},
                                null, Shader.TileMode.CLAMP));
                    } else {
                        getPaint().setShader(new LinearGradient(
                                0, 0, w, 0,
                                new int[]{Color.rgb(0, 255, 255), Color.rgb(120, 200, 255)},
                                null, Shader.TileMode.CLAMP));
                    }
                }
            }
        };
        statusText.setTextSize(12);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setTextColor(supported ? Color.rgb(0, 255, 255) : Color.rgb(255, 100, 100));
        int controlGap = 12;
        LinearLayout.LayoutParams autoSwitchParams = new LinearLayout.LayoutParams(
                dp(54),
                dp(32)
        );
        autoSwitchParams.rightMargin = dp(controlGap);
        top.addView(autoSwitchOutputSwitch, autoSwitchParams);

        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                dp(42),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.rightMargin = dp(controlGap);
        top.addView(statusText, statusParams);

        top.addView(enabledSwitch, new LinearLayout.LayoutParams(dp(54), dp(32)));

        LinearLayout deviceRow = new LinearLayout(this);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        deviceRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controlCard.addView(deviceRow, blockParams(6));

        deviceSpinner = new Spinner(this);
        normalizeSpinnerSurface(deviceSpinner);
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
        GradientDrawable deviceBg = new GradientDrawable();
        deviceBg.setShape(GradientDrawable.RECTANGLE);
        deviceBg.setColor(Color.argb(40, 255, 255, 255));
        deviceBg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        deviceBg.setCornerRadius(dp(8));
        deviceSpinner.setBackground(deviceBg);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(0, dp(38), 1.8f);
        deviceParams.leftMargin = 0;
        deviceParams.rightMargin = 0;
        deviceRow.addView(deviceSpinner, deviceParams);

        savedPresetSpinner = new Spinner(this);
        normalizeSpinnerSurface(savedPresetSpinner);
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
        GradientDrawable spinnerBg = new GradientDrawable();
        spinnerBg.setShape(GradientDrawable.RECTANGLE);
        spinnerBg.setColor(Color.argb(40, 255, 255, 255));
        spinnerBg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        spinnerBg.setCornerRadius(dp(8));
        savedPresetSpinner.setBackground(spinnerBg);
        LinearLayout.LayoutParams savedPresetParams = new LinearLayout.LayoutParams(0, dp(38), 1.2f);
        savedPresetParams.leftMargin = dp(controlGap);
        savedPresetParams.rightMargin = 0;
        deviceRow.addView(savedPresetSpinner, savedPresetParams);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controlCard.addView(presetRow, blockParams(6));

        presetSelectButton = new MarqueeButton(this);
        presetSelectButton.setTextSize(13);
        presetSelectButton.setAllCaps(false);
        presetSelectButton.setGravity(android.view.Gravity.CENTER);
        configureCenteredMarquee(presetSelectButton);
        
        presetSelectButton.setOnClickListener(v -> showPresetMenu());
        presetRow.addView(presetSelectButton, presetButtonParams(0, 1.3f, 0, controlGap));

        undoButton = new Button(this);
        undoButton.setText("\u2039");
        undoButton.setTextSize(24);
        undoButton.setOnClickListener(v -> undoEdit());
        presetRow.addView(undoButton, presetButtonParams(dp(48), 0f, 0, controlGap));

        redoButton = new Button(this);
        redoButton.setText("\u203A");
        redoButton.setTextSize(24);
        redoButton.setOnClickListener(v -> redoEdit());
        presetRow.addView(redoButton, presetButtonParams(dp(48), 0f, 0, controlGap));

        savePresetButton = new Button(this);
        savePresetButton.setText("Save");
        savePresetButton.setTextSize(12);
        savePresetButton.setOnClickListener(v -> showSavePresetDialog());
        presetRow.addView(savePresetButton, presetButtonParams(dp(48), 0f, 0, 0));

        FrameLayout curveFrame = new FrameLayout(this);
        curveFrameView = curveFrame;
        curveFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        curveFrame.setBackground(createGlassCard(20));
        
        curveView = new EqCurveView(this);
        curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
        curveView.setMaxDb(curveGraphMaxDb);
        curveFrame.addView(curveView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        curveRangeSpinner = new Spinner(this);
        normalizeSpinnerSurface(curveRangeSpinner);
        ArrayAdapter<String> rangeAdapter = new CompactSpinnerAdapter(CURVE_RANGE_LABELS);
        rangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        curveRangeSpinner.setAdapter(rangeAdapter);
        curveRangeSpinner.setSelection(curveRangeIndex(curveGraphMaxDb));
        curveRangeSpinner.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        curveRangeSpinner.setBackground(curveControlBackground());
        curveRangeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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

    private void buildSettingsPage(LinearLayout page) {
        page.setPadding(0, dp(16), 0, 0);
        
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));
        page.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this) {
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
        title.setText("Engine Status");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title, blockParams(0));

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

        TextView statusVal = new TextView(this) {
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
        statusVal.setText(supported ? "ACTIVE" : "UNSUPPORTED");
        statusVal.setTextSize(14);
        statusVal.setTextColor(supported ? Color.rgb(0, 255, 255) : Color.rgb(255, 100, 100));
        statusVal.postInvalidate();
        statusVal.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusRow.addView(statusVal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout aboutPanel = new LinearLayout(this);
        aboutPanel.setOrientation(LinearLayout.VERTICAL);
        aboutPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        aboutPanel.setBackground(createGlassCard(30));
        
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        aboutParams.topMargin = dp(16);
        page.addView(aboutPanel, aboutParams);

        TextView aboutTitle = new TextView(this) {
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
        aboutTitle.setText("About Global PEQ");
        aboutTitle.setTextSize(18);
        aboutTitle.setTextColor(Color.WHITE);
        aboutTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        aboutPanel.addView(aboutTitle, blockParams(0));

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
        page.addView(footerText, footerParams);
    }

    private LinearLayout.LayoutParams presetButtonParams(int width, float weight, int leftDp, int rightDp) {
        LinearLayout.LayoutParams params = width > 0 
                ? new LinearLayout.LayoutParams(width, dp(38))
                : new LinearLayout.LayoutParams(0, dp(38), weight);
        params.leftMargin = dp(leftDp);
        params.rightMargin = dp(rightDp);
        return params;
    }

    private void renderAll() {
        updatingUi = true;
        boolean hasClip = PeqMath.presetMayClip(editingPreset, PeqMath.HEADROOM_LIMIT_MB);
        if (statusText != null) {
            statusText.setText(statusLabel(hasClip));
            statusText.setTextColor(hasClip ? Color.rgb(255, 100, 100) : Color.rgb(0, 255, 255));
            statusText.postInvalidate();
        }
        renderDeviceSpinner();
        if (modeSpinner != null) {
            modeSpinner.setSelection(editingPreset.mode.ordinal());
        }
        if (autoSwitchOutputSwitch != null) {
            autoSwitchOutputSwitch.setChecked(autoSwitchOutput);
        }
        if (presetSelectButton != null) {
            presetSelectButton.setText(editingPreset.name);
        }
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(runningPreset.enabled);
        }

        if (presetSelectButton != null) {
            styleSubtlePrimaryButton(presetSelectButton, supported);
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
        if (systemBassBoostKnob != null) {
            systemBassBoostKnob.setValue(editingPreset.systemBassBoostPercent, false);
        }
        if (systemBassBoostInput != null) {
            systemBassBoostInput.setText(String.valueOf(editingPreset.systemBassBoostPercent));
        }
        if (cutoffKnob != null) {
            cutoffKnob.setValue(editingPreset.virtualBassCutoffHz, false);
        }
        if (amountKnob != null) {
            amountKnob.setValue(editingPreset.virtualBassAmountPercent, false);
        }
        if (cutoffInput != null) {
            cutoffInput.setText(String.valueOf(editingPreset.virtualBassCutoffHz));
        }
        if (amountInput != null) {
            amountInput.setText(String.valueOf(editingPreset.virtualBassAmountPercent));
        }
        renderSavedPresetSpinner();
        renderCurveButtons();
        if (curveView != null) {
            curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
            curveView.setMaxDb(curveGraphMaxDb);
            curveView.setPreset(editingPreset);
        }
        renderHeader();
        renderRows();
        updatingUi = false;
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

        ArrayAdapter<String> adapter = new SmallSpinnerAdapter(labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        deviceSpinner.setSelection(selected);
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
        styleSubtlePrimaryButton(add, supported);
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        if (PeqMath.bandMayClip(editingPreset, index, PeqMath.HEADROOM_LIMIT_MB)) {
            GradientDrawable warningBg = new GradientDrawable();
            warningBg.setShape(GradientDrawable.RECTANGLE);
            warningBg.setColor(Color.argb(45, 255, 100, 100));
            warningBg.setStroke(dp(1), Color.argb(100, 255, 100, 100));
            warningBg.setCornerRadius(dp(8));
            row.setBackground(warningBg);
        }

        View enable = circleButton("", band.enabled, false);
        enable.setBackground(stateIndicatorDrawable(band.enabled));
        enable.setOnClickListener(v -> {
            updateBand(index, editingPreset.bands[index].withEnabled(!editingPreset.bands[index].enabled));
            renderRows();
        });
        row.addView(wrapCircularButton(enable, 0.35f, 22, 0, 4, -1));

        Spinner type = new Spinner(this);
        normalizeSpinnerSurface(type);
        ArrayAdapter<String> adapter = new CompactSpinnerAdapter(FilterType.labels());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        type.setAdapter(adapter);
        type.setSelection(band.type.ordinal());
        type.setEnabled(supported);
        type.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingUi) {
                    return;
                }
                FilterType selectedType = FilterType.values()[position];
                type.setBackground(typeCellBackground(selectedType));
                updateBand(index, editingPreset.bands[index].withType(selectedType));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        type.setBackground(typeCellBackground(band.type));
        row.addView(type, cellParams(1f, 36));

        EditText frequencyInput = createNumberInput(String.valueOf(band.frequencyHz), "Hz", value -> {
            updateBand(index, editingPreset.bands[index].withFrequencyHz(clamp(Math.round(value), 20, 20000)));
        });
        attachNumberWatermark(frequencyInput);
        attachEqEditFocus(frequencyInput, index, EQ_EDIT_FIELD_FREQ);
        row.addView(frequencyInput, cellParams(1f, 36));

        EditText gainInput = createNumberInput(formatDecimal(band.gainMb / 100f), "dB", value -> {
            int gainMb = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withGainMb(clamp(gainMb, -1800, 1800)));
        });
        attachNumberWatermark(gainInput);
        attachEqEditFocus(gainInput, index, EQ_EDIT_FIELD_GAIN);
        row.addView(gainInput, cellParams(1f, 36));

        EditText qInput = createNumberInput(formatDecimal(band.qHundred / 100f), "Q", value -> {
            int qHundred = Math.round(value * 100f);
            updateBand(index, editingPreset.bands[index].withQHundred(clamp(qHundred, 20, 1000)));
        });
        attachNumberWatermark(qInput);
        attachEqEditFocus(qInput, index, EQ_EDIT_FIELD_Q);
        row.addView(qInput, cellParams(1f, 36));

        View delete = circleButton("", false, true);
        delete.setEnabled(supported && editingPreset.bands.length > 1);
        delete.setBackground(deleteSymbolDrawable(delete.isEnabled()));
        delete.setOnClickListener(v -> confirmDeleteBand(index));
        row.addView(wrapCircularButton(delete, 0.35f, 22, 4, 0, 1));

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
        inputBg.setColor(Color.argb(30, 255, 255, 255));
        inputBg.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        inputBg.setCornerRadius(dp(6));
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
            curveView.setPreset(editingPreset);
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
                view.postDelayed(() -> {
                    View focused = getCurrentFocus();
                    if (!(focused instanceof EditText)) {
                        hideEqEditOverlay();
                    }
                }, 80);
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

        removeEqEditOverlayView();
        ParametricBand band = editingPreset.bands[bandIndex];
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(android.view.Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(8), dp(6), dp(8), dp(6));
        overlay.setBackground(createGlassCard(88));
        overlay.setElevation(dp(12));

        addEqOverlayCell(overlay, String.valueOf(bandIndex + 1), 0.38f, Color.rgb(0, 255, 255));
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

            removeEqEditDim();
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
                    .setDuration(120)
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
        input.setBackground(createFieldBackground(150, 95, 7));
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
        removeEqEditOverlayView();
        removeEqEditDim();
        if (hadOverlay && rows != null && editingPreset != null && editingPreset.mode == EqMode.PEQ) {
            renderRows();
        }
    }

    private void removeEqEditOverlayView() {
        if (activeEqEditOverlay == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) activeEqEditOverlay.getParent();
        if (parent != null) {
            parent.removeView(activeEqEditOverlay);
        }
        activeEqEditOverlay = null;
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
            repository.saveDeviceCurveGainOffsetDb(deviceCurveGainOffsetDb);
            repository.saveDeviceCurveSmoothing(deviceCurveSmoothing);
            repository.saveSelectedDeviceCurveName(item);
            refreshDeviceCurveCache();
            renderAll();
        }, item -> imported.contains(item), item -> {
            repository.deleteDeviceCurve(item);
            if (item.equals(selectedDeviceCurveName)) {
                selectedDeviceCurveName = "Default";
                deviceCurveGainOffsetDb = 0f;
                deviceCurveSmoothing = "Default";
                repository.saveSelectedDeviceCurveName(selectedDeviceCurveName);
                repository.saveDeviceCurveGainOffsetDb(deviceCurveGainOffsetDb);
                repository.saveDeviceCurveSmoothing(deviceCurveSmoothing);
                refreshDeviceCurveCache();
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
            repository.saveTargetCurveGainOffsetDb(targetCurveGainOffsetDb);
            repository.saveTargetCurveSmoothing(targetCurveSmoothing);
            repository.saveSelectedTargetCurveName(item);
            refreshTargetCurveCache();
            renderAll();
        }, item -> imported.contains(item), item -> {
            repository.deleteTargetCurve(item);
            if (item.equals(selectedTargetCurveName)) {
                selectedTargetCurveName = "Default";
                targetCurveGainOffsetDb = 0f;
                targetCurveSmoothing = "Default";
                repository.saveSelectedTargetCurveName(selectedTargetCurveName);
                repository.saveTargetCurveGainOffsetDb(targetCurveGainOffsetDb);
                repository.saveTargetCurveSmoothing(targetCurveSmoothing);
                refreshTargetCurveCache();
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

    private void refreshCurvePreviewOnly() {
        renderCurveButtons();
        if (curveView != null) {
            curveView.setReferenceCurves(selectedDeviceCurve, selectedTargetCurve);
            curveView.setMaxDb(curveGraphMaxDb);
            curveView.setPreset(editingPreset);
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
            repository.saveTargetCurveSmoothing(targetCurveSmoothing);
            refreshTargetCurveCache();
        } else {
            if (smoothing.equals(deviceCurveSmoothing)) {
                return;
            }
            deviceCurveSmoothing = smoothing;
            repository.saveDeviceCurveSmoothing(deviceCurveSmoothing);
            refreshDeviceCurveCache();
        }
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
        topRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        Spinner smoothingSpinner = new Spinner(this);
        ArrayAdapter<String> smoothingAdapter = new CompactSpinnerAdapter(CURVE_SMOOTHING_LABELS);
        smoothingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        smoothingSpinner.setAdapter(smoothingAdapter);
        smoothingSpinner.setSelection(curveSmoothingIndex(targetCurve ? targetCurveSmoothing : deviceCurveSmoothing));
        smoothingSpinner.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        smoothingSpinner.setBackground(createFieldBackground(18, 38, 7));
        smoothingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setCurveSmoothing(targetCurve, CURVE_SMOOTHING_LABELS[Math.max(0, Math.min(CURVE_SMOOTHING_LABELS.length - 1, position))]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams smoothingParams = new LinearLayout.LayoutParams(dp(84), dp(28));
        smoothingParams.leftMargin = dp(8);
        topRow.addView(smoothingSpinner, smoothingParams);

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

    private void setCurveGainOffset(boolean targetCurve, float offsetDb) {
        offsetDb = clampCurveGainOffset(offsetDb);
        if (targetCurve) {
            if (Math.abs(targetCurveGainOffsetDb - offsetDb) < 0.001f) {
                return;
            }
            targetCurveGainOffsetDb = offsetDb;
            repository.saveTargetCurveGainOffsetDb(offsetDb);
            selectedTargetCurve = applyCurveGainOffset(selectedTargetCurveBase, targetCurveGainOffsetDb);
        } else {
            if (Math.abs(deviceCurveGainOffsetDb - offsetDb) < 0.001f) {
                return;
            }
            deviceCurveGainOffsetDb = offsetDb;
            repository.saveDeviceCurveGainOffsetDb(offsetDb);
            selectedDeviceCurve = applyCurveGainOffset(selectedDeviceCurveBase, deviceCurveGainOffsetDb);
        }
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
        removeEqEditDim();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dim.setRenderEffect(RenderEffect.createBlurEffect(dpf(8f), dpf(8f), Shader.TileMode.CLAMP));
        }
        dim.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        root.addView(dim, params);
        dim.animate().alpha(1f).setDuration(100).start();
        eqEditDimViews.add(dim);
    }

    private void removeEqEditDim() {
        for (View view : eqEditDimViews) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        }
        eqEditDimViews.clear();
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
            list.addView(createCurveMenuRow(item, canDelete.canDelete(item), dialogHolder, () -> {
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

    private View createCurveMenuRow(String name, boolean canDelete, AlertDialog[] dialogHolder, Runnable selected, Runnable deleted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(Color.argb(24, 255, 255, 255));
        background.setStroke(dp(1), Color.argb(34, 255, 255, 255));
        background.setCornerRadius(dp(8));
        row.setBackground(background);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        title.setTextColor(Color.WHITE);
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

        Spinner targetSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new SmallSpinnerAdapter(targets.toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
        targetSpinner.setBackground(createFieldBackground(30, 60, 8));
        targetSpinner.setPopupBackgroundDrawable(solidColorDrawable(Color.rgb(22, 26, 38)));
        layout.addView(targetSpinner, new LinearLayout.LayoutParams(
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

        int selectedIndex = names.contains(editingPreset.name) ? targets.indexOf(editingPreset.name) : 0;
        targetSpinner.setSelection(Math.max(0, selectedIndex));
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String target = String.valueOf(parent.getItemAtPosition(position));
                input.setText(position == 0 ? nextPresetName() : target);
                input.selectAll();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView("Save preset"))
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, which) -> {
                    int selected = targetSpinner.getSelectedItemPosition();
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
        styleSubtlePrimaryButton(add, true);
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(Color.argb(name.equals(editingPreset.name) ? 42 : 24, 255, 255, 255));
        background.setStroke(dp(1), name.equals(editingPreset.name)
                 ? Color.argb(130, 0, 255, 255)
                : Color.argb(38, 255, 255, 255));
        background.setCornerRadius(dp(10));
        row.setBackground(background);

        TextView title = new TextView(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && h > 0 && name.equals(editingPreset.name)) {
                    getPaint().setShader(new LinearGradient(
                            0, 0, w, 0,
                            new int[]{Color.rgb(0, 255, 255), Color.rgb(180, 100, 255)},
                            null, Shader.TileMode.CLAMP));
                }
            }
        };
        title.setText(name);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setTextColor(Color.WHITE);
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
        Preset savedPreset = editingPreset.withName(finalName);

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
                    editingPreset = Preset.flat(runningPreset != null && runningPreset.enabled).withName(name);
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
            persistEditingPreset();
        }
        applyRunningPreset();
        curveView.setPreset(editingPreset);
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

    private void applyRunningPreset() {
        repository.saveSelectedDevice(currentDevice);
        repository.savePreset(currentDevice, runningPreset);
        repository.saveGlobalPreset(runningPreset);
        engine.apply(runningPreset);
        Intent service = new Intent(this, GlobalEqForegroundService.class);
        service.setAction(GlobalEqForegroundService.ACTION_APPLY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void selectOutputDevice(AudioOutputDevice selected) {
        repository.saveSelectedDevice(selected);
        currentDevice = selected;
        Preset loadedPreset = repository.loadPreset(currentDevice);
        loadedPreset = limitPresetForHeadroom(loadedPreset);
        runningPreset = loadedPreset.withEnabled(loadedPreset.enabled && supported);
        editingPreset = runningPreset;
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
        ArrayAdapter<String> adapter = new SmallSpinnerAdapter(names.toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        savedPresetSpinner.setAdapter(adapter);
        int selected = Math.max(0, names.indexOf(runningPreset.name));
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
            curveView.setPreset(editingPreset);
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
            statusText.setTextColor(hasClip ? Color.rgb(190, 0, 0) : Color.rgb(0, 121, 107));
        }
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
        if (cutoffKnob == null || amountKnob == null || cutoffInput == null || amountInput == null
                || systemBassBoostKnob == null || systemBassBoostInput == null) {
            return;
        }
        systemBassBoostKnob.setValue(editingPreset.systemBassBoostPercent, false);
        systemBassBoostInput.setText(String.valueOf(editingPreset.systemBassBoostPercent));
        cutoffKnob.setValue(editingPreset.virtualBassCutoffHz, false);
        amountKnob.setValue(editingPreset.virtualBassAmountPercent, false);
        cutoffInput.setText(String.valueOf(editingPreset.virtualBassCutoffHz));
        amountInput.setText(String.valueOf(editingPreset.virtualBassAmountPercent));
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

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setGravity(android.view.Gravity.CENTER);

        GradientDrawable navBg = new GradientDrawable();
        navBg.setShape(GradientDrawable.RECTANGLE);
        navBg.setColor(Color.argb(45, 20, 24, 38));
        navBg.setStroke(dp(1), Color.argb(35, 255, 255, 255));
        navBg.setCornerRadius(dp(16));
        nav.setBackground(navBg);

        eqTabButton = new Button(this);
        eqTabButton.setText("EQ");
        eqTabButton.setTextSize(13);
        eqTabButton.setAllCaps(false);
        normalizeBottomTab(eqTabButton);
        eqTabButton.setOnClickListener(v -> showEqPage());
        nav.addView(eqTabButton, bottomTabParams());

        extraTabButton = new Button(this);
        extraTabButton.setText("EXTRA");
        extraTabButton.setTextSize(13);
        extraTabButton.setAllCaps(false);
        normalizeBottomTab(extraTabButton);
        extraTabButton.setOnClickListener(v -> showExtraPage());
        nav.addView(extraTabButton, bottomTabParams());

        settingsTabButton = new Button(this);
        settingsTabButton.setText("Settings");
        settingsTabButton.setTextSize(13);
        settingsTabButton.setAllCaps(false);
        normalizeBottomTab(settingsTabButton);
        settingsTabButton.setOnClickListener(v -> showSettingsPage());
        nav.addView(settingsTabButton, bottomTabParams());

        Button[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        for (Button tab : tabs) {
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

        updateBottomNavSelection(0);

        return nav;
    }

    private LinearLayout.LayoutParams bottomTabParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private void normalizeBottomTab(Button button) {
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setSingleLine(true);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setIncludeFontPadding(false);
    }

    private void showEqPage() {
        animatePageTransition(eqPage, extraPage, settingsPage);
        updateBottomNavSelection(0);
    }

    private void showExtraPage() {
        animatePageTransition(extraPage, eqPage, settingsPage);
        updateBottomNavSelection(1);
    }

    private void showSettingsPage() {
        animatePageTransition(settingsPage, eqPage, extraPage);
        updateBottomNavSelection(2);
    }

    private void buildExtraPage(LinearLayout page) {
        page.setPadding(dp(12), dp(16), dp(12), dp(12));

        LinearLayout systemPanel = createExtraPanel("System BassBoost", "Uses Android's built-in bass boost effect.");
        page.addView(systemPanel, extraPanelParams(0));
        LinearLayout systemKnobs = createExtraKnobRow(systemPanel);
        systemKnobs.addView(createSystemBassBoostControl(), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout virtualPanel = createExtraPanel("Virtual Bass", "Shelf boost below the cutoff frequency.");
        page.addView(virtualPanel, extraPanelParams(12));
        LinearLayout virtualKnobs = createExtraKnobRow(virtualPanel);
        virtualKnobs.addView(createVirtualBassControl("Cutoff", true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        virtualKnobs.addView(createVirtualBassControl("Boost", false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private LinearLayout createExtraPanel(String titleText, String detailText) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(createGlassCard(35));

        TextView title = new TextView(this) {
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
        title.setText(titleText);
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title, blockParams(0));

        TextView detail = new TextView(this);
        detail.setText(detailText);
        detail.setTextSize(11);
        detail.setTextColor(Color.rgb(160, 170, 190));
        panel.addView(detail, blockParams(2));
        return panel;
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
        LinearLayout.LayoutParams knobsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        knobsParams.topMargin = dp(10);
        panel.addView(knobs, knobsParams);
        return knobs;
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

        systemBassBoostKnob = new KnobView(this);
        systemBassBoostKnob.configure(0, 100, editingPreset.systemBassBoostPercent, "%", value -> setEditingPreset(editingPreset.withSystemBassBoostPercent(value), true));
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(8);
        knobParams.bottomMargin = dp(8);
        column.addView(systemBassBoostKnob, knobParams);

        systemBassBoostInput = createNumberInput(String.valueOf(editingPreset.systemBassBoostPercent), "%", value -> {
            int percent = Math.round(value);
            setEditingPreset(editingPreset.withSystemBassBoostPercent(clamp(percent, 0, 100)), true);
        });
        systemBassBoostInput.setGravity(android.view.Gravity.CENTER);
        column.addView(systemBassBoostInput, new LinearLayout.LayoutParams(dp(84), dp(36)));
        return column;
    }

    private LinearLayout createVirtualBassControl(String label, boolean cutoff) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(Color.rgb(200, 210, 230));
        title.setGravity(android.view.Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        KnobView knob = new KnobView(this);
        EditText input;
        if (cutoff) {
            cutoffKnob = knob;
            knob.configure(60, 250, editingPreset.virtualBassCutoffHz, "Hz", value -> setEditingPreset(editingPreset.withVirtualBassCutoffHz(value), true));
            input = createNumberInput(String.valueOf(editingPreset.virtualBassCutoffHz), "Hz", value -> {
                int hz = Math.round(value);
                setEditingPreset(editingPreset.withVirtualBassCutoffHz(clamp(hz, 60, 250)), true);
            });
            cutoffInput = input;
        } else {
            amountKnob = knob;
            knob.configure(0, 100, editingPreset.virtualBassAmountPercent, "%", value -> setEditingPreset(editingPreset.withVirtualBassAmountPercent(value), true));
            input = createNumberInput(String.valueOf(editingPreset.virtualBassAmountPercent), "%", value -> {
                int percent = Math.round(value);
                setEditingPreset(editingPreset.withVirtualBassAmountPercent(clamp(percent, 0, 100)), true);
            });
            amountInput = input;
        }
        LinearLayout.LayoutParams knobParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        knobParams.topMargin = dp(8);
        knobParams.bottomMargin = dp(8);
        column.addView(knob, knobParams);

        input.setGravity(android.view.Gravity.CENTER);
        column.addView(input, new LinearLayout.LayoutParams(dp(84), dp(36)));
        return column;
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

    private GradientDrawable curveControlBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(7));
        background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        background.setColors(new int[]{
                Color.argb(232, 21, 34, 43),
                Color.argb(232, 12, 18, 28)
        });
        background.setStroke(dp(1), Color.argb(150, 76, 220, 205));
        return background;
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

                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(strokeWidth);
                ringPaint.setColor(active ? Color.argb(200, 0, 255, 255) : Color.argb(110, 160, 170, 190));
                canvas.drawCircle(cx, cy, radius, ringPaint);

                dotPaint.setStyle(Paint.Style.FILL);
                if (active) {
                    dotPaint.setColor(Color.rgb(0, 255, 255));
                    canvas.drawCircle(cx, cy, radius * 0.45f, dotPaint);
                } else {
                    dotPaint.setColor(Color.argb(70, 160, 170, 190));
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
                ringPaint.setColor(Color.argb(enabled ? 35 : 12, 255, 100, 100));
                canvas.drawCircle(cx, cy, radius, ringPaint);

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
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
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
        if (manager != null) {
            manager.hideSoftInputFromWindow(tokenView.getWindowToken(), 0);
        }
        if (focused != null) {
            focused.clearFocus();
        } else if (view != null) {
            view.clearFocus();
        }
        hideEqEditOverlay();
        refreshCurvePreviewOnly();
    }

    private interface FloatChanged {
        void onChanged(float value);
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
            if (Math.abs(gainMb) > 10) {
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
            paint.setStrokeWidth(adjustingGain ? dpf(1.6f) : dpf(1.0f));
            paint.setColor(adjustingGain ? Color.rgb(0, 245, 212) : Color.argb(100, 255, 255, 255));
            if (adjustingGain) {
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
            paint.setShadowLayer(dpf(3f), 0, 0, Color.argb(220, 0, 245, 212));
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
            if (gainMb != 0) {
                paint.setColor(Color.rgb(0, 245, 212));
                paint.setFakeBoldText(true);
            } else {
                paint.setColor(Color.argb(130, 255, 255, 255));
            }
            canvas.drawText(formatDecimal(gainMb / 100f), centerX, height - dpf(6f), paint);
            paint.setFakeBoldText(false);
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

    private class SmallSpinnerAdapter extends ArrayAdapter<String> {
        SmallSpinnerAdapter(String[] labels) {
            super(MainActivity.this, android.R.layout.simple_spinner_item, labels);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = spinnerTextView(convertView);
            view.setText(getItem(position));
            view.setTextSize(11);
            view.setPadding(dp(6), 0, dp(6), 0);
            view.setTextColor(Color.WHITE);
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
            view.setTextColor(Color.WHITE);
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
            view.setTextColor(Color.WHITE);
            configureCenteredMarquee(view);
            
            return view;
        }
    }

    private final class CompactSpinnerAdapter extends SmallSpinnerAdapter {
        CompactSpinnerAdapter(String[] labels) {
            super(labels);
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

    private void styleTopSwitch(Switch switchView, boolean autoSwitch) {
        switchView.setShowText(false);
        switchView.setText("");
        switchView.setPadding(0, 0, 0, 0);
        switchView.setMinWidth(dp(54));
        switchView.setMinimumWidth(dp(54));
        switchView.setMinHeight(dp(32));
        switchView.setMinimumHeight(dp(32));
        switchView.setSwitchMinWidth(dp(54));
        switchView.setThumbDrawable(switchThumbDrawable(
                autoSwitch ? Color.rgb(150, 155, 175) : Color.rgb(180, 185, 200),
                Color.rgb(0, 255, 255)
        ));
        switchView.setTrackDrawable(labeledSwitchTrackDrawable(
                autoSwitch ? "AUTO" : "OFF",
                autoSwitch ? "AUTO" : "ON",
                autoSwitch ? Color.argb(40, 255, 255, 255) : Color.argb(50, 255, 255, 255),
                autoSwitch ? Color.argb(100, 0, 255, 255) : Color.argb(120, 0, 255, 255)
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
                rect.set(b.left, b.top + dpf(2f), b.right, b.bottom - dpf(2f));

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(checked ? checkedColor : uncheckedColor);
                float radius = rect.height() / 2f;
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(checked ? Color.argb(95, 0, 255, 255) : Color.argb(42, 255, 255, 255));
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(dpf(8.5f));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(checked ? Color.argb(92, 4, 32, 34) : Color.argb(88, 255, 255, 255));
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float textY = rect.centerY() - (metrics.ascent + metrics.descent) / 2f + dpf(4f);
                float leftTextX = rect.left + rect.width() * 0.32f;
                float rightTextX = rect.left + rect.width() * 0.68f;
                float textX = rightTextX + (leftTextX - rightTextX) * labelProgress;
                canvas.drawText(checked ? checkedLabel : uncheckedLabel, textX, textY, paint);
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
                labelAnimator.setDuration(150);
                labelAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
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

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                boolean checked = drawableStateChecked(getState());
                float radius = Math.min(b.width(), b.height()) / 2f - dpf(1f);
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(checked ? checkedColor : uncheckedColor);
                canvas.drawCircle(b.centerX(), b.centerY(), radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(checked ? Color.argb(110, 255, 255, 255) : Color.argb(70, 255, 255, 255));
                canvas.drawCircle(b.centerX(), b.centerY(), radius, paint);
            }

            @Override
            protected boolean onStateChange(int[] state) {
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
        updateNumberWatermark(input);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateNumberWatermark(input);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void updateNumberWatermark(EditText input) {
        input.setBackground(numberCellBackground(integerWatermark(input.getText().toString())));
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
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                rect.set(b.left, b.top, b.right, b.bottom);
                float radius = dpf(6f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(32, 255, 255, 255));
                canvas.drawRoundRect(rect, radius, radius, paint);

                if (watermark != null && !watermark.isEmpty()) {
                    canvas.save();
                    canvas.clipRect(b.left, b.top, b.right, b.bottom);
                    paint.setShader(null);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextAlign(Paint.Align.RIGHT);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    paint.setTextSize(dpf(watermark.length() > 3 ? 24f : 28f));
                    paint.setColor(Color.argb(30, 0, 245, 212));
                    canvas.drawText(watermark, b.right + dpf(4f), b.bottom - dpf(1f), paint);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                    canvas.restore();
                }

                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(Color.argb(66, 255, 255, 255));
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

    private Drawable typeCellBackground(FilterType filterType) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final android.graphics.RectF rect = new android.graphics.RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                rect.set(b.left, b.top, b.right, b.bottom);
                float radius = dpf(6f);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(32, 255, 255, 255));
                canvas.drawRoundRect(rect, radius, radius, paint);

                canvas.save();
                canvas.clipRect(b.left, b.top, b.right, b.bottom);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(dpf(1.5f));
                paint.setColor(Color.argb(58, 0, 245, 212));
                buildFilterTypePath(path, b, filterType);
                canvas.drawPath(path, paint);

                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(dpf(filterType.label.length() > 5 ? 19f : 22f));
                paint.setColor(Color.argb(24, 0, 245, 212));
                canvas.drawText(filterType.label, b.right + dpf(4f), b.bottom - dpf(1f), paint);
                paint.setTypeface(android.graphics.Typeface.DEFAULT);
                canvas.restore();

                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1f));
                paint.setColor(Color.argb(66, 255, 255, 255));
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

    private void buildFilterTypePath(Path path, Rect b, FilterType filterType) {
        path.reset();
        float left = b.left + dpf(8f);
        float right = b.right - dpf(8f);
        float center = b.centerY() + dpf(4f);
        float high = b.top + dpf(11f);
        float low = b.bottom - dpf(10f);
        float midX = b.centerX();
        switch (filterType) {
            case LOW_SHELF:
                path.moveTo(left, low);
                path.lineTo(left + (right - left) * 0.30f, low);
                path.cubicTo(midX - dpf(12f), low, midX - dpf(8f), high, midX + dpf(10f), high);
                path.lineTo(right, high);
                break;
            case HIGH_SHELF:
                path.moveTo(left, high);
                path.lineTo(left + (right - left) * 0.34f, high);
                path.cubicTo(midX - dpf(6f), high, midX + dpf(8f), low, midX + dpf(12f), low);
                path.lineTo(right, low);
                break;
            case LOW_PASS:
                path.moveTo(left, high);
                path.lineTo(left + (right - left) * 0.42f, high);
                path.cubicTo(midX, high, midX + dpf(10f), low, right, low);
                break;
            case HIGH_PASS:
                path.moveTo(left, low);
                path.cubicTo(left + dpf(18f), low, midX - dpf(8f), high, midX, high);
                path.lineTo(right, high);
                break;
            case PEAK:
            default:
                path.moveTo(left, center);
                path.cubicTo(left + dpf(18f), center, midX - dpf(15f), high, midX, high);
                path.cubicTo(midX + dpf(15f), high, right - dpf(18f), center, right, center);
                break;
        }
    }

    private TextView dialogTitleView(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
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
        }
        TextView title = (TextView) dialog.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(Color.WHITE);
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
            pos.setTextColor(Color.rgb(0, 245, 212));
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

    private void styleButton(Button button, boolean isPrimary, boolean isEnabled) {
        button.setEnabled(isEnabled);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        if (isPrimary) {
            if (isEnabled) {
                gd.setColors(new int[]{Color.rgb(0, 160, 255), Color.rgb(0, 229, 255)});
                gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                button.setTextColor(Color.WHITE);
            } else {
                gd.setColor(Color.argb(40, 80, 90, 100));
                button.setTextColor(Color.argb(100, 255, 255, 255));
            }
        } else {
            if (isEnabled) {
                gd.setColor(Color.argb(30, 255, 255, 255));
                gd.setStroke(dp(1), Color.argb(60, 255, 255, 255));
                button.setTextColor(Color.WHITE);
            } else {
                gd.setColor(Color.argb(10, 255, 255, 255));
                gd.setStroke(dp(1), Color.argb(20, 255, 255, 255));
                button.setTextColor(Color.argb(80, 255, 255, 255));
            }
        }
        button.setBackground(gd);
        button.setAllCaps(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        
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

    private void styleSubtlePrimaryButton(Button button, boolean isEnabled) {
        button.setEnabled(isEnabled);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        if (isEnabled) {
            gd.setColors(new int[]{Color.argb(180, 0, 180, 255), Color.argb(180, 140, 0, 255)});
            gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            gd.setStroke(dp(1), Color.argb(150, 0, 255, 255));
            button.setTextColor(Color.WHITE);
        } else {
            gd.setColor(Color.argb(35, 80, 90, 100));
            gd.setStroke(dp(1), Color.argb(25, 255, 255, 255));
            button.setTextColor(Color.argb(100, 255, 255, 255));
        }
        button.setBackground(gd);
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

    private void animatePageTransition(View show, View... hide) {
        for (View h : hide) {
            h.setVisibility(View.GONE);
        }
        show.setVisibility(View.VISIBLE);
        show.setAlpha(0f);
        show.setTranslationY(dp(15));
        show.animate()
            .alpha(1f)
            .translationY(0)
            .setDuration(220)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();
    }

    private void updateBottomNavSelection(int activeIndex) {
        if (eqTabButton == null || extraTabButton == null || settingsTabButton == null) return;
        
        Button[] tabs = {eqTabButton, extraTabButton, settingsTabButton};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == activeIndex);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp(12));
            if (active) {
                gd.setColor(Color.argb(35, 0, 245, 212));
                gd.setStroke(dp(1), Color.argb(70, 0, 245, 212));
                tabs[i].setTextColor(Color.rgb(0, 245, 212));
            } else {
                gd.setColor(Color.TRANSPARENT);
                tabs[i].setTextColor(Color.argb(140, 255, 255, 255));
            }
            tabs[i].setBackground(gd);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
