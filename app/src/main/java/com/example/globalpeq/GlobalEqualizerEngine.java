package com.example.globalpeq;

import android.media.audiofx.BassBoost;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

final class GlobalEqualizerEngine {
    private static final String TAG = "GlobalEqualizerEngine";
    private static final int GLOBAL_AUDIO_SESSION = 0;
    // Keep the same high-priority session-0 arbitration used by the reference path.
    private static final int AUDIO_EFFECT_PRIORITY = 1337;
    private static final int DYNAMICS_CHANNEL_COUNT = 2;
    private static final int[] DEFAULT_DYNAMICS_BAND_COUNT_CANDIDATES = {32, 24, 16, 10};
    private static final int[] GLOBAL_DSP_BAND_COUNT_CANDIDATES = {128, 96, 64, 48, 32, 24, 16, 10};
    private static final int[] DVC_GLOBAL_DSP_BAND_COUNT_CANDIDATES = {
            300, 256, 128, 96, 64, 48, 32, 24, 16, 10
    };
    // Poweramp's API 29+ DynamicsProcessing model clamps the generated response to -24..+30 dB.
    // Individual UI filters remain limited by ParametricBand; this wider range only preserves the
    // combined response of overlapping filters instead of truncating it at +/-18 dB.
    private static final int DYNAMICS_MIN_LEVEL_MB = -2400;
    private static final int DYNAMICS_MAX_LEVEL_MB = 3000;
    private static final int EXTRA_BASS_MAX_GAIN_MB = 1500;
    private static final float DVC_LIMITER_RATIO = 50f;
    private static final float DVC_LIMITER_THRESHOLD_DB = 15f;
    private static final float DVC_MAX_VOLUME_POSITIVE_GAIN_DB = 2f;
    private static final float DVC_INPUT_MIN_DB = -96f;
    private static final float DYNAMICS_LIMITER_RATIO = 20f;
    private static final long ARM_DELAY_MS = 120;
    private static final long CONTROL_REARM_DELAY_MS = 180;
    private static final long CONTROL_REARM_GUARD_MS = 1000;
    private static final long ROUTE_REAPPLY_DELAY_MS = 220;
    private static final long ROUTE_REAPPLY_GUARD_MS = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DynamicsProcessing dynamicsProcessing;
    private PowerampDynamicsProcessing powerampDynamicsProcessing;
    private DynamicsProcessing.Eq dynamicsPreEq;
    private DynamicsProcessing.Eq dynamicsPostEq;
    private int dynamicsPreEqBandCount;
    private float[] dynamicsBandCenterHz = new float[0];
    private float dynamicsPreferredFrameDurationMs;
    private boolean dynamicsLimiterConfigured;
    private boolean dynamicsProcessingUnavailable;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private int bassBoostAudioSessionId = GLOBAL_AUDIO_SESSION;
    private short minLevelMb = -1800;
    private short maxLevelMb = 1800;
    private Preset pendingPreset;
    private Preset lastAppliedPreset;
    private AdvancedModeConfig lastAppliedDynamicsConfig;
    private boolean armedWithZeroBands;
    private boolean targetApplyPending;
    private int applyGeneration;
    private long lastControlRearmElapsedMs;
    private long lastRouteReapplyElapsedMs;
    private AdvancedModeConfig dynamicsConfig = AdvancedModeConfig.DEFAULT;
    private ProcessingMode processingMode = ProcessingMode.SYSTEM_EQ;
    private boolean dvcActive;
    private float dvcDownstreamHeadroomDb;
    private float dvcMappedPeakGainDb;
    private float dvcSafetyAttenuationDb;
    private int dynamicsAudioSessionId = GLOBAL_AUDIO_SESSION;
    private float[] powerampAppliedGainsDb = new float[0];
    private String powerampBackendFailure = "";

    private enum ApplyStrategy {
        AUTO,
        FORCE_FULL_RESET
    }

    synchronized boolean start() {
        if (dynamicsProcessing != null || powerampDynamicsProcessing != null || equalizer != null) {
            return true;
        }

        if (!dynamicsProcessingUnavailable && startDynamicsProcessing()) {
            return true;
        }
        return startLegacyEqualizer();
    }

    private boolean startDynamicsProcessing() {
        int[] bandCountCandidates = processingMode == ProcessingMode.GLOBAL_DSP
                ? (dvcActive
                ? DVC_GLOBAL_DSP_BAND_COUNT_CANDIDATES
                : GLOBAL_DSP_BAND_COUNT_CANDIDATES)
                : DEFAULT_DYNAMICS_BAND_COUNT_CANDIDATES;
        return startDynamicsProcessing(bandCountCandidates, true);
    }

