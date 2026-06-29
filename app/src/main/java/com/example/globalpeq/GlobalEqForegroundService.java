package com.example.globalpeq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

public final class GlobalEqForegroundService extends Service {
    static final String ACTION_APPLY = "com.example.globalpeq.APPLY";
    static final String ACTION_BOOTSTRAP_CAPTURE = "com.example.globalpeq.BOOTSTRAP_CAPTURE";
    static final String ACTION_PAUSE_SHIZUKU = "com.example.globalpeq.PAUSE_SHIZUKU";
    static final String EXTRA_CAPTURE_RESULT_CODE = "capture_result_code";
    static final String EXTRA_CAPTURE_DATA = "capture_result_data";
    static final String EXTRA_PRESET_JSON = "preset_json";
    static final String EXTRA_DEVICE_KEY = "device_key";
    static final String EXTRA_DEVICE_LABEL = "device_label";
    static final String EXTRA_PROCESSING_MODE = "processing_mode";
    static final String EXTRA_ADVANCED_MODE_CONFIG_JSON = "advanced_mode_config_json";
    private static final String CHANNEL_ID = "global_eq";
    private static final int NOTIFICATION_ID = 10;
    private static final long CAPTURE_UPDATE_DEBOUNCE_MS = 350L;
    private static volatile boolean instanceRunning;

