package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

/**
 * Keeps DVC on the output-mix session so it remains independent of individual player sessions.
 *
 * <p>Android anonymizes other apps' audio-session IDs unless the caller has a privileged
 * permission. Depending on those IDs made DVC detach whenever video or messaging apps replaced
 * their AudioTrack. Session 0 is the only public, first-install-safe attachment point. The media
 * stream's existing attenuation is measured and used as positive-EQ headroom; this controller
 * never changes the user's volume index.</p>
 */
final class GlobalDvcController {
    private static final String TAG = "GlobalDvcController";
    private static final int GLOBAL_AUDIO_SESSION = 0;
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
        // Keep status and the EQ gain budget synchronized with physical volume changes.
        filter.setPriority(999);
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
        routeDecision = DvcRoutePolicy.evaluate(nextRoute);
        this.mode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        this.presetEnabled = presetEnabled;
        this.userIntentEnabled = userIntentEnabled;

        if (this.mode != ProcessingMode.GLOBAL_DSP || !presetEnabled) {
            deactivate(DvcRuntimeState.Kind.OFF, true, "DVC is off");
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

        boolean starting = !mappingActive;
        curve = DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
        if (starting) {
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
        if (!engine.supportsDvcProcessing()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, false,
                    "Global DynamicsProcessing is unavailable");
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
        mappingActive = false;
        // Service shutdown releases the effect directly. The normal DVC-off path deliberately
        // keeps the session-0 EQ alive, which would leak an effect after the service is gone.
        engine.release();
        publish(DvcRuntimeState.Kind.OFF, false, true, "DVC is off");
    }

    private void refreshVolumeMapping() {
        if (!started || mode != ProcessingMode.GLOBAL_DSP || !presetEnabled
                || !userIntentEnabled || !routeDecision.allowsDvc || !mappingActive) {
            return;
        }
        DvcVolumeMapper.Curve nextCurve =
                DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
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
        float volumeHeadroomDb = curve == null ? 0f : curve.headroomDb();
        float displayedHeadroomDb = Math.round(volumeHeadroomDb * 10f) / 10f;
        engine.setDvcVolumeHeadroomDb(volumeHeadroomDb);

        if (!engine.setDvcModeEnabled(true, GLOBAL_AUDIO_SESSION)
                || engine.getActiveDynamicsAudioSessionId() != GLOBAL_AUDIO_SESSION) {
            failMappedCurve("全局 DynamicsProcessing 挂载失败，DVC 未启用");
            return;
        }

        mappingActive = true;
        boolean chineseUi = "zh".equalsIgnoreCase(repository.loadUiLanguage());
        String compactStatus = String.format(
                Locale.US,
                chineseUi
                        ? "全局音量余量 · %.1f dB"
                        : "Global volume headroom · %.1f dB",
                displayedHeadroomDb);
        publish(kind, true, true, compactStatus);
        Log.i(TAG, "DVC active on output-mix session 0; media-volume headroom="
                + displayedHeadroomDb + " dB, bank=" + engine.describeActiveDynamicsBank()
                + "\n" + engine.describeDvcReadback());
    }

    private void failMappedCurve(String detail) {
        deactivateEngineMapping();
        publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                detail == null ? "DVC output-mix pipeline failed" : detail);
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        deactivateEngineMapping();
        publish(kind, false, switchAvailable, detail);
    }

    private void deactivateEngineMapping() {
        mappingActive = false;
        boolean switchedOff = engine.setDvcModeEnabled(false, GLOBAL_AUDIO_SESSION);
        if (!switchedOff && engine.isDvcModeActive()) {
            mappingActive = true;
            Log.w(TAG, "DVC teardown aborted because the normal session-0 bank was unavailable");
            return;
        }
        engine.completeDvcOffHandoff(() -> { }, () -> { });
        Log.i(TAG, "DVC teardown: switchedOff=" + switchedOff
                + ", engineDvcActive=" + engine.isDvcModeActive());
        engine.setDvcVolumeHeadroomDb(0f);
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
