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

public final class GlobalEqForegroundService extends Service {
    static final String ACTION_APPLY = "com.example.globalpeq.APPLY";
    static final String ACTION_BOOTSTRAP_CAPTURE = "com.example.globalpeq.BOOTSTRAP_CAPTURE";
    static final String EXTRA_CAPTURE_RESULT_CODE = "capture_result_code";
    static final String EXTRA_CAPTURE_DATA = "capture_result_data";
    private static final String CHANNEL_ID = "global_eq";
    private static final int NOTIFICATION_ID = 10;
    private static final long CAPTURE_UPDATE_DEBOUNCE_MS = 350L;

    private GlobalEqualizerEngine engine;
    private PlaybackCaptureEngine captureEngine;
    private ShizukuSessionMuteEngine shizukuMuteEngine;
    private PresetRepository repository;
    private AudioOutputDeviceMonitor deviceMonitor;
    private HandlerThread captureControlThread;
    private Handler captureControlHandler;
    private AudioOutputDevice currentDevice = new AudioOutputDevice("none", "Output device");
    private Preset currentPreset = Preset.flat(false);
    private boolean awaitingInitialDeviceMonitorEvent;
    private ProcessingMode pendingCaptureMode = ProcessingMode.SYSTEM_EQ;
    private Preset pendingCapturePreset = Preset.flat(false);
    private AdvancedModeConfig pendingCaptureConfig = AdvancedModeConfig.DEFAULT;
    private int pendingCaptureBassModeIndex;
    private AudioOutputDevice pendingCaptureDevice = new AudioOutputDevice("none", "Output device");
    private final Runnable applyPendingCaptureUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (captureEngine == null) {
                return;
            }
            captureEngine.updateProcessing(
                    pendingCaptureMode,
                    pendingCapturePreset,
                    pendingCaptureConfig,
                    pendingCaptureBassModeIndex,
                    pendingCaptureDevice);
            shizukuMuteEngine.updateProcessing(
                    pendingCaptureMode,
                    pendingCapturePreset,
                    pendingCaptureConfig);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new PresetRepository(this);
        engine = GlobalEqRuntime.engine();
        captureEngine = new PlaybackCaptureEngine(this, repository, this::updateNotification);
        shizukuMuteEngine = new ShizukuSessionMuteEngine(this, repository, this::updateNotification);
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
            if (awaitingInitialDeviceMonitorEvent) {
                awaitingInitialDeviceMonitorEvent = false;
                currentDevice = device;
                repository.saveSelectedDevice(currentDevice);
                currentPreset = repository.loadPreset(device);
                if (sameRoute) {
                    updateNotification();
                    return;
                }
            }
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            currentPreset = repository.loadPreset(device);
            ProcessingMode processingMode = repository.loadProcessingMode();
            int bassModeIndex = repository.loadBassBoostModeIndex();
            Preset effectivePreset = AudioProcessingPolicy.effectiveSystemPreset(currentPreset, processingMode, bassModeIndex);
            if (sameRoute) {
                engine.reapplyForRouteChange(effectivePreset);
            } else {
                engine.applyWithFullReset(effectivePreset);
            }
            scheduleCaptureUpdate(
                    processingMode,
                    currentPreset,
                    repository.loadAdvancedModeConfig(),
                    bassModeIndex,
                    currentDevice,
                    CAPTURE_UPDATE_DEBOUNCE_MS);
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
        } else {
            startForegroundInternal(captureEngine.hasProjection());
        }
        Preset preset = applySavedPreset();
        if (!preset.enabled) {
            scheduleCaptureStopAll();
            scheduleShizukuStopAll();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        deviceMonitor.stop();
        scheduleCaptureStopAll();
        scheduleShizukuStopAll();
        if (captureControlHandler != null) {
            captureControlHandler.removeCallbacksAndMessages(null);
        }
        if (captureControlThread != null) {
            captureControlThread.quitSafely();
            captureControlThread = null;
        }
        captureControlHandler = null;
        super.onDestroy();
    }

    private Preset applySavedPreset() {
        Preset preset = refreshSavedPresetState();
        ProcessingMode processingMode = repository.loadProcessingMode();
        int bassModeIndex = repository.loadBassBoostModeIndex();
        engine.apply(AudioProcessingPolicy.effectiveSystemPreset(
                currentPreset,
                processingMode,
                bassModeIndex));
        scheduleCaptureUpdate(
                processingMode,
                currentPreset,
                repository.loadAdvancedModeConfig(),
                bassModeIndex,
                currentDevice,
                0L);
        return preset;
    }

    private Preset refreshSavedPresetState() {
        AudioOutputDevice selected = repository.loadSelectedDevice();
        currentDevice = selected == null ? deviceMonitor.currentOutputDevice() : selected;
        repository.saveSelectedDevice(currentDevice);
        Preset preset = repository.loadPreset(currentDevice);
        currentPreset = preset;
        updateNotification();
        return preset;
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
        ProcessingMode mode = repository.loadProcessingMode();
        String content;
        if (mode == ProcessingMode.SHIZUKU_MUTE) {
            content = repository.loadMonitorCaptureActive()
                    ? repository.loadShizukuMuteStatus()
                    : repository.loadMonitorCaptureStatus();
        } else {
            content = currentDevice.label;
        }
        return builder
                .setSmallIcon(R.drawable.ic_eq)
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
            captureEngine.updateProcessing(
                    repository.loadProcessingMode(),
                    currentPreset,
                    repository.loadAdvancedModeConfig(),
                    repository.loadBassBoostModeIndex(),
                    currentDevice);
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

    private void scheduleCaptureUpdate(ProcessingMode processingMode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int bassModeIndex,
                                       AudioOutputDevice outputDevice,
                                       long delayMs) {
        Handler handler = captureControlHandler;
        if (handler == null || captureEngine == null || shizukuMuteEngine == null) {
            return;
        }
        pendingCaptureMode = processingMode == null ? ProcessingMode.SYSTEM_EQ : processingMode;
        pendingCapturePreset = preset == null ? Preset.flat(false) : preset;
        pendingCaptureConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        pendingCaptureBassModeIndex = bassModeIndex;
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
}