    private GlobalEqualizerEngine engine;
    private PlaybackCaptureEngine captureEngine;
    private ShizukuSessionMuteEngine shizukuMuteEngine;
    private PresetRepository repository;
    private AudioOutputDeviceMonitor deviceMonitor;
    private HandlerThread captureControlThread;
    private Handler captureControlHandler;
    private AudioOutputDevice currentDevice = new AudioOutputDevice("none", "Output device");
    private Preset currentPreset = Preset.flat(false);
    private ProcessingMode currentProcessingMode = ProcessingMode.SYSTEM_EQ;
    private AdvancedModeConfig currentAdvancedModeConfig = AdvancedModeConfig.DEFAULT;
    private boolean awaitingInitialDeviceMonitorEvent;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessingMode pendingCaptureMode = ProcessingMode.SYSTEM_EQ;
    private Preset pendingCapturePreset = Preset.flat(false);
    private AdvancedModeConfig pendingCaptureConfig = AdvancedModeConfig.DEFAULT;
    private int pendingCaptureVirtualBassModeIndex;
    private AudioOutputDevice pendingCaptureDevice = new AudioOutputDevice("none", "Output device");
    private boolean pendingCaptureRouteRefresh;
    private final Runnable applyPendingCaptureUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (captureEngine == null || shizukuMuteEngine == null) {
                return;
            }
            android.util.Log.i("GlobalEqService", "DBG_RESUME applyPendingCaptureUpdate"
                    + " mode=" + pendingCaptureMode
                    + " device=" + (pendingCaptureDevice == null ? "null" : pendingCaptureDevice.key)
                    + " presetEnabled=" + (pendingCapturePreset != null && pendingCapturePreset.enabled)
                    + " routeRefresh=" + pendingCaptureRouteRefresh);
            if (pendingCaptureRouteRefresh) {
                captureEngine.requestOutputRouteRefresh();
            }
            captureEngine.updateProcessing(
                    pendingCaptureMode,
                    pendingCapturePreset,
                    pendingCaptureConfig,
                    pendingCaptureVirtualBassModeIndex,
                    pendingCaptureDevice);
            pendingCaptureRouteRefresh = false;
            shizukuMuteEngine.updateProcessing(
                    pendingCaptureMode,
                    pendingCapturePreset,
                    pendingCaptureConfig);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRunning = true;
        repository = new PresetRepository(this);
        repository.saveServiceActive(true);
        engine = GlobalEqRuntime.engine();
        currentProcessingMode = repository.loadProcessingMode();
        currentAdvancedModeConfig = repository.loadAdvancedModeConfig();
        captureEngine = new PlaybackCaptureEngine(this, repository, this::updateNotification);
        shizukuMuteEngine = new ShizukuSessionMuteEngine(
                this,
                repository,
                this::updateNotification,
                () -> captureEngine == null ? java.util.Collections.emptySet() : captureEngine.getOwnedAudioSessionIds());
        captureControlThread = new HandlerThread("global-peq-capture-control");
        captureControlThread.start();
        captureControlHandler = new Handler(captureControlThread.getLooper());
        deviceMonitor = new AudioOutputDeviceMonitor(this);
        createNotificationChannel();
        AudioOutputDevice selected = repository.loadSelectedDevice();
        if (selected != null) {
            currentDevice = selected;
        }
        awaitingInitialDeviceMonitorEvent = true;
        deviceMonitor.start(device -> {
            repository.saveKnownDevice(device);
            if (!repository.loadAutoSwitchOutput()) {
                return;
            }
            boolean sameRoute = currentDevice != null && currentDevice.key.equals(device.key);
            android.util.Log.i("GlobalEqService", "DBG_RESUME deviceMonitor"
                    + " device=" + device.key
                    + " sameRoute=" + sameRoute
                    + " modeBeforeReload=" + currentProcessingMode);
            currentProcessingMode = repository.loadProcessingMode();
            currentAdvancedModeConfig = repository.loadAdvancedModeConfig();
            if (awaitingInitialDeviceMonitorEvent) {
                awaitingInitialDeviceMonitorEvent = false;
                currentDevice = device;
                repository.saveSelectedDevice(currentDevice);
                currentPreset = repository.loadPreset(device, currentProcessingMode)
                        .withEnabled(repository.loadMasterEnabled());
                if (sameRoute) {
                    updateNotification();
                    return;
                }
            }
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            currentPreset = repository.loadPreset(device, currentProcessingMode)
                    .withEnabled(repository.loadMasterEnabled());
            int virtualBassModeIndex = currentPreset.virtualBassModeIndex;
            Preset effectivePreset = AudioProcessingPolicy.effectiveSystemPreset(currentPreset, currentProcessingMode, virtualBassModeIndex);
            if (currentProcessingMode == ProcessingMode.SHIZUKU_MUTE) {
                engine.apply(effectivePreset);
            } else if (sameRoute) {
                engine.reapplyForRouteChange(effectivePreset);
            } else {
                engine.applyWithFullReset(effectivePreset);
            }
            scheduleCaptureUpdate(
                    currentProcessingMode,
                    currentPreset,
                    currentAdvancedModeConfig,
                    virtualBassModeIndex,
                    currentDevice,
                    CAPTURE_UPDATE_DEBOUNCE_MS,
                    sameRoute && currentProcessingMode == ProcessingMode.SHIZUKU_MUTE);
            updateNotification();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BOOTSTRAP_CAPTURE.equals(action)) {
            startForegroundInternal(true);
            scheduleCaptureBootstrap(
                    intent.getIntExtra(EXTRA_CAPTURE_RESULT_CODE, android.app.Activity.RESULT_CANCELED),
                    intent.getParcelableExtra(EXTRA_CAPTURE_DATA));
        } else if (ACTION_PAUSE_SHIZUKU.equals(action)) {
            requestStopAllAndStopService();
            return START_NOT_STICKY;
        } else {
            startForegroundInternal(captureEngine.hasProjection());
        }
        boolean appliedIntentState = (ACTION_APPLY.equals(action) || ACTION_BOOTSTRAP_CAPTURE.equals(action))
                && applyStateFromIntent(intent);
        Preset preset = appliedIntentState ? applyCurrentPresetState() : applySavedPreset();
        if (!preset.enabled) {
            requestStopAllAndStopService();
            return START_NOT_STICKY;
        }
        if (action == null
                && currentProcessingMode == ProcessingMode.SHIZUKU_MUTE
                && (captureEngine == null || !captureEngine.hasProjection())) {
            requestStopAllAndStopService();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        requestStopAllAndStopService();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instanceRunning = false;
        deviceMonitor.stop();
        if (repository != null) {
            repository.saveServiceActive(false);
        }
        if (captureControlHandler != null) {
            captureControlHandler.removeCallbacksAndMessages(null);
        }
        stopAllProcessingNow();
        if (captureControlThread != null) {
            captureControlThread.quitSafely();
            captureControlThread = null;
        }
        captureControlHandler = null;
        super.onDestroy();
    }

    private Preset applySavedPreset() {
        refreshSavedPresetState();
        return applyCurrentPresetState();
    }

    private Preset refreshSavedPresetState() {
        AudioOutputDevice selected = repository.loadSelectedDevice();
        currentDevice = selected == null ? deviceMonitor.currentOutputDevice() : selected;
        repository.saveSelectedDevice(currentDevice);
        currentProcessingMode = repository.loadProcessingMode();
        currentPreset = repository.loadPreset(currentDevice, currentProcessingMode)
                .withEnabled(repository.loadMasterEnabled());
        currentAdvancedModeConfig = repository.loadAdvancedModeConfig();
        updateNotification();
        return currentPreset;
    }

    private Preset applyCurrentPresetState() {
        if (currentPreset == null) {
            currentPreset = Preset.flat(false);
        }
        if (currentDevice == null) {
            currentDevice = deviceMonitor.currentOutputDevice();
        }
        int virtualBassModeIndex = currentPreset.virtualBassModeIndex;
        engine.apply(AudioProcessingPolicy.effectiveSystemPreset(
                currentPreset,
                currentProcessingMode,
                virtualBassModeIndex));
        scheduleCaptureUpdate(
                currentProcessingMode,
                currentPreset,
                currentAdvancedModeConfig,
                virtualBassModeIndex,
                currentDevice,
                0L);
        updateNotification();
        return currentPreset;
    }

    private boolean applyStateFromIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String presetJson = intent.getStringExtra(EXTRA_PRESET_JSON);
        if (presetJson == null || presetJson.trim().isEmpty()) {
            return false;
        }
        currentPreset = Preset.fromJson(presetJson).withEnabled(repository.loadMasterEnabled());
        String deviceKey = intent.getStringExtra(EXTRA_DEVICE_KEY);
        String deviceLabel = intent.getStringExtra(EXTRA_DEVICE_LABEL);
        if (deviceKey != null && !deviceKey.trim().isEmpty()
                && deviceLabel != null && !deviceLabel.trim().isEmpty()) {
            currentDevice = new AudioOutputDevice(deviceKey, deviceLabel);
        }
        currentProcessingMode = ProcessingMode.fromKey(intent.getStringExtra(EXTRA_PROCESSING_MODE));
        currentAdvancedModeConfig = AdvancedModeConfig.fromJson(
                intent.getStringExtra(EXTRA_ADVANCED_MODE_CONFIG_JSON));
        updateNotification();
        return true;
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        String state = currentPreset.enabled ? "Global PEQ on" : "Global PEQ off";
        ProcessingMode mode = currentProcessingMode;
        String content;
        if (mode == ProcessingMode.SHIZUKU_MUTE) {
            content = repository.loadShizukuMuteStatus();
        } else {
            content = currentDevice.label;
        }
        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(state)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(currentPreset.enabled)
                .build();
    }

