package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.audiofx.BassBoost;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

final class PlaybackCaptureEngine {
    private static final String TAG = "PlaybackCaptureEngine";
    private static final int CHANNEL_COUNT = 2;
    private static final int SAMPLE_RATE = 48000;
    private static final float SIGNAL_THRESHOLD = 0.0018f;
    private static final int SOURCE_APP_STREAM = AudioManager.STREAM_MUSIC;
    private static final int MIN_PIPELINE_BUFFER_FRAMES = 128;
    private static final int MIN_TRACK_LATENCY_MS = 40;
    private static final int MIN_PROCESSING_CHUNK_FRAMES = 256;
    private static final int MAX_PROCESSING_CHUNK_FRAMES = 2048;
    private static final int MAX_BLUETOOTH_CHUNK_FRAMES = 640;
    private static final int BLUETOOTH_HEAVY_DSP_CHUNK_FRAMES = 384;
    private static final int BLUETOOTH_PREFILL_CHUNKS = 4;
    private static final int STALLED_READ_LIMIT = 6;
    private static final long ACTIVE_PLAYBACK_RECOVERY_MIN_MS = 1800L;
    private static final long AUTO_RESTART_COOLDOWN_MS = 1500L;
    private static final int PLAYER_STATE_STARTED = 2;
    private final Context appContext;
    private final AudioManager audioManager;
    private final PackageManager packageManager;
    private final PresetRepository repository;
    private final Runnable notificationCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object dspLock = new Object();
    private final PcmDspProcessor dspProcessor = new PcmDspProcessor();

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private BassBoost trackBassBoost;
    private Thread workerThread;
    private Object activeWorkerToken;

    private volatile boolean running;
    private volatile ProcessingMode currentMode = ProcessingMode.SYSTEM_EQ;
    private volatile Preset currentPreset = Preset.flat(false);
    private volatile AdvancedModeConfig currentConfig = AdvancedModeConfig.DEFAULT;
    private volatile int currentVirtualBassModeIndex;
    private volatile int currentTargetUid = -1;
    private volatile String currentTargetLabel = "";
    private volatile AudioOutputDevice currentOutputDevice = new AudioOutputDevice("none", "Output device");

