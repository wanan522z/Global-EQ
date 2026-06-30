package com.example.globalpeq;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShizukuSessionMuteEngine {
    private static final String TAG = "ShizukuSessionMute";
    private static final float MUTE_GAIN_DB = -144f;
    private static final long ACTIVE_RESCAN_INTERVAL_MS = 750L;
    private static final long PASSIVE_RESCAN_INTERVAL_MS = 5000L;
    private static final Pattern SESSION_REGEX = Pattern.compile(
            "Session Id:\\s*(\\d+)\\s+UID:\\s*(\\d+)[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_REGEX_33 = Pattern.compile(
            "Session ID:\\s*(\\d+);\\s*uid\\s*(\\d+);[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_BLOCK_START_REGEX = Pattern.compile(
            "\\bSession(?:\\s+I(?:d|D))?\\s*[:=]?\\s*(\\d+)(?:\\s*;)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_UID_REGEX = Pattern.compile(
            "\\buid\\b\\s*:?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_CONTENT_REGEX = Pattern.compile(
            "Content\\s+type\\s*:?\\s*([A-Z0-9_()/-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_USAGE_REGEX = Pattern.compile(
            "Usage\\s*:?\\s*([A-Z0-9_()/-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYBACK_SESSION_REGEX = Pattern.compile(
            "\\bsession(?:Id)?\\b\\s*[:=]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYBACK_UID_REGEX = Pattern.compile(
            "\\b(?:client)?uid\\b\\s*[:=]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYBACK_USAGE_NAME_REGEX = Pattern.compile(
            "\\busage\\b\\s*[:=]\\s*(AUDIO_USAGE_[A-Z_]+|USAGE_[A-Z_]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYBACK_USAGE_VALUE_REGEX = Pattern.compile(
            "\\busage\\b\\s*[:=]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final int PLAYER_STATE_STARTED = resolveStartedPlayerState();

    interface SessionIdProvider {
        Set<Integer> getOwnedAudioSessionIds();
    }

    private static final class SessionInfo {
        final int sessionId;
        final int uid;
        final String usage;
        final String content;
        final String packageName;

        SessionInfo(int sessionId, int uid, String usage, String content, String packageName) {
            this.sessionId = sessionId;
            this.uid = uid;
            this.usage = usage == null ? "" : usage;
            this.content = content == null ? "" : content;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    private static final class ActivePlaybackSnapshot {
        final boolean activePlaybackDetected;
        final Set<Integer> activeUids;
        final String primaryPackageName;

        ActivePlaybackSnapshot(boolean activePlaybackDetected,
                               Set<Integer> activeUids,
                               String primaryPackageName) {
            this.activePlaybackDetected = activePlaybackDetected;
            this.activeUids = activeUids == null ? new LinkedHashSet<>() : activeUids;
            this.primaryPackageName = primaryPackageName == null ? "" : primaryPackageName;
        }

        boolean hasActivePlayback() {
            return activePlaybackDetected || !activeUids.isEmpty();
        }

        boolean hasResolvedActiveUids() {
            return !activeUids.isEmpty();
        }

        boolean containsUid(int uid) {
            return uid > 0 && activeUids.contains(uid);
        }
    }

    private static final class MuteScanResult {
        final String activePackageName;
        final String mutedPackageName;

        MuteScanResult(String activePackageName, String mutedPackageName) {
            this.activePackageName = activePackageName == null ? "" : activePackageName;
            this.mutedPackageName = mutedPackageName == null ? "" : mutedPackageName;
        }
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final PackageManager packageManager;
    private final PresetRepository repository;
    private final Runnable notificationCallback;
    private final SessionIdProvider sessionIdProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, DynamicsProcessing> muteEffects = new ConcurrentHashMap<>();
    private final Map<Integer, SessionInfo> knownSessions = new ConcurrentHashMap<>();
    private final Set<Integer> rebuildingSessions = new LinkedHashSet<>();
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    Log.d(TAG, "Playback configs changed, rescanning sessions");
                    scanSessionsAndRefreshState();
                }
            };
    private final Runnable periodicRescanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldMonitorPlaybackSessions()) {
                return;
            }
            scanSessionsAndRefreshState();
            mainHandler.postDelayed(this, currentRescanIntervalMs);
        }
    };

    private boolean playbackCallbackRegistered;
    private volatile ProcessingMode currentMode = ProcessingMode.SYSTEM_EQ;
    private volatile Preset currentPreset = Preset.flat(false);
    private volatile Set<Integer> currentAppSessionIds = new LinkedHashSet<>();
    private volatile String currentActivePackageName = "";
    private volatile String currentMutedPackageName = "";
    private volatile long currentRescanIntervalMs = PASSIVE_RESCAN_INTERVAL_MS;
    private String publishedStatus = "";
    private boolean publishedActive;

    ShizukuSessionMuteEngine(Context context,
                             PresetRepository repository,
                             Runnable notificationCallback,
                             SessionIdProvider sessionIdProvider) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.packageManager = appContext.getPackageManager();
        this.repository = repository;
        this.notificationCallback = notificationCallback;
        this.sessionIdProvider = sessionIdProvider;
        publishStatus("Shizuku mute is idle.", false);
    }

    synchronized void updateProcessing(ProcessingMode mode, Preset preset, AdvancedModeConfig config) {
        currentMode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        currentPreset = preset == null ? Preset.flat(false) : preset;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            stopAll();
            publishStatus("Shizuku mute requires Android 9 or later.", false);
            return;
        }
        if (!ShizukuCompat.hasPermission()) {
            stopAll();
            publishStatus(ShizukuCompat.describeState(appContext), false);
            return;
        }

        ShizukuCompat.grantPermissionsAndAppOps(appContext);
        if (wantsToMuteSessions()) {
            currentRescanIntervalMs = ACTIVE_RESCAN_INTERVAL_MS;
            updateCurrentAppSessionIds();
            registerPlaybackCallback();
            schedulePeriodicRescan();
            scanSessionsAndRefreshState();
            return;
        }

        releaseAllEffects();
        currentAppSessionIds = new LinkedHashSet<>();
        currentRescanIntervalMs = PASSIVE_RESCAN_INTERVAL_MS;
        registerPlaybackCallback();
        schedulePeriodicRescan();
        scanSessionsAndRefreshState();
        publishStatus(currentMode == ProcessingMode.SHIZUKU_MUTE
                ? "Shizuku mute ready. Enable EQ to start."
                : "Shizuku is ready.", false);
    }

    synchronized void stopAll() {
        cancelPeriodicRescan();
        unregisterPlaybackCallback();
        releaseAllEffects();
        currentAppSessionIds = new LinkedHashSet<>();
        updateActivePackageName("");
        updateMutedPackageName("");
        publishStatus("Shizuku mute is idle.", false);
    }

    private boolean shouldMonitorPlaybackSessions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && ShizukuCompat.hasPermission();
    }

    private boolean wantsToMuteSessions() {
        return shouldMonitorPlaybackSessions()
                && currentMode.requiresShizukuMute()
                && currentPreset != null
                && currentPreset.enabled;
    }

    private boolean hasOwnedCaptureSessions() {
        return currentAppSessionIds != null && !currentAppSessionIds.isEmpty();
    }

    private boolean shouldActivelyMuteSessions(ActivePlaybackSnapshot activePlayback) {
        return wantsToMuteSessions()
                && hasOwnedCaptureSessions()
                && ((activePlayback != null && activePlayback.hasActivePlayback())
                || repository.loadMonitorCaptureActive());
    }

    private void scanSessionsAndRefreshState() {
        if (!shouldMonitorPlaybackSessions()) {
            return;
        }
        boolean wantsMuteEffects = wantsToMuteSessions();
        if (wantsMuteEffects) {
            updateCurrentAppSessionIds();
        } else {
            currentAppSessionIds = new LinkedHashSet<>();
        }
        List<SessionInfo> sessions = dumpPolicySessions();
        ActivePlaybackSnapshot activePlayback = captureActivePlaybackSnapshot();
        boolean applyMuteEffects = shouldActivelyMuteSessions(activePlayback);
        if (!applyMuteEffects && (!muteEffects.isEmpty() || !knownSessions.isEmpty())) {
            releaseAllEffects();
        }
        Log.d(TAG, "Rescanned audio policy, matched sessions=" + sessions.size()
                + ", ownedSessions=" + currentAppSessionIds.size()
                + ", activeMuteEffects=" + muteEffects.size()
                + ", muteMode=" + applyMuteEffects
                + ", activePlaybackUids=" + activePlayback.activeUids.size());
        logSessionSnapshot("scanSessionsAndRefreshState", sessions);
        MuteScanResult scanResult = muteOtherSessions(sessions, activePlayback, applyMuteEffects);
        updateActivePackageName(scanResult.activePackageName);
        updateMutedPackageName(scanResult.mutedPackageName);
        if (wantsMuteEffects && !applyMuteEffects) {
            publishStatus(repository.loadMonitorCaptureActive()
                    ? "Waiting for native capture playback session."
                    : "Waiting for active playback sessions.", false);
            return;
        }
        if (!applyMuteEffects) {
            return;
        }
        int mutedCount = muteEffects.size();
        if (mutedCount == 0) {
            publishStatus("Waiting for active playback sessions.", false);
            return;
        }
        publishStatus("Muted " + mutedCount + " session(s) while monitoring system audio.", true);
    }

    private ActivePlaybackSnapshot captureActivePlaybackSnapshot() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return new ActivePlaybackSnapshot(false, new LinkedHashSet<>(), "");
        }
        LinkedHashSet<Integer> activeUids = new LinkedHashSet<>();
        String primaryPackageName = "";
        boolean activePlaybackDetected = false;
        try {
            List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
            if (configs == null) {
                return new ActivePlaybackSnapshot(false, activeUids, primaryPackageName);
            }
            Log.d(TAG, "Active playback config count=" + configs.size());
            for (AudioPlaybackConfiguration configuration : configs) {
                int uid = readPlaybackClientUid(configuration);
                int usage = readPlaybackUsage(configuration);
                int playerState = readPlaybackPlayerState(configuration);
                boolean relevant = isRelevantActivePlayback(configuration, playerState);
                if (relevant) {
                    activePlaybackDetected = true;
                }
                Log.d(TAG, "Playback config uid=" + uid
                        + ", usage=" + usage
                        + ", playerState=" + playerState
                        + ", relevant=" + relevant);
                Log.d(TAG, "Playback config raw=" + summarizeConfig(configuration));
                if (!relevant) {
                    continue;
                }
                if (uid <= 0) {
                    continue;
                }
                activeUids.add(uid);
                if (primaryPackageName.isEmpty()) {
                    primaryPackageName = getPackageNameForUid(uid);
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect active playback configurations", ex);
        }
        return new ActivePlaybackSnapshot(activePlaybackDetected, activeUids, primaryPackageName);
    }

    private int readPlaybackUsage(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return -1;
        }
        try {
            android.media.AudioAttributes attributes = configuration.getAudioAttributes();
            return attributes == null ? -1 : attributes.getUsage();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback usage", ex);
        }
        return parsePlaybackUsage(configuration.toString());
    }

    private int readPlaybackPlayerState(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return -1;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
            Object value = method.invoke(configuration);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (NoSuchMethodException ex) {
            Log.w(TAG, "AudioPlaybackConfiguration#getPlayerState is unavailable on this device", ex);
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#getPlayerState", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback player state", ex);
        }
        return -1;
    }

    private void updateCurrentAppSessionIds() {
        SessionIdProvider provider = sessionIdProvider;
        if (provider == null) {
            currentAppSessionIds = new LinkedHashSet<>();
            return;
        }
        Set<Integer> sessionIds = provider.getOwnedAudioSessionIds();
        currentAppSessionIds = sessionIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(sessionIds);
    }

    private List<SessionInfo> dumpPolicySessions() {
        String output = ShizukuCompat.dumpSystemService("media.audio_policy");
        List<SessionInfo> sessions = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) {
            Log.w(TAG, "Audio policy dump was empty");
        } else {
            collectSessionsWithPattern(output, SESSION_REGEX, sessions);
            if (!sessions.isEmpty()) {
                Log.d(TAG, "audio_policy matched with SESSION_REGEX count=" + sessions.size());
            }
            if (sessions.isEmpty()) {
                collectSessionsWithPattern(output, SESSION_REGEX_33, sessions);
                if (!sessions.isEmpty()) {
                    Log.d(TAG, "audio_policy matched with SESSION_REGEX_33 count=" + sessions.size());
                }
            }
            if (sessions.isEmpty()) {
                collectSessionsByBlocks(output, sessions);
                if (!sessions.isEmpty()) {
                    Log.d(TAG, "audio_policy matched with block parser count=" + sessions.size());
                }
            }
        }

        mergeActivePlaybackSessions(sessions);

        if (sessions.isEmpty()) {
            Log.w(TAG, "No audio sessions matched the audio_policy dump");
        }
        return sessions;
    }

    private void collectSessionsWithPattern(String output, Pattern pattern, List<SessionInfo> sessions) {
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            try {
                int sessionId = safeParseInt(matcher.group(1));
                int uid = safeParseInt(matcher.group(2));
                String content = matcher.group(3);
                String usage = matcher.group(4);
                if (sessionId <= 0 || uid <= 0) {
                    continue;
                }
                String pkgName = getPackageNameForUid(uid);
                sessions.add(new SessionInfo(sessionId, uid, usage, content, pkgName));
                Log.d(TAG, "Parsed audio_policy session sid=" + sessionId
                        + ", uid=" + uid
                        + ", pkg=" + pkgName
                        + ", usage=" + usage
                        + ", content=" + content);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Unable to parse audio policy session", ex);
            }
        }
    }

    private void collectSessionsByBlocks(String output, List<SessionInfo> sessions) {
        Matcher matcher = SESSION_BLOCK_START_REGEX.matcher(output);
        List<int[]> blocks = new ArrayList<>();
        while (matcher.find()) {
            int sessionId = safeParseInt(matcher.group(1));
            if (sessionId > 0) {
                blocks.add(new int[]{sessionId, matcher.start()});
            }
        }

        for (int i = 0; i < blocks.size(); i++) {
            int sessionId = blocks.get(i)[0];
            int start = blocks.get(i)[1];
            int end = i + 1 < blocks.size() ? blocks.get(i + 1)[1] : output.length();
            if (start < 0 || end <= start || end > output.length()) {
                continue;
            }

            String block = output.substring(start, end);
            int uid = findBlockInt(block, SESSION_UID_REGEX);
            if (uid <= 0) {
                continue;
            }

            String content = findBlockValue(block, SESSION_CONTENT_REGEX);
            String usage = findBlockValue(block, SESSION_USAGE_REGEX);
            if (usage.isEmpty()) {
                continue;
            }

            String packageName = getPackageNameForUid(uid);
            sessions.add(new SessionInfo(sessionId, uid, usage, content, packageName));
            Log.d(TAG, "Parsed audio_policy block session sid=" + sessionId
                    + ", uid=" + uid
                    + ", pkg=" + packageName
                    + ", usage=" + usage
                    + ", content=" + content);
        }
    }

    private void mergeActivePlaybackSessions(List<SessionInfo> sessions) {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        Set<Integer> knownSessionIds = new LinkedHashSet<>();
        for (SessionInfo session : sessions) {
            knownSessionIds.add(session.sessionId);
        }

        try {
            List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
            if (configs == null) {
                return;
            }
            for (AudioPlaybackConfiguration configuration : configs) {
                int playerState = readPlaybackPlayerState(configuration);
                if (configuration == null || !isRelevantActivePlayback(configuration, playerState)) {
                    continue;
                }

                int sessionId = readPlaybackSessionId(configuration);
                int uid = readPlaybackClientUid(configuration);
                if (sessionId <= 0 || uid <= 0 || knownSessionIds.contains(sessionId)) {
                    Log.d(TAG, "Skipped active playback merge sid=" + sessionId
                            + ", uid=" + uid
                            + ", known=" + knownSessionIds.contains(sessionId)
                            + ", raw=" + summarizeConfig(configuration));
                    continue;
                }

                String packageName = getPackageNameForUid(uid);
                String usage = playbackUsageToString(readPlaybackUsage(configuration));
                sessions.add(new SessionInfo(sessionId, uid, usage, "", packageName));
                knownSessionIds.add(sessionId);
                Log.d(TAG, "Recovered active playback session from AudioPlaybackConfiguration: sid="
                        + sessionId + ", uid=" + uid + ", package=" + packageName
                        + ", usage=" + usage + ", raw=" + summarizeConfig(configuration));
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to merge active playback sessions", ex);
        }
    }

    private int findBlockInt(String block, Pattern pattern) {
        String value = findBlockValue(block, pattern);
        return safeParseInt(value);
    }

    private String findBlockValue(String block, Pattern pattern) {
        if (block == null || block.isEmpty()) {
            return "";
        }
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private MuteScanResult muteOtherSessions(List<SessionInfo> sessions,
                                             ActivePlaybackSnapshot activePlayback,
                                             boolean applyMuteEffects) {
        Set<Integer> currentSessionIds = new LinkedHashSet<>();
        Set<Integer> desiredMuteSessionIds = new LinkedHashSet<>();
        String firstActivePackageName = activePlayback == null ? "" : activePlayback.primaryPackageName;
        String firstMutedPackageName = "";
        for (SessionInfo session : sessions) {
            currentSessionIds.add(session.sessionId);
        }

        List<Integer> removedSessions = new ArrayList<>();
        for (Integer sid : knownSessions.keySet()) {
            if (!currentSessionIds.contains(sid)) {
                removedSessions.add(sid);
            }
        }
        for (Integer sid : removedSessions) {
            knownSessions.remove(sid);
            releaseEffect(sid);
        }

        int ownUid = Process.myUid();
        for (SessionInfo session : sessions) {
            knownSessions.put(session.sessionId, session);
            if (session.packageName.equals(appContext.getPackageName()) || session.uid == ownUid) {
                Log.d(TAG, "Skip session sid=" + session.sessionId + " because it belongs to our app");
                continue;
            }
            if (currentAppSessionIds.contains(session.sessionId)) {
                Log.d(TAG, "Skip session sid=" + session.sessionId + " because it is an owned capture session");
                continue;
            }
            if (!isEligibleSessionUsage(session.usage)) {
                Log.d(TAG, "Skip session sid=" + session.sessionId + " due to usage=" + session.usage);
                continue;
            }
            if (activePlayback != null
                    && activePlayback.hasResolvedActiveUids()
                    && !activePlayback.containsUid(session.uid)) {
                Log.d(TAG, "Skip session sid=" + session.sessionId
                        + " because activePlaybackUids=" + activePlayback.activeUids
                        + " does not include uid=" + session.uid
                        + " pkg=" + session.packageName);
                continue;
            }
            if (firstActivePackageName.isEmpty() && !session.packageName.isEmpty()) {
                firstActivePackageName = session.packageName;
            }
            desiredMuteSessionIds.add(session.sessionId);
            Log.d(TAG, "Session selected for mute sid=" + session.sessionId
                    + ", uid=" + session.uid
                    + ", pkg=" + session.packageName
                    + ", usage=" + session.usage);
        }
        List<Integer> staleMutedSessions = new ArrayList<>();
        for (Integer sid : muteEffects.keySet()) {
            if (!applyMuteEffects || !desiredMuteSessionIds.contains(sid)) {
                staleMutedSessions.add(sid);
            }
        }
        for (Integer sid : staleMutedSessions) {
            releaseEffect(sid);
        }

        for (SessionInfo session : sessions) {
            if (!desiredMuteSessionIds.contains(session.sessionId)) {
                continue;
            }
            if (!applyMuteEffects) {
                continue;
            }
            if (muteEffects.containsKey(session.sessionId)) {
                continue;
            }

            Log.d(TAG, "Attempting to mute session " + session.sessionId
                    + " uid=" + session.uid
                    + " package=" + session.packageName
                    + " usage=" + session.usage
                    + " content=" + session.content);

            try {
                DynamicsProcessing muteEffect = makeMuteEffect(session.sessionId, session.packageName);
                if (muteEffect != null) {
                    muteEffects.put(session.sessionId, muteEffect);
                    if (firstMutedPackageName.isEmpty() && !session.packageName.isEmpty()) {
                        firstMutedPackageName = session.packageName;
                    }
                } else {
                    Log.w(TAG, "Failed to create mute effect for session: " + session.sessionId
                            + ", package: " + session.packageName);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Error creating mute effect for session: " + session.sessionId, ex);
            }
        }
        if (firstMutedPackageName.isEmpty() && !muteEffects.isEmpty()) {
            for (SessionInfo session : sessions) {
                if (muteEffects.containsKey(session.sessionId) && !session.packageName.isEmpty()) {
                    firstMutedPackageName = session.packageName;
                    break;
                }
            }
        }
        return new MuteScanResult(firstActivePackageName, firstMutedPackageName);
    }

    private boolean isRelevantActivePlayback(AudioPlaybackConfiguration configuration, int playerState) {
        if (configuration == null) {
            return false;
        }
        if (!isPlaybackActive(configuration, playerState)) {
            return false;
        }
        try {
            android.media.AudioAttributes attributes = configuration.getAudioAttributes();
            if (attributes == null) {
                return false;
            }
            int usage = attributes.getUsage();
            return usage == android.media.AudioAttributes.USAGE_MEDIA
                    || usage == android.media.AudioAttributes.USAGE_GAME
                    || usage == android.media.AudioAttributes.USAGE_UNKNOWN;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect playback attributes", ex);
            return false;
        }
    }

    private boolean isPlaybackActive(AudioPlaybackConfiguration configuration, int playerState) {
        if (configuration == null) {
            return false;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("isActive");
            Object value = method.invoke(configuration);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#isActive", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback active state", ex);
        }
        return isPlaybackStarted(playerState);
    }

    private boolean isPlaybackStarted(int playerState) {
        return playerState < 0 || playerState == PLAYER_STATE_STARTED;
    }

    private static int resolveStartedPlayerState() {
        try {
            java.lang.reflect.Field field = AudioPlaybackConfiguration.class.getField("PLAYER_STATE_STARTED");
            return field.getInt(null);
        } catch (ReflectiveOperationException ignored) {
            return 2;
        } catch (RuntimeException ignored) {
            return 2;
        }
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
                java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod(methodName);
                Object value = method.invoke(configuration);
                if (value instanceof Integer) {
                    int sessionId = (Integer) value;
                    if (sessionId > 0) {
                        return sessionId;
                    }
                }
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException ex) {
                Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#" + methodName, ex);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Unable to read playback session id via " + methodName, ex);
            }
        }

        return parseSessionIdFromPlaybackConfig(configuration.toString());
    }

    private int parseSessionIdFromPlaybackConfig(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        Matcher matcher = PLAYBACK_SESSION_REGEX.matcher(text);
        if (!matcher.find()) {
            Log.d(TAG, "Playback config text had no session token: " + summarizeText(text));
            return -1;
        }
        return safeParseInt(matcher.group(1));
    }

    private int parsePlaybackUid(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        Matcher matcher = PLAYBACK_UID_REGEX.matcher(text);
        if (!matcher.find()) {
            Log.d(TAG, "Playback config text had no uid token: " + summarizeText(text));
            return -1;
        }
        return safeParseInt(matcher.group(1));
    }

    private int parsePlaybackUsage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }

        Matcher nameMatcher = PLAYBACK_USAGE_NAME_REGEX.matcher(text);
        if (nameMatcher.find()) {
            String value = nameMatcher.group(1);
            if (value != null) {
                String normalized = value.trim().toUpperCase(Locale.US);
                if (normalized.endsWith("MEDIA")) {
                    return android.media.AudioAttributes.USAGE_MEDIA;
                }
                if (normalized.endsWith("GAME")) {
                    return android.media.AudioAttributes.USAGE_GAME;
                }
                if (normalized.endsWith("UNKNOWN")) {
                    return android.media.AudioAttributes.USAGE_UNKNOWN;
                }
            }
        }

        Matcher valueMatcher = PLAYBACK_USAGE_VALUE_REGEX.matcher(text);
        if (!valueMatcher.find()) {
            Log.d(TAG, "Playback config text had no usage token: " + summarizeText(text));
            return -1;
        }
        return safeParseInt(valueMatcher.group(1));
    }

    private String playbackUsageToString(int usage) {
        switch (usage) {
            case android.media.AudioAttributes.USAGE_MEDIA:
                return "USAGE_MEDIA";
            case android.media.AudioAttributes.USAGE_GAME:
                return "USAGE_GAME";
            case android.media.AudioAttributes.USAGE_UNKNOWN:
                return "USAGE_UNKNOWN";
            default:
                return usage <= 0 ? "" : "USAGE_" + usage;
        }
    }

    private boolean isEligibleSessionUsage(String usageValue) {
        String usage = usageValue == null ? "" : usageValue.toUpperCase(Locale.US).trim();
        return usage.contains("USAGE_MEDIA")
                || usage.contains("USAGE_GAME")
                || usage.contains("USAGE_UNKNOWN");
    }

    private void logSessionSnapshot(String label, List<SessionInfo> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            Log.d(TAG, label + " sessions=[]");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (SessionInfo session : sessions) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("sid=").append(session.sessionId)
                    .append(",uid=").append(session.uid)
                    .append(",pkg=").append(session.packageName)
                    .append(",usage=").append(session.usage)
                    .append(",content=").append(session.content);
        }
        Log.d(TAG, label + " sessions=[" + builder + "]");
    }

    private String summarizeConfig(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return "null";
        }
        return summarizeText(configuration.toString());
    }

    private String summarizeText(String text) {
        if (text == null) {
            return "null";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private int readPlaybackClientUid(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getClientUid");
            Object value = method.invoke(configuration);
            if (value instanceof Integer) {
                return (Integer) value;
            }
            Log.w(TAG, "Playback config returned non-integer client uid: " + value);
        } catch (NoSuchMethodException ex) {
            Log.w(TAG, "AudioPlaybackConfiguration#getClientUid is unavailable on this device", ex);
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#getClientUid", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback client uid", ex);
        }
        return parsePlaybackUid(configuration.toString());
    }

    private void updateActivePackageName(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.equals(currentActivePackageName)) {
            return;
        }
        currentActivePackageName = normalized;
        Log.d(TAG, "Active playback package -> " + normalized);
        repository.saveActivePlaybackPackage(normalized);
        if (notificationCallback != null) {
            mainHandler.post(notificationCallback);
        }
    }

    private void updateMutedPackageName(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.equals(currentMutedPackageName)) {
            return;
        }
        currentMutedPackageName = normalized;
        Log.d(TAG, "Muted playback package -> " + normalized);
        repository.saveActiveMutedPackage(normalized);
        if (notificationCallback != null) {
            mainHandler.post(notificationCallback);
        }
    }

    private DynamicsProcessing makeMuteEffect(int sessionId, String packageName) {
        DynamicsProcessing effect = new DynamicsProcessing(Integer.MAX_VALUE, sessionId, null);
        effect.setInputGainAllChannelsTo(MUTE_GAIN_DB);
        effect.setEnabled(true);
        setupEffectListeners(effect, sessionId, packageName);
        Log.i(TAG, "Muted foreign audio session " + sessionId + " package=" + packageName);
        return effect;
    }

    private void setupEffectListeners(DynamicsProcessing effect, int sessionId, String packageName) {
        try {
            try {
                Class<?> listenerInterface = Class.forName("android.media.audiofx.AudioEffect$OnEnableStatusChangeListener");
                java.lang.reflect.Method method = AudioEffect.class.getMethod(
                        "setEnableStatusListener",
                        listenerInterface
                );
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        listenerInterface.getClassLoader(),
                        new Class[]{listenerInterface},
                        (proxy, invokedMethod, args) -> {
                            if ("onEnableStatusChange".equals(invokedMethod.getName())) {
                                boolean enabled = args != null && args.length > 1 && args[1] instanceof Boolean
                                        && (Boolean) args[1];
                                if (!enabled) {
                                    Log.w(TAG, "DynamicsProcessing effect disabled for session: " + sessionId + ", re-enabling");
                                    try {
                                        effect.setInputGainAllChannelsTo(MUTE_GAIN_DB);
                                        effect.setEnabled(true);
                                    } catch (RuntimeException reError) {
                                        Log.e(TAG, "Failed to re-enable DynamicsProcessing for session: " + sessionId, reError);
                                        postReacquireControl(sessionId, packageName);
                                    }
                                }
                            }
                            return null;
                        });
                method.invoke(effect, listener);
            } catch (NoSuchMethodException ex) {
                Log.d(TAG, "setEnableStatusListener not available on this API level");
            }
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to set enable listener for session: " + sessionId, ex);
        }

        try {
            try {
                Class<?> listenerInterface = Class.forName("android.media.audiofx.AudioEffect$OnControlStatusChangeListener");
                java.lang.reflect.Method method = AudioEffect.class.getMethod(
                        "setControlStatusListener",
                        listenerInterface
                );
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        listenerInterface.getClassLoader(),
                        new Class[]{listenerInterface},
                        (proxy, invokedMethod, args) -> {
                            if ("onControlStatusChange".equals(invokedMethod.getName())) {
                                boolean controlGranted = args != null && args.length > 1 && args[1] instanceof Boolean
                                        && (Boolean) args[1];
                                if (!controlGranted) {
                                    Log.w(TAG, "DynamicsProcessing control lost for session: " + sessionId + ", package: " + packageName);
                                    postReacquireControl(sessionId, packageName);
                                } else {
                                    try {
                                        effect.setInputGainAllChannelsTo(-200f);
                                        effect.setEnabled(true);
                                    } catch (RuntimeException regainError) {
                                        Log.w(TAG, "Failed to regain control over DynamicsProcessing (session " + sessionId + ")", regainError);
                                        postReacquireControl(sessionId, packageName);
                                    }
                                }
                            }
                            return null;
                        });
                method.invoke(effect, listener);
            } catch (NoSuchMethodException ex) {
                Log.d(TAG, "setControlStatusListener not available on this API level");
            }
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to set control listener for session: " + sessionId, ex);
        }
    }

    private void postReacquireControl(int sessionId, String packageName) {
        synchronized (rebuildingSessions) {
            if (!rebuildingSessions.add(sessionId)) {
                return;
            }
        }
        mainHandler.post(() -> {
            try {
                releaseEffect(sessionId);
                DynamicsProcessing newEffect = makeMuteEffect(sessionId, packageName);
                if (newEffect != null) {
                    muteEffects.put(sessionId, newEffect);
                    Log.d(TAG, "Successfully reacquired control for session: " + sessionId);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Failed to reacquire control for session: " + sessionId, ex);
            } finally {
                synchronized (rebuildingSessions) {
                    rebuildingSessions.remove(sessionId);
                }
            }
        });
    }

    private void registerPlaybackCallback() {
        if (playbackCallbackRegistered || audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler);
        playbackCallbackRegistered = true;
        Log.i(TAG, "PlaybackCallback registered");
    }

    private void unregisterPlaybackCallback() {
        if (!playbackCallbackRegistered || audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        playbackCallbackRegistered = false;
        Log.i(TAG, "PlaybackCallback unregistered");
    }

    private void schedulePeriodicRescan() {
        mainHandler.removeCallbacks(periodicRescanRunnable);
        mainHandler.post(periodicRescanRunnable);
    }

    private void cancelPeriodicRescan() {
        mainHandler.removeCallbacks(periodicRescanRunnable);
    }

    private void releaseAllEffects() {
        for (Integer sessionId : new ArrayList<>(muteEffects.keySet())) {
            releaseEffect(sessionId);
        }
        muteEffects.clear();
        knownSessions.clear();
    }

    private void releaseEffect(Integer sessionId) {
        DynamicsProcessing effect = muteEffects.remove(sessionId);
        if (effect == null) {
            return;
        }
        try {
            effect.setInputGainAllChannelsTo(0f);
            effect.setEnabled(false);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Error disabling mute effect for session: " + sessionId, ex);
        }
        try {
            effect.release();
            Log.d(TAG, "Released mute effect for session: " + sessionId);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Error releasing mute effect for session: " + sessionId, ex);
        }
    }

    private String getPackageNameForUid(int uid) {
        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            return packages != null && packages.length > 0 ? packages[0] : "";
        } catch (RuntimeException ex) {
            Log.w(TAG, "Error getting package name for uid: " + uid, ex);
            return "";
        }
    }

    private int safeParseInt(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private synchronized void publishStatus(String status, boolean active) {
        String nextStatus = status == null || status.trim().isEmpty()
                ? "Shizuku mute is idle."
                : status;
        if (nextStatus.equals(publishedStatus) && active == publishedActive) {
            return;
        }
        publishedStatus = nextStatus;
        publishedActive = active;
        repository.saveShizukuMuteStatus(nextStatus, active);
        if (notificationCallback != null) {
            mainHandler.post(notificationCallback);
        }
    }
}
