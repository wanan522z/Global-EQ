package com.example.globalpeq;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

    private final Context appContext;
    private final PackageManager packageManager;
    private final PresetRepository repository;
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

    private int configuredTargetUid = -1;
    private int configuredBufferFrames = -1;
    private int configuredLatencyMs = -1;

    private String publishedStatus = "";
    private boolean publishedActive;

    PlaybackCaptureEngine(Context context, PresetRepository repository) {
        this.appContext = context.getApplicationContext();
        this.packageManager = appContext.getPackageManager();
        this.repository = repository;
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
                                       int bassModeIndex) {
        currentPreset = preset == null ? Preset.flat(false) : preset;
        currentConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        currentBassModeIndex = bassModeIndex;
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
                || configuredLatencyMs != currentConfig.latencyMs;
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
            int trackBufferBytes = Math.max(minTrackBytes, latencyFrames * bytesPerFrame);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(trackFormat)
                    .setBufferSizeInBytes(trackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack failed to initialize");
            }

            configuredTargetUid = currentTargetUid;
            configuredBufferFrames = currentConfig.bufferSizeFrames;
            configuredLatencyMs = currentConfig.latencyMs;
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
            dspProcessor.configure(
                    currentPreset,
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
    }
}
