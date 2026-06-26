package com.example.globalpeq;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShizukuSessionMuteEngine {
    private static final String TAG = "ShizukuSessionMute";
    private static final float MUTE_GAIN_DB = -144f;
    private static final long MIN_POLL_INTERVAL_MS = 200L;
    private static final Pattern UID_THEN_SESSION_PATTERN = Pattern.compile(
            "(?i)\\buid\\s*[:=]?\\s*(\\d+)\\b.*?\\bsession(?:\\s+id)?\\s*[:=]?\\s*(\\d+)\\b");
    private static final Pattern SESSION_THEN_UID_PATTERN = Pattern.compile(
            "(?i)\\bsession(?:\\s+id)?\\s*[:=]?\\s*(\\d+)\\b.*?\\buid\\s*[:=]?\\s*(\\d+)\\b");

    private final Context appContext;
    private final AudioManager audioManager;
    private final PackageManager packageManager;
    private final PresetRepository repository;
    private final Runnable notificationCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Map<Integer, DynamicsProcessing> activeEffects = new HashMap<>();
    private final Set<Integer> rebuildingSessions = new LinkedHashSet<>();
    private final Runnable pollRunnable = this::pollOnWorker;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private volatile ProcessingMode currentMode = ProcessingMode.SYSTEM_EQ;
    private volatile Preset currentPreset = Preset.flat(false);
    private volatile AdvancedModeConfig currentConfig = AdvancedModeConfig.DEFAULT;
    private volatile int currentTargetUid = -1;
    private volatile String currentTargetLabel = "";
    private String publishedStatus = "";
    private boolean publishedActive;

    ShizukuSessionMuteEngine(Context context, PresetRepository repository, Runnable notificationCallback) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.packageManager = appContext.getPackageManager();
        this.repository = repository;
        this.notificationCallback = notificationCallback;
        publishStatus("Shizuku mute is idle.", false);
    }

    synchronized void updateProcessing(ProcessingMode mode, Preset preset, AdvancedModeConfig config) {
        currentMode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        currentPreset = preset == null ? Preset.flat(false) : preset;
        currentConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        currentTargetLabel = currentConfig.monitoredAppLabel.isEmpty()
                ? currentConfig.monitoredAppPackage
                : currentConfig.monitoredAppLabel;

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
        if (currentConfig.monitoredAppPackage.isEmpty()) {
            stopPollingLocked();
            publishStatus("Choose an app to monitor.", false);
            return;
        }
        String shizukuState = ShizukuCompat.describeState(appContext);
        if (!ShizukuCompat.hasPermission()) {
            stopPollingLocked();
            publishStatus(shizukuState, false);
            return;
        }

        currentTargetUid = resolveTargetUid(currentConfig.monitoredAppPackage);
        if (currentTargetUid <= 0) {
            stopPollingLocked();
            publishStatus("Unable to resolve the selected app.", false);
            return;
        }

        ensureWorkerLocked();
        Handler handler = workerHandler;
        if (handler == null) {
            publishStatus("Unable to start the Shizuku mute worker.", false);
            return;
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
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
        if (currentMode != ProcessingMode.SHIZUKU_MUTE || currentTargetUid <= 0 || workerHandler == null) {
            return;
        }
        try {
            List<Integer> sessionIds = collectTargetSessionIds(currentTargetUid);
            applySessionMuteSet(sessionIds);
            if (sessionIds.isEmpty()) {
                publishStatus("Waiting for " + currentTargetLabel + " sessions.", false);
            } else {
                publishStatus("Muted " + sessionIds.size() + " session(s) for " + currentTargetLabel + ".", true);
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

    private List<Integer> collectTargetSessionIds(int targetUid) {
        LinkedHashSet<Integer> sessionIds = new LinkedHashSet<>();
        sessionIds.addAll(collectSessionsFromAudioManager(targetUid));
        if (sessionIds.isEmpty()) {
            sessionIds.addAll(collectSessionsFromShizukuDump(targetUid));
        }
        return new ArrayList<>(sessionIds);
    }

    private Set<Integer> collectSessionsFromAudioManager(int targetUid) {
        LinkedHashSet<Integer> sessionIds = new LinkedHashSet<>();
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return sessionIds;
        }
        try {
            for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                if (configuration == null) {
                    continue;
                }
                int clientUid = readIntMethod(configuration, "getClientUid");
                int sessionId = readIntMethod(configuration, "getSessionId");
                if (clientUid != targetUid || sessionId <= 0) {
                    continue;
                }
                AudioAttributes attributes = configuration.getAudioAttributes();
                int usage = attributes == null ? AudioAttributes.USAGE_UNKNOWN : attributes.getUsage();
                if (usage != AudioAttributes.USAGE_MEDIA
                        && usage != AudioAttributes.USAGE_GAME
                        && usage != AudioAttributes.USAGE_UNKNOWN) {
                    continue;
                }
                sessionIds.add(sessionId);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect active playback configurations", ex);
        }
        return sessionIds;
    }

    private Set<Integer> collectSessionsFromShizukuDump(int targetUid) {
        LinkedHashSet<Integer> sessionIds = new LinkedHashSet<>();
        Process process = ShizukuCompat.newProcess("dumpsys media.audio_flinger");
        if (process == null) {
            return sessionIds;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseDumpLineForSession(targetUid, line, sessionIds);
            }
        } catch (IOException ex) {
            Log.w(TAG, "Unable to parse audio_flinger dump", ex);
        } finally {
            process.destroy();
        }
        return sessionIds;
    }

    private void parseDumpLineForSession(int targetUid, String line, Set<Integer> out) {
        if (line == null || out == null) {
            return;
        }
        Matcher uidFirst = UID_THEN_SESSION_PATTERN.matcher(line);
        while (uidFirst.find()) {
            int uid = safeParseInt(uidFirst.group(1));
            int sessionId = safeParseInt(uidFirst.group(2));
            if (uid == targetUid && sessionId > 0) {
                out.add(sessionId);
            }
        }
        Matcher sessionFirst = SESSION_THEN_UID_PATTERN.matcher(line);
        while (sessionFirst.find()) {
            int sessionId = safeParseInt(sessionFirst.group(1));
            int uid = safeParseInt(sessionFirst.group(2));
            if (uid == targetUid && sessionId > 0) {
                out.add(sessionId);
            }
        }
    }

    private void applySessionMuteSet(List<Integer> sessionIds) {
        synchronized (stateLock) {
            Set<Integer> desired = new LinkedHashSet<>(sessionIds);
            List<Integer> stale = new ArrayList<>();
            for (Integer sessionId : activeEffects.keySet()) {
                if (!desired.contains(sessionId)) {
                    stale.add(sessionId);
                }
            }
            for (Integer sessionId : stale) {
                releaseEffectLocked(sessionId);
            }
            for (Integer sessionId : desired) {
                if (sessionId == null || sessionId <= 0 || activeEffects.containsKey(sessionId)) {
                    continue;
                }
                DynamicsProcessing effect = createMuteEffect(sessionId);
                if (effect != null) {
                    activeEffects.put(sessionId, effect);
                }
            }
        }
    }

    private DynamicsProcessing createMuteEffect(int sessionId) {
        try {
            DynamicsProcessing effect = new DynamicsProcessing(Integer.MAX_VALUE, sessionId, null);
            effect.setEnabled(false);
            effect.setInputGainAllChannelsTo(MUTE_GAIN_DB);
            for (int channel = 0; channel < effect.getChannelCount(); channel++) {
                effect.setInputGainbyChannel(channel, MUTE_GAIN_DB);
                try {
                    DynamicsProcessing.Channel channelState = effect.getChannelByChannelIndex(channel);
                    if (channelState != null) {
                        channelState.setInputGain(MUTE_GAIN_DB);
                        effect.setChannelTo(channel, channelState);
                    }
                } catch (RuntimeException channelEx) {
                    Log.w(TAG, "Unable to update channel state for session " + sessionId
                            + " channel " + channel, channelEx);
                }
            }
            effect.setEnabled(true);
            setupEffectListeners(effect, sessionId);
            Log.i(TAG, "Muted foreign audio session " + sessionId
                    + " channels=" + effect.getChannelCount()
                    + " gainDb=" + MUTE_GAIN_DB);
            return effect;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to mute foreign audio session " + sessionId, ex);
            return null;
        }
    }

    private void setupEffectListeners(DynamicsProcessing effect, int sessionId) {
        try {
            effect.setEnableStatusListener(new AudioEffect.OnEnableStatusChangeListener() {
                @Override
                public void onEnableStatusChange(AudioEffect audioEffect, boolean enabled) {
                    if (!enabled) {
                        Log.w(TAG, "DynamicsProcessing disabled for session " + sessionId + ", rebuilding");
                        rebuildMuteEffect(sessionId);
                    }
                }
            });
        } catch (RuntimeException listenerEx) {
            Log.w(TAG, "Unable to set enable listener for session " + sessionId, listenerEx);
        }
        try {
            effect.setControlStatusListener(new AudioEffect.OnControlStatusChangeListener() {
                @Override
                public void onControlStatusChange(AudioEffect audioEffect, boolean controlGranted) {
                    if (!controlGranted) {
                        Log.w(TAG, "DynamicsProcessing lost control for session " + sessionId + ", rebuilding");
                        rebuildMuteEffect(sessionId);
                    }
                }
            });
        } catch (RuntimeException listenerEx) {
            Log.w(TAG, "Unable to set control listener for session " + sessionId, listenerEx);
        }
    }

    private void rebuildMuteEffect(int sessionId) {
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
                    DynamicsProcessing effect = createMuteEffect(sessionId);
                    if (effect != null) {
                        activeEffects.put(sessionId, effect);
                    }
                }
            } finally {
                synchronized (stateLock) {
                    rebuildingSessions.remove(sessionId);
                }
            }
        });
    }

    private int resolveTargetUid(String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ex) {
            return -1;
        }
    }

    private synchronized void ensureWorkerLocked() {
        if (workerThread != null && workerHandler != null) {
            return;
        }
        workerThread = new HandlerThread("global-peq-shizuku-mute");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    private synchronized void stopPollingLocked() {
        Handler handler = workerHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        synchronized (stateLock) {
            List<Integer> sessions = new ArrayList<>(activeEffects.keySet());
            for (Integer sessionId : sessions) {
                releaseEffectLocked(sessionId);
            }
        }
        currentTargetUid = -1;
    }

    private void releaseEffectLocked(Integer sessionId) {
        DynamicsProcessing effect = activeEffects.remove(sessionId);
        if (effect == null) {
            return;
        }
        try {
            effect.setEnabled(false);
        } catch (RuntimeException ignored) {
        }
        try {
            effect.release();
        } catch (RuntimeException ignored) {
        }
    }

    private int readIntMethod(Object target, String methodName) {
        if (target == null || methodName == null) {
            return -1;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read " + methodName + " via reflection", ex);
            return -1;
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
