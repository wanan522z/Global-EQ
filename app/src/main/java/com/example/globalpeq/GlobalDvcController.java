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
import java.lang.reflect.Method;
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
    private float appliedCompensationDb;
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
        publish(DvcRuntimeState.Kind.OFF, false, true, 0f, "DVC is off");
    }

    void prepareForRouteChange(AudioOutputDevice nextRoute) {
        String currentKey = route == null || route.key == null ? "" : route.key;
        String nextKey = nextRoute == null || nextRoute.key == null ? "" : nextRoute.key;
        if (currentKey.equals(nextKey)) {
            return;
        }
        // Withdraw the positive stage first. The temporary state can only be quieter.
        applyCompensationSafely(0f);
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
        if (!ensureVolumeEffect()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, true,
                    "Volume AudioEffect probe failed");
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
        float compensationDb = curve == null ? 0f : curve.compensationDb();
        applyCompensationSafely(compensationDb);
        publish(kind, true, true, compensationDb,
                "Volume curve mapped through DynamicsProcessing");
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        applyCompensationSafely(0f);
        releaseVolumeEffect();
        publish(kind, false, switchAvailable, 0f, detail);
    }

    private void applyCompensationSafely(float nextCompensationDb) {
        float next = Float.isFinite(nextCompensationDb)
                ? Math.max(0f, Math.min(96f, nextCompensationDb))
                : 0f;
        if (next > appliedCompensationDb) {
            // Add the negative digital-volume stage before raising the DP input stage.
            engine.setDvcPostGainDb(-next);
            engine.setDvcPreCompensationDb(next);
        } else {
            // Remove/reduce the positive stage before relaxing the negative stage.
            engine.setDvcPreCompensationDb(next);
            engine.setDvcPostGainDb(-next);
        }
        appliedCompensationDb = next;
    }

    private boolean ensureVolumeEffect() {
        if (volumeEffect != null) {
            return true;
        }
        AudioEffect probe = null;
        try {
            int probeSession = audioManager.generateAudioSessionId();
            probe = createVolumeEffect(probeSession);
            if (!verifyVolumeEffect(probe)) {
                return false;
            }
            probe.release();
            probe = null;
            volumeEffect = createVolumeEffect(GLOBAL_AUDIO_SESSION);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Volume AudioEffect probe failed", error);
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private boolean verifyVolumeEffect(AudioEffect effect) {
        try {
            Method getParameter = AudioEffect.class.getDeclaredMethod(
                    "getParameter", int.class, short[].class);
            getParameter.setAccessible(true);
            Object result = getParameter.invoke(effect, 0, new short[1]);
            if (result instanceof Integer && (Integer) result >= 0) {
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException hiddenApiError) {
            // Poweramp calls the same parameter getter through JNI. When Android blocks hidden
            // Java reflection, descriptor and control ownership still give us a safe probe.
            Log.d(TAG, "Hidden Volume AudioEffect parameter probe unavailable", hiddenApiError);
        }
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
        Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                UUID.class, UUID.class, int.class, int.class);
        return constructor.newInstance(
                VOLUME_EFFECT_TYPE,
                VOLUME_EFFECT_IMPLEMENTATION,
                EFFECT_PRIORITY,
                sessionId);
    }

    private void releaseVolumeEffect() {
        if (volumeEffect == null) {
            return;
        }
        try {
            volumeEffect.release();
        } catch (RuntimeException ignored) {
        } finally {
            volumeEffect = null;
        }
    }

    private void publish(DvcRuntimeState.Kind kind,
                         boolean active,
                         boolean switchAvailable,
                         float compensationDb,
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
                compensationDb,
                detail));
    }

    private static AudioOutputDevice safeRoute(AudioOutputDevice route) {
        return route == null ? new AudioOutputDevice("none", "Output device") : route;
    }
}
