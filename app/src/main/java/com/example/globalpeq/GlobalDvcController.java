package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GlobalDvcController {
    private static final String TAG = "GlobalDvcController";
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final Pattern PLAYBACK_SESSION_REGEX = Pattern.compile(
            "\\bsession(?:Id)?\\b\\s*[:=]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final Context appContext;
    private final AudioManager audioManager;
    private final GlobalEqualizerEngine engine;
    private final PresetRepository repository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<Integer> announcedPlaybackSessions = new LinkedHashSet<>();
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
    private final DvcAudioSessionRegistry.Listener sessionRegistryListener;
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    handlePlaybackSessionsChanged(-1);
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
    private PowerampDvcVolumeChain volumeChain;
    private int activeAudioSessionId;
    private int preferredAudioSessionId;
    private int initialVolumeIndex;
    private boolean sessionZeroVolumeAttempted;

    GlobalDvcController(Context context,
                        GlobalEqualizerEngine engine,
                        PresetRepository repository) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.engine = engine;
        this.repository = repository;
        sessionRegistryListener =
                (preferredSessionId, closedSessionId) -> mainHandler.post(() -> {
                    announcedPlaybackSessions.clear();
                    announcedPlaybackSessions.addAll(
                            DvcAudioSessionRegistry.loadOpenSessionIds(appContext));
                    preferredAudioSessionId = preferredSessionId > 0
                            ? preferredSessionId
                            : DvcAudioSessionRegistry.loadPreferredSessionId(appContext);
                    handlePlaybackSessionsChanged(closedSessionId);
                });
    }

    void start() {
        if (started) {
            return;
        }
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        // Keep status/headroom readback synchronized with physical stream-volume changes.
        filter.setPriority(999);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(volumeReceiver, filter);
        }
        announcedPlaybackSessions.clear();
        announcedPlaybackSessions.addAll(DvcAudioSessionRegistry.loadOpenSessionIds(appContext));
        preferredAudioSessionId = DvcAudioSessionRegistry.loadPreferredSessionId(appContext);
        DvcAudioSessionRegistry.addListener(sessionRegistryListener);
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler);
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
        if (!engine.supportsDvcSessionPlacement()) {
            deactivate(DvcRuntimeState.Kind.PROBE_FAILED, false,
                    "Global DynamicsProcessing player-session path is unavailable");
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
            DvcAudioSessionRegistry.removeListener(sessionRegistryListener);
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback);
            } catch (RuntimeException ignored) {
            }
            started = false;
        }
        announcedPlaybackSessions.clear();
        preferredAudioSessionId = 0;
        mappingActive = false;
        activeAudioSessionId = 0;
        sessionZeroVolumeAttempted = false;
        // Service shutdown must not use the normal DVC-off path: that path deliberately rebuilds
        // a session-0 EQ. Release the player-session EQ directly so no replacement effect can be
        // created while the service is being destroyed.
        engine.release();
        releaseDvcVolumeChain();
        publish(DvcRuntimeState.Kind.OFF, false, true, "DVC is off");
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
        applyMappedCurve(kind, discoverPlaybackSessionIds(-1));
    }

    private void applyMappedCurve(DvcRuntimeState.Kind kind,
                                  Set<Integer> playbackSessions) {
        float downstreamHeadroomDb = curve == null ? 0f : curve.headroomDb();
        float displayedHeadroomDb = Math.round(downstreamHeadroomDb * 10f) / 10f;
        if (playbackSessions.isEmpty()) {
            failMappedCurve(
                    "未发现播放器的非零 audio session；manifest session registry 与 playback callback 均为空。请让播放器停止后重新开始播放，DVC 会自动重试");
            return;
        }
        int targetAudioSessionId = 0;
        for (Integer candidateSessionId : orderedPlaybackSessionIds(playbackSessions)) {
            if (candidateSessionId == null || candidateSessionId <= 0) {
                continue;
            }
            if (volumeChain != null
                    && volumeChain.getAudioSessionId() != candidateSessionId) {
                // Poweramp tears down player-session DP before VolumeFX. Reversing that order
                // briefly leaves boosted DP without its downstream attenuation and can jump loud.
                engine.setDvcModeEnabled(false, 0);
                releaseDvcVolumeChain();
            }
            if (volumeChain == null) {
                try {
                    // Poweramp initializes VolumeFX before it creates the real DP bank. This
                    // ordering moves the current stream attenuation behind the boosted EQ.
                    volumeChain = new PowerampDvcVolumeChain(audioManager, candidateSessionId);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not create the Poweramp DVC volume chain for session "
                            + candidateSessionId, error);
                    releaseDvcVolumeChain();
                    continue;
                }
            }
            // Some broken players omit CLOSE. Try all known sessions, newest first, so one stale
            // manifest entry cannot hide a valid current player session.
            if (engine.setDvcModeEnabled(true, candidateSessionId)
                    && engine.getActiveDynamicsAudioSessionId() == candidateSessionId) {
                targetAudioSessionId = candidateSessionId;
                break;
            }
            releaseDvcVolumeChain();
        }
        if (targetAudioSessionId <= 0) {
            failMappedCurve("播放器 audio session、VolumeFX 或 DP effect 挂载失败，DVC 未启用");
            return;
        }
        mappingActive = true;
        activeAudioSessionId = targetAudioSessionId;
        publish(kind, true, true,
                "DVC EQ audio session: " + targetAudioSessionId + "\n"
                        + "DVC discovered sessions: " + playbackSessions + "\n"
                        + "Player-session VolumeFX: "
                        + volumeChain.describeAttachment() + "\n"
                        + "DVC mapped headroom: " + displayedHeadroomDb + " dB\n"
                        + "DVC EQ bank: " + engine.describeActiveDynamicsBank() + "\n"
                        + "DVC limiter: enabled above +15 dB (50:1, 25 ms release)\n"
                        + "DVC session tracking: manifest receiver + playback lifecycle\n"
                        + "System media volume ownership: disabled (Poweramp one-shot initialization only)\n"
                        + engine.describeDvcReadback() + "\n"
                        + "DVC chain: player-session VolumeFX + player-session EQ");
    }

    private void failMappedCurve(String detail) {
        deactivateEngineMapping();
        publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                detail == null ? "DVC player-session pipeline failed" : detail);
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        deactivateEngineMapping();
        publish(kind, false, switchAvailable, detail);
    }

    private void deactivateEngineMapping() {
        mappingActive = false;
        activeAudioSessionId = 0;
        // Remove/re-home the boosted player-session DP before detaching VolumeFX. Neither step
        // writes a replacement media-volume index during normal DVC teardown.
        engine.setDvcModeEnabled(false, 0);
        releaseDvcVolumeChain();
    }

    private void releaseDvcVolumeChain() {
        if (volumeChain == null) {
            return;
        }
        try {
            volumeChain.release();
        } catch (RuntimeException ignored) {
        } finally {
            volumeChain = null;
        }
    }

    private void handlePlaybackSessionsChanged(int excludedSessionId) {
        if (!started || mode != ProcessingMode.GLOBAL_DSP || !presetEnabled
                || !userIntentEnabled || !routeDecision.allowsDvc) {
            return;
        }
        Set<Integer> sessions = discoverPlaybackSessionIds(excludedSessionId);
        if (sessions.isEmpty()) {
            if (mappingActive) {
                failMappedCurve(
                        "当前播放器 audio session 已关闭；DVC 已安全退出，检测到新播放 session 后会自动重试");
            }
            return;
        }
        DvcVolumeMapper.Curve nextCurve =
                DvcVolumeMapper.probe(audioManager, routeDecision.deviceType);
        if (!nextCurve.meaningful) {
            curve = nextCurve;
            failMappedCurve(nextCurve.failure);
            return;
        }
        if (!engine.supportsDvcSessionPlacement()) {
            failMappedCurve("Global DynamicsProcessing player-session path is unavailable");
            return;
        }
        curve = nextCurve;
        applyMappedCurve(
                routeDecision.isUsb()
                        ? DvcRuntimeState.Kind.USB_HARDWARE
                        : DvcRuntimeState.Kind.ACTIVE,
                sessions);
    }

    private Iterable<Integer> orderedPlaybackSessionIds(Set<Integer> playbackSessions) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        if (activeAudioSessionId > 0 && playbackSessions.contains(activeAudioSessionId)) {
            ordered.add(activeAudioSessionId);
        }
        if (preferredAudioSessionId > 0 && playbackSessions.contains(preferredAudioSessionId)) {
            ordered.add(preferredAudioSessionId);
        }
        ArrayList<Integer> newestFirst = new ArrayList<>(playbackSessions);
        Collections.reverse(newestFirst);
        ordered.addAll(newestFirst);
        return ordered;
    }

    private Set<Integer> discoverPlaybackSessionIds(int excludedSessionId) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Integer sessionId : announcedPlaybackSessions) {
            if (sessionId != null && sessionId > 0 && sessionId != excludedSessionId) {
                result.add(sessionId);
            }
        }
        try {
            List<AudioPlaybackConfiguration> configurations =
                    audioManager.getActivePlaybackConfigurations();
            int lastConfigurationSessionId = 0;
            if (configurations != null) {
                for (AudioPlaybackConfiguration configuration : configurations) {
                    int sessionId = readPlaybackSessionId(configuration);
                    if (sessionId > 0 && sessionId != excludedSessionId) {
                        result.add(sessionId);
                        lastConfigurationSessionId = sessionId;
                    }
                }
            }
            if ((preferredAudioSessionId <= 0 || !result.contains(preferredAudioSessionId))
                    && lastConfigurationSessionId > 0) {
                preferredAudioSessionId = lastConfigurationSessionId;
            }
        } catch (RuntimeException error) {
            Log.d(TAG, "Could not enumerate active player audio sessions", error);
        }
        return result;
    }

    private int readPlaybackSessionId(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return -1;
        }
        String[] methodNames = new String[]{
                "getSessionId",
                "getAudioSessionId",
                "getClientSessionId",
                "getClientAudioSessionId"
        };
        for (String methodName : methodNames) {
            try {
                Method method = AudioPlaybackConfiguration.class.getMethod(methodName);
                Object value = method.invoke(configuration);
                if (value instanceof Integer && (Integer) value > 0) {
                    return (Integer) value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        Matcher matcher = PLAYBACK_SESSION_REGEX.matcher(configuration.toString());
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
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
