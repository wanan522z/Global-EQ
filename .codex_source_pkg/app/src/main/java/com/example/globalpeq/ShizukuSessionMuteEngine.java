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
        publishStatus("Shizuku mute is idle.", false);
    }

    private boolean shouldMonitorPlaybackSessions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && ShizukuCompat.hasPermission();
    }

    private boolean wantsToMuteSessions() {
        return shouldMonitorPlaybackSessions()
                && currentMode == ProcessingMode.SHIZUKU_MUTE
                && currentPreset != null
                && currentPreset.enabled;
    }

    private boolean hasOwnedCaptureSessions() {
        return currentAppSessionIds != null && !currentAppSessionIds.isEmpty();
    }

    private boolean shouldActivelyMuteSessions() {
        return wantsToMuteSessions() && hasOwnedCaptureSessions();
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
        boolean applyMuteEffects = shouldActivelyMuteSessions();
        if (!applyMuteEffects) {
            if (!muteEffects.isEmpty() || !knownSessions.isEmpty()) {
                releaseAllEffects();
            }
        }
        List<SessionInfo> sessions = dumpPolicySessions();
        Log.d(TAG, "Rescanned audio policy, matched sessions=" + sessions.size()
                + ", ownedSessions=" + currentAppSessionIds.size()
                + ", activeMuteEffects=" + muteEffects.size()
                + ", muteMode=" + applyMuteEffects);
        String activePackageName = muteOtherSessions(sessions, applyMuteEffects);
        updateActivePackageName(activePackageName);
        if (wantsMuteEffects && !applyMuteEffects) {
            publishStatus("Waiting for native capture playback session.", false);
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
        if (output == null || output.trim().isEmpty()) {
            Log.w(TAG, "Audio policy dump was empty");
            return new ArrayList<>();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        collectSessionsWithPattern(output, SESSION_REGEX, sessions);
        if (sessions.isEmpty()) {
            collectSessionsWithPattern(output, SESSION_REGEX_33, sessions);
        }
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
            } catch (RuntimeException ex) {
                Log.w(TAG, "Unable to parse audio policy session", ex);
            }
        }
    }

    private String muteOtherSessions(List<SessionInfo> sessions, boolean applyMuteEffects) {
        Set<Integer> currentSessionIds = new LinkedHashSet<>();
        String firstActivePackageName = "";
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
                continue;
            }
            if (currentAppSessionIds.contains(session.sessionId)) {
                continue;
            }
            String usage = session.usage.toUpperCase(Locale.US).trim();
            if (!usage.contains("USAGE_MEDIA")
                    && !usage.contains("USAGE_GAME")) {
                continue;
            }
            if (firstActivePackageName.isEmpty() && !session.packageName.isEmpty()) {
                firstActivePackageName = session.packageName;
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
                } else {
                    Log.w(TAG, "Failed to create mute effect for session: " + session.sessionId
                            + ", package: " + session.packageName);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Error creating mute effect for session: " + session.sessionId, ex);
            }
        }
        return firstActivePackageName;
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