    private int configuredTargetUid = -1;
    private int configuredBufferFrames = -1;
    private int configuredChunkFrames = -1;
    private int configuredLatencyMs = -1;
    private String configuredOutputDeviceKey = "";
    private String configuredPreferredDeviceSignature = "none";
    private boolean captureSignalLogged;
    private boolean captureWaitingLogged;
    private String publishedStatus = "";
    private boolean publishedActive;
    private long lastAutoRestartAtMs;
    private volatile long lastCaptureSignalAtMs;
    private volatile String currentReplayPackageName = "";
    private long lastReplayPackageRefreshAtMs;
    private String lastReplayDecisionTrace = "";
    PlaybackCaptureEngine(Context context, PresetRepository repository, Runnable notificationCallback) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.packageManager = appContext.getPackageManager();
        this.repository = repository;
        this.notificationCallback = notificationCallback;
        publishStatus("Native capture is idle.", false);
    }

    synchronized void bootstrapProjection(int resultCode, Intent data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            repository.saveMonitorCaptureAuthorized(false);
            publishStatus("Native capture requires Android 10 or later.", false);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            repository.saveMonitorCaptureAuthorized(false);
            publishStatus("Capture authorization was cancelled.", false);
            return;
        }

        MediaProjectionManager manager = appContext.getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            repository.saveMonitorCaptureAuthorized(false);
            publishStatus("MediaProjection service is unavailable.", false);
            return;
        }

        releaseProjectionLocked();
        try {
            mediaProjection = manager.getMediaProjection(resultCode, new Intent(data));
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to obtain MediaProjection token", ex);
            mediaProjection = null;
        }
        if (mediaProjection == null) {
            repository.saveMonitorCaptureAuthorized(false);
            publishStatus("Capture authorization could not be initialized.", false);
            return;
        }

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                synchronized (PlaybackCaptureEngine.this) {
                    stopPipelineLocked();
                    releaseProjectionLocked();
                }
                publishStatus("Capture permission ended. Authorize again to resume.", false);
            }
        };
        mediaProjection.registerCallback(projectionCallback, mainHandler);
        repository.saveMonitorCaptureAuthorized(true);
        if (currentMode.capturesSystemAudio()) {
            publishStatus("Capture authorized for system audio.", false);
        } else {
            publishStatus(currentTargetLabel.isEmpty()
                    ? "Capture authorized. Choose an app to monitor."
                    : "Capture authorized for " + currentTargetLabel + ".", false);
        }
    }

    synchronized void updateProcessing(ProcessingMode mode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int virtualBassModeIndex,
                                       AudioOutputDevice outputDevice) {
        currentMode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        currentPreset = preset == null ? Preset.flat(false) : preset;
        currentConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        currentVirtualBassModeIndex = virtualBassModeIndex;
        currentOutputDevice = outputDevice == null ? new AudioOutputDevice("none", "Output device") : outputDevice;
        currentTargetLabel = currentConfig.monitoredAppLabel.isEmpty()
                ? currentConfig.monitoredAppPackage
                : currentConfig.monitoredAppLabel;
        if (currentMode.capturesSystemAudio()) {
            currentTargetLabel = "system audio";
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopPipelineLocked();
            publishStatus("Native capture requires Android 10 or later.", false);
            return;
        }
        if (!AudioProcessingPolicy.advancedModeEnabled(currentMode)) {
            stopPipelineLocked();
            publishStatus("Default mode active. Native capture disabled.", false);
            return;
        }
        if (preset == null || !preset.enabled) {
            stopPipelineLocked();
            publishStatus(currentMode.requiresShizukuMute()
                    ? "Shizuku Mode ready. Enable EQ to start capture."
                    : "Global DSP ready. Enable EQ to start capture.", false);
            return;
        }
        if (!currentMode.capturesSystemAudio()
                && currentConfig.monitoredAppPackage.isEmpty()) {
            stopPipelineLocked();
            publishStatus("Choose an app to monitor.", false);
            return;
        }
        if (mediaProjection == null) {
            stopPipelineLocked();
            publishStatus(currentMode.capturesSystemAudio()
                    ? "Authorize native capture for system audio."
                    : "Authorize native capture for " + currentTargetLabel + ".", false);
            return;
        }

        currentTargetUid = currentMode.capturesSystemAudio()
                ? -1
                : resolveTargetUid(currentConfig.monitoredAppPackage);
        if (!currentMode.capturesSystemAudio() && currentTargetUid <= 0) {
            stopPipelineLocked();
            publishStatus("Unable to resolve the selected app.", false);
            return;
        }

        boolean outputRouteRestartRequired =
                !safeDeviceKey(currentOutputDevice).equals(configuredOutputDeviceKey);
        String desiredPreferredDeviceSignature =
                describeResolvedDeviceSignature(resolvePreferredOutputDeviceInfo());
        boolean preferredRouteRestartRequired =
                !desiredPreferredDeviceSignature.equals(configuredPreferredDeviceSignature);
        boolean requiresRestart = !running
                || configuredTargetUid != currentTargetUid
                || configuredBufferFrames != currentConfig.bufferSizeFrames
                || configuredLatencyMs != currentConfig.latencyMs
                || outputRouteRestartRequired
                || preferredRouteRestartRequired;
        if (requiresRestart) {
            startPipelineLocked();
        } else {
            reconfigureEffectsLocked();
        }
    }

    synchronized boolean hasProjection() {
        return mediaProjection != null;
    }

    synchronized Set<Integer> getOwnedAudioSessionIds() {
        if (!running) {
            return java.util.Collections.emptySet();
        }
        Set<Integer> sessionIds = new LinkedHashSet<>();
        if (audioRecord != null) {
            int recordSessionId = audioRecord.getAudioSessionId();
            if (recordSessionId > 0) {
                sessionIds.add(recordSessionId);
            }
        }
        if (audioTrack != null) {
            int trackSessionId = audioTrack.getAudioSessionId();
            if (trackSessionId > 0) {
                sessionIds.add(trackSessionId);
            }
        }
        return sessionIds;
    }

    synchronized void stopAll() {
        stopPipelineLocked();
        releaseProjectionLocked();
        publishStatus("Native capture is idle.", false);
    }

    private int resolveTargetUid(String packageName) {
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ex) {
            return -1;
        }
    }

    private AudioPlaybackCaptureConfiguration buildCaptureConfiguration() {
        AudioPlaybackCaptureConfiguration.Builder builder =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME);
        if (currentMode.capturesSystemAudio()) {
            builder.excludeUid(android.os.Process.myUid());
        } else {
            builder.addMatchingUid(currentTargetUid);
        }
        AudioPlaybackCaptureConfiguration configuration = builder.build();
        Log.i(TAG, "Built capture configuration mode=" + currentMode
                + ", targetUid=" + currentTargetUid
                + ", excludeOwnUid=" + currentMode.capturesSystemAudio()
                + ", usages=[MEDIA,GAME]");
        return configuration;
    }

    private String monitoringStatusText() {
        if (currentMode.capturesSystemAudio()) {
            return "Monitoring system audio via native capture.";
        }
        return "Monitoring " + currentTargetLabel + " via native capture.";
    }

    private String replayBlockedStatusText() {
        if (currentMode.requiresShizukuMute()) {
            return "Source app could not be muted. Processed replay is off.";
        }
        return "Processed replay is off.";
    }

    private String waitingStatusText() {
        if (currentMode.capturesSystemAudio()) {
            return "Armed for system audio - waiting for playback.";
        }
        return "Armed for " + currentTargetLabel + " - waiting for playback.";
    }

    private boolean isHeavyRealtimeDspEnabled() {
        if (currentPreset == null || !currentPreset.enabled) {
            return false;
        }
        boolean reverbEnabled = AudioProcessingPolicy.reverbAllowed(currentMode)
                && !"Default".equals(currentPreset.reverbType)
                && currentPreset.reverbMixPercent > 0;
        boolean dspBassEnabled = AudioProcessingPolicy.dspVirtualBassAllowed(currentMode, currentVirtualBassModeIndex)
                && currentPreset.dspVirtualBassAmountPercent > 0;
        return reverbEnabled || dspBassEnabled;
    }

    private void startPipelineLocked() {
        stopPipelineLocked();
        if (mediaProjection == null || (!currentMode.capturesSystemAudio() && currentTargetUid <= 0)) {
            publishStatus("Native capture is not authorized.", false);
            return;
        }

        try {
            AudioFormat recordFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioPlaybackCaptureConfiguration captureConfiguration = buildCaptureConfiguration();

            int bytesPerFrame = CHANNEL_COUNT * 2;
            boolean heavyRealtimeDsp = isHeavyRealtimeDspEnabled();
            int desiredFrames = Math.max(MIN_PIPELINE_BUFFER_FRAMES, currentConfig.bufferSizeFrames);
            AudioDeviceInfo preferredOutputDevice = resolvePreferredOutputDeviceInfo();
            boolean bluetoothOutput = isBluetoothOutput(preferredOutputDevice);
            int processingChunkFrames = chooseProcessingChunkFrames(
                    desiredFrames,
                    heavyRealtimeDsp,
                    bluetoothOutput);
            int minRecordBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minRecordBytes <= 0) {
                minRecordBytes = processingChunkFrames * bytesPerFrame * 4;
            }
            int recordMultiplier = heavyRealtimeDsp ? 4 : 2;
            int recordBufferBytes = Math.max(minRecordBytes * recordMultiplier,
                    desiredFrames * bytesPerFrame * (heavyRealtimeDsp ? 6 : 3));
            recordBufferBytes = Math.max(recordBufferBytes, processingChunkFrames * bytesPerFrame * 4);

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(recordFormat)
                    .setBufferSizeInBytes(recordBufferBytes)
                    .setAudioPlaybackCaptureConfig(captureConfiguration)
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }

            AudioFormat trackFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            int minTrackBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int latencyMs = Math.max(MIN_TRACK_LATENCY_MS, currentConfig.latencyMs);
            int requestedLatencyFrames = Math.max(desiredFrames, SAMPLE_RATE * latencyMs / 1000);
            int latencyFrames = Math.max(desiredFrames + desiredFrames / 2, requestedLatencyFrames);
            if (minTrackBytes <= 0) {
                minTrackBytes = latencyFrames * bytesPerFrame;
            }
            int trackMultiplier = bluetoothOutput
                    ? (heavyRealtimeDsp ? 6 : 4)
                    : (heavyRealtimeDsp ? 4 : 2);
            int trackBufferBytes = Math.max(minTrackBytes * trackMultiplier,
                    latencyFrames * bytesPerFrame * (bluetoothOutput ? 4 : (heavyRealtimeDsp ? 3 : 2)));
            trackBufferBytes = Math.max(trackBufferBytes, processingChunkFrames * bytesPerFrame * (bluetoothOutput ? 8 : 6));

            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioAttributes(buildTrackAttributesForCurrentMode())
                    .setAudioFormat(trackFormat)
                    .setBufferSizeInBytes(trackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE);
            }
            audioTrack = trackBuilder.build();
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack failed to initialize");
            }
            bindTrackToPreferredOutputLocked(audioTrack, preferredOutputDevice);

            configuredTargetUid = currentTargetUid;
            configuredBufferFrames = currentConfig.bufferSizeFrames;
            configuredChunkFrames = processingChunkFrames;
            configuredLatencyMs = currentConfig.latencyMs;
            configuredOutputDeviceKey = safeDeviceKey(currentOutputDevice);
            configuredPreferredDeviceSignature =
                    describeResolvedDeviceSignature(preferredOutputDevice);
            running = true;
            lastCaptureSignalAtMs = 0L;
            reconfigureEffectsLocked();

            audioTrack.play();
            Log.i(TAG, "AudioTrack playState after play()=" + audioTrack.getPlayState());
            prefillTrackIfNeeded(audioTrack, processingChunkFrames, bluetoothOutput);
            audioRecord.startRecording();
            Log.i(TAG, "AudioRecord recordingState after startRecording()=" + audioRecord.getRecordingState());
            Log.i(TAG, "Capture sessions recordSid=" + audioRecord.getAudioSessionId()
                    + ", trackSid=" + audioTrack.getAudioSessionId()
                    + ", targetLabel=" + currentTargetLabel
                    + ", targetUid=" + currentTargetUid);
            Object workerToken = new Object();
            activeWorkerToken = workerToken;
            workerThread = new Thread(() -> runCaptureLoop(workerToken), "global-peq-capture");
            workerThread.start();
            Log.i(TAG, "Started experimental native capture route"
                    + " target=" + currentTargetLabel
                    + " trackUsage=MEDIA"
                    + " desiredFrames=" + desiredFrames
                    + " processingChunkFrames=" + processingChunkFrames
                    + " heavyRealtimeDsp=" + heavyRealtimeDsp
                    + " bluetoothOutput=" + bluetoothOutput
                    + " trackBufferBytes=" + trackBufferBytes
                    + " latencyMs=" + latencyMs
                    + " output=" + configuredOutputDeviceKey);
            publishStatus(waitingStatusText(), false);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to start playback capture pipeline", ex);
            stopPipelineLocked();
            publishStatus("Failed to start native capture. Re-authorize and try again.", false);
        }
    }

    private void runCaptureLoop(Object workerToken) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioRecord record = audioRecord;
        AudioTrack track = audioTrack;
        if (record == null || track == null) {
            return;
        }

        int chunkFrames = Math.max(MIN_PROCESSING_CHUNK_FRAMES,
                configuredChunkFrames > 0 ? configuredChunkFrames : configuredBufferFrames);
        short[] pcm = new short[chunkFrames * CHANNEL_COUNT];
        float[] samples = new float[pcm.length];
        short[] rendered = new short[pcm.length];
        long lastSignalAt = SystemClock.elapsedRealtime();
        boolean signaledLive = false;
        captureSignalLogged = false;
        captureWaitingLogged = false;
        int silentReadCount = 0;
        int nonZeroReadCount = 0;
        int stalledReadCount = 0;
        long nextRecoveryCheckAt = 0L;
        boolean requestRestart = false;
        String restartReason = null;
        short[] silence = new short[pcm.length];

        while (running) {
            int read;
            try {
                read = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Capture read failed", ex);
                break;
            }
            long now = SystemClock.elapsedRealtime();
            refreshReplayPackageNameIfNeeded(now, false);
            if (read <= 0) {
                stalledReadCount++;
                if (!captureWaitingLogged) {
                    Log.i(TAG, "Capture read returned " + read + " while waiting for playback");
                    captureWaitingLogged = true;
                }
                boolean deadObject = read == AudioRecord.ERROR_DEAD_OBJECT;
                boolean stalledWhilePlaybackActive = stalledReadCount >= STALLED_READ_LIMIT
                        && (signaledLive || hasRecoverableActivePlayback());
                if (deadObject || stalledWhilePlaybackActive) {
                    requestRestart = true;
                    restartReason = "capture read stalled with code=" + read
                            + ", stalledReadCount=" + stalledReadCount;
                    running = false;
                    break;
                }
                if (now - lastSignalAt > currentConfig.monitorIntervalMs) {
                    publishStatus(waitingStatusText(), false);
                    signaledLive = false;
                }
                continue;
            }
            stalledReadCount = 0;

            float peak = 0f;
            for (int i = 0; i < read; i++) {
                samples[i] = pcm[i] / 32768f;
                float absolute = Math.abs(samples[i]);
                if (absolute > peak) {
                    peak = absolute;
                }
            }

            if (peak > 0f) {
                nonZeroReadCount++;
                if (nonZeroReadCount <= 5) {
                    Log.i(TAG, "Capture read non-zero frame readSamples=" + read + ", peak=" + peak);
                }
            }

            if (peak > SIGNAL_THRESHOLD) {
                long signalAt = SystemClock.elapsedRealtime();
                lastSignalAt = signalAt;
                lastCaptureSignalAtMs = signalAt;
                silentReadCount = 0;
                if (!captureSignalLogged) {
                    Log.i(TAG, "Capture loop detected signal: readSamples=" + read + ", peak=" + peak);
                    captureSignalLogged = true;
                }
                refreshReplayPackageNameIfNeeded(signalAt, !signaledLive);
                boolean replayAllowed = shouldOutputProcessedReplay();
                if (!signaledLive) {
                    publishStatus(replayAllowed ? monitoringStatusText() : replayBlockedStatusText(), true);
                    signaledLive = true;
                } else if (replayAllowed != publishedStatus.equals(monitoringStatusText())) {
                    publishStatus(replayAllowed ? monitoringStatusText() : replayBlockedStatusText(), true);
                }
            } else {
                silentReadCount++;
                if (silentReadCount == 1 || silentReadCount == 10 || silentReadCount == 30 || silentReadCount % 120 == 0) {
                    Log.i(TAG, "Capture read below threshold readSamples=" + read
                            + ", peak=" + peak
                            + ", silentReadCount=" + silentReadCount
                            + ", target=" + currentTargetLabel
                            + ", targetUid=" + currentTargetUid);
                }
                if (SystemClock.elapsedRealtime() - lastSignalAt > currentConfig.monitorIntervalMs && signaledLive) {
                    publishStatus(waitingStatusText(), false);
                    signaledLive = false;
                }
                if (now >= nextRecoveryCheckAt) {
                    nextRecoveryCheckAt = now + 450L;
                    restartReason = detectSilentCaptureStall(now, lastSignalAt);
                    if (restartReason != null) {
                        requestRestart = true;
                        running = false;
                        break;
                    }
                }
            }

            synchronized (dspLock) {
                dspProcessor.processInterleaved(samples, read / CHANNEL_COUNT);
            }
            for (int i = 0; i < read; i++) {
                float clamped = Math.max(-1f, Math.min(1f, samples[i]));
                rendered[i] = (short) Math.round(clamped * 32767f);
            }

            try {
                short[] outputBuffer = shouldOutputProcessedReplay() ? rendered : silence;
                if (!writeTrackFully(track, outputBuffer, read)) {
                    break;
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "Capture write failed", ex);
                running = false;
                break;
            }
        }

        finishCaptureLoop(workerToken, requestRestart, restartReason);
    }

    private void finishCaptureLoop(Object workerToken, boolean requestRestart, String restartReason) {
        if (workerToken == null || activeWorkerToken != workerToken) {
            return;
        }
        boolean restarted = false;
        synchronized (this) {
            if (activeWorkerToken != workerToken || workerThread != Thread.currentThread()) {
                return;
            }
            stopPipelineLocked();
            if (requestRestart) {
                restarted = restartPipelineLocked(restartReason);
            }
        }
        if (!restarted) {
            publishStatus("Native capture stopped. Re-authorize if the session was interrupted.", false);
        }
    }

    private void reconfigureEffectsLocked() {
        Preset effectiveDspPreset = AudioProcessingPolicy.effectiveDspPreset(
                currentPreset,
                currentMode,
                currentVirtualBassModeIndex);
        boolean enableDspBass = AudioProcessingPolicy.dspVirtualBassAllowed(
                currentMode,
                currentVirtualBassModeIndex);
        synchronized (dspLock) {
            dspProcessor.configure(
                    effectiveDspPreset,
                    SAMPLE_RATE,
                    CHANNEL_COUNT,
                    enableDspBass,
                    currentConfig);
        }
        applyTrackVirtualBassLocked();
    }

    private AudioAttributes buildTrackAttributesForCurrentMode() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void applyTrackVirtualBassLocked() {
        if (audioTrack == null) {
            releaseTrackVirtualBassLocked();
            return;
        }
        int systemBassAmountPercent = currentPreset == null ? 0 : currentPreset.systemVirtualBassAmountPercent;
        boolean enableSystemBass = AudioProcessingPolicy.systemVirtualBassAllowed(currentVirtualBassModeIndex)
                && systemBassAmountPercent > 0;
        if (!enableSystemBass) {
            releaseTrackVirtualBassLocked();
            return;
        }
        try {
            if (trackBassBoost == null) {
                trackBassBoost = new BassBoost(1000, audioTrack.getAudioSessionId());
            }
            trackBassBoost.setEnabled(false);
            trackBassBoost.setStrength((short) Math.max(0, Math.min(1000, systemBassAmountPercent * 10)));
            trackBassBoost.setEnabled(true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Track virtual bass effect failed", ex);
            releaseTrackVirtualBassLocked();
        }
    }

    private void releaseTrackVirtualBassLocked() {
        if (trackBassBoost == null) {
            return;
        }
        try {
            trackBassBoost.setEnabled(false);
            trackBassBoost.release();
        } catch (RuntimeException ignored) {
        } finally {
            trackBassBoost = null;
        }
    }

    private void stopPipelineLocked() {
        running = false;
        activeWorkerToken = null;
        lastCaptureSignalAtMs = 0L;
        lastReplayPackageRefreshAtMs = 0L;
        updateReplayPackageName("");

        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
        }

        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (RuntimeException ignored) {
            }
        }

        releaseTrackVirtualBassLocked();
        Thread thread = workerThread;
        workerThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.interrupt();
                thread.join(350L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (record != null) {
            try {
                record.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (track != null) {
            try {
                track.release();
            } catch (RuntimeException ignored) {
            }
        }

        configuredTargetUid = -1;
        configuredBufferFrames = -1;
        configuredChunkFrames = -1;
        configuredLatencyMs = -1;
        configuredOutputDeviceKey = "";
        configuredPreferredDeviceSignature = "none";
    }

    private void bindTrackToPreferredOutputLocked(AudioTrack track, AudioDeviceInfo preferredDevice) {
        if (track == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (preferredDevice == null) {
            Log.i(TAG, "AudioTrack preferred device not set; using system default route");
            return;
        }
        try {
            boolean applied = track.setPreferredDevice(preferredDevice);
            if (!applied) {
                Log.w(TAG, "AudioTrack preferred device was rejected: " + preferredDevice.getId());
            } else {
                Log.i(TAG, "AudioTrack preferred device applied: id=" + preferredDevice.getId()
                        + ", type=" + preferredDevice.getType()
                        + ", key=" + describeOutputDeviceKey(preferredDevice));
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to bind AudioTrack to preferred output device", ex);
        }
    }

    private AudioDeviceInfo resolvePreferredOutputDeviceInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        AudioDeviceInfo activeTargetDevice = resolveActiveTargetPlaybackDeviceInfo();
        if (activeTargetDevice != null) {
            return activeTargetDevice;
        }
        String desiredKey = safeDeviceKey(currentOutputDevice);
        if (desiredKey.isEmpty() || "none".equals(desiredKey)) {
            return null;
        }
        android.media.AudioManager audioManager =
                (android.media.AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSink()) {
                continue;
            }
            if (desiredKey.equals(describeOutputDeviceKey(device))) {
                return device;
            }
        }
        return null;
    }

    private int chooseProcessingChunkFrames(int desiredFrames,
                                            boolean heavyRealtimeDsp,
                                            boolean bluetoothOutput) {
        int upperBound = bluetoothOutput ? MAX_BLUETOOTH_CHUNK_FRAMES : MAX_PROCESSING_CHUNK_FRAMES;
        int chunkFrames = desiredFrames;
        if (chunkFrames > 2048) {
            chunkFrames = chunkFrames / 8;
        } else if (chunkFrames > 1024) {
            chunkFrames = chunkFrames / 4;
        } else if (chunkFrames > 512) {
            chunkFrames = chunkFrames / 2;
        }
        if (heavyRealtimeDsp) {
            chunkFrames = Math.min(chunkFrames, bluetoothOutput ? BLUETOOTH_HEAVY_DSP_CHUNK_FRAMES : 1024);
        }
        return Math.max(MIN_PROCESSING_CHUNK_FRAMES, Math.min(upperBound, chunkFrames));
    }

    private void prefillTrackIfNeeded(AudioTrack track, int chunkFrames, boolean bluetoothOutput) {
        if (track == null || chunkFrames <= 0) {
            return;
        }
        int prefillChunks = bluetoothOutput ? BLUETOOTH_PREFILL_CHUNKS : 1;
        short[] silence = new short[Math.max(MIN_PROCESSING_CHUNK_FRAMES, chunkFrames) * CHANNEL_COUNT];
        for (int i = 0; i < prefillChunks; i++) {
            if (!writeTrackFully(track, silence, silence.length)) {
                break;
            }
        }
    }

    private boolean writeTrackFully(AudioTrack track, short[] buffer, int sampleCount) {
        int written = 0;
        while (running && written < sampleCount) {
            int chunk = track.write(buffer, written, sampleCount - written, AudioTrack.WRITE_BLOCKING);
            if (chunk <= 0) {
                running = false;
                return false;
            }
            written += chunk;
        }
        return true;
    }

    private boolean isBluetoothOutput(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && type == AudioDeviceInfo.TYPE_BLE_BROADCAST);
    }

    private AudioDeviceInfo resolveActiveTargetPlaybackDeviceInfo() {
        if (audioManager == null || currentTargetUid <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                Log.i(TAG, "Target route candidate raw=" + summarizeConfig(configuration));
                if (configuration == null || readPlaybackClientUid(configuration) != currentTargetUid) {
                    continue;
                }
                if (!isRelevantActivePlayback(configuration)) {
                    continue;
                }
                AudioDeviceInfo device = readPlaybackDeviceInfo(configuration);
                if (device == null || !device.isSink()) {
                    continue;
                }
                Log.i(TAG, "Binding capture playback to target app route id=" + device.getId()
                        + " type=" + device.getType());
                return device;
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect active playback configurations", ex);
        }
        return null;
    }

    private String detectSilentCaptureStall(long now, long lastSignalAt) {
        long silentForMs = now - lastSignalAt;
        long recoveryThresholdMs = Math.max(
                ACTIVE_PLAYBACK_RECOVERY_MIN_MS,
                Math.max(currentConfig.monitorIntervalMs * 2L, currentConfig.latencyMs * 6L));
        if (silentForMs < recoveryThresholdMs) {
            return null;
        }
        String resolvedSignature =
                describeResolvedDeviceSignature(resolvePreferredOutputDeviceInfo());
        if (!resolvedSignature.equals(configuredPreferredDeviceSignature)) {
            return "preferred output changed from " + configuredPreferredDeviceSignature
                    + " to " + resolvedSignature;
        }
        if (!hasRecoverableActivePlayback()) {
            return null;
        }
        return "active playback is present but capture stayed silent for " + silentForMs + "ms";
    }

    private boolean hasRecoverableActivePlayback() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        try {
            for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                if (configuration == null || !isRelevantActivePlayback(configuration)) {
                    continue;
                }
                int clientUid = readPlaybackClientUid(configuration);
                if (clientUid == android.os.Process.myUid()) {
                    continue;
                }
                if (currentMode.capturesSystemAudio()) {
                    return true;
                }
                if (clientUid == currentTargetUid) {
                    return true;
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect active playback for capture recovery", ex);
        }
        return false;
    }

    private boolean restartPipelineLocked(String reason) {
        if (mediaProjection == null || !AudioProcessingPolicy.advancedModeEnabled(currentMode)) {
            return false;
        }
        if (currentPreset == null || !currentPreset.enabled) {
            return false;
        }
        if (!currentMode.capturesSystemAudio() && currentTargetUid <= 0) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAutoRestartAtMs < AUTO_RESTART_COOLDOWN_MS) {
            Log.i(TAG, "Skipping capture auto-restart during cooldown, reason=" + reason);
            return false;
        }
        lastAutoRestartAtMs = now;
        Log.i(TAG, "Auto-restarting capture pipeline, reason=" + reason);
        startPipelineLocked();
        return running;
    }

    private boolean shouldOutputProcessedReplay() {
        boolean allowed;
        if (!currentMode.requiresShizukuMute()) {
            allowed = true;
            traceReplayDecision("modeDoesNotRequireMute", "", "", "");
            return allowed;
        }
        if (repository.loadShizukuMuteActive()) {
            allowed = true;
            traceReplayDecision("muteActive", "", "", "");
            return allowed;
        }
        if (currentConfig.allowReplayWithoutMute) {
            allowed = true;
            traceReplayDecision("allowReplayWithoutMute", "", "", "");
            return allowed;
        }
        String mutedPackage = normalizePackageName(repository.loadActiveMutedPackage());
        if (mutedPackage.isEmpty()) {
            allowed = false;
            traceReplayDecision("mutedPackageEmpty", mutedPackage, "", "");
            return allowed;
        }
        String expectedReplayPackage = normalizePackageName(currentReplayPackageName);
        if (expectedReplayPackage.isEmpty()) {
            expectedReplayPackage = normalizePackageName(repository.loadActivePlaybackPackage());
        }
        if (expectedReplayPackage.isEmpty() && !currentMode.capturesSystemAudio()) {
            expectedReplayPackage = normalizePackageName(currentConfig.monitoredAppPackage);
        }
        allowed = packageListsIntersect(expectedReplayPackage, mutedPackage);
        traceReplayDecision("comparePackages", mutedPackage, expectedReplayPackage,
                normalizePackageName(currentConfig.monitoredAppPackage));
        return allowed;
    }

    private void refreshReplayPackageNameIfNeeded(long now, boolean forceRefresh) {
        if (!forceRefresh && now - lastReplayPackageRefreshAtMs < 900L) {
            return;
        }
        lastReplayPackageRefreshAtMs = now;
        updateReplayPackageName(resolveCurrentReplayPackageName());
    }

    private String resolveCurrentReplayPackageName() {
        String fallbackPackage = repository.loadActiveMutedPackage();
        if (fallbackPackage == null || fallbackPackage.trim().isEmpty()) {
            fallbackPackage = repository.loadActivePlaybackPackage();
        }
        if (!currentMode.capturesSystemAudio()) {
            String monitoredPackage = currentConfig.monitoredAppPackage == null ? "" : currentConfig.monitoredAppPackage.trim();
            return monitoredPackage.isEmpty() ? fallbackPackage : monitoredPackage;
        }
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return fallbackPackage == null ? "" : fallbackPackage.trim();
        }
        try {
            LinkedHashSet<String> candidatePackages = new LinkedHashSet<>();
            for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                if (configuration == null) {
                    continue;
                }
                boolean relevant = isRelevantActivePlayback(configuration);
                int clientUid = readPlaybackClientUid(configuration);
                int playerState = readPlaybackPlayerState(configuration);
                int usage = -1;
                AudioAttributes attributes = null;
                try {
                    attributes = configuration.getAudioAttributes();
                    usage = attributes == null ? -1 : attributes.getUsage();
                } catch (RuntimeException ignored) {
                }
                Log.d(TAG, "TRACE_SWITCH replayCandidate"
                        + " relevant=" + relevant
                        + " uid=" + clientUid
                        + " playerState=" + playerState
                        + " usage=" + usage
                        + " raw=" + summarizeConfig(configuration));
                if (!relevant) {
                    continue;
                }
                if (clientUid <= 0 || clientUid == android.os.Process.myUid()) {
                    continue;
                }
                if (attributes == null) {
                    continue;
                }
                if (usage != AudioAttributes.USAGE_MEDIA
                        && usage != AudioAttributes.USAGE_GAME
                        && usage != AudioAttributes.USAGE_UNKNOWN) {
                    continue;
                }
                String[] packages = packageManager.getPackagesForUid(clientUid);
                if (packages != null && packages.length > 0 && packages[0] != null) {
                    candidatePackages.add(packages[0].trim());
                    Log.d(TAG, "TRACE_SWITCH replayPackageChosen"
                            + " pkg=" + packages[0].trim()
                            + " uid=" + clientUid
                            + " fallback=" + fallbackPackage);
                }
            }
            String joinedPackages = joinPackageList(candidatePackages);
            if (!joinedPackages.isEmpty()) {
                return joinedPackages;
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to resolve replay package name", ex);
        }
        Log.d(TAG, "TRACE_SWITCH replayPackageFallback fallback=" + fallbackPackage);
        return fallbackPackage == null ? "" : fallbackPackage.trim();
    }

    private boolean isRelevantActivePlayback(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        if (!isPlaybackActive(configuration)) {
            return false;
        }
        try {
            AudioAttributes attributes = configuration.getAudioAttributes();
            if (attributes == null) {
                return false;
            }
            int usage = attributes.getUsage();
            return usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_GAME
                    || usage == AudioAttributes.USAGE_UNKNOWN;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to inspect replay playback attributes", ex);
            return false;
        }
    }

    private boolean isPlaybackActive(AudioPlaybackConfiguration configuration) {
        if (configuration == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        boolean activeByState = readPlaybackPlayerState(configuration) == PLAYER_STATE_STARTED;
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("isActive");
            Object value = method.invoke(configuration);
            if (value instanceof Boolean) {
                return (Boolean) value || activeByState;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#isActive", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback active state", ex);
        }
        return activeByState;
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
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "Unable to invoke AudioPlaybackConfiguration#getPlayerState", ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback player state", ex);
        }
        return -1;
    }

    private void updateReplayPackageName(String packageName) {
        String normalized = normalizePackageName(packageName);
        if (normalized.equals(currentReplayPackageName)) {
            return;
        }
        Log.d(TAG, "TRACE_SWITCH replayPackageUpdate from=" + currentReplayPackageName + " to=" + normalized);
        currentReplayPackageName = normalized;
        repository.saveActiveReplayPackage(normalized);
        if (notificationCallback != null) {
            mainHandler.post(notificationCallback);
        }
    }

    private void traceReplayDecision(String reason,
                                     String mutedPackage,
                                     String expectedReplayPackage,
                                     String monitoredPackage) {
        String trace = "reason=" + reason
                + ", allowed=" + computeReplayAllowedPreview(mutedPackage, expectedReplayPackage)
                + ", mode=" + currentMode
                + ", replayPkg=" + normalizePackageName(currentReplayPackageName)
                + ", mutedPkg=" + normalizePackageName(repository.loadActiveMutedPackage())
                + ", activePlaybackPkg=" + normalizePackageName(repository.loadActivePlaybackPackage())
                + ", expectedPkg=" + normalizePackageName(expectedReplayPackage)
                + ", monitoredPkg=" + normalizePackageName(monitoredPackage);
        if (trace.equals(lastReplayDecisionTrace)) {
            return;
        }
        lastReplayDecisionTrace = trace;
        Log.d(TAG, "TRACE_SWITCH replayDecision " + trace);
    }

    private boolean computeReplayAllowedPreview(String mutedPackage, String expectedReplayPackage) {
        if (!currentMode.requiresShizukuMute()) {
            return true;
        }
        if (repository.loadShizukuMuteActive()) {
            return true;
        }
        if (currentConfig.allowReplayWithoutMute) {
            return true;
        }
        return packageListsIntersect(expectedReplayPackage, mutedPackage);
    }

    private boolean packageListsIntersect(String leftPackages, String rightPackages) {
        LinkedHashSet<String> left = splitPackageList(leftPackages);
        LinkedHashSet<String> right = splitPackageList(rightPackages);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String packageName : left) {
            if (right.contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    private LinkedHashSet<String> splitPackageList(String packages) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = normalizePackageName(packages);
        if (normalized.isEmpty()) {
            return result;
        }
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String packageName = normalizePackageName(part);
            if (!packageName.isEmpty()) {
                result.add(packageName);
            }
        }
        return result;
    }

    private String joinPackageList(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            String normalized = normalizePackageName(packageName);
            if (normalized.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(normalized);
        }
        return builder.toString();
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
        return -1;
    }

    private String normalizePackageName(String packageName) {
        return packageName == null ? "" : packageName.trim();
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
        return -1;
    }

    private AudioDeviceInfo readPlaybackDeviceInfo(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = AudioPlaybackConfiguration.class.getMethod("getAudioDeviceInfo");
            Object value = method.invoke(configuration);
            if (value instanceof AudioDeviceInfo) {
                return (AudioDeviceInfo) value;
            }
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to read playback device info", ex);
        }
        return null;
    }

    private String describeOutputDeviceKey(AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        CharSequence productName = device.getProductName();
        String product = productName == null ? "" : productName.toString().trim();
        String keyProduct = product.isEmpty()
                ? "default"
                : product.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", "_");
        return device.getType() + ":" + keyProduct;
    }

    private String describeResolvedDeviceSignature(AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        return describeOutputDeviceKey(device) + "#" + device.getId();
    }

    private String safeDeviceKey(AudioOutputDevice outputDevice) {
        return outputDevice == null || outputDevice.key == null ? "" : outputDevice.key;
    }

    private void releaseProjectionLocked() {
        MediaProjection projection = mediaProjection;
        MediaProjection.Callback callback = projectionCallback;
        mediaProjection = null;
        projectionCallback = null;
        repository.saveMonitorCaptureAuthorized(false);
        if (projection == null) {
            return;
        }
        try {
            if (callback != null) {
                projection.unregisterCallback(callback);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void publishStatus(String status, boolean active) {
        String nextStatus = status == null || status.trim().isEmpty()
                ? "Native capture is idle."
                : status;
        if (nextStatus.equals(publishedStatus) && active == publishedActive) {
            return;
        }
        publishedStatus = nextStatus;
        publishedActive = active;
        repository.saveMonitorCaptureStatus(nextStatus, active);
        if (notificationCallback != null) {
            mainHandler.post(notificationCallback);
        }
    }

    private String summarizeConfig(AudioPlaybackConfiguration configuration) {
        if (configuration == null) {
            return "null";
        }
        String text = configuration.toString();
        if (text == null) {
            return "null";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
