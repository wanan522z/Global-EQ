package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
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
                refreshVolumeMapping();
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
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(volumeReceiver, filter);
        }
        started = true;
        publish(DvcRuntimeState.Kind.OFF, false, true, "DVC is off");
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

        boolean startingNewDvcSession = !mappingActive;
        curve = DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
        if (startingNewDvcSession) {
            initialVolumeIndex = curve.currentIndex;
        }
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
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true,
                    "DynamicsProcessing volume mapping is unavailable");
            return;
        }
        applyMappedCurve(routeDecision.isUsb()
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

    private void refreshVolumeMapping() {
        if (!started || mode != ProcessingMode.GLOBAL_DSP || !presetEnabled
                || !userIntentEnabled || !routeDecision.allowsDvc || !mappingActive) {
            return;
        }
        DvcVolumeMapper.Curve nextCurve = DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
        if (!nextCurve.meaningful) {
            curve = nextCurve;
            deactivate(routeDecision.isUsb()
                            ? DvcRuntimeState.Kind.USB_DIGITAL_ONLY
                            : DvcRuntimeState.Kind.PROBE_FAILED,
                    true,
                    nextCurve.failure);
            return;
        }
        curve = nextCurve;
        applyMappedCurve(routeDecision.isUsb()
                ? DvcRuntimeState.Kind.USB_HARDWARE
                : DvcRuntimeState.Kind.ACTIVE);
    }

    private void applyMappedCurve(DvcRuntimeState.Kind kind) {
        float headroomDb = curve == null ? 0f : curve.headroomDb();
        float displayedHeadroomDb = Math.round(headroomDb * 10f) / 10f;
        float volumeDb = curve == null ? 0f : curve.currentDb;
        if (!engine.setDvcVolumeMapping(true, volumeDb)) {
            mappingActive = false;
            publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                    "DynamicsProcessing volume mapping failed");
            return;
        }
        mappingActive = true;
        publish(kind, true, true,
                "Downstream media-volume headroom: " + displayedHeadroomDb + " dB\n"
                        + "Mapped DP input compensation: +" + displayedHeadroomDb + " dB\n"
                        + "Control path: " + (engine.usesPowerampRawDvcPath()
                        ? "Poweramp-compatible raw command"
                        : "Android public API fallback"));
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        deactivateEngineMapping();
        publish(kind, false, switchAvailable, detail);
    }

    private void deactivateEngineMapping() {
        engine.setDvcVolumeMapping(false, 0f);
        mappingActive = false;
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
                currentCurve == null ? 0 : currentCurve.currentIndex,
                currentCurve == null ? 0 : currentCurve.minIndex,
                currentCurve == null ? 0 : currentCurve.maxIndex,
                detail));
    }

    private static AudioOutputDevice safeRoute(AudioOutputDevice route) {
        return route == null ? new AudioOutputDevice("none", "Output device") : route;
    }
}