    private boolean startDynamicsProcessing(int[] bandCountCandidates,
                                            boolean markUnavailableOnFailure) {
        RuntimeException lastFailure = null;
        for (int bandCount : bandCountCandidates) {
            DynamicsProcessing candidate = null;
            try {
                // Poweramp model P=0 is the preferred 300-band single post-EQ bank. Its P=1/P=2
                // compatibility models (256/64 bands) are the ones split across pre/post stages.
                boolean splitDvcBank = dvcActive
                        && processingMode == ProcessingMode.GLOBAL_DSP
                        && (bandCount == 256 || bandCount == 64);
                int preEqBandCount = splitDvcBank ? bandCount / 2 : 0;
                int postEqBandCount = bandCount - preEqBandCount;
                DynamicsProcessing.Config.Builder configBuilder =
                        new DynamicsProcessing.Config.Builder(
                        0,
                        DYNAMICS_CHANNEL_COUNT,
                        splitDvcBank,
                        preEqBandCount,
                        false,
                        0,
                        true,
                        postEqBandCount,
                        true
                );
                float preferredFrameDurationMs = processingMode == ProcessingMode.GLOBAL_DSP
                        ? PowerampDynamicsProcessing.resolvePreferredFrameDurationMs()
                        : 0f;
                if (preferredFrameDurationMs > 0f) {
                    configBuilder.setPreferredFrameDuration(preferredFrameDurationMs);
                }
                DynamicsProcessing.Config config = configBuilder.build();
                candidate = new DynamicsProcessing(
                        AUDIO_EFFECT_PRIORITY,
                        dynamicsAudioSessionId,
                        config);
                float[] centerFrequencies = dvcActive
                        && processingMode == ProcessingMode.GLOBAL_DSP
                        && bandCount == 300
                        ? createPoweramp300BandCenters()
                        : createLogBandCenters(
                        bandCount,
                        processingMode == ProcessingMode.GLOBAL_DSP && bandCount >= 48
                                ? 10.0
                                : 20.0);
                DynamicsProcessing.Eq preEq = splitDvcBank
                        ? new DynamicsProcessing.Eq(true, true, preEqBandCount)
                        : null;
                DynamicsProcessing.Eq postEq = new DynamicsProcessing.Eq(
                        true,
                        true,
                        postEqBandCount);
                for (int band = 0; band < preEqBandCount; band++) {
                    DynamicsProcessing.EqBand eqBand = preEq.getBand(band);
                    eqBand.setEnabled(true);
                    eqBand.setCutoffFrequency(centerFrequencies[band]);
                    eqBand.setGain(0f);
                }
                for (int band = 0; band < postEqBandCount; band++) {
                    DynamicsProcessing.EqBand eqBand = postEq.getBand(band);
                    eqBand.setEnabled(true);
                    eqBand.setCutoffFrequency(centerFrequencies[preEqBandCount + band]);
                    eqBand.setGain(0f);
                }
                candidate.setInputGainAllChannelsTo(0f);
                if (preEq != null) {
                    candidate.setPreEqAllChannelsTo(preEq);
                }
                candidate.setPostEqAllChannelsTo(postEq);
                candidate.setLimiterAllChannelsTo(createLimiter(dynamicsConfig, 0f));
                candidate.setEnabled(false);
                candidate.setControlStatusListener(this::onControlStatusChanged);

                dynamicsProcessing = candidate;
                dynamicsPreEq = preEq;
                dynamicsPostEq = postEq;
                dynamicsPreEqBandCount = preEqBandCount;
                dynamicsBandCenterHz = centerFrequencies;
                dynamicsPreferredFrameDurationMs = config.getPreferredFrameDuration();
                dynamicsLimiterConfigured = true;
                minLevelMb = DYNAMICS_MIN_LEVEL_MB;
                maxLevelMb = DYNAMICS_MAX_LEVEL_MB;
                armedWithZeroBands = false;
                Log.i(TAG, "Using DynamicsProcessing on audio session "
                        + dynamicsAudioSessionId + " with "
                        + (splitDvcBank
                        ? preEqBandCount + "+" + postEqBandCount + " pre/post-EQ bands"
                        : postEqBandCount + " post-EQ bands"));
                return true;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }

        if (markUnavailableOnFailure) {
            dynamicsProcessingUnavailable = true;
        }
        if (lastFailure != null) {
            if (markUnavailableOnFailure) {
                Log.w(TAG, "DynamicsProcessing unavailable; falling back to legacy Equalizer", lastFailure);
            } else {
                Log.d(TAG, "DynamicsProcessing candidate was rejected", lastFailure);
            }
        }
        return false;
    }

    private boolean startLegacyEqualizer() {
        try {
            equalizer = new Equalizer(AUDIO_EFFECT_PRIORITY, GLOBAL_AUDIO_SESSION);
            short[] range = equalizer.getBandLevelRange();
            if (range != null && range.length >= 2) {
                minLevelMb = range[0];
                maxLevelMb = range[1];
            }
            equalizer.setEnabled(false);
            equalizer.setControlStatusListener(this::onControlStatusChanged);
            resetBands(equalizer.getNumberOfBands());
            armedWithZeroBands = false;
            return true;
        } catch (RuntimeException ex) {
            Log.w(TAG, "Global equalizer session could not be created", ex);
            equalizer = null;
            armedWithZeroBands = false;
            return false;
        }
    }

    private static float[] createLogBandCenters(int bandCount, double minHz) {
        int safeCount = Math.max(1, bandCount);
        float[] frequencies = new float[safeCount];
        double maxHz = 20000.0;
        for (int band = 0; band < safeCount; band++) {
            double position = safeCount == 1 ? 0.0 : band / (double) (safeCount - 1);
            float frequency = (float) (minHz * Math.pow(maxHz / minHz, position));
            // EqBand cutoff is a float. Preserve the sub-Hz log spacing used by the 300-band DVC
            // bank instead of quantizing the lowest octaves into a coarse integer-Hz staircase.
            frequencies[band] = band == 0
                    ? frequency
                    : Math.max(Math.nextUp(frequencies[band - 1]), frequency);
        }
        return frequencies;
    }

    private static float[] createPoweramp300BandCenters() {
        final int bandCount = 300;
        final float maximumFrequencyHz = 20000f;
        final float firstOctaveStartHz = 200f;
        float[] frequencies = new float[bandCount];
        float[] lowFrequencyGrid = {
                10f, 15f, 20f, 25f, 30f, 35f, 40f, 45f, 50f, 56f, 63f,
                76f, 80f, 100f, 112.5f, 125f, 148f, 160f, 172f, 185f, 200f
        };
        System.arraycopy(lowFrequencyGrid, 0, frequencies, 0, lowFrequencyGrid.length);

        // Match sa0.B's grid construction. It counts complete octaves and the remaining part of
        // the last octave linearly, rather than using log2(20_000 / 200). Poweramp deliberately
        // bases the density on 19 reserved slots although the P=0 low grid contains 21 points.
        float octaveEndHz = firstOctaveStartHz * 2f;
        float previousOctaveEndHz = firstOctaveStartHz;
        float octaveSpan = 0f;
        while (octaveEndHz <= maximumFrequencyHz) {
            octaveSpan += 1f;
            previousOctaveEndHz = octaveEndHz;
            octaveEndHz *= 2f;
        }
        octaveSpan += 1f - (octaveEndHz - maximumFrequencyHz)
                / (octaveEndHz - previousOctaveEndHz);
        float bandsPerOctave = (bandCount - 19) / octaveSpan;

        int index = lowFrequencyGrid.length;
        float frequencyHz = firstOctaveStartHz;
        float intervalStartHz = firstOctaveStartHz;
        float intervalEndHz = firstOctaveStartHz * 2f;
        float lastStepHz = 0f;
        while (index < bandCount) {
            lastStepHz = (intervalEndHz - intervalStartHz) / bandsPerOctave;
            while (frequencyHz < intervalEndHz && index < bandCount) {
                frequencyHz += lastStepHz;
                frequencies[index++] = frequencyHz;
            }
            intervalStartHz = intervalEndHz;
            intervalEndHz *= 2f;
        }

        // sa0.B pulls the final point close to 20 kHz when the two extra low-frequency border
        // points make the generated sequence finish more than half a step below the ceiling.
        int lastBand = bandCount - 1;
        if (maximumFrequencyHz - frequencies[lastBand] > lastStepHz / 2f) {
            frequencies[lastBand] = maximumFrequencyHz - lastStepHz / 2f;
        }
        return frequencies;
    }

    private DynamicsProcessing.Limiter createLimiter(AdvancedModeConfig config,
                                                      float postGainDb) {
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        float attackMs = configuredLimiterAttackMs(safeConfig);
        if (dvcActive && processingMode == ProcessingMode.GLOBAL_DSP) {
            // Use real downstream attenuation when it exceeds the explicit maximum-volume test
            // allowance. Toward maximum volume, retain up to +2 dB for distortion testing while
            // still respecting the configured limiter-ceiling margin.
            return new DynamicsProcessing.Limiter(
                    safeConfig.limiterEnabled,
                    true,
                    0,
                    attackMs,
                    safeConfig.limiterReleaseMs,
                    DVC_LIMITER_RATIO,
                    dvcLimiterThresholdDb(config),
                    postGainDb);
        }
        return new DynamicsProcessing.Limiter(
                safeConfig.limiterEnabled,
                true,
                0,
                attackMs,
                safeConfig.limiterReleaseMs,
                DYNAMICS_LIMITER_RATIO,
                normalLimiterThresholdDb(safeConfig),
                postGainDb);
    }

    private static float configuredLimiterAttackMs(AdvancedModeConfig config) {
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        return safeConfig.limiterAttackMs <= 0 ? 0.000001f : safeConfig.limiterAttackMs;
    }

    private static float normalLimiterThresholdDb(AdvancedModeConfig config) {
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        float ceiling = Math.max(0.001f, safeConfig.limiterCeilingPermille / 1000f);
        return (float) (20.0 * Math.log10(ceiling));
    }

    private float dvcLimiterThresholdDb(AdvancedModeConfig config) {
        float availablePeakDb = Math.max(
                DVC_MAX_VOLUME_POSITIVE_GAIN_DB,
                dvcDownstreamHeadroomDb) + normalLimiterThresholdDb(config);
        return Math.min(DVC_LIMITER_THRESHOLD_DB, availablePeakDb);
    }

    boolean supportsDvcSessionPlacement() {
        return processingMode == ProcessingMode.GLOBAL_DSP
                && (dynamicsProcessing != null || powerampDynamicsProcessing != null);
    }

    synchronized boolean isDvcModeActive() {
        return dvcActive;
    }

    synchronized void setDvcDownstreamHeadroomDb(float headroomDb) {
        float nextHeadroomDb = Float.isFinite(headroomDb)
                ? clamp(headroomDb, 0f, 96f)
                : 0f;
        if (Math.abs(nextHeadroomDb - dvcDownstreamHeadroomDb) < 0.05f) {
            return;
        }
        dvcDownstreamHeadroomDb = nextHeadroomDb;
        if (!dvcActive) {
            return;
        }
        Preset targetPreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        if (targetPreset == null || !targetPreset.enabled) {
            return;
        }
        try {
            // A volume change only affects available headroom. Keep the EQ bank intact and update
            // the DVC input safety gain plus its final peak limiter.
            dynamicsLimiterConfigured = false;
            if (powerampDynamicsProcessing != null) {
                refreshPowerampDvcControls(targetPreset);
            } else if (dynamicsProcessing != null) {
                ensureFrameworkLimiterConfigured();
                applyAndVerifyPreEqInputGain(targetPreset);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not update DVC headroom for media volume", error);
        }
    }

    /**
     * Moves the full GlobalDSP EQ between output-mix session 0 and a player session.
     * In DVC mode the player-session EQ runs before Android's stream-volume attenuation, making
     * that unchanged downstream attenuation real post-EQ headroom.
     */
    synchronized boolean setDvcModeEnabled(boolean active, int requestedAudioSessionId) {
        if (processingMode != ProcessingMode.GLOBAL_DSP
                || (dynamicsProcessing == null && powerampDynamicsProcessing == null)) {
            if (!active) {
                dvcActive = false;
                dynamicsAudioSessionId = GLOBAL_AUDIO_SESSION;
                return true;
            }
            return false;
        }
        Preset targetPreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        if (targetPreset == null || !targetPreset.enabled) {
            if (!active) {
                // There is no enabled preset worth rebuilding on session 0. Releasing here keeps
                // the real effect attachment and the recorded session state consistent.
                release();
                return true;
            }
            return false;
        }
        int targetAudioSessionId = active ? requestedAudioSessionId : GLOBAL_AUDIO_SESSION;
        if (active && targetAudioSessionId < GLOBAL_AUDIO_SESSION) {
            return false;
        }
        boolean previousActive = dvcActive;
        int previousAudioSessionId = dynamicsAudioSessionId;

        if (!active
                && previousActive
                && previousAudioSessionId == GLOBAL_AUDIO_SESSION
                && dynamicsProcessing != null) {
            try {
                // A session-0 fallback already occupies the normal GlobalDSP attachment point.
                // Reuse it and atomically replace the DVC response/limiter instead of releasing
                // and racing AudioFlinger to create another session-0 effect.
                dvcActive = false;
                dynamicsLimiterConfigured = false;
                applyDynamicsTargetLevels(targetPreset);
                lastAppliedPreset = targetPreset;
                lastAppliedDynamicsConfig = dynamicsConfig;
                armedWithZeroBands = true;
                targetApplyPending = false;
                Log.i(TAG, "Disabled session-0 DVC in place");
                return true;
            } catch (RuntimeException error) {
                Log.w(TAG, "In-place session-0 DVC disable failed; rebuilding", error);
                dvcActive = previousActive;
            }
        }

        if (active != previousActive || targetAudioSessionId != previousAudioSessionId) {
            return rebuildDynamicsProcessingForDvc(
                    active,
                    targetAudioSessionId,
                    targetPreset,
                    previousActive,
                    previousAudioSessionId);
        }

        try {
            dvcActive = active;
            if (powerampDynamicsProcessing != null) {
                refreshPowerampDvcControls(targetPreset);
                Log.d(TAG, "Poweramp raw DVC on for player session "
                        + dynamicsAudioSessionId);
                return true;
            }
            applyAndVerifyPreEqInputGain(targetPreset);
            DynamicsProcessing.Limiter limiter = ensureFrameworkLimiterConfigured();
            float expectedThresholdDb = limiter.getThreshold();
            for (int channel = 0; channel < DYNAMICS_CHANNEL_COUNT; channel++) {
                DynamicsProcessing.Limiter appliedLimiter =
                        dynamicsProcessing.getLimiterByChannelIndex(channel);
                if (appliedLimiter.isEnabled() != dynamicsConfig.limiterEnabled
                        || Math.abs(appliedLimiter.getThreshold() - expectedThresholdDb) >= 0.1f) {
                    throw new IllegalStateException(
                            "Limiter configuration rejected for channel " + channel);
                }
            }
            Log.d(TAG, active
                    ? "Global DVC on for player session " + dynamicsAudioSessionId
                    : "Global DVC off on session 0");
            return true;
        } catch (RuntimeException error) {
            dvcActive = previousActive;
            try {
                if (powerampDynamicsProcessing != null) {
                    refreshPowerampDvcControls(targetPreset);
                } else if (dynamicsProcessing != null) {
                    ensureFrameworkLimiterConfigured();
                    applyAndVerifyPreEqInputGain(targetPreset);
                }
            } catch (RuntimeException ignored) {
            }
            Log.w(TAG, "Failed to switch the safe global DVC pipeline", error);
            return false;
        }
    }

    private boolean rebuildDynamicsProcessingForDvc(boolean active,
                                                    int targetAudioSessionId,
                                                    Preset targetPreset,
                                                    boolean previousActive,
                                                    int previousAudioSessionId) {
        Preset savedPendingPreset = pendingPreset;
        Preset savedLastAppliedPreset = lastAppliedPreset;
        AdvancedModeConfig savedLastAppliedConfig = lastAppliedDynamicsConfig;

        applyGeneration++;
        handler.removeCallbacksAndMessages(null);
        releaseDynamicsProcessing();
        dvcActive = active;
        dynamicsAudioSessionId = targetAudioSessionId;
        dynamicsProcessingUnavailable = false;

        try {
            boolean started = active
                    ? startAndApplyDvcBackend(targetPreset)
                    : startAndApplyDynamicsCandidates(
                    GLOBAL_DSP_BAND_COUNT_CANDIDATES,
                    targetPreset);
            if (!started) {
                throw new IllegalStateException("No DynamicsProcessing configuration accepted");
            }
            lastAppliedPreset = targetPreset;
            lastAppliedDynamicsConfig = dynamicsConfig;
            armedWithZeroBands = true;
            targetApplyPending = false;
            Log.i(TAG, "Rebuilt GlobalDSP DVC=" + active
                    + " session=" + dynamicsAudioSessionId
                    + " with " + describeActiveDynamicsBank());
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to rebuild the GlobalDSP DVC pipeline", error);
            releaseDynamicsProcessing();
            dynamicsProcessingUnavailable = false;
            pendingPreset = savedPendingPreset;
            lastAppliedPreset = savedLastAppliedPreset;
            lastAppliedDynamicsConfig = savedLastAppliedConfig;
            if (!active) {
                // Turning DVC off is fail-safe. Never restore a boosted player-session/session-0
                // DVC bank after reporting the switch off. If framework DP cannot be recreated,
                // fall back to Android's ordinary output-mix Equalizer instead.
                dvcActive = false;
                dynamicsAudioSessionId = GLOBAL_AUDIO_SESSION;
                dynamicsProcessingUnavailable = true;
                try {
                    if (!startLegacyEqualizer()) {
                        throw new IllegalStateException("Legacy Equalizer is unavailable");
                    }
                    applyTargetLevels(targetPreset);
                    lastAppliedPreset = targetPreset;
                    lastAppliedDynamicsConfig = dynamicsConfig;
                    armedWithZeroBands = true;
                    targetApplyPending = false;
                    Log.i(TAG, "DVC off fell back to the ordinary global Equalizer");
                    return true;
                } catch (RuntimeException fallbackError) {
                    Log.e(TAG, "Could not create a non-DVC fallback", fallbackError);
                    release();
                    return false;
                }
            }
            dvcActive = previousActive;
            dynamicsAudioSessionId = previousAudioSessionId;
            try {
                Preset restorePreset = savedPendingPreset != null
                        ? savedPendingPreset
                        : savedLastAppliedPreset;
                boolean restored = restorePreset != null
                        && restorePreset.enabled
                        && (previousActive
                        ? startAndApplyDvcBackend(restorePreset)
                        : startAndApplyDynamicsCandidates(
                        GLOBAL_DSP_BAND_COUNT_CANDIDATES,
                        restorePreset));
                if (!restored) {
                    throw new IllegalStateException("No previous DynamicsProcessing configuration accepted");
                }
                armedWithZeroBands = true;
                targetApplyPending = false;
            } catch (RuntimeException restoreError) {
                Log.e(TAG, "Could not restore the previous GlobalDSP pipeline", restoreError);
                releaseDynamicsProcessing();
                dynamicsProcessingUnavailable = true;
            }
            return false;
        }
    }

    private boolean startAndApplyDvcBackend(Preset preset) {
        if (PowerampDynamicsProcessing.isRawCommandApiAvailable()) {
            if (startAndApplyPowerampDvc(preset)) {
                return true;
            }
            Log.w(TAG, "Poweramp raw DVC backend unavailable; using framework DP: "
                    + powerampBackendFailure);
        } else {
            // Poweramp reaches AudioEffect.command through its private JNI bridge. Reflection on
            // the SDK AudioEffect class cannot provide that method, so do not create and tear down
            // a temporary DP effect before starting the working framework backend.
            powerampBackendFailure = "JNI raw command bridge unavailable";
        }
        return startAndApplyDynamicsCandidates(DVC_GLOBAL_DSP_BAND_COUNT_CANDIDATES, preset);
    }

    private boolean startAndApplyPowerampDvc(Preset preset) {
        releaseDynamicsProcessing();
        try {
            int bandCount = DVC_GLOBAL_DSP_BAND_COUNT_CANDIDATES[0];
            dynamicsPreEqBandCount = 0;
            dynamicsBandCenterHz = createPoweramp300BandCenters();
            dynamicsPreferredFrameDurationMs =
                    PowerampDynamicsProcessing.resolvePreferredFrameDurationMs();
            powerampDynamicsProcessing = new PowerampDynamicsProcessing(
                    dynamicsAudioSessionId,
                    dynamicsPreEqBandCount,
                    bandCount - dynamicsPreEqBandCount);
            powerampAppliedGainsDb = new float[bandCount];
            minLevelMb = DYNAMICS_MIN_LEVEL_MB;
            maxLevelMb = DYNAMICS_MAX_LEVEL_MB;
            pendingPreset = preset;
            applyPowerampDvcTargetLevels(preset);
            powerampBackendFailure = "";
            return true;
        } catch (RuntimeException error) {
            powerampBackendFailure = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            Log.w(TAG, "Poweramp raw DVC candidate failed", error);
            releaseDynamicsProcessing();
            return false;
        }
    }

    private boolean startAndApplyDynamicsCandidates(int[] candidates, Preset preset) {
        for (int bandCount : candidates) {
            releaseDynamicsProcessing();
            dynamicsProcessingUnavailable = false;
            if (!startDynamicsProcessing(new int[]{bandCount}, false)) {
                continue;
            }
            try {
                pendingPreset = preset;
                applyDynamicsTargetLevels(preset);
                applySystemVirtualBass(preset);
                return true;
            } catch (RuntimeException candidateError) {
                Log.w(TAG, "DynamicsProcessing " + bandCount
                        + "-band configuration failed during apply", candidateError);
            }
        }
        releaseDynamicsProcessing();
        dynamicsProcessingUnavailable = true;
        return false;
    }

    int getActiveDynamicsAudioSessionId() {
        if (powerampDynamicsProcessing != null) {
            return powerampDynamicsProcessing.getAudioSessionId();
        }
        return dynamicsProcessing == null ? -1 : dynamicsAudioSessionId;
    }

    String describeActiveDynamicsBank() {
        if (powerampDynamicsProcessing != null) {
            int postEqBandCount = dynamicsBandCenterHz.length - dynamicsPreEqBandCount;
            return dynamicsPreEqBandCount > 0
                    ? "Poweramp raw UUID, " + dynamicsPreEqBandCount + "+"
                    + postEqBandCount + " pre/post bands"
                    : "Poweramp raw UUID, " + postEqBandCount + " post-EQ bands";
        }
        if (dynamicsProcessing == null) {
            return "unavailable";
        }
        int postEqBandCount = dynamicsBandCenterHz.length - dynamicsPreEqBandCount;
        String bank = dynamicsPreEqBandCount > 0
                ? dynamicsPreEqBandCount + "+" + postEqBandCount + " pre/post bands"
                : postEqBandCount + " post-EQ bands";
        return String.format(
                Locale.US,
                "framework DP, %s, frame %.2f ms",
                bank,
                dynamicsPreferredFrameDurationMs);
    }

    String describeDvcReadback() {
        Preset activePreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        if (powerampDynamicsProcessing != null) {
            float inputGainDb = targetPreEqInputGainDb(activePreset);
            float maxLowGainDb = 0f;
            float maxGainDb = 0f;
            for (int band = 0; band < powerampAppliedGainsDb.length; band++) {
                float gainDb = powerampAppliedGainsDb[band];
                maxGainDb = Math.max(maxGainDb, gainDb);
                if (band < dynamicsBandCenterHz.length && dynamicsBandCenterHz[band] <= 80f) {
                    maxLowGainDb = Math.max(maxLowGainDb, gainDb);
                }
            }
            return String.format(
                    Locale.US,
                    "DVC raw command/reply accepted: session %d, channels %d, input %.1f dB, <=80 Hz max %.1f dB, all-band max %.1f dB, downstream headroom %.1f dB, safety attenuation %.1f dB, limiter %s @ %+.1f dB, attack %.3f ms, release %d ms",
                    dynamicsAudioSessionId,
                    powerampDynamicsProcessing.getChannelCount(),
                    inputGainDb,
                    maxLowGainDb,
                    maxGainDb,
                    dvcDownstreamHeadroomDb,
                    dvcSafetyAttenuationDb,
                    dynamicsConfig.limiterEnabled ? "enabled" : "bypassed",
                    dvcLimiterThresholdDb(dynamicsConfig),
                    configuredLimiterAttackMs(dynamicsConfig),
                    dynamicsConfig.limiterReleaseMs);
        }
        if (dynamicsProcessing == null) {
            return powerampBackendFailure.isEmpty()
                    ? "DVC readback: DynamicsProcessing unavailable"
                    : "DVC raw backend failed: " + powerampBackendFailure;
        }
        try {
            DynamicsProcessing.Eq appliedPreEq = dynamicsPreEqBandCount > 0
                    ? dynamicsProcessing.getPreEqByChannelIndex(0)
                    : null;
            DynamicsProcessing.Eq appliedPostEq = dynamicsProcessing.getPostEqByChannelIndex(0);
            int bandCount = dynamicsBandCenterHz.length;
            float firstCutoffHz = bandCount > 0
                    ? appliedDynamicsBandAt(appliedPreEq, appliedPostEq, 0)
                    .getCutoffFrequency()
                    : 0f;
            float maxLowGainDb = -Float.MAX_VALUE;
            float maxGainDb = -Float.MAX_VALUE;
            for (int band = 0; band < bandCount; band++) {
                DynamicsProcessing.EqBand appliedBand = appliedDynamicsBandAt(
                        appliedPreEq,
                        appliedPostEq,
                        band);
                float gainDb = appliedBand.getGain();
                maxGainDb = Math.max(maxGainDb, gainDb);
                if (appliedBand.getCutoffFrequency() <= 80f) {
                    maxLowGainDb = Math.max(maxLowGainDb, gainDb);
                }
            }
            if (maxLowGainDb == -Float.MAX_VALUE) {
                maxLowGainDb = 0f;
            }
            if (maxGainDb == -Float.MAX_VALUE) {
                maxGainDb = 0f;
            }
            float inputGainDb = dynamicsProcessing.getInputGainByChannelIndex(0);
            DynamicsProcessing.Limiter appliedLimiter =
                    dynamicsProcessing.getLimiterByChannelIndex(0);
            boolean limiterEnabled = appliedLimiter.isEnabled();
            return String.format(
                    Locale.US,
                        "DVC readback: session %d, input %.1f dB, <=80 Hz max %.1f dB, all-band max %.1f dB, downstream headroom %.1f dB, safety attenuation %.1f dB, first %.2f Hz, limiter %s @ %+.1f dB, release %.0f ms",
                    dynamicsAudioSessionId,
                        inputGainDb,
                        maxLowGainDb,
                        maxGainDb,
                        dvcDownstreamHeadroomDb,
                        dvcSafetyAttenuationDb,
                        firstCutoffHz,
                        limiterEnabled ? "enabled" : "bypassed",
                        appliedLimiter.getThreshold(),
                        appliedLimiter.getReleaseTime());
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read back the DVC pipeline", error);
            return "DVC readback: unavailable (" + error.getClass().getSimpleName() + ")";
        }
    }

    private void applyAndVerifyPreEqInputGain(Preset preset) {
        float targetGainDb = targetPreEqInputGainDb(preset);
        dynamicsProcessing.setInputGainAllChannelsTo(targetGainDb);
        for (int channel = 0; channel < DYNAMICS_CHANNEL_COUNT; channel++) {
            float appliedGainDb = dynamicsProcessing.getInputGainByChannelIndex(channel);
            if (Math.abs(appliedGainDb - targetGainDb) >= 0.1f) {
                dynamicsProcessing.setInputGainbyChannel(channel, targetGainDb);
                appliedGainDb = dynamicsProcessing.getInputGainByChannelIndex(channel);
            }
            if (Math.abs(appliedGainDb - targetGainDb) >= 0.1f) {
                throw new IllegalStateException(
                        "DVC input-gain write rejected for channel " + channel);
            }
        }
        Log.d(TAG, "GlobalDSP pre-EQ input gain=" + targetGainDb + " dB");
    }

    private float targetPreEqInputGainDb(Preset preset) {
        float presetGainDb = presetPregainDb(preset);
        if (!dvcActive || processingMode != ProcessingMode.GLOBAL_DSP) {
            dvcSafetyAttenuationDb = 0f;
            return clamp(
                    presetGainDb,
                    DYNAMICS_MIN_LEVEL_MB / 100f,
                    DYNAMICS_MAX_LEVEL_MB / 100f);
        }
        float safePeakDb = dvcLimiterThresholdDb(dynamicsConfig);
        // Negative pregain is an explicit user attenuation and must remain audible. Including it
        // in the automatic headroom calculation made the safety attenuation shrink by the same
        // amount, cancelling every downward pregain adjustment while the DVC peak cap was active.
        // Positive pregain still consumes the safety budget and is capped when no headroom remains.
        float safetyPregainDb = Math.max(0f, presetGainDb);
        float unprotectedPeakDb = safetyPregainDb + dvcMappedPeakGainDb;
        dvcSafetyAttenuationDb = Math.max(0f, unprotectedPeakDb - safePeakDb);
        return clamp(
                presetGainDb - dvcSafetyAttenuationDb,
                DVC_INPUT_MIN_DB,
                DYNAMICS_MAX_LEVEL_MB / 100f);
    }

    private static float presetPregainDb(Preset preset) {
        if (preset == null) {
            return 0f;
        }
        return clamp(
                preset.pregainMb / 100f,
                DYNAMICS_MIN_LEVEL_MB / 100f,
                DYNAMICS_MAX_LEVEL_MB / 100f);
    }

    void apply(Preset preset) {
        apply(preset, AdvancedModeConfig.DEFAULT);
    }

    void apply(Preset preset, AdvancedModeConfig config) {
        apply(preset, ProcessingMode.SYSTEM_EQ, config);
    }

    synchronized void apply(Preset preset, ProcessingMode mode, AdvancedModeConfig config) {
        selectProcessingMode(mode);
        updateDynamicsConfig(config);
        applyInternal(preset, ApplyStrategy.AUTO);
    }

    void applyWithFullReset(Preset preset) {
        applyWithFullReset(preset, AdvancedModeConfig.DEFAULT);
    }

    void applyWithFullReset(Preset preset, AdvancedModeConfig config) {
        applyWithFullReset(preset, ProcessingMode.SYSTEM_EQ, config);
    }

    synchronized void applyWithFullReset(Preset preset,
                                         ProcessingMode mode,
                                         AdvancedModeConfig config) {
        selectProcessingMode(mode);
        updateDynamicsConfig(config);
        applyInternal(preset, ApplyStrategy.FORCE_FULL_RESET);
    }

    private void applyInternal(Preset preset, ApplyStrategy strategy) {
        applyGeneration++;
        if (preset == null || !start()) {
            return;
        }

        if (!preset.enabled) {
            pendingPreset = null;
            release();
            return;
        }

        pendingPreset = preset;
        try {
            if (canSkipApply(preset, strategy)) {
                lastAppliedPreset = preset;
                return;
            }
            if (usesAtomicDvcBankApply()) {
                // DVC must never disable or clear the live DP bank during an edit. Submit the
                // complete replacement bank directly so a single-band toggle cannot expose a
                // short bypass/flat interval before the 120 ms staged write.
                applyTargetLevels(preset);
                return;
            }
            if (strategy == ApplyStrategy.FORCE_FULL_RESET || shouldStageThroughZero(preset)) {
                armWithZeroBands();
                scheduleTargetApply();
                return;
            }
            if (shouldStageRaisedBands(preset)) {
                armRaisedBands(lastAppliedPreset, preset);
                scheduleTargetApply();
                return;
            }
            applyTargetLevels(preset);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Failed to apply global preset", ex);
        }
    }

    void reapplyStaged(Preset preset) {
        reapplyStaged(preset, processingMode, dynamicsConfig);
    }

    synchronized void reapplyStaged(Preset preset,
                                    ProcessingMode mode,
                                    AdvancedModeConfig config) {
        selectProcessingMode(mode);
        updateDynamicsConfig(config);
        applyGeneration++;
        if (preset == null || !preset.enabled || !start()) {
            return;
        }

        pendingPreset = preset;
        try {
            if (usesAtomicDvcBankApply()) {
                applyTargetLevels(preset);
                return;
            }
            armWithZeroBands();
            scheduleTargetApply();
        } catch (RuntimeException ex) {
            Log.w(TAG, "Failed to staged reapply global preset", ex);
        }
    }

    void reapplyForRouteChange(Preset preset) {
        reapplyForRouteChange(preset, dynamicsConfig);
    }

    void reapplyForRouteChange(Preset preset, AdvancedModeConfig config) {
        reapplyForRouteChange(preset, processingMode, config);
    }

    synchronized void reapplyForRouteChange(Preset preset,
                                            ProcessingMode mode,
                                            AdvancedModeConfig config) {
        if (preset == null || !preset.enabled) {
            return;
        }
        selectProcessingMode(mode);
        updateDynamicsConfig(config);

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRouteReapplyElapsedMs < ROUTE_REAPPLY_GUARD_MS) {
            pendingPreset = preset;
            return;
        }
        lastRouteReapplyElapsedMs = now;

        int generation = ++applyGeneration;
        pendingPreset = preset;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (generation == applyGeneration && pendingPreset != null && pendingPreset.enabled) {
                reapplyStaged(pendingPreset);
            }
        }, ROUTE_REAPPLY_DELAY_MS);
    }

    private synchronized void onControlStatusChanged(AudioEffect effect, boolean controlGranted) {
        if (!controlGranted || pendingPreset == null || !pendingPreset.enabled) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastControlRearmElapsedMs < CONTROL_REARM_GUARD_MS) {
            return;
        }
        lastControlRearmElapsedMs = now;

        int generation = ++applyGeneration;
        handler.postDelayed(() -> {
            if (generation == applyGeneration && pendingPreset != null && pendingPreset.enabled) {
                reapplyStaged(pendingPreset, processingMode, dynamicsConfig);
            }
        }, CONTROL_REARM_DELAY_MS);
    }

    synchronized void setEnabled(boolean enabled) {
        if (!enabled) {
            release();
            return;
        }
        if (pendingPreset != null) {
            apply(pendingPreset.withEnabled(true), dynamicsConfig);
        }
    }

    int getBandCount() {
        if (!start()) {
            return Preset.DEFAULT_FILTER_COUNT;
        }

        try {
            if (dynamicsProcessing != null || powerampDynamicsProcessing != null) {
                return dynamicsBandCenterHz.length;
            }
            return equalizer.getNumberOfBands();
        } catch (RuntimeException ex) {
            return Preset.DEFAULT_FILTER_COUNT;
        }
    }

    int getBandCenterHz(int band) {
        if (!start()) {
            return 0;
        }

        try {
            if (dynamicsProcessing != null || powerampDynamicsProcessing != null) {
                return band >= 0 && band < dynamicsBandCenterHz.length
                        ? Math.round(dynamicsBandCenterHz[band])
                        : 0;
            }
            return equalizer.getCenterFreq((short) band) / 1000;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    int getMinLevelMb() {
        start();
        return minLevelMb;
    }

    int getMaxLevelMb() {
        start();
        return maxLevelMb;
    }

    private void armWithZeroBands() {
        if (dynamicsProcessing != null) {
            dynamicsProcessing.setEnabled(false);
            resetDynamicsBands();
            dynamicsProcessing.setEnabled(true);
            armedWithZeroBands = true;
            targetApplyPending = true;
            return;
        }
        short bandCount = equalizer.getNumberOfBands();
        equalizer.setEnabled(false);
        resetBands(bandCount);
        equalizer.setEnabled(true);
        armedWithZeroBands = true;
        targetApplyPending = true;
    }

    private void scheduleTargetApply() {
        int generation = applyGeneration;
        handler.postDelayed(() -> {
            if (generation == applyGeneration && pendingPreset != null && pendingPreset.enabled) {
                applyTargetLevels(pendingPreset);
            }
        }, ARM_DELAY_MS);
    }

    private void armRaisedBands(Preset before, Preset after) {
        if (!hasActiveEffect() || before == null || after == null) {
            return;
        }
        if (dynamicsProcessing != null) {
            for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
                float centerHz = dynamicsBandCenterHz[band];
                int beforeLevel = targetDynamicsLevelMb(centerHz, before);
                int afterLevel = targetDynamicsLevelMb(centerHz, after);
                if (afterLevel > 0 && afterLevel > beforeLevel) {
                    configuredDynamicsBandAt(band).setGain(0f);
                }
            }
            if (dynamicsPreEq != null) {
                dynamicsProcessing.setPreEqAllChannelsTo(dynamicsPreEq);
            }
            dynamicsProcessing.setPostEqAllChannelsTo(dynamicsPostEq);
            armedWithZeroBands = true;
            targetApplyPending = true;
            return;
        }
        short bandCount = equalizer.getNumberOfBands();
        for (short band = 0; band < bandCount; band++) {
            int beforeLevel = targetLevelMb(band, before);
            int afterLevel = targetLevelMb(band, after);
            if (afterLevel > 0 && afterLevel > beforeLevel) {
                equalizer.setBandLevel(band, (short) 0);
            }
        }
        armedWithZeroBands = true;
        targetApplyPending = true;
    }

    private boolean usesAtomicDvcBankApply() {
        return dvcActive
                && processingMode == ProcessingMode.GLOBAL_DSP
                && (dynamicsProcessing != null || powerampDynamicsProcessing != null);
    }

    private DynamicsProcessing.Limiter ensureFrameworkLimiterConfigured() {
        if (dynamicsProcessing == null) {
            throw new IllegalStateException("DynamicsProcessing is unavailable");
        }
        DynamicsProcessing.Limiter limiter = createLimiter(dynamicsConfig, 0f);
        // Rewriting an active limiter clears its gain-reduction envelope. In DVC that produces a
        // short loud surge followed by re-compression whenever an EQ band is edited.
        if (!dvcActive || !dynamicsLimiterConfigured) {
            dynamicsProcessing.setLimiterAllChannelsTo(limiter);
            dynamicsLimiterConfigured = true;
        }
        return limiter;
    }

    private synchronized void applyTargetLevels(Preset preset) {
        if (!hasActiveEffect() || preset == null || !preset.enabled) {
            return;
        }

        try {
            if (powerampDynamicsProcessing != null) {
                applyPowerampDvcTargetLevels(preset);
                applySystemVirtualBass(preset);
                lastAppliedPreset = preset;
                lastAppliedDynamicsConfig = dynamicsConfig;
                targetApplyPending = false;
                return;
            }
            if (dynamicsProcessing != null) {
                applyDynamicsTargetLevels(preset);
                applySystemVirtualBass(preset);
                lastAppliedPreset = preset;
                lastAppliedDynamicsConfig = dynamicsConfig;
                targetApplyPending = false;
                return;
            }
            short bandCount = equalizer.getNumberOfBands();
            boolean hasActiveGain = false;
            for (short band = 0; band < bandCount; band++) {
                int clamped = targetLevelMb(band, preset);
                if (clamped != 0) {
                    hasActiveGain = true;
                }
                equalizer.setBandLevel(band, (short) clamped);
            }
            if (!hasActiveGain) {
                resetBands(bandCount);
            }
            applySystemVirtualBass(preset);
            lastAppliedPreset = preset;
            lastAppliedDynamicsConfig = dynamicsConfig;
            targetApplyPending = false;
        } catch (RuntimeException ex) {
            if (powerampDynamicsProcessing != null) {
                Log.w(TAG, "Poweramp raw DynamicsProcessing apply failed", ex);
                return;
            }
            if (dynamicsProcessing != null) {
                Log.w(TAG, "DynamicsProcessing apply failed; switching to legacy Equalizer", ex);
                releaseDynamicsProcessing();
                dynamicsProcessingUnavailable = true;
                if (startLegacyEqualizer()) {
                    applyTargetLevels(preset);
                }
                return;
            }
            Log.w(TAG, "Failed to write target EQ levels", ex);
        }
    }

    private boolean shouldStageThroughZero(Preset preset) {
        if (!hasActiveEffect() || !armedWithZeroBands) {
            return true;
        }
        try {
            if (!isActiveEffectEnabled()) {
                return true;
            }
        } catch (RuntimeException ex) {
            return true;
        }
        if (lastAppliedPreset == null) {
            return hasAnyEqGain(preset);
        }
        if (!hasAnyEqGain(lastAppliedPreset) && hasAnyEqGain(preset)) {
            return true;
        }
        return eqBandSwitchStateChanged(lastAppliedPreset, preset);
    }

    private boolean shouldStageRaisedBands(Preset preset) {
        if (!hasActiveEffect() || preset == null || lastAppliedPreset == null) {
            return false;
        }
        try {
            if (!isActiveEffectEnabled()) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        if (eqBandSwitchStateChanged(lastAppliedPreset, preset)) {
            return false;
        }
        return hasRaisedPositiveEqGain(lastAppliedPreset, preset);
    }

    private boolean canSkipApply(Preset preset, ApplyStrategy strategy) {
        if (strategy != ApplyStrategy.AUTO
                || targetApplyPending
                || !hasActiveEffect()
                || preset == null
                || lastAppliedPreset == null) {
            return false;
        }
        try {
            if (!isActiveEffectEnabled()) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        return samePresetState(lastAppliedPreset, preset)
                && sameLimiterConfig(lastAppliedDynamicsConfig, dynamicsConfig);
    }

    private boolean hasAnyEqGain(Preset preset) {
        if (preset == null) {
            return false;
        }
        int bandCount = activeBandCount();
        for (int band = 0; band < bandCount; band++) {
            try {
                int centerHz = activeBandCenterHz(band);
                int levelMb = dynamicsProcessing != null || powerampDynamicsProcessing != null
                        ? targetDynamicsLevelMb(centerHz, preset) + preset.pregainMb
                        : PeqMath.gainAtHzMb(centerHz, preset);
                if (levelMb != 0) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRaisedPositiveEqGain(Preset before, Preset after) {
        int bandCount = activeBandCount();
        for (int band = 0; band < bandCount; band++) {
            int beforeLevel = activeTargetLevelMb(band, before);
            int afterLevel = activeTargetLevelMb(band, after);
            if (afterLevel > 0 && afterLevel > beforeLevel) {
                return true;
            }
        }
        return false;
    }

    private void applyDynamicsTargetLevels(Preset preset) {
        PeqMath.PreparedResponse response = PeqMath.prepareResponse(preset);
        PeqMath.PreparedBandResponse extraBassResponse = prepareExtraBassResponse(preset);
        float mappedPeakGainDb = 0f;
        for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
            DynamicsProcessing.EqBand eqBand = configuredDynamicsBandAt(band);
            eqBand.setEnabled(true);
            eqBand.setCutoffFrequency(dynamicsBandCenterHz[band]);
            float targetGainDb = targetDynamicsLevelMb(
                    dynamicsBandCenterHz[band],
                    preset,
                    response,
                    extraBassResponse) / 100f;
            eqBand.setGain(targetGainDb);
            mappedPeakGainDb = Math.max(mappedPeakGainDb, targetGainDb);
        }
        dvcMappedPeakGainDb = dvcActive ? mappedPeakGainDb : 0f;
        if (dynamicsPreEq != null) {
            dynamicsProcessing.setPreEqAllChannelsTo(dynamicsPreEq);
        }
        // DynamicsProcessing copies this configuration into the native effect. Reusing the same
        // Java bank avoids allocating 300 EqBand objects per edit while retaining one atomic
        // native submission (individual band setters can expose a partially updated response).
        dynamicsProcessing.setPostEqAllChannelsTo(dynamicsPostEq);
        // Preserve the complete EQ shape, but use only the positive peak range that the current
        // downstream stream-volume attenuation can safely absorb.
        ensureFrameworkLimiterConfigured();
        applyAndVerifyPreEqInputGain(preset);
        if (!dynamicsProcessing.getEnabled()) {
            dynamicsProcessing.setEnabled(true);
        }
    }

    private void applyPowerampDvcTargetLevels(Preset preset) {
        if (powerampDynamicsProcessing == null || preset == null) {
            throw new IllegalStateException("Poweramp raw DVC is unavailable");
        }
        if (powerampAppliedGainsDb.length != dynamicsBandCenterHz.length) {
            powerampAppliedGainsDb = new float[dynamicsBandCenterHz.length];
        }
        PeqMath.PreparedResponse response = PeqMath.prepareResponse(preset);
        PeqMath.PreparedBandResponse extraBassResponse = prepareExtraBassResponse(preset);
        float mappedPeakGainDb = 0f;
        for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
            float targetGainDb = targetDynamicsLevelMb(
                            dynamicsBandCenterHz[band],
                            preset,
                            response,
                            extraBassResponse) / 100f;
            powerampAppliedGainsDb[band] = targetGainDb;
            mappedPeakGainDb = Math.max(mappedPeakGainDb, targetGainDb);
        }
        dvcMappedPeakGainDb = dvcActive ? mappedPeakGainDb : 0f;
        powerampDynamicsProcessing.setEq(dynamicsBandCenterHz, powerampAppliedGainsDb);
        refreshPowerampDvcControls(preset);
    }

    private void refreshPowerampDvcControls(Preset preset) {
        if (powerampDynamicsProcessing == null || preset == null) {
            throw new IllegalStateException("Poweramp raw DVC is unavailable");
        }
        if (!dynamicsLimiterConfigured) {
            powerampDynamicsProcessing.setLimiter(
                    dynamicsConfig.limiterEnabled,
                    configuredLimiterAttackMs(dynamicsConfig),
                    dynamicsConfig.limiterReleaseMs,
                    DVC_LIMITER_RATIO,
                    dvcLimiterThresholdDb(dynamicsConfig),
                    0f);
            dynamicsLimiterConfigured = true;
        }
        // PARAM_INPUT_GAIN is the DP input stage, before both pre-EQ and post-EQ banks.
        powerampDynamicsProcessing.setInputGain(targetPreEqInputGainDb(preset));
        if (!powerampDynamicsProcessing.getEnabled()) {
            powerampDynamicsProcessing.setEnabled(true);
        }
    }

    private void resetDynamicsBands() {
        if (dynamicsProcessing == null || dynamicsPostEq == null) {
            return;
        }
        for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
            DynamicsProcessing.EqBand eqBand = configuredDynamicsBandAt(band);
            eqBand.setEnabled(true);
            eqBand.setCutoffFrequency(dynamicsBandCenterHz[band]);
            eqBand.setGain(0f);
        }
        if (dynamicsPreEq != null) {
            dynamicsProcessing.setPreEqAllChannelsTo(dynamicsPreEq);
        }
        dynamicsProcessing.setPostEqAllChannelsTo(dynamicsPostEq);
        Preset targetPreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        ensureFrameworkLimiterConfigured();
        if (targetPreset != null) {
            applyAndVerifyPreEqInputGain(targetPreset);
        } else {
            dynamicsProcessing.setInputGainAllChannelsTo(0f);
        }
    }

    private DynamicsProcessing.EqBand configuredDynamicsBandAt(int band) {
        if (band < 0 || band >= dynamicsBandCenterHz.length) {
            throw new IndexOutOfBoundsException("DynamicsProcessing band " + band);
        }
        if (band < dynamicsPreEqBandCount) {
            return dynamicsPreEq.getBand(band);
        }
        return dynamicsPostEq.getBand(band - dynamicsPreEqBandCount);
    }

    private DynamicsProcessing.EqBand appliedDynamicsBandAt(DynamicsProcessing.Eq appliedPreEq,
                                                             DynamicsProcessing.Eq appliedPostEq,
                                                             int band) {
        if (band < dynamicsPreEqBandCount) {
            if (appliedPreEq == null) {
                throw new IllegalStateException("DVC pre-EQ readback is unavailable");
            }
            return appliedPreEq.getBand(band);
        }
        return appliedPostEq.getBand(band - dynamicsPreEqBandCount);
    }

    private int targetDynamicsLevelMb(double frequencyHz, Preset preset) {
        if (preset == null) {
            return 0;
        }
        boolean powerampDvcResponse = dvcActive
                && processingMode == ProcessingMode.GLOBAL_DSP;
        int levelMb = powerampDvcResponse
                ? PeqMath.powerampDvcGainAtFrequencyMb(frequencyHz, preset)
                : PeqMath.gainAtFrequencyMb(frequencyHz, preset) - preset.pregainMb;
        if (preset.extraBassEnabled && preset.extraBassAmountPercent > 0) {
            int extraBassGainMb = Math.round(
                    preset.extraBassAmountPercent / 100f * EXTRA_BASS_MAX_GAIN_MB);
            ParametricBand extraBassBand = new ParametricBand(
                    FilterType.LOW_SHELF,
                    true,
                    preset.extraBassCutoffHz,
                    extraBassGainMb,
                    70);
            int extraBassResponseMb = PeqMath.bandGainAtHzMb(frequencyHz, extraBassBand);
            if (powerampDvcResponse) {
                extraBassResponseMb = PeqMath.powerampDvcDeadBandMb(
                        extraBassResponseMb,
                        extraBassGainMb);
            }
            levelMb += extraBassResponseMb;
        }
        return Math.max(DYNAMICS_MIN_LEVEL_MB, Math.min(DYNAMICS_MAX_LEVEL_MB, levelMb));
    }

    private int targetDynamicsLevelMb(double frequencyHz,
                                      Preset preset,
                                      PeqMath.PreparedResponse response,
                                      PeqMath.PreparedBandResponse extraBassResponse) {
        boolean powerampDvcResponse = dvcActive
                && processingMode == ProcessingMode.GLOBAL_DSP;
        int levelMb = powerampDvcResponse
                ? response.powerampDvcGainAtFrequencyMb(frequencyHz)
                : response.eqGainAtFrequencyMb(frequencyHz);
        if (extraBassResponse != null) {
            int extraBassGainMb = Math.round(
                    preset.extraBassAmountPercent / 100f * EXTRA_BASS_MAX_GAIN_MB);
            int extraBassResponseMb = extraBassResponse.gainAtFrequencyMb(frequencyHz);
            if (powerampDvcResponse) {
                extraBassResponseMb = PeqMath.powerampDvcDeadBandMb(
                        extraBassResponseMb,
                        extraBassGainMb);
            }
            levelMb += extraBassResponseMb;
        }
        return Math.max(DYNAMICS_MIN_LEVEL_MB, Math.min(DYNAMICS_MAX_LEVEL_MB, levelMb));
    }

    private PeqMath.PreparedBandResponse prepareExtraBassResponse(Preset preset) {
        if (preset == null || !preset.extraBassEnabled || preset.extraBassAmountPercent <= 0) {
            return null;
        }
        int extraBassGainMb = Math.round(
                preset.extraBassAmountPercent / 100f * EXTRA_BASS_MAX_GAIN_MB);
        return PeqMath.prepareBandResponse(new ParametricBand(
                FilterType.LOW_SHELF,
                true,
                preset.extraBassCutoffHz,
                extraBassGainMb,
                70));
    }

    private int activeBandCount() {
        try {
            if (dynamicsProcessing != null || powerampDynamicsProcessing != null) {
                return dynamicsBandCenterHz.length;
            }
            return equalizer == null ? 0 : equalizer.getNumberOfBands();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private int activeBandCenterHz(int band) {
        if (dynamicsProcessing != null || powerampDynamicsProcessing != null) {
            return band >= 0 && band < dynamicsBandCenterHz.length
                    ? Math.round(dynamicsBandCenterHz[band])
                    : 0;
        }
        if (equalizer == null || band < 0 || band >= equalizer.getNumberOfBands()) {
            return 0;
        }
        return equalizer.getCenterFreq((short) band) / 1000;
    }

    private int activeTargetLevelMb(int band, Preset preset) {
        int centerHz = activeBandCenterHz(band);
        if (dynamicsProcessing != null || powerampDynamicsProcessing != null) {
            return targetDynamicsLevelMb(centerHz, preset) + (preset == null ? 0 : preset.pregainMb);
        }
        return targetLevelMb((short) band, preset);
    }

    private boolean hasActiveEffect() {
        return dynamicsProcessing != null || powerampDynamicsProcessing != null || equalizer != null;
    }

    private boolean isActiveEffectEnabled() {
        if (powerampDynamicsProcessing != null) {
            return powerampDynamicsProcessing.getEnabled();
        }
        if (dynamicsProcessing != null) {
            return dynamicsProcessing.getEnabled();
        }
        return equalizer != null && equalizer.getEnabled();
    }

    private void setActiveEffectEnabled(boolean enabled) {
        if (powerampDynamicsProcessing != null) {
            powerampDynamicsProcessing.setEnabled(enabled);
        } else if (dynamicsProcessing != null) {
            dynamicsProcessing.setEnabled(enabled);
        } else if (equalizer != null) {
            equalizer.setEnabled(enabled);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean eqBandSwitchStateChanged(Preset before, Preset after) {
        if (before == null || after == null) {
            return before != after;
        }
        if (before.mode != after.mode) {
            return true;
        }
        if (after.mode == EqMode.GEQ) {
            return false;
        }
        int count = Math.max(before.bands.length, after.bands.length);
        for (int i = 0; i < count; i++) {
            boolean beforeEnabled = i < before.bands.length && before.bands[i].enabled;
            boolean afterEnabled = i < after.bands.length && after.bands[i].enabled;
            if (beforeEnabled != afterEnabled) {
                return true;
            }
        }
        return false;
    }

    private void resetBands(short bandCount) {
        if (equalizer == null) {
            return;
        }

        for (short band = 0; band < bandCount; band++) {
            try {
                equalizer.setBandLevel(band, (short) 0);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Failed to reset band " + band, ex);
            }
        }
    }

    private int targetLevelMb(short band, Preset preset) {
        if (equalizer == null || preset == null) {
            return 0;
        }
        try {
            int centerHz = equalizer.getCenterFreq(band) / 1000;
            int levelMb = PeqMath.gainAtHzMb(centerHz, preset);
            return Math.max(minLevelMb, Math.min(maxLevelMb, levelMb));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private boolean samePresetState(Preset before, Preset after) {
        return before != null && after != null && before.toJson().equals(after.toJson());
    }

    private void updateDynamicsConfig(AdvancedModeConfig config) {
        AdvancedModeConfig nextConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        if (!sameLimiterConfig(dynamicsConfig, nextConfig)) {
            dynamicsLimiterConfigured = false;
        }
        dynamicsConfig = nextConfig;
    }

    private boolean sameLimiterConfig(AdvancedModeConfig before, AdvancedModeConfig after) {
        if (before == after) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        return before.limiterCeilingPermille == after.limiterCeilingPermille
                && before.limiterEnabled == after.limiterEnabled
                && before.limiterAttackMs == after.limiterAttackMs
                && before.limiterReleaseMs == after.limiterReleaseMs;
    }

    private void selectProcessingMode(ProcessingMode mode) {
        ProcessingMode nextMode = mode == null ? ProcessingMode.SYSTEM_EQ : mode;
        if (processingMode == nextMode) {
            return;
        }
        release();
        processingMode = nextMode;
        dynamicsProcessingUnavailable = false;
    }

    private void applySystemVirtualBass(Preset preset) {
        int systemBassAmountPercent = preset == null ? 0 : preset.systemVirtualBassAmountPercent;
        if (systemBassAmountPercent <= 0) {
            releaseSystemVirtualBass();
            return;
        }

        try {
            int targetAudioSessionId = dvcActive
                    ? dynamicsAudioSessionId
                    : GLOBAL_AUDIO_SESSION;
            if (bassBoost != null && bassBoostAudioSessionId != targetAudioSessionId) {
                releaseSystemVirtualBass();
            }
            if (bassBoost == null) {
                bassBoost = new BassBoost(AUDIO_EFFECT_PRIORITY, targetAudioSessionId);
                bassBoostAudioSessionId = targetAudioSessionId;
            }
            bassBoost.setEnabled(false);
            bassBoost.setStrength((short) Math.max(0, Math.min(1000, systemBassAmountPercent * 10)));
            bassBoost.setEnabled(true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "System virtual bass effect could not be applied", ex);
            releaseSystemVirtualBass();
        }
    }

    private void releaseSystemVirtualBass() {
        if (bassBoost == null) {
            return;
        }

        try {
            bassBoost.setEnabled(false);
            bassBoost.release();
        } catch (RuntimeException ignored) {
        } finally {
            bassBoost = null;
            bassBoostAudioSessionId = GLOBAL_AUDIO_SESSION;
        }
    }

    private void releaseDynamicsProcessing() {
        if (powerampDynamicsProcessing != null) {
            try {
                powerampDynamicsProcessing.release();
            } catch (RuntimeException ignored) {
            } finally {
                powerampDynamicsProcessing = null;
            }
        }
        if (dynamicsProcessing != null) {
            try {
                dynamicsProcessing.setEnabled(false);
                dynamicsProcessing.release();
            } catch (RuntimeException ignored) {
            } finally {
                dynamicsProcessing = null;
            }
        }
        dynamicsPreEq = null;
        dynamicsPostEq = null;
        dynamicsPreEqBandCount = 0;
        dynamicsBandCenterHz = new float[0];
        dynamicsPreferredFrameDurationMs = 0f;
        dynamicsLimiterConfigured = false;
        powerampAppliedGainsDb = new float[0];
    }

    synchronized void release() {
        handler.removeCallbacksAndMessages(null);
        pendingPreset = null;
        lastAppliedPreset = null;
        lastAppliedDynamicsConfig = null;
        applyGeneration++;
        releaseDynamicsProcessing();
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                resetBands(equalizer.getNumberOfBands());
                equalizer.release();
            } catch (RuntimeException ignored) {
            } finally {
                equalizer = null;
            }
        }
        releaseSystemVirtualBass();
        armedWithZeroBands = false;
        targetApplyPending = false;
        lastControlRearmElapsedMs = 0;
        lastRouteReapplyElapsedMs = 0;
        dvcActive = false;
        dvcDownstreamHeadroomDb = 0f;
        dvcMappedPeakGainDb = 0f;
        dvcSafetyAttenuationDb = 0f;
        dynamicsAudioSessionId = GLOBAL_AUDIO_SESSION;
    }
}
