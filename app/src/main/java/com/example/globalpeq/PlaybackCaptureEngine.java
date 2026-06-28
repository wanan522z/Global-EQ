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
    private static final int DEFAULT_ASSISTANT_STREAM = 11;
    private static final int EXPERIMENTAL_PLAYBACK_STREAM = resolveAssistantStream();
    private static final int EXPERIMENTAL_PLAYBACK_USAGE = AudioAttributes.USAGE_ASSISTANT;
    private static final int MIN_PIPELINE_BUFFER_FRAMES = 128;
    private static final int MIN_TRACK_LATENCY_MS = 40;
    private static final int MIN_PROCESSING_CHUNK_FRAMES = 256;
    private static final int MAX_PROCESSING_CHUNK_FRAMES = 2048;
    private static final int MAX_BLUETOOTH_CHUNK_FRAMES = 640;
    private static final int BLUETOOTH_HEAVY_DSP_CHUNK_FRAMES = 384;
    private static final int BLUETOOTH_PREFILL_CHUNKS = 4;
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
    private boolean captureSignalLogged;
    private boolean captureWaitingLogged;
    private String publishedStatus = "";
    private boolean publishedActive;

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
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
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
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
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
            publishStatus("Shizuku Mode ready. Enable EQ to start capture.", false);
            return;
        }
        if (currentMode != ProcessingMode.SHIZUKU_MUTE
                && currentConfig.monitoredAppPackage.isEmpty()) {
            stopPipelineLocked();
            publishStatus("Choose an app to monitor.", false);
            return;
        }
        if (mediaProjection == null) {
            stopPipelineLocked();
            publishStatus(currentMode == ProcessingMode.SHIZUKU_MUTE
                    ? "Authorize native capture for system audio."
                    : "Authorize native capture for " + currentTargetLabel + ".", false);
            return;
        }

        currentTargetUid = currentMode == ProcessingMode.SHIZUKU_MUTE
                ? -1
                : resolveTargetUid(currentConfig.monitoredAppPackage);
        if (currentMode != ProcessingMode.SHIZUKU_MUTE && currentTargetUid <= 0) {
            stopPipelineLocked();
            publishStatus("Unable to resolve the selected app.", false);
            return;
        }

        boolean outputRouteRestartRequired = currentMode != ProcessingMode.SHIZUKU_MUTE
                && !safeDeviceKey(currentOutputDevice).equals(configuredOutputDeviceKey);
        boolean requiresRestart = !running
                || configuredTargetUid != currentTargetUid
                || configuredBufferFrames != currentConfig.bufferSizeFrames
                || configuredLatencyMs != currentConfig.latencyMs
                || outputRouteRestartRequired;
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
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
            builder.excludeUid(android.os.Process.myUid());
        } else {
            builder.addMatchingUid(currentTargetUid);
        }
        return builder.build();
    }

    private String monitoringStatusText() {
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
            return "Monitoring system audio via native capture.";
        }
        return "Monitoring " + currentTargetLabel + " via native capture.";
    }

    private String waitingStatusText() {
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
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
        if (mediaProjection == null || (currentMode != ProcessingMode.SHIZUKU_MUTE && currentTargetUid <= 0)) {
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
            running = true;
            reconfigureEffectsLocked();

            audioTrack.play();
            Log.i(TAG, "AudioTrack playState after play()=" + audioTrack.getPlayState());
            prefillTrackIfNeeded(audioTrack, processingChunkFrames, bluetoothOutput);
            audioRecord.startRecording();
            Log.i(TAG, "AudioRecord recordingState after startRecording()=" + audioRecord.getRecordingState());
            Object workerToken = new Object();
            activeWorkerToken = workerToken;
            workerThread = new Thread(() -> runCaptureLoop(workerToken), "global-peq-capture");
            workerThread.start();
            Log.i(TAG, "Started experimental native capture route"
                    + " target=" + currentTargetLabel
                    + " trackUsage=MEDIA"
                    + " stream=" + EXPERIMENTAL_PLAYBACK_STREAM
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

        while (running) {
            int read;
            try {
                read = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Capture read failed", ex);
                break;
            }
            if (read <= 0) {
                if (!captureWaitingLogged) {
                    Log.i(TAG, "Capture read returned " + read + " while waiting for playback");
                    captureWaitingLogged = true;
                }
                if (SystemClock.elapsedRealtime() - lastSignalAt > currentConfig.monitorIntervalMs) {
                    publishStatus(waitingStatusText(), false);
                    signaledLive = false;
                }
                continue;
            }

            float peak = 0f;
            for (int i = 0; i < read; i++) {
                samples[i] = pcm[i] / 32768f;
                float absolute = Math.abs(samples[i]);
                if (absolute > peak) {
                    peak = absolute;
                }
            }

            if (peak > SIGNAL_THRESHOLD) {
                lastSignalAt = SystemClock.elapsedRealtime();
                if (!captureSignalLogged) {
                    Log.i(TAG, "Capture loop detected signal: readSamples=" + read + ", peak=" + peak);
                    captureSignalLogged = true;
                }
                if (!signaledLive) {
                    publishStatus(monitoringStatusText(), true);
                    signaledLive = true;
                }
            } else if (SystemClock.elapsedRealtime() - lastSignalAt > currentConfig.monitorIntervalMs && signaledLive) {
                publishStatus(waitingStatusText(), false);
                signaledLive = false;
            }

            synchronized (dspLock) {
                dspProcessor.processInterleaved(samples, read / CHANNEL_COUNT);
            }
            for (int i = 0; i < read; i++) {
                float clamped = Math.max(-1f, Math.min(1f, samples[i]));
                rendered[i] = (short) Math.round(clamped * 32767f);
            }

            try {
                if (!writeTrackFully(track, rendered, read)) {
                    break;
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "Capture write failed", ex);
                running = false;
                break;
            }
        }

        finishCaptureLoop(workerToken);
    }

    private void finishCaptureLoop(Object workerToken) {
        if (workerToken == null || activeWorkerToken != workerToken) {
            return;
        }
        synchronized (this) {
            if (activeWorkerToken != workerToken || workerThread != Thread.currentThread()) {
                return;
            }
            stopPipelineLocked();
        }
        publishStatus("Native capture stopped. Re-authorize if the session was interrupted.", false);
    }

    private void reconfigureEffectsLocked() {
        Preset effectiveDspPreset = AudioProcessingPolicy.effectiveDspPreset(
                currentPreset,
                currentMode,
                currentVirtualBassModeIndex);
        boolean enableDspBass = AudioProcessingPolicy.dspVirtualBassAllowed(
                currentMode,
                currentVirtualBassModeIndex);
        Log.i(TAG, "reconfigureEffects mode=" + currentMode.key
                + " modeIndex=" + currentVirtualBassModeIndex
                + " enableDspBass=" + enableDspBass
                + " activeAmount=" + (currentPreset == null ? -1 : currentPreset.virtualBassAmountPercent)
                + " systemAmount=" + (currentPreset == null ? -1 : currentPreset.systemVirtualBassAmountPercent)
                + " dspAmount=" + (currentPreset == null ? -1 : currentPreset.dspVirtualBassAmountPercent));
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
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        if (currentMode == ProcessingMode.SHIZUKU_MUTE) {
            builder.setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        } else {
            builder.setUsage(EXPERIMENTAL_PLAYBACK_USAGE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        }
        return builder.build();
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
                if (configuration == null || readPlaybackClientUid(configuration) != currentTargetUid) {
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

    private static int resolveAssistantStream() {
        try {
            java.lang.reflect.Field field = AudioManager.class.getField("STREAM_ASSISTANT");
            return field.getInt(null);
        } catch (ReflectiveOperationException ignored) {
            return DEFAULT_ASSISTANT_STREAM;
        }
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
}
