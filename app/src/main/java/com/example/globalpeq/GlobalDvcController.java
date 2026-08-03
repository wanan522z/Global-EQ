package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

final class GlobalDvcController {
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Context appContext;
    private final AudioManager audioManager;
    private final GlobalEqualizerEngine engine;
    private final PresetRepository repository;
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !VOLUME_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
            if (stream == AudioManager.STREAM_MUSIC) {
                handlePhysicalVolumeChanged();
            }
        }
    };

    private boolean started;
    private ProcessingMode mode = ProcessingMode.SYSTEM_EQ;
    private boolean presetEnabled;
    private boolean userIntentEnabled;
    private AudioOutputDevice route = new AudioOutputDevice("none", "Output device");
    private DvcRoutePolicy.Decision routeDecision = DvcRoutePolicy.evaluate(route);
    private DvcVolumeMapper.Curve curve;
    private boolean mappingActive;
    private int initialVolumeIndex;
    private int virtualVolumeIndex;
    private int lastAudibleVolumeIndex;
    private MediaSession volumeSession;
    private VolumeProvider volumeProvider;

    GlobalDvcController(Context context,
                        GlobalEqualizerEngine engine,
                        PresetRepository repository) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.engine = engine;
        this.repository = repository;
    }

    void start() {
        if (started) {
            return;
        }
        restoreStaleDvcVolume();
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(volumeReceiver, filter);
        }
        started = true;
        publish(DvcRuntimeState.Kind.OFF, false, true, "DVC is off");
    }

    private void restoreStaleDvcVolume() {
        DvcRuntimeState staleState = repository.loadDvcRuntimeState();
        if (staleState == null || !staleState.active) {
            return;
        }
        try {
            int min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int restoreIndex = Math.max(min, Math.min(max, staleState.currentVolumeIndex));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreIndex, 0);
        } catch (RuntimeException ignored) {
        }
    }

    void prepareForRouteChange(AudioOutputDevice nextRoute) {
        String currentKey = route == null || route.key == null ? "" : route.key;
        String nextKey = nextRoute == null || nextRoute.key == null ? "" : nextRoute.key;
        if (currentKey.equals(nextKey)) {
            return;
        }
        deactivateEngineMapping();
        curve = null;
        route = safeRoute(nextRoute);
        routeDecision = DvcRoutePolicy.evaluate(route);
    }

    void update(ProcessingMode mode,
                boolean presetEnabled,
                boolean userIntentEnabled,
                AudioOutputDevice route) {
        AudioOutputDevice nextRoute = safeRoute(route);
        prepareForRouteChange(nextRoute);
        this.route = nextRoute;
        this.routeDecision = DvcRoutePolicy.evaluate(nextRoute);
        this.mode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        this.presetEnabled = presetEnabled;
        this.userIntentEnabled = userIntentEnabled;

        if (this.mode != ProcessingMode.GLOBAL_DSP || !presetEnabled) {
            deactivate(DvcRuntimeState.Kind.OFF, true, "DVC is off");
            return;
        }
        if (routeDecision.isBluetooth()) {
            deactivate(DvcRuntimeState.Kind.BLUETOOTH_UNAVAILABLE, false,
                    "Bluetooth devices do not allow DVC");
            return;
        }
        if (!routeDecision.allowsDvc) {
            deactivate(DvcRuntimeState.Kind.ROUTE_UNAVAILABLE, false,
                    "This output route does not support DVC");
            return;
        }
        if (!userIntentEnabled) {
            deactivate(DvcRuntimeState.Kind.OFF, true, "DVC is off");
            return;
        }

        if (mappingActive) {
            publishActiveState(routeDecision.isUsb()
                    ? DvcRuntimeState.Kind.USB_HARDWARE
                    : DvcRuntimeState.Kind.ACTIVE);
            return;
        }

        curve = DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
        initialVolumeIndex = curve.currentIndex;
        virtualVolumeIndex = curve.currentIndex;
        lastAudibleVolumeIndex = curve.currentIndex;
        if (routeDecision.isUsb() && (curve.fixedVolume || !curve.meaningful)) {
            deactivate(DvcRuntimeState.Kind.USB_DIGITAL_ONLY, true,
                    curve.failure.isEmpty() ? "USB fixed hardware volume" : curve.failure);
            return;
        }
        if (!curve.meaningful) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true, curve.failure);
            return;
        }
        if (!engine.supportsDvcVolumeMapping()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, false,
                    "Global DynamicsProcessing volume-control path is unavailable");
            return;
        }
        activateMappedVolume(routeDecision.isUsb()
                ? DvcRuntimeState.Kind.USB_HARDWARE
                : DvcRuntimeState.Kind.ACTIVE);
    }

    void stop() {
        if (started) {
            try {
                appContext.unregisterReceiver(volumeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            started = false;
        }
        deactivate(DvcRuntimeState.Kind.OFF, true, "DVC is off");
    }

    private void handlePhysicalVolumeChanged() {
        if (!started || mode != ProcessingMode.GLOBAL_DSP || !presetEnabled
                || !userIntentEnabled || !routeDecision.allowsDvc || !mappingActive) {
            return;
        }
        int physicalIndex;
        try {
            physicalIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException error) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true,
                    "Could not read system media volume");
            return;
        }
        if (curve == null || physicalIndex == curve.maxIndex) {
            return;
        }

        // Fallback for ROMs that deliver hardware volume keys to STREAM_MUSIC instead of the
        // active VolumeProvider. A down key moves max to max-1; apply one virtual step and put the
        // physical stream back at max only after the new pre-EQ attenuation is installed.
        int targetIndex = physicalIndex == curve.maxIndex - 1
                ? virtualVolumeIndex - 1
                : physicalIndex;
        if (setVirtualVolumeIndex(targetIndex)) {
            forceSystemVolumeMax();
        }
    }

    private void activateMappedVolume(DvcRuntimeState.Kind kind) {
        float volumeDb = virtualVolumeDb();
        if (!engine.setDvcVolumeMapping(true, volumeDb)) {
            deactivateEngineMapping();
            publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                    "DVC pre-EQ volume mapping was rejected");
            return;
        }

        if (!startVolumeSession()) {
            deactivateEngineMapping();
            publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                    "DVC could not take ownership of media volume keys");
            return;
        }

        // From this point the negative pre-EQ gain is verified. Raising the physical stream can
        // therefore preserve loudness but cannot produce the former unattenuated burst.
        mappingActive = true;
        if (!forceSystemVolumeMax()) {
            deactivateEngineMapping();
            publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                    "DVC could not lock system media volume at maximum");
            return;
        }
        publishActiveState(kind);
    }

    private boolean setVirtualVolumeIndex(int requestedIndex) {
        if (!mappingActive || curve == null) {
            return false;
        }
        int targetIndex = Math.max(curve.minIndex, Math.min(curve.maxIndex, requestedIndex));
        float targetDb = DvcVolumeMapper.volumeDbForIndex(
                audioManager,
                targetIndex,
                routeDecision.deviceType);
        if (!engine.setDvcVolumeMapping(true, targetDb)) {
            return false;
        }
        virtualVolumeIndex = targetIndex;
        if (targetIndex > curve.minIndex) {
            lastAudibleVolumeIndex = targetIndex;
        }
        if (volumeProvider != null) {
            volumeProvider.setCurrentVolume(targetIndex - curve.minIndex);
        }
        publishActiveState(routeDecision.isUsb()
                ? DvcRuntimeState.Kind.USB_HARDWARE
                : DvcRuntimeState.Kind.ACTIVE);
        return true;
    }

    private float virtualVolumeDb() {
        return curve == null
                ? 0f
                : DvcVolumeMapper.volumeDbForIndex(
                        audioManager,
                        virtualVolumeIndex,
                        routeDecision.deviceType);
    }

    private void publishActiveState(DvcRuntimeState.Kind kind) {
        float volumeDb = virtualVolumeDb();
        float displayedAttenuationDb = Math.round(Math.max(0f, -volumeDb) * 10f) / 10f;
        int activeBandCount = engine.getActiveDynamicsBandCount();
        publish(kind, true, true,
                "DVC virtual media attenuation: " + displayedAttenuationDb + " dB\n"
                        + "System media volume: locked at maximum\n"
                        + "DVC post-EQ bank: " + activeBandCount + " full-range bands\n"
                        + "DVC limiter: bypassed\n"
                        + engine.describeDvcReadback() + "\n"
                        + "Volume keys control pre-EQ virtual volume");
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        deactivateEngineMapping();
        publish(kind, false, switchAvailable, detail);
    }

    private void deactivateEngineMapping() {
        boolean wasActive = mappingActive;
        mappingActive = false;
        if (wasActive && curve != null) {
            setPhysicalVolume(virtualVolumeIndex);
        }
        // Restore downstream attenuation before removing the matching pre-EQ attenuation.
        engine.setDvcVolumeMapping(false, 0f);
        releaseVolumeSession();
    }

    private boolean forceSystemVolumeMax() {
        if (curve == null || !setPhysicalVolume(curve.maxIndex)) {
            return false;
        }
        try {
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == curve.maxIndex;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean setPhysicalVolume(int index) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean startVolumeSession() {
        if (curve == null) {
            return false;
        }
        releaseVolumeSession();
        try {
            int range = Math.max(1, curve.maxIndex - curve.minIndex);
            volumeProvider = new VolumeProvider(
                    VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                    range,
                    virtualVolumeIndex - curve.minIndex) {
                @Override
                public void onAdjustVolume(int direction) {
                    if (!mappingActive || curve == null) {
                        return;
                    }
                    if (direction == AudioManager.ADJUST_RAISE) {
                        setVirtualVolumeIndex(virtualVolumeIndex + 1);
                    } else if (direction == AudioManager.ADJUST_LOWER) {
                        setVirtualVolumeIndex(virtualVolumeIndex - 1);
                    } else if (direction == AudioManager.ADJUST_MUTE) {
                        setVirtualVolumeIndex(curve.minIndex);
                    } else if (direction == AudioManager.ADJUST_UNMUTE) {
                        setVirtualVolumeIndex(Math.max(curve.minIndex + 1,
                                lastAudibleVolumeIndex));
                    } else if (direction == AudioManager.ADJUST_TOGGLE_MUTE) {
                        setVirtualVolumeIndex(virtualVolumeIndex <= curve.minIndex
                                ? Math.max(curve.minIndex + 1, lastAudibleVolumeIndex)
                                : curve.minIndex);
                    }
                }

                @Override
                public void onSetVolumeTo(int volume) {
                    if (mappingActive && curve != null) {
                        setVirtualVolumeIndex(curve.minIndex + volume);
                    }
                }
            };
            volumeSession = new MediaSession(appContext, "GlobalPEQ-DVC");
            volumeSession.setPlaybackToRemote(volumeProvider);
            volumeSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build());
            volumeSession.setActive(true);
            return true;
        } catch (RuntimeException error) {
            releaseVolumeSession();
            return false;
        }
    }

    private void releaseVolumeSession() {
        MediaSession currentSession = volumeSession;
        volumeSession = null;
        volumeProvider = null;
        if (currentSession == null) {
            return;
        }
        try {
            currentSession.setActive(false);
            currentSession.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void publish(DvcRuntimeState.Kind kind,
                         boolean active,
                         boolean switchAvailable,
                         String detail) {
        DvcVolumeMapper.Curve currentCurve = curve;
        repository.saveDvcRuntimeState(new DvcRuntimeState(
                kind,
                active,
                switchAvailable,
                route == null ? "" : route.key,
                route == null ? "" : route.label,
                initialVolumeIndex,
                currentCurve == null ? 0 : virtualVolumeIndex,
                currentCurve == null ? 0 : currentCurve.minIndex,
                currentCurve == null ? 0 : currentCurve.maxIndex,
                detail));
    }

    private static AudioOutputDevice safeRoute(AudioOutputDevice route) {
        return route == null ? new AudioOutputDevice("none", "Output device") : route;
    }
}
