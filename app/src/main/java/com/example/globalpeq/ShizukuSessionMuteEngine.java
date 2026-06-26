package com.example.globalpeq;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
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
    private static final long MIN_POLL_INTERVAL_MS = 200L;
    private static final Pattern SESSION_REGEX = Pattern.compile(
            "Session Id:\\s*(\\d+)\\s+UID:\\s*(\\d+)[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_REGEX_33 = Pattern.compile(
            "Session ID:\\s*(\\d+);\\s*uid\\s*(\\d+);[\\s\\S]*?Attributes:[\\s\\S]*?Content type:\\s*(\\w+)\\s*Usage:\\s*(\\w+)",
            Pattern.CASE_INSENSITIVE);

    private final Context appContext;
    private final AudioManager audioManager;
    private final PackageManager packageManager;
    private final PresetRepository repository;
    private final Runnable notificationCallback;
    private final SessionIdProvider sessionIdProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Map<Integer, DynamicsProcessing> muteEffects = new ConcurrentHashMap<>();
    private final Map<Integer, SessionInfo> knownSessions = new ConcurrentHashMap<>();
    private final Set<Integer> rebuildingSessions = new LinkedHashSet<>();
    private final Runnable pollRunnable = this::pollOnWorker;
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    scheduleImmediatePoll();
                }
            };

    private HandlerThread workerThread;
    private Handler workerHandler;
    private boolean playbackCallbackRegistered;
    private volatile ProcessingMode currentMode = ProcessingMode.SYSTEM_EQ;
    private volatile Preset currentPreset = Preset.flat(false);
    private volatile AdvancedModeConfig currentConfig = AdvancedModeConfig.DEFAULT;
    private volatile int currentTargetUid = -1;
    private volatile String currentTargetPackage = "";
    private volatile String currentTargetLabel = "";
    private volatile Set<Integer> currentAppSessionIds = new LinkedHashSet<>();
    private String publishedStatus = "";
    private boolean publishedActive;

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
        currentConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        currentTargetPackage = "";
        currentTargetUid = -1;
        currentTargetLabel = "system audio";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            stopAll();
            publishStatus("Shizuku mute requires Android 9 or later.", false);
            return;
        }
        if (currentMode != ProcessingMode.SHIZUKU_MUTE) {
            stopPollingLocked();
            publishStatus("Shizuku mute is idle.", false);
            return;
        }
        if (preset == null || !preset.enabled) {
            stopPollingLocked();
            publishStatus("Shizuku mute ready. Enable EQ to start.", false);
            return;
        }
        String shizukuState = ShizukuCompat.describeState(appContext);
        if (!ShizukuCompat.hasPermission()) {
            stopPollingLocked();
            publishStatus(shizukuState, false);
            return;
        }

        ShizukuCompat.grantPermissionsAndAppOps(appContext);
        ensureWorkerLocked();
        registerPlaybackCallbackLocked();
        scheduleImmediatePoll();
    }

    synchronized void stopAll() {
        stopPollingLocked();
        HandlerThread thread = workerThread;
        workerThread = null;
        workerHandler = null;
        if (thread != null) {
            thread.quitSafely();
        }
        publishStatus("Shizuku mute is idle.", false);
    }

    private void pollOnWorker() {
        if (currentMode != ProcessingMode.SHIZUKU_MUTE || workerHandler == null) {
            return;
        }
        try {
            updateCurrentAppSessionIds();
            List<SessionInfo> sessions = dumpPolicySessions();
            muteOtherSessions(sessions);
            int mutedCount = muteEffects.size();
            if (mutedCount == 0) {
                publishStatus("Waiting for active playback sessions.", false);
            } else {
                publishStatus("Muted " + mutedCount + " session(s) while monitoring system audio.", true);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Shizuku mute poll failed", ex);
            publishStatus("Shizuku mute poll failed. Re-open Shizuku and try again.", false);
        }
        Handler handler = workerHandler;
        if (handler != null && currentMode == ProcessingMode.SHIZUKU_MUTE) {
            long delayMs = Math.max(MIN_POLL_INTERVAL_MS, currentConfig.monitorIntervalMs);
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, delayMs);
        }
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
        List<SessionInfo> activeSessions = collectActivePlaybackSessions();
        if (!activeSessions.isEmpty()) {
            return activeSessions;
        }
        String output = ShizukuCompat.dumpSystemService("media.audio_policy");
        if (output == null || output.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return parseAudioConfigurations(output);
    }

    private List<SessionInfo> collectActivePlaybackSessions() {
        List<SessionInfo> sessions = new ArrayList<>();
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return sessions;
        }
        try {
            List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
            if (configs == null) {
                return sessions;
            }
            for (AudioPlaybackConfiguration config : configs) {
                if (config == null) {
                    continue;
                }
                int sessionId = readPlaybackSessionId(config);
                int uid = readPlaybackClientUid(config);
                if (sessionId <= 0 || uid <= 0) {
                    continue;
                }
                AudioAttributes attributes = readPlaybackAttributes(config);
                String usage = describeUsage(attributes);
                String content = describeContentType(attributes);
                String pkgName = getPackageNameForUid(uid);
                sessions.add(new SessionInfo(sessionId, uid, usage, content, pkgName));
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect active playback configurations for mute", ex);
        }
        return sessions;
    }

    private List<SessionInfo> parseAudioConfigurations(String output) {
        List<SessionInfo> sessions = new ArrayList<>();
        collectSessionsWithPattern(output, SESSION_REGEX, sessions);
        if (sessions.isEmpty()) {
            collectSessionsWithPattern(output, SESSION_REGEX_33, sessions);
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

    private void muteOtherSessions(List<SessionInfo> sessions) {
        Set<Integer> currentSessionIds = new LinkedHashSet<>();
        for (SessionInfo session : sessions) {
            currentSessionIds.add(session.sessionId);
        }

        List<Integer> removedSessions = new ArrayList<>();
        for (Integer sid : knownSessions.keySet()) {
            if (!currentSessionIds.contains(sid)) {
                removedSessions.add(sid);
            }
        }
        synchronized (stateLock) {
            for (Integer sid : removedSessions) {
                knownSessions.remove(sid);
                releaseEffectLocked(sid);
            }
        }

        int ownUid = Process.myUid();
        for (SessionInfo session : sessions) {
            knownSessions.put(session.sessionId, session);
            if (session.uid == ownUid || appContext.getPackageName().equals(session.packageName)) {
                continue;
            }
            if (currentAppSessionIds.contains(session.sessionId)) {
                continue;
            }
            if (session.sessionId <= 0 || session.sessionId == 0) {
                continue;
            }
            synchronized (stateLock) {
                if (muteEffects.containsKey(session.sessionId)) {
                    continue;
                }
                try {
                    DynamicsProcessing effect = makeMuteEffect(session.sessionId, session.packageName);
                    if (effect != null) {
                        muteEffects.put(session.sessionId, effect);
                    }
                } catch (RuntimeException ex) {
                    Log.e(TAG, "Error creating mute effect for session " + session.sessionId, ex);
                }
            }
        }
    }

    private int readPlaybackSessionId(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getAudioSessionId");
            Object value = method.invoke(configuration);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback session id", ex);
        }
        return -1;
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
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback client uid", ex);
        }
        return -1;
    }

    private AudioAttributes readPlaybackAttributes(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        try {
            return configuration.getAudioAttributes();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback attributes", ex);
            return null;
        }
    }

    private String describeUsage(AudioAttributes attributes) {
        if (attributes == null) {
            return "USAGE_UNKNOWN";
        }
        switch (attributes.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
                return "USAGE_MEDIA";
            case AudioAttributes.USAGE_GAME:
                return "USAGE_GAME";
            case AudioAttributes.USAGE_ASSISTANT:
                return "USAGE_ASSISTANT";
            case AudioAttributes.USAGE_NOTIFICATION:
                return "USAGE_NOTIFICATION";
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                return "USAGE_NOTIFICATION_RINGTONE";
            case AudioAttributes.USAGE_ALARM:
                return "USAGE_ALARM";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return "USAGE_VOICE_COMMUNICATION";
            default:
                return "USAGE_" + attributes.getUsage();
        }
    }

    private String describeContentType(AudioAttributes attributes) {
        if (attributes == null) {
            return "CONTENT_TYPE_UNKNOWN";
        }
        switch (attributes.getContentType()) {
            case AudioAttributes.CONTENT_TYPE_MUSIC:
                return "CONTENT_TYPE_MUSIC";
            case AudioAttributes.CONTENT_TYPE_MOVIE:
                return "CONTENT_TYPE_MOVIE";
            case AudioAttributes.CONTENT_TYPE_SPEECH:
                return "CONTENT_TYPE_SPEECH";
            case AudioAttributes.CONTENT_TYPE_SONIFICATION:
                return "CONTENT_TYPE_SONIFICATION";
            default:
                return "CONTENT_TYPE_" + attributes.getContentType();
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
            effect.setEnableStatusListener(new AudioEffect.OnEnableStatusChangeListener() {
                @Override
                public void onEnableStatusChange(AudioEffect audioEffect, boolean enabled) {
                    if (!enabled) {
                        Log.w(TAG, "DynamicsProcessing disabled for session " + sessionId + ", re-enabling");
                        try {
                            effect.setInputGainAllChannelsTo(MUTE_GAIN_DB);
                            effect.setEnabled(true);
                        } catch (RuntimeException reError) {
                            Log.e(TAG, "Failed to re-enable DynamicsProcessing for session " + sessionId, reError);
                            rebuildMuteEffect(sessionId, packageName);
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to set enable listener for session " + sessionId, ex);
        }
        try {
            effect.setControlStatusListener(new AudioEffect.OnControlStatusChangeListener() {
                @Override
                public void onControlStatusChange(AudioEffect audioEffect, boolean controlGranted) {
                    if (!controlGranted) {
                        Log.w(TAG, "DynamicsProcessing control lost for session " + sessionId);
                        rebuildMuteEffect(sessionId, packageName);
                    } else {
                        try {
                            effect.setInputGainAllChannelsTo(-200f);
                            effect.setEnabled(true);
                        } catch (RuntimeException regainError) {
                            Log.w(TAG, "Failed to regain control for session " + sessionId, regainError);
                            rebuildMuteEffect(sessionId, packageName);
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to set control listener for session " + sessionId, ex);
        }
    }

    private void rebuildMuteEffect(int sessionId, String packageName) {
        Handler handler = workerHandler;
        if (handler == null || sessionId <= 0) {
            return;
        }
        synchronized (stateLock) {
            if (!rebuildingSessions.add(sessionId)) {
                return;
            }
        }
        handler.post(() -> {
            try {
                synchronized (stateLock) {
                    releaseEffectLocked(sessionId);
                    DynamicsProcessing effect = makeMuteEffect(sessionId, packageName);
                    if (effect != null) {
                        muteEffects.put(sessionId, effect);
                    }
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Failed to reacquire control for session " + sessionId, ex);
            } finally {
                synchronized (stateLock) {
                    rebuildingSessions.remove(sessionId);
                }
            }
        });
    }

    private synchronized void ensureWorkerLocked() {
        if (workerThread != null && workerHandler != null) {
            return;
        }
        workerThread = new HandlerThread("global-peq-shizuku-mute");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    private synchronized void registerPlaybackCallbackLocked() {
        if (playbackCallbackRegistered || audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler);
        playbackCallbackRegistered = true;
    }

    private synchronized void unregisterPlaybackCallbackLocked() {
        if (!playbackCallbackRegistered || audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        playbackCallbackRegistered = false;
    }

    private void scheduleImmediatePoll() {
        Handler handler = workerHandler;
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private synchronized void stopPollingLocked() {
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        unregisterPlaybackCallbackLocked();
        synchronized (stateLock) {
            List<Integer> sessions = new ArrayList<>(muteEffects.keySet());
            for (Integer sid : sessions) {
                releaseEffectLocked(sid);
            }
        }
        knownSessions.clear();
        currentAppSessionIds = new LinkedHashSet<>();
        currentTargetUid = -1;
        currentTargetPackage = "";
        currentTargetLabel = "";
    }

    private void releaseEffectLocked(Integer sessionId) {
        DynamicsProcessing effect = muteEffects.remove(sessionId);
        if (effect == null) {
            return;
        }
        try {
            effect.release();
        } catch (RuntimeException ignored) {
        }
    }

    private int resolveTargetUid(String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ex) {
            return -1;
        }
    }

    private String getPackageNameForUid(int uid) {
        try {
            String[] packages = packageManager.getPackagesForUid(uid);
            return packages != null && packages.length > 0 ? packages[0] : "";
        } catch (RuntimeException ex) {
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
