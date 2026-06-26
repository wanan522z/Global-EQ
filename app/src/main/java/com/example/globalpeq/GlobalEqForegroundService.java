package com.example.globalpeq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class GlobalEqForegroundService extends Service {
    static final String ACTION_APPLY = "com.example.globalpeq.APPLY";
    private static final String CHANNEL_ID = "global_eq";
    private static final int NOTIFICATION_ID = 10;

    private GlobalEqualizerEngine engine;
    private PresetRepository repository;
    private AudioOutputDeviceMonitor deviceMonitor;
    private AudioOutputDevice currentDevice = new AudioOutputDevice("none", "Output device");
    private Preset currentPreset = Preset.flat(false);

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new PresetRepository(this);
        engine = GlobalEqRuntime.engine();
        deviceMonitor = new AudioOutputDeviceMonitor(this);
        createNotificationChannel();
        AudioOutputDevice selected = repository.loadSelectedDevice();
        if (selected != null) {
            currentDevice = selected;
        }
        deviceMonitor.start(device -> {
            repository.saveKnownDevice(device);
            if (!repository.loadAutoSwitchOutput()) {
                return;
            }
            boolean sameRoute = currentDevice != null && currentDevice.key.equals(device.key);
            currentDevice = device;
            repository.saveSelectedDevice(currentDevice);
            currentPreset = repository.loadPreset(device);
            ProcessingMode processingMode = repository.loadProcessingMode();
            int bassModeIndex = repository.loadBassBoostModeIndex();
            Preset effectivePreset = AudioProcessingPolicy.effectiveSystemPreset(currentPreset, processingMode, bassModeIndex);
            if (sameRoute) {
                engine.reapplyForRouteChange(effectivePreset);
            } else {
                engine.apply(effectivePreset);
            }
            updateNotification();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        Preset preset = applySavedPreset();
        if (!preset.enabled) {
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
        super.onDestroy();
    }

    private Preset applySavedPreset() {
        AudioOutputDevice selected = repository.loadSelectedDevice();
        currentDevice = selected == null ? deviceMonitor.currentOutputDevice() : selected;
        repository.saveSelectedDevice(currentDevice);
        Preset preset = repository.loadPreset(currentDevice);
        currentPreset = preset;
        engine.apply(AudioProcessingPolicy.effectiveSystemPreset(
                currentPreset,
                repository.loadProcessingMode(),
                repository.loadBassBoostModeIndex()));
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
        return builder
                .setSmallIcon(R.drawable.ic_eq)
                .setContentTitle(state)
                .setContentText(currentDevice.label)
                .setContentIntent(pendingIntent)
                .setOngoing(currentPreset.enabled)
                .build();
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
}
