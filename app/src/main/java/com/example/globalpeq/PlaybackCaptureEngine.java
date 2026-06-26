package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
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

final class PlaybackCaptureEngine {
    private static final String TAG = "PlaybackCaptureEngine";
    private static final int CHANNEL_COUNT = 2;
    private static final int SAMPLE_RATE = 48000;
    private static final float SIGNAL_THRESHOLD = 0.0018f;
    private static final int EQ_PLAYBACK_STREAM = AudioManager.STREAM_ACCESSIBILITY;

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

    private volatile boolean running;
    private volatile Preset currentPreset = Preset.flat(false);
    private volatile AdvancedModeConfig currentConfig = AdvancedModeConfig.DEFAULT;
    private volatile int currentBassModeIndex;
    private volatile int currentTargetUid = -1;
    private volatile String currentTargetLabel = "";
    private volatile AudioOutputDevice currentOutputDevice = new AudioOutputDevice("none", "Output device");

    private int configuredTargetUid = -1;
    private int configuredBufferFrames = -1;
    private int configuredLatencyMs = -1;
    private String configuredOutputDeviceKey = "";
    private int savedMusicStreamVolume = -1;
    private int savedEqPlaybackStreamVolume = -1;
    private boolean sourceMutedForCapture;

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
            publishStatus("Native capture requires Android 10 or later.", false);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            publishStatus("Capture authorization was cancelled.", false);
            return;
        }

        MediaProjectionManager manager = appContext.getSystemService(MediaProjectionManager.class);
        if (manager == null) {
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
        publishStatus(currentTargetLabel.isEmpty()
                ? "Capture authorized. Choose an app to monitor."
                : "Capture authorized for " + currentTargetLabel + ".", false);
    }

    synchronized void updateProcessing(ProcessingMode mode,
                                       Preset preset,
                                       AdvancedModeConfig config,
                                       int bassModeIndex,
                                       AudioOutputDevice outputDevice) {
        currentPreset = preset == null ? Preset.flat(false) : preset;
        currentConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        currentBassModeIndex = bassModeIndex;
        currentOutputDevice = outputDevice == null ? new AudioOutputDevice("none", "Output device") : outputDevice;
        currentTargetLabel = currentConfig.monitoredAppLabel.isEmpty()
                ? currentConfig.monitoredAppPackage
                : currentConfig.monitoredAppLabel;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopPipelineLocked();
            publishStatus("Native capture requires Android 10 or later.", false);
            return;
        }
        if (mode != ProcessingMode.ADVANCED_DSP) {
            stopPipelineLocked();
            publishStatus("Default mode active. Native capture disabled.", false);
            return;
        }
        if (preset == null || !preset.enabled) {
            stopPipelineLocked();
            publishStatus("Monitor DSP ready. Enable EQ to start capture.", false);
            return;
        }
        if (currentConfig.monitoredAppPackage.isEmpty()) {
            stopPipelineLocked();
            publishStatus("Choose an app to monitor.", false);
            return;
        }
        if (mediaProjection == null) {
            stopPipelineLocked();
            publishStatus("Authorize native capture for " + currentTargetLabel + ".", false);
            return;
        }

        currentTargetUid = resolveTargetUid(currentConfig.monitoredAppPackage);
        if (currentTargetUid <= 0) {
            stopPipelineLocked();
            publishStatus("Unable to resolve the selected app.", false);
            return;
        }

        boolean requiresRestart = !running
                || configuredTargetUid != currentTargetUid
                || configuredBufferFrames != currentConfig.bufferSizeFrames
                || configuredLatencyMs != currentConfig.latencyMs
                || !safeDeviceKey(currentOutputDevice).equals(configuredOutputDeviceKey);
        if (requiresRestart) {
            startPipelineLocked();
        } else {
            reconfigureEffectsLocked();
            publishStatus("Monitoring " + currentTargetLabel + " via native capture.", true);
        }
    }

    synchronized boolean hasProjection() {
        return mediaProjection != null;
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

    private void startPipelineLocked() {
        stopPipelineLocked();
        if (mediaProjection == null || currentTargetUid <= 0) {
            publishStatus("Native capture is not authorized.", false);
            return;
        }

        try {
            AudioFormat recordFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioPlaybackCaptureConfiguration captureConfiguration =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUid(currentTargetUid)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .build();

            int bytesPerFrame = CHANNEL_COUNT * 2;
            int desiredFrames = Math.max(256, currentConfig.bufferSizeFrames);
            int minRecordBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minRecordBytes <= 0) {
                minRecordBytes = desiredFrames * bytesPerFrame * 2;
            }
            int recordBufferBytes = Math.max(minRecordBytes, desiredFrames * bytesPerFrame * 2);

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
            int latencyFrames = Math.max(desiredFrames, SAMPLE_RATE * Math.max(20, currentConfig.latencyMs) / 1000);
            if (minTrackBytes <= 0) {
                minTrackBytes = latencyFrames * bytesPerFrame;
            }
            int trackBufferBytes = Math.max(minTrackBytes, latencyFrames * bytesPerFrame);

            muteSourceAndLiftEqStreamLocked();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setLegacyStreamType(EQ_PLAYBACK_STREAM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(trackFormat)
                    .setBufferSizeInBytes(trackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack failed to initialize");
            }
            bindTrackToPreferredOutputLocked(audioTrack);

            configuredTargetUid = currentTargetUid;
            configuredBufferFrames = currentConfig.bufferSizeFrames;
            configuredLatencyMs = currentConfig.latencyMs;
            configuredOutputDeviceKey = safeDeviceKey(currentOutputDevice);
            running = true;
            reconfigureEffectsLocked();

            audioRecord.startRecording();
            audioTrack.play();
            workerThread = new Thread(this::runCaptureLoop, "global-peq-capture");
            workerThread.start();
            publishStatus("Monitoring " + currentTargetLabel + " via native capture. Mute the source app.", true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to start playback capture pipeline", ex);
            stopPipelineLocked();
            publishStatus("Failed to start native capture. Re-authorize and try again.", false);
        }
    }

    private void runCaptureLoop() {
        AudioRecord record = audioRecord;
        AudioTrack track = audioTrack;
        if (record == null || track == null) {
            return;
        }

        short[] pcm = new short[Math.max(512, configuredBufferFrames) * CHANNEL_COUNT];
        float[] samples = new float[pcm.length];
        short[] rendered = new short[pcm.length];
        long lastSignalAt = SystemClock.elapsedRealtime();
        boolean signaledLive = false;

        while (running) {
            int read;
            try {
                read = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Capture read failed", ex);
                break;
            }
            if (read <= 0) {
                if (SystemClock.elapsedRealtime() - lastSignalAt > currentConfig.monitorIntervalMs) {
                    publishStatus("Armed for " + currentTargetLabel + " - waiting for playback.", false);
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
                if (!signaledLive) {
                    publishStatus("Monitoring " + currentTargetLabel + " via native capture. Mute the source app.", true);
                    signaledLive = true;
                }
            } else if (SystemClock.elapsedRealtime() - lastSignalAt > currentConfig.monitorIntervalMs && signaledLive) {
                publishStatus("Armed for " + currentTargetLabel + " - waiting for playback.", false);
                signaledLive = false;
            }

            synchronized (dspLock) {
                dspProcessor.processInterleaved(samples, read / CHANNEL_COUNT);
            }
            for (int i = 0; i < read; i++) {
                float clamped = Math.max(-1f, Math.min(1f, samples[i]));
                rendered[i] = (short) Math.round(clamped * 32767f);
            }

            int written = 0;
            while (running && written < read) {
                int chunk;
                try {
                    chunk = track.write(rendered, written, read - written, AudioTrack.WRITE_BLOCKING);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "Capture write failed", ex);
                    running = false;
                    break;
                }
                if (chunk <= 0) {
                    break;
                }
                written += chunk;
            }
        }

        synchronized (this) {
            stopPipelineLocked();
        }
        publishStatus("Native capture stopped. Re-authorize if the session was interrupted.", false);
    }

    private void reconfigureEffectsLocked() {
        synchronized (dspLock) {
            Preset effectiveDspPreset = AudioProcessingPolicy.effectiveDspPreset(
                    currentPreset,
                    ProcessingMode.ADVANCED_DSP,
                    currentBassModeIndex);
            dspProcessor.configure(
                    effectiveDspPreset,
                    SAMPLE_RATE,
                    CHANNEL_COUNT,
                    AudioProcessingPolicy.dspBassAllowed(ProcessingMode.ADVANCED_DSP, currentBassModeIndex),
                    currentConfig);
        }
        applyTrackBassBoostLocked();
    }

    private void applyTrackBassBoostLocked() {
        if (audioTrack == null) {
            releaseTrackBassBoostLocked();
            return;
        }
        boolean enableSystemBass = currentBassModeIndex == 1 && currentPreset.systemBassBoostPercent > 0;
        if (!enableSystemBass) {
            releaseTrackBassBoostLocked();
            return;
        }
        try {
            if (trackBassBoost == null) {
                trackBassBoost = new BassBoost(1000, audioTrack.getAudioSessionId());
            }
            trackBassBoost.setEnabled(false);
            trackBassBoost.setStrength((short) Math.max(0, Math.min(1000, currentPreset.systemBassBoostPercent * 10)));
            trackBassBoost.setEnabled(true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Track BassBoost failed", ex);
            releaseTrackBassBoostLocked();
        }
    }

    private void releaseTrackBassBoostLocked() {
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

        releaseTrackBassBoostLocked();
        restoreSourceAndEqStreamsLocked();

        Thread thread = workerThread;
        workerThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
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
        configuredLatencyMs = -1;
        configuredOutputDeviceKey = "";
    }

    private void muteSourceAndLiftEqStreamLocked() {
        if (audioManager == null || sourceMutedForCapture) {
            return;
        }
        try {
            savedMusicStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            savedEqPlaybackStreamVolume = audioManager.getStreamVolume(EQ_PLAYBACK_STREAM);

            int musicMax = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int eqMax = Math.max(1, audioManager.getStreamMaxVolume(EQ_PLAYBACK_STREAM));
            int desiredEqVolume = Math.max(
                    savedEqPlaybackStreamVolume,
                    Math.max(1, Math.round((savedMusicStreamVolume / (float) musicMax) * eqMax))
            );
            desiredEqVolume = Math.max(0, Math.min(eqMax, desiredEqVolume));

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            if (desiredEqVolume != savedEqPlaybackStreamVolume) {
                audioManager.setStreamVolume(EQ_PLAYBACK_STREAM, desiredEqVolume, 0);
            }
            sourceMutedForCapture = true;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to remap stream volumes for capture playback", ex);
            savedMusicStreamVolume = -1;
            savedEqPlaybackStreamVolume = -1;
            sourceMutedForCapture = false;
        }
    }

    private void restoreSourceAndEqStreamsLocked() {
        if (audioManager == null || !sourceMutedForCapture) {
            return;
        }
        try {
            if (savedMusicStreamVolume >= 0) {
                int musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        Math.max(0, Math.min(savedMusicStreamVolume, musicMax)),
                        0
                );
            }
            if (savedEqPlaybackStreamVolume >= 0) {
                int eqMax = audioManager.getStreamMaxVolume(EQ_PLAYBACK_STREAM);
                audioManager.setStreamVolume(
                        EQ_PLAYBACK_STREAM,
                        Math.max(0, Math.min(savedEqPlaybackStreamVolume, eqMax)),
                        0
                );
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to restore stream volumes after capture playback", ex);
        } finally {
            savedMusicStreamVolume = -1;
            savedEqPlaybackStreamVolume = -1;
            sourceMutedForCapture = false;
        }
    }

    private void bindTrackToPreferredOutputLocked(AudioTrack track) {
        if (track == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        AudioDeviceInfo preferredDevice = resolvePreferredOutputDeviceInfo();
        if (preferredDevice == null) {
            return;
        }
        try {
            boolean applied = track.setPreferredDevice(preferredDevice);
            if (!applied) {
                Log.w(TAG, "AudioTrack preferred device was rejected: " + preferredDevice.getId());
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Unable to bind AudioTrack to preferred output device", ex);
        }
    }

    private AudioDeviceInfo resolvePreferredOutputDeviceInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
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
