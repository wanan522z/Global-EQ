package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.UUID;

final class GlobalDvcController {
    private static final String TAG = "GlobalDvcController";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final UUID VOLUME_EFFECT_TYPE =
            UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");
    private static final UUID VOLUME_EFFECT_IMPLEMENTATION =
            UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");
    private static final int EFFECT_PRIORITY = 1337;
    private static final int GLOBAL_AUDIO_SESSION = 0;

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
    private AudioEffect volumeEffect;
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
        applyHeadroomSafely(0f);
        releaseVolumeEffect();
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

        boolean startingNewDvcSession = volumeEffect == null;
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
        if (!engine.supportsDvcGainMapping()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true,
                    "DynamicsProcessing volume mapping is unavailable");
            return;
        }
        if (!ensureVolumeEffect()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true,
                    "Volume AudioEffect activation failed");
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
                || !userIntentEnabled || !routeDecision.allowsDvc || volumeEffect == null) {
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
        if (!applyHeadroomSafely(headroomDb)) {
            releaseVolumeEffect();
            publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                    "DynamicsProcessing volume mapping failed");
            return;
        }
        publish(kind, true, true,
                "Media volume provides " + headroomDb + " dB before PEQ and the limiter");
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        applyHeadroomSafely(0f);
        releaseVolumeEffect();
        publish(kind, false, switchAvailable, detail);
    }

    private boolean applyHeadroomSafely(float nextHeadroomDb) {
        float next = Float.isFinite(nextHeadroomDb)
                ? Math.max(0f, Math.min(96f, nextHeadroomDb))
                : 0f;
        boolean success = engine.setDvcHeadroomDb(next);
        if (success) {
            return true;
        }
        if (next > 0f) {
            engine.setDvcHeadroomDb(0f);
        }
        return false;
    }

    private boolean ensureVolumeEffect() {
        if (volumeEffect != null) {
            return true;
        }
        try {
            volumeEffect = createVolumeEffect(GLOBAL_AUDIO_SESSION);
            if (!verifyVolumeEffect(volumeEffect)
                    || volumeEffect.setEnabled(true) != AudioEffect.SUCCESS
                    || !volumeEffect.getEnabled()) {
                releaseVolumeEffect();
                return false;
            }
            // Re-submit the current stream volume without changing its index. This makes the
            // framework immediately propagate its existing volume to the newly enabled volume
            // controller and cannot create a volume jump in either direction.
            try {
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_SAME,
                        0);
            } catch (RuntimeException syncError) {
                // The effect is already active. Some OEMs reject ADJUST_SAME from third-party
                // apps, but that does not invalidate AudioFlinger's volume-controller state.
                Log.d(TAG, "Current media-volume sync was rejected", syncError);
            }
            if (!engine.recreateAfterDvcVolumeEffect()) {
                Log.w(TAG, "Could not place DynamicsProcessing after the Volume effect");
                releaseVolumeEffect();
                return false;
            }
            Log.d(TAG, "Enabled session-0 Volume AudioEffect for media-volume mapping");
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Volume AudioEffect probe failed", error);
            releaseVolumeEffect();
            return false;
        }
    }

    private boolean verifyVolumeEffect(AudioEffect effect) {
        try {
            AudioEffect.Descriptor descriptor = effect.getDescriptor();
            boolean matchingType = descriptor != null
                    && (VOLUME_EFFECT_TYPE.equals(descriptor.type)
                    || VOLUME_EFFECT_IMPLEMENTATION.equals(descriptor.uuid));
            return matchingType && effect.hasControl();
        } catch (RuntimeException error) {
            return false;
        }
    }

    private AudioEffect createVolumeEffect(int sessionId) throws ReflectiveOperationException {
        return createAudioEffect(
                VOLUME_EFFECT_TYPE,
                VOLUME_EFFECT_IMPLEMENTATION,
                sessionId);
    }

    private AudioEffect createAudioEffect(UUID type,
                                          UUID implementation,
                                          int sessionId) throws ReflectiveOperationException {
        Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                UUID.class, UUID.class, int.class, int.class);
        return constructor.newInstance(
                type,
                implementation,
                EFFECT_PRIORITY,
                sessionId);
    }

    private void releaseVolumeEffect() {
        if (volumeEffect == null) {
            return;
        }
        try {
            if (volumeEffect.getEnabled()) {
                volumeEffect.setEnabled(false);
            }
            volumeEffect.release();
        } catch (RuntimeException ignored) {
        } finally {
            volumeEffect = null;
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
                currentCurve == null ? 0 : currentCurve.currentIndex,
                currentCurve == null ? 0 : currentCurve.minIndex,
                currentCurve == null ? 0 : currentCurve.maxIndex,
                detail));
    }

    private static AudioOutputDevice safeRoute(AudioOutputDevice route) {
        return route == null ? new AudioOutputDevice("none", "Output device") : route;
    }
}
