package com.example.globalpeq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

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
    private static final long CAPTURE_ROUTE_SUPPRESSION_AFTER_UNLOCK_MS = 2500L;
    private static final long CAPTURE_WAKE_RECOVERY_DELAY_MS =
            CAPTURE_ROUTE_SUPPRESSION_AFTER_UNLOCK_MS + CAPTURE_UPDATE_DEBOUNCE_MS;
    private static final long LAUNCHER_ALIAS_NOTIFICATION_REFRESH_DELAY_MS = 650L;
    private static volatile boolean instanceRunning;
    private static volatile GlobalEqForegroundService runningInstance;

    private GlobalEqualizerEngine engine;
    private GlobalDvcController dvcController;
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
    private final Runnable notificationAppearanceRefreshRunnable = this::updateNotification;
    private ProcessingMode pendingCaptureMode = ProcessingMode.SYSTEM_EQ;
    private Preset pendingCapturePreset = Preset.flat(false);
    private AdvancedModeConfig pendingCaptureConfig = AdvancedModeConfig.DEFAULT;
    private int pendingCaptureVirtualBassModeIndex;
    private AudioOutputDevice pendingCaptureDevice = new AudioOutputDevice("none", "Output device");
    private long suppressCaptureRouteUpdatesUntilMs;
    private boolean systemEqPlaybackStateKnown;
    private boolean systemEqPlaybackActive;
    private volatile boolean stopping;
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                suppressCaptureRouteUpdatesUntilMs = SystemClock.elapsedRealtime()
                        + CAPTURE_ROUTE_SUPPRESSION_AFTER_UNLOCK_MS;
                scheduleWakeRecovery();
            }
        }
    };
    private final Runnable applyPendingCaptureUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (captureEngine == null || shizukuMuteEngine == null) {
                return;
            }
            captureEngine.updateProcessing(
                    pendingCaptureMode,
                    pendingCapturePreset,
                    pendingCaptureConfig,
                    pendingCaptureVirtualBassModeIndex,
                    pendingCaptureDevice);
            if (pendingCaptureMode.requiresShizukuMute()) {
                shizukuMuteEngine.updateProcessing(
                        pendingCaptureMode,
                        pendingCapturePreset,
                        pendingCaptureConfig);
            } else {
                shizukuMuteEngine.stopAll();
            }
        }
    };
    private final Runnable wakeRecoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (captureEngine == null || shizukuMuteEngine == null) {
                return;
            }
            captureEngine.handleDeviceWake();
            shizukuMuteEngine.handleDeviceWake();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRunning = true;
        runningInstance = this;
        repository = new PresetRepository(this);
        repository.saveServiceActive(true);
        engine = GlobalEqRuntime.engine();
        dvcController = new GlobalDvcController(this, engine, repository);
        dvcController.start();
        currentProcessingMode = repository.loadProcessingMode();
        currentAdvancedModeConfig = repository.loadAdvancedModeConfig();
        captureEngine = new PlaybackCaptureEngine(this, repository, this::updateNotification);
        shizukuMuteEngine = new ShizukuSessionMuteEngine(
                this,
                repository,
                this::updateNotification,
                new ShizukuSessionMuteEngine.SessionIdProvider() {
                    @Override
                    public java.util.Set<Integer> getOwnedAudioSessionIds() {
                        return captureEngine == null
                                ? java.util.Collections.emptySet()
                                : captureEngine.getOwnedAudioSessionIds();
                    }

                    @Override
                    public boolean hasRecentCaptureActivity(long withinMs) {
                        return captureEngine != null
                                && captureEngine.hasRecentCaptureActivity(withinMs);
                    }
                });
        captureControlThread = new HandlerThread("global-peq-capture-control");
        captureControlThread.start();
        captureControlHandler = new Handler(captureControlThread.getLooper());
        deviceMonitor = new AudioOutputDeviceMonitor(this);
        registerScreenStateReceiver();
        createNotificationChannel();
        AudioOutputDevice selected = repository.loadSelectedDevice();
        if (selected != null) {
            currentDevice = selected;
        }
        awaitingInitialDeviceMonitorEvent = true;
        deviceMonitor.start(device -> {
            if (stopping) {
                return;
            }
            dvcController.prepareForRouteChange(device);
            repository.saveKnownDevice(device);
            repository.reconcileManualDeviceSelectionOverride(device);
            currentProcessingMode = repository.loadProcessingMode();
            currentAdvancedModeConfig = repository.loadAdvancedModeConfig();
            if (!repository.loadAutoSwitchOutput()) {
                syncDvcState(device);
                return;
            }
            if (repository.isManualDeviceSelectionOverrideActiveFor(device)) {
                syncDvcState(device);
                updateNotification();
                return;
            }
            boolean sameRoute = currentDevice != null && currentDevice.key.equals(device.key);
            if (awaitingInitialDeviceMonitorEvent) {
                awaitingInitialDeviceMonitorEvent = false;
                currentDevice = device;
                repository.saveSelectedDevice(currentDevice);
                currentPreset = repository.loadPreset(device, currentProcessingMode)
                        .withEnabled(repository.loadMasterEnabled());
                if (sameRoute) {
                    syncDvcState(device);
                    updateNotification();
                    return;
                }
            }
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            currentPreset = repository.loadPreset(device, currentProcessingMode)
                    .withEnabled(repository.loadMasterEnabled());
            long routeSuppressionRemainingMs = currentProcessingMode.usesNativeCapture()
                    ? remainingCaptureRouteSuppressionMs()
                    : 0L;
            if (routeSuppressionRemainingMs > 0L && sameRoute) {
                syncDvcState(device);
                updateNotification();
                return;
            }
            int virtualBassModeIndex = currentPreset.virtualBassModeIndex;
            Preset effectivePreset = AudioProcessingPolicy.effectiveSystemPreset(currentPreset, currentProcessingMode, virtualBassModeIndex);
            if (!currentProcessingMode.usesSystemEqBackend()) {
                resetSystemEqPlaybackState();
                engine.release();
            } else if (sameRoute) {
                engine.reapplyForRouteChange(effectivePreset, currentProcessingMode, currentAdvancedModeConfig);
            } else {
                engine.applyWithFullReset(effectivePreset, currentProcessingMode, currentAdvancedModeConfig);
            }
            syncDvcState(device);
            scheduleCaptureUpdate(
                    currentProcessingMode,
                    currentPreset,
                    currentAdvancedModeConfig,
                    virtualBassModeIndex,
                    currentDevice,
                    routeSuppressionRemainingMs > 0L
                            ? routeSuppressionRemainingMs + CAPTURE_UPDATE_DEBOUNCE_MS
                            : CAPTURE_UPDATE_DEBOUNCE_MS);
            updateNotification();
        }, this::handleSystemEqPlaybackActivityChanged);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopping) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
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
                && currentProcessingMode.requiresShizukuMute()
                && (captureEngine == null || !captureEngine.hasProjection())) {
            requestStopAllAndStopService();
            return START_NOT_STICKY;
        }
        // The master switch represents the user's explicit request to keep the equalizer active.
        // Ask Android to recreate the foreground service after reclaiming the process. State that
        // can be restored without an Activity (system EQ/global DSP) is loaded from the repository
        // when Android delivers the null restart intent.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Removing the UI task is not the same as switching the equalizer off. The service is a
        // foreground component and must keep owning the audio effects after the recent-apps card
        // is dismissed. Explicit shutdown still goes through requestStopAllAndStopService().
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        instanceRunning = false;
        if (runningInstance == this) {
            runningInstance = null;
        }
        mainHandler.removeCallbacks(notificationAppearanceRefreshRunnable);
        deviceMonitor.stop();
        unregisterReceiver(screenStateReceiver);
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
        if (!currentProcessingMode.usesSystemEqBackend()) {
            resetSystemEqPlaybackState();
            engine.release();
        } else {
            engine.apply(AudioProcessingPolicy.effectiveSystemPreset(
                    currentPreset,
                    currentProcessingMode,
                    virtualBassModeIndex), currentProcessingMode, currentAdvancedModeConfig);
            syncSystemEqPlaybackState();
        }
        syncDvcState(deviceMonitor.currentOutputDevice());
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
        if (stopping) {
            return;
        }
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
        String detail = null;
        if (mode.requiresShizukuMute()) {
            ShizukuStatusSummary summary = currentShizukuSummary();
            content = buildShizukuNotificationContent(summary);
            detail = buildShizukuNotificationDetail(summary);
        } else if (mode.usesNativeCapture()) {
            content = repository.loadMonitorCaptureStatus();
        } else {
            content = currentDevice.label;
        }
        int notificationIcon = UiTheme.isLiquidGlass(this)
                ? R.mipmap.ic_launcher_liquid
                : R.mipmap.ic_launcher;
        Bitmap notificationArtwork = BitmapFactory.decodeResource(
                getResources(), notificationIcon);
        Icon notificationSmallIcon = notificationArtwork == null
                ? Icon.createWithResource(this, notificationIcon)
                : Icon.createWithBitmap(notificationArtwork);
        builder
                .setSmallIcon(notificationSmallIcon)
                .setContentTitle(state)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(currentPreset.enabled);
        if (detail != null && !detail.trim().isEmpty()) {
            builder.setStyle(new Notification.BigTextStyle().bigText(detail));
        }
        return builder.build();
    }

    private ShizukuStatusSummary currentShizukuSummary() {
        return ShizukuStatusSummary.resolve(
                currentProcessingMode,
                currentPreset != null && currentPreset.enabled,
                currentAdvancedModeConfig,
                repository == null ? ShizukuRuntimeState.DEFAULT : repository.loadShizukuRuntimeState(),
                ShizukuCompat.hasPermission());
    }

    private boolean isChineseUi() {
        return repository != null && "zh".equalsIgnoreCase(repository.loadUiLanguage());
    }

    private String buildShizukuNotificationContent(ShizukuStatusSummary summary) {
        ShizukuStatusSummary safe = summary == null
                ? new ShizukuStatusSummary(ShizukuStatusSummary.Kind.STANDBY, "", "", "")
                : summary;
        String appLabel = firstNonEmpty(
                describeRuntimePackages(safe.replayPackage),
                describeRuntimePackages(safe.playbackPackage),
                describeRuntimePackages(safe.mutedPackage));
        String headline = safe.compactText(isChineseUi());
        if (appLabel.isEmpty()) {
            return headline;
        }
        return headline + " | " + firstPackage(appLabel);
    }

    private String buildShizukuNotificationDetail(ShizukuStatusSummary summary) {
        ShizukuStatusSummary safe = summary == null
                ? new ShizukuStatusSummary(ShizukuStatusSummary.Kind.STANDBY, "", "", "")
                : summary;
        String detail = safe.detailText(isChineseUi());
        String appLine = "";
        if (!safe.replayPackage.isEmpty()) {
            appLine = isChineseUi()
                    ? "当前回放应用: " + describeRuntimePackages(safe.replayPackage)
                    : "Replay app: " + describeRuntimePackages(safe.replayPackage);
        } else if (!safe.playbackPackage.isEmpty()) {
            appLine = isChineseUi()
                    ? "当前播放应用: " + describeRuntimePackages(safe.playbackPackage)
                    : "Playback app: " + describeRuntimePackages(safe.playbackPackage);
        } else if (!safe.mutedPackage.isEmpty()) {
            appLine = isChineseUi()
                    ? "当前静音应用: " + describeRuntimePackages(safe.mutedPackage)
                    : "Muted app: " + describeRuntimePackages(safe.mutedPackage);
        }
        if (appLine.isEmpty()) {
            return detail;
        }
        return detail + "\n" + appLine;
    }

    private String describeRuntimePackages(String packageNames) {
        if (packageNames == null || packageNames.trim().isEmpty()) {
            return "";
        }
        String normalized = packageNames.trim();
        String[] parts = normalized.split(",");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String appName = describeRuntimePackage(part == null ? "" : part.trim());
            if (appName.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(appName);
        }
        return builder.length() == 0 ? normalized : builder.toString();
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
            if (!safeLabel.isEmpty()) {
                return safeLabel;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return normalized;
    }

    private String firstPackage(String packageList) {
        if (packageList == null || packageList.trim().isEmpty()) {
            return "";
        }
        String[] parts = packageList.split(",");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
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

    private void registerScreenStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, filter);
        }
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
            if (mode.requiresShizukuMute()) {
                shizukuMuteEngine.updateProcessing(
                        mode,
                        currentPreset,
                        config);
            } else {
                shizukuMuteEngine.stopAll();
            }
        });
    }

    private void scheduleCaptureStopAll() {
        Handler handler = captureControlHandler;
        if (handler == null || captureEngine == null) {
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.removeCallbacks(wakeRecoveryRunnable);
        handler.post(() -> captureEngine.stopAll());
    }

    private void scheduleShizukuStopAll() {
        Handler handler = captureControlHandler;
        if (handler == null || shizukuMuteEngine == null) {
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.removeCallbacks(wakeRecoveryRunnable);
        handler.post(() -> shizukuMuteEngine.stopAll());
    }

    private void requestStopAllAndStopService() {
        if (stopping) {
            return;
        }
        stopping = true;
        instanceRunning = false;
        if (runningInstance == this) {
            runningInstance = null;
        }
        resetSystemEqPlaybackState();
        mainHandler.removeCallbacksAndMessages(null);
        if (deviceMonitor != null) {
            deviceMonitor.stop();
        }
        // Tear down AudioEffect handles synchronously on the service/main thread. Deferring this
        // to the capture worker allowed queued route/session callbacks to attach a replacement EQ
        // after shutdown had already begun.
        stopSystemEffectsNow();
        Handler handler = captureControlHandler;
        if (handler == null) {
            stopRemainingProcessingNow();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        handler.removeCallbacks(wakeRecoveryRunnable);
        handler.post(() -> {
            stopRemainingProcessingNow();
            mainHandler.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        });
    }

    private void stopSystemEffectsNow() {
        if (dvcController != null) {
            dvcController.stop();
        }
        if (engine != null) {
            engine.release();
        }
    }

    private void stopAllProcessingNow() {
        resetSystemEqPlaybackState();
        stopSystemEffectsNow();
        stopRemainingProcessingNow();
    }

    private void stopRemainingProcessingNow() {
        if (captureEngine != null) {
            captureEngine.stopAll();
        }
        if (shizukuMuteEngine != null) {
            shizukuMuteEngine.stopAll();
        }
        if (repository != null) {
            repository.clearRuntimeAudioState(currentProcessingMode.requiresShizukuMute()
                    ? ShizukuCompat.describeState(this)
                    : "Shizuku mute is idle.");
        }
    }

    private void scheduleCaptureUpdate(ProcessingMode processingMode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int virtualBassModeIndex,
                                       AudioOutputDevice outputDevice,
                                       long delayMs) {
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
        handler.removeCallbacks(applyPendingCaptureUpdateRunnable);
        if (delayMs <= 0L) {
            handler.post(applyPendingCaptureUpdateRunnable);
        } else {
            handler.postDelayed(applyPendingCaptureUpdateRunnable, delayMs);
        }
    }

    private void syncDvcState(AudioOutputDevice physicalRoute) {
        if (dvcController == null) {
            return;
        }
        boolean enabled = currentPreset != null && currentPreset.enabled;
        boolean userIntent = currentAdvancedModeConfig != null
                && currentAdvancedModeConfig.globalDvcEnabled;
        dvcController.update(
                currentProcessingMode,
                enabled,
                userIntent,
                physicalRoute);
    }

    private void scheduleWakeRecovery() {
        Handler handler = captureControlHandler;
        if (handler == null || !currentPreset.enabled) {
            return;
        }
        if (!currentProcessingMode.usesNativeCapture()) {
            return;
        }
        handler.removeCallbacks(wakeRecoveryRunnable);
        handler.postDelayed(wakeRecoveryRunnable, CAPTURE_WAKE_RECOVERY_DELAY_MS);
    }

    private long remainingCaptureRouteSuppressionMs() {
        long remaining = suppressCaptureRouteUpdatesUntilMs - SystemClock.elapsedRealtime();
        return Math.max(0L, remaining);
    }

    private void handleSystemEqPlaybackActivityChanged(boolean active) {
        if (currentProcessingMode != ProcessingMode.SYSTEM_EQ
                || currentPreset == null
                || !currentPreset.enabled) {
            resetSystemEqPlaybackState();
            return;
        }
        if (!systemEqPlaybackStateKnown) {
            systemEqPlaybackStateKnown = true;
            systemEqPlaybackActive = active;
            return;
        }
        boolean playbackJustStopped = systemEqPlaybackActive && !active;
        systemEqPlaybackActive = active;
        if (!playbackJustStopped) {
            return;
        }
        engine.reapplyStaged(AudioProcessingPolicy.effectiveSystemPreset(
                currentPreset,
                currentProcessingMode,
                currentPreset.virtualBassModeIndex), currentProcessingMode, currentAdvancedModeConfig);
    }

    private void resetSystemEqPlaybackState() {
        systemEqPlaybackStateKnown = false;
        systemEqPlaybackActive = false;
    }

    private void syncSystemEqPlaybackState() {
        if (currentProcessingMode != ProcessingMode.SYSTEM_EQ
                || currentPreset == null
                || !currentPreset.enabled
                || deviceMonitor == null) {
            resetSystemEqPlaybackState();
            return;
        }
        systemEqPlaybackStateKnown = true;
        systemEqPlaybackActive = deviceMonitor.hasRelevantActivePlayback();
    }

    static boolean isRunningInProcess() {
        return instanceRunning;
    }

    static boolean refreshNotificationAfterLauncherAliasChange() {
        GlobalEqForegroundService service = runningInstance;
        if (service == null || service.stopping) {
            return false;
        }
        service.mainHandler.removeCallbacks(service.notificationAppearanceRefreshRunnable);
        service.mainHandler.postDelayed(
                service.notificationAppearanceRefreshRunnable,
                LAUNCHER_ALIAS_NOTIFICATION_REFRESH_DELAY_MS);
        return true;
    }
}
