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
import java.util.Locale;
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
    private static final long[] SESSION_ATTACH_RETRY_DELAYS_MS = {300L, 700L, 1500L, 3000L};
    private static final long SESSION_WATCHDOG_INTERVAL_MS = 1500L;
    private static final int PLAYER_STATE_STARTED = resolveStartedPlayerState();

    private final Context appContext;
    private final AudioManager audioManager;
    private final GlobalEqualizerEngine engine;
    private final PresetRepository repository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<Integer> announcedPlaybackSessions = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> lastActiveConfigurationSessions = new LinkedHashSet<>();
    private final Runnable sessionAttachRetry = new Runnable() {
        @Override
        public void run() {
            sessionAttachRetryScheduled = false;
            if (!canAttachDvc() || mappingActive) {
                return;
            }
            handlePlaybackSessionsChanged(-1, false);
        }
    };
    private final Runnable sessionWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!canAttachDvc() || !mappingActive) {
                return;
            }
            Set<Integer> sessions = discoverPlaybackSessionIds(-1);
            boolean privateSessionChanged = activeAudioSessionId > 0
                    ? !sessions.contains(activeAudioSessionId)
                    || (preferredAudioSessionId > 0
                    && preferredAudioSessionId != activeAudioSessionId
                    && sessions.contains(preferredAudioSessionId))
                    : preferredAudioSessionId > 0
                    && sessions.contains(preferredAudioSessionId);
            if (privateSessionChanged) {
                Log.i(TAG, "DVC watchdog detected player-session change: active="
                        + activeAudioSessionId + ", preferred=" + preferredAudioSessionId
                        + ", sessions=" + sessions);
                handlePlaybackSessionsChanged(-1, false);
                return;
            }
            scheduleSessionWatchdog();
        }
    };
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
                    handlePlaybackSessionsChanged(-1, true);
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
    private int sessionAttachRetryAttempt;
    private boolean sessionAttachRetryScheduled;

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
                    handlePlaybackSessionsChanged(closedSessionId, true);
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
        cancelSessionAttachRetry();
        cancelSessionWatchdog();
        lastActiveConfigurationSessions.clear();
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
        cancelSessionAttachRetry();
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
        cancelSessionAttachRetry();
        cancelSessionWatchdog();
        lastActiveConfigurationSessions.clear();
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
        engine.setDvcDownstreamHeadroomDb(downstreamHeadroomDb);
        int targetAudioSessionId = -1;
        for (Integer candidateSessionId : orderedPlaybackSessionIds(playbackSessions)) {
            if (candidateSessionId == null || candidateSessionId < 0) {
                continue;
            }
            boolean sessionZeroFallback = candidateSessionId == 0;
            if (volumeChain != null
                    && volumeChain.getAudioSessionId() != candidateSessionId) {
                // Remove boosted DP before VolumeFX, but never write the stream volume during
                // teardown. The replacement session-0 bank is already enabled at -24 dB before
                // VolumeFX is detached, then its audible post-EQ handoff restores the preset.
                mappingActive = false;
                boolean switchedOff = engine.setDvcModeEnabled(false, 0);
                if (!switchedOff) {
                    Log.w(TAG, "Could not prepare guarded DVC handoff for session change");
                    return;
                }
                engine.completeDvcOffHandoff(
                        this::releaseDvcVolumeChain,
                        () -> resumeMappedCurveAfterHandoff(kind));
                return;
            }
            if (volumeChain == null
                    && (!sessionZeroFallback || !sessionZeroVolumeAttempted)) {
                if (sessionZeroFallback) {
                    sessionZeroVolumeAttempted = true;
                }
                try {
                    // Poweramp initializes VolumeFX before it creates the real DP bank. This
                    // ordering moves the current stream attenuation behind the boosted EQ.
                    volumeChain = new PowerampDvcVolumeChain(audioManager, candidateSessionId);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not create the Poweramp DVC volume chain for session "
                            + candidateSessionId, error);
                    releaseDvcVolumeChain();
                    if (!sessionZeroFallback) {
                        continue;
                    }
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
        if (targetAudioSessionId < 0) {
            failMappedCurve("播放器 audio session、VolumeFX 或 DP effect 挂载失败，DVC 未启用");
            return;
        }
        mappingActive = true;
        cancelSessionAttachRetry();
        activeAudioSessionId = targetAudioSessionId;
        scheduleSessionWatchdog();
        boolean sessionZeroFallback = targetAudioSessionId == 0;
        boolean chineseUi = "zh".equalsIgnoreCase(repository.loadUiLanguage());
        String attachment = volumeChain == null
                ? (chineseUi ? "仅 EQ" : "EQ only")
                : (sessionZeroFallback
                ? (chineseUi ? "全局回退" : "Global fallback")
                : (chineseUi ? "播放器链" : "Player chain"));
        String compactStatus = String.format(
                Locale.US,
                chineseUi
                        ? "%s · 可用余量 %.1f dB"
                        : "%s · %.1f dB headroom",
                attachment,
                displayedHeadroomDb);
        publish(kind, true, true,
                compactStatus);
        Log.i(TAG, "DVC active: session=" + targetAudioSessionId
                + ", discovered=" + playbackSessions
                + ", VolumeFX=" + (volumeChain == null
                ? "unavailable"
                : volumeChain.describeAttachment())
                + ", bank=" + engine.describeActiveDynamicsBank()
                + "\n" + engine.describeDvcReadback());
    }

    private void failMappedCurve(String detail) {
        cancelSessionWatchdog();
        deactivateEngineMapping();
        publish(DvcRuntimeState.Kind.PROBE_FAILED, false, true,
                detail == null ? "DVC player-session pipeline failed" : detail);
        scheduleSessionAttachRetry();
    }

    private void deactivate(DvcRuntimeState.Kind kind, boolean switchAvailable, String detail) {
        cancelSessionAttachRetry();
        cancelSessionWatchdog();
        deactivateEngineMapping();
        publish(kind, false, switchAvailable, detail);
    }

    private void deactivateEngineMapping() {
        mappingActive = false;
        activeAudioSessionId = 0;
        sessionZeroVolumeAttempted = false;
        // First prepare an enabled, -24 dB post-EQ bank on session 0. Only then detach VolumeFX
        // and let the engine release the old player bank and fade the real session-0 response in.
        boolean switchedOff = engine.setDvcModeEnabled(false, 0);
        if (!switchedOff && engine.isDvcModeActive()) {
            // The engine deliberately keeps the old bank and VolumeFX intact if the guarded bank
            // could not be created. Destroying either one here would expose unprotected audio.
            mappingActive = true;
            activeAudioSessionId = engine.getActiveDynamicsAudioSessionId();
            Log.w(TAG, "DVC teardown aborted because guarded handoff was unavailable");
            return;
        }
        engine.completeDvcOffHandoff(this::releaseDvcVolumeChain, () -> { });
        Log.i(TAG, "DVC teardown: switchedOff=" + switchedOff
                + ", engineDvcActive=" + engine.isDvcModeActive());
        engine.setDvcDownstreamHeadroomDb(0f);
    }

    private void releaseDvcVolumeChain() {
        if (volumeChain == null) {
            return;
        }
        try {
            volumeChain.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not release the DVC VolumeFX chain", error);
        } finally {
            volumeChain = null;
        }
    }

    private void resumeMappedCurveAfterHandoff(DvcRuntimeState.Kind kind) {
        if (!started
                || mode != ProcessingMode.GLOBAL_DSP
                || !presetEnabled
                || !userIntentEnabled
                || !routeDecision.allowsDvc
                || mappingActive) {
            return;
        }
        // Video players can replace their AudioTrack again while the guarded handoff is fading.
        // Never attach the new bank to the stale snapshot captured before that handoff.
        applyMappedCurve(kind, discoverPlaybackSessionIds(-1));
    }

    private void handlePlaybackSessionsChanged(int excludedSessionId,
                                               boolean newPlaybackSignal) {
        if (newPlaybackSignal) {
            cancelSessionAttachRetry();
            cancelSessionWatchdog();
        }
        if (!started || mode != ProcessingMode.GLOBAL_DSP || !presetEnabled
                || !userIntentEnabled || !routeDecision.allowsDvc) {
            return;
        }
        Set<Integer> sessions = discoverPlaybackSessionIds(excludedSessionId);
        if (sessions.isEmpty()) {
            applyMappedCurve(
                    routeDecision.isUsb()
                            ? DvcRuntimeState.Kind.USB_HARDWARE
                            : DvcRuntimeState.Kind.ACTIVE,
                    Collections.emptySet());
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
        // Prefer the newest active player. Keeping the currently attached session first made
        // video apps lose EQ whenever they replaced an AudioTrack without closing the old one.
        if (preferredAudioSessionId > 0 && playbackSessions.contains(preferredAudioSessionId)) {
            ordered.add(preferredAudioSessionId);
        }
        if (activeAudioSessionId > 0 && playbackSessions.contains(activeAudioSessionId)) {
            ordered.add(activeAudioSessionId);
        }
        ArrayList<Integer> newestFirst = new ArrayList<>(playbackSessions);
        Collections.reverse(newestFirst);
        ordered.addAll(newestFirst);
        // Always try session 0 last. This avoids making DVC activation depend on a player
        // broadcasting a private session ID; a real player session still takes precedence.
        ordered.add(0);
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
            LinkedHashSet<Integer> activeConfigurationSessions = new LinkedHashSet<>();
            int lastConfigurationSessionId = 0;
            if (configurations != null) {
                for (AudioPlaybackConfiguration configuration : configurations) {
                    if (!isPlaybackConfigurationActive(configuration)) {
                        continue;
                    }
                    int sessionId = readPlaybackSessionId(configuration);
                    if (sessionId > 0 && sessionId != excludedSessionId) {
                        activeConfigurationSessions.add(sessionId);
                        lastConfigurationSessionId = sessionId;
                    }
                }
            }
            if (!activeConfigurationSessions.isEmpty()) {
                // OPEN/CLOSE broadcasts are advisory and many video players omit CLOSE. When the
                // framework can identify currently playing sessions, discard stale announcements.
                result.clear();
                result.addAll(activeConfigurationSessions);
                LinkedHashSet<Integer> newlyActiveSessions =
                        new LinkedHashSet<>(activeConfigurationSessions);
                newlyActiveSessions.removeAll(lastActiveConfigurationSessions);
                boolean firstSnapshot = lastActiveConfigurationSessions.isEmpty();
                if (firstSnapshot
                        && preferredAudioSessionId > 0
                        && activeConfigurationSessions.contains(preferredAudioSessionId)) {
                    // Preserve the explicit OPEN_AUDIO_EFFECT session on the first snapshot.
                } else if (!newlyActiveSessions.isEmpty()) {
                    preferredAudioSessionId = lastSessionId(newlyActiveSessions);
                } else if (!activeConfigurationSessions.contains(preferredAudioSessionId)) {
                    preferredAudioSessionId = activeConfigurationSessions.contains(activeAudioSessionId)
                            ? activeAudioSessionId
                            : lastConfigurationSessionId;
                }
                Log.d(TAG, "DVC active-session snapshot: previous="
                        + lastActiveConfigurationSessions
                        + ", current=" + activeConfigurationSessions
                        + ", new=" + newlyActiveSessions
                        + ", selected=" + preferredAudioSessionId);
                lastActiveConfigurationSessions.clear();
                lastActiveConfigurationSessions.addAll(activeConfigurationSessions);
            } else {
                lastActiveConfigurationSessions.clear();
            }
        } catch (RuntimeException error) {
            Log.d(TAG, "Could not enumerate active player audio sessions", error);
        }
        return result;
    }

    private boolean isPlaybackConfigurationActive(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        try {
            Method method = AudioPlaybackConfiguration.class.getMethod("isActive");
            Object value = method.invoke(configuration);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Method method = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
            Object value = method.invoke(configuration);
            if (value instanceof Integer) {
                return (Integer) value == PLAYER_STATE_STARTED;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        // Some vendor frameworks hide both methods. The API already calls this the active
        // playback list, so fail open and retain its session instead of losing compatibility.
        return true;
    }

    private static int resolveStartedPlayerState() {
        try {
            return AudioPlaybackConfiguration.class
                    .getField("PLAYER_STATE_STARTED")
                    .getInt(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 2;
        }
    }

    private boolean canAttachDvc() {
        return started
                && mode == ProcessingMode.GLOBAL_DSP
                && presetEnabled
                && userIntentEnabled
                && routeDecision.allowsDvc;
    }

    private void scheduleSessionAttachRetry() {
        if (!canAttachDvc()
                || mappingActive
                || sessionAttachRetryScheduled
                || sessionAttachRetryAttempt >= SESSION_ATTACH_RETRY_DELAYS_MS.length) {
            return;
        }
        long delayMs = SESSION_ATTACH_RETRY_DELAYS_MS[sessionAttachRetryAttempt++];
        sessionAttachRetryScheduled = true;
        mainHandler.postDelayed(sessionAttachRetry, delayMs);
        Log.d(TAG, "Scheduled DVC session attach retry " + sessionAttachRetryAttempt
                + " in " + delayMs + " ms");
    }

    private void cancelSessionAttachRetry() {
        mainHandler.removeCallbacks(sessionAttachRetry);
        sessionAttachRetryScheduled = false;
        sessionAttachRetryAttempt = 0;
    }

    private void scheduleSessionWatchdog() {
        mainHandler.removeCallbacks(sessionWatchdog);
        if (canAttachDvc() && mappingActive) {
            mainHandler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_INTERVAL_MS);
        }
    }

    private void cancelSessionWatchdog() {
        mainHandler.removeCallbacks(sessionWatchdog);
    }

    private static int lastSessionId(Set<Integer> sessions) {
        int selected = 0;
        if (sessions != null) {
            for (Integer sessionId : sessions) {
                if (sessionId != null && sessionId > 0) {
                    selected = sessionId;
                }
            }
        }
        return selected;
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