    private void startForegroundInternal(boolean withProjection) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (withProjection) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            startForeground(NOTIFICATION_ID, notification, type);
            return;
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Global equalizer",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    private void scheduleCaptureBootstrap(int resultCode, Intent data) {
        Handler handler = captureControlHandler;
        if (handler == null || captureEngine == null) {
            return;
        }
        Intent copy = data == null ? null : new Intent(data);
        handler.post(() -> {
            captureEngine.bootstrapProjection(resultCode, copy);
            ProcessingMode mode = repository.loadProcessingMode();
            AdvancedModeConfig config = repository.loadAdvancedModeConfig();
            captureEngine.updateProcessing(
                    mode,
                    currentPreset,
                    config,
                    currentPreset.virtualBassModeIndex,
                    currentDevice);
            shizukuMuteEngine.updateProcessing(
                    mode,
                    currentPreset,
                    config);
        });
    }

    private void scheduleCaptureStopAll() {
        Handler handler = captureControlHandler;
        if (handler == null || captureEngine == null) {
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.post(() -> captureEngine.stopAll());
    }

    private void scheduleShizukuStopAll() {
        Handler handler = captureControlHandler;
        if (handler == null || shizukuMuteEngine == null) {
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.post(() -> shizukuMuteEngine.stopAll());
    }

    private void requestStopAllAndStopService() {
        Handler handler = captureControlHandler;
        if (handler == null) {
            stopAllProcessingNow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            updateNotification();
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.post(() -> {
            stopAllProcessingNow();
            mainHandler.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                updateNotification();
            });
        });
    }

    private void stopAllProcessingNow() {
        if (captureEngine != null) {
            captureEngine.stopAll();
        }
        if (shizukuMuteEngine != null) {
            shizukuMuteEngine.stopAll();
        }
        if (engine != null) {
            engine.apply(Preset.flat(false));
        }
        if (repository != null) {
            repository.clearRuntimeAudioState(ShizukuCompat.describeState(this));
        }
    }

    private void scheduleCaptureUpdate(ProcessingMode processingMode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int virtualBassModeIndex,
                                       AudioOutputDevice outputDevice,
                                       long delayMs) {
        scheduleCaptureUpdate(processingMode, preset, config, virtualBassModeIndex, outputDevice, delayMs, false);
    }

    private void scheduleCaptureUpdate(ProcessingMode processingMode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int virtualBassModeIndex,
                                       AudioOutputDevice outputDevice,
                                       long delayMs,
                                       boolean forceRouteRefresh) {
        Handler handler = captureControlHandler;
        if (handler == null || captureEngine == null || shizukuMuteEngine == null) {
            return;
        }
        pendingCaptureMode = processingMode == null ? ProcessingMode.SYSTEM_EQ : processingMode;
        pendingCapturePreset = preset == null ? Preset.flat(false) : preset;
        pendingCaptureConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        pendingCaptureVirtualBassModeIndex = virtualBassModeIndex;
        pendingCaptureDevice = outputDevice == null
                ? new AudioOutputDevice("none", "Output device")
                : outputDevice;
        pendingCaptureRouteRefresh = forceRouteRefresh;
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        if (delayMs <= 0L) {
            handler.post(applyPendingCaptureUpdateRunnable);
        } else {
            handler.postDelayed(applyPendingCaptureUpdateRunnable, delayMs);
        }
    }

    static boolean isRunningInProcess() {
        return instanceRunning;
    }
}
