package com.example.globalpeq;

import android.media.audiofx.BassBoost;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

final class GlobalEqualizerEngine {
    private static final String TAG = "GlobalEqualizerEngine";
    private static final int GLOBAL_AUDIO_SESSION = 0;
    // Keep the same high-priority session-0 arbitration used by the reference path.
    private static final int AUDIO_EFFECT_PRIORITY = 1337;
    private static final int DYNAMICS_CHANNEL_COUNT = 2;
    private static final int[] DEFAULT_DYNAMICS_BAND_COUNT_CANDIDATES = {32, 24, 16, 10};
    private static final int[] GLOBAL_DSP_BAND_COUNT_CANDIDATES = {128, 96, 64, 48, 32, 24, 16, 10};
    private static final int DYNAMICS_MIN_LEVEL_MB = -1800;
    private static final int DYNAMICS_MAX_LEVEL_MB = 1800;
    private static final int EXTRA_BASS_MAX_GAIN_MB = 1500;
    private static final float DYNAMICS_LIMITER_ATTACK_MS = 1f;
    private static final float DYNAMICS_LIMITER_RATIO = 20f;
    private static final long ARM_DELAY_MS = 120;
    private static final long CONTROL_REARM_DELAY_MS = 180;
    private static final long CONTROL_REARM_GUARD_MS = 1000;
    private static final long ROUTE_REAPPLY_DELAY_MS = 220;
    private static final long ROUTE_REAPPLY_GUARD_MS = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DynamicsProcessing dynamicsProcessing;
    private DynamicsProcessing.Eq dynamicsPostEq;
    private int[] dynamicsBandCenterHz = new int[0];
    private boolean dynamicsProcessingUnavailable;
    private Equalizer equalizer;
    private BassBoost bassBoost;
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

    private enum ApplyStrategy {
        AUTO,
        FORCE_FULL_RESET
    }

    boolean start() {
        if (dynamicsProcessing != null || equalizer != null) {
            return true;
        }

        if (!dynamicsProcessingUnavailable && startDynamicsProcessing()) {
            return true;
        }
        return startLegacyEqualizer();
    }

    private boolean startDynamicsProcessing() {
        RuntimeException lastFailure = null;
        int[] bandCountCandidates = processingMode == ProcessingMode.GLOBAL_DSP
                ? GLOBAL_DSP_BAND_COUNT_CANDIDATES
                : DEFAULT_DYNAMICS_BAND_COUNT_CANDIDATES;
        for (int bandCount : bandCountCandidates) {
            DynamicsProcessing candidate = null;
            try {
                DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        0,
                        DYNAMICS_CHANNEL_COUNT,
                        false,
                        0,
                        false,
                        0,
                        true,
                        bandCount,
                        true
                ).build();
                candidate = new DynamicsProcessing(
                        AUDIO_EFFECT_PRIORITY,
                        GLOBAL_AUDIO_SESSION,
                        config);
                DynamicsProcessing.Eq postEq = new DynamicsProcessing.Eq(true, true, bandCount);
                int[] centerFrequencies = createLogBandCenters(
                        bandCount,
                        processingMode == ProcessingMode.GLOBAL_DSP && bandCount >= 48 ? 10.0 : 20.0);
                for (int band = 0; band < bandCount; band++) {
                    DynamicsProcessing.EqBand eqBand = postEq.getBand(band);
                    eqBand.setEnabled(true);
                    eqBand.setCutoffFrequency(centerFrequencies[band]);
                    eqBand.setGain(0f);
                }
                candidate.setInputGainAllChannelsTo(0f);
                candidate.setPostEqAllChannelsTo(postEq);
                candidate.setLimiterAllChannelsTo(createLimiter(dynamicsConfig, 0f));
                candidate.setEnabled(false);
                candidate.setControlStatusListener(this::onControlStatusChanged);

                dynamicsProcessing = candidate;
                dynamicsPostEq = postEq;
                dynamicsBandCenterHz = centerFrequencies;
                minLevelMb = DYNAMICS_MIN_LEVEL_MB;
                maxLevelMb = DYNAMICS_MAX_LEVEL_MB;
                armedWithZeroBands = false;
                Log.i(TAG, "Using DynamicsProcessing on global audio session with "
                        + bandCount + " post-EQ bands");
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

        dynamicsProcessingUnavailable = true;
        if (lastFailure != null) {
            Log.w(TAG, "DynamicsProcessing unavailable; falling back to legacy Equalizer", lastFailure);
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

    private static int[] createLogBandCenters(int bandCount, double minHz) {
        int safeCount = Math.max(1, bandCount);
        int[] frequencies = new int[safeCount];
        double maxHz = 20000.0;
        for (int band = 0; band < safeCount; band++) {
            double position = safeCount == 1 ? 0.0 : band / (double) (safeCount - 1);
            frequencies[band] = (int) Math.round(minHz * Math.pow(maxHz / minHz, position));
        }
        return frequencies;
    }

    private static DynamicsProcessing.Limiter createLimiter(AdvancedModeConfig config,
                                                              float postGainDb) {
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        float ceiling = Math.max(0.001f, safeConfig.limiterCeilingPermille / 1000f);
        float thresholdDb = (float) (20.0 * Math.log10(ceiling));
        return new DynamicsProcessing.Limiter(
                true,
                true,
                0,
                DYNAMICS_LIMITER_ATTACK_MS,
                safeConfig.limiterReleaseMs,
                DYNAMICS_LIMITER_RATIO,
                thresholdDb,
                postGainDb);
    }

    boolean supportsDvcVolumeMapping() {
        // A positive input-gain mapping on the same DP instance that owns the EQ/limiter is not
        // a safe DVC path. AudioFlinger can expose that gain before a separate attenuation stage
        // is processing, and the main limiter sees the boosted signal. Keep it disabled until an
        // isolated, verified volume-controller path is available.
        return false;
    }

    /**
     * Restores the normal GlobalDSP input gain. The former DP-only compensation path is rejected:
     * it could produce a short full-volume burst and drove the main limiter instead of creating
     * usable downstream headroom.
     */
    boolean setDvcVolumeMapping(boolean active, float requestedVolumeDb) {
        if (active) {
            Log.w(TAG, "Rejected unsafe main-DP DVC compensation");
            return false;
        }
        if (processingMode != ProcessingMode.GLOBAL_DSP || dynamicsProcessing == null) {
            return true;
        }
        Preset targetPreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        if (targetPreset == null || !targetPreset.enabled) {
            return true;
        }
        try {
            applyAndVerifyInputGain(targetPreset);
            Log.d(TAG, "Global DVC off");
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to restore normal GlobalDSP input gain", error);
            return false;
        }
    }

    private void applyAndVerifyInputGain(Preset preset) {
        float targetGainDb = clamp(
                presetPregainDb(preset),
                DYNAMICS_MIN_LEVEL_MB / 100f,
                DYNAMICS_MAX_LEVEL_MB / 100f);
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
        Log.d(TAG, "GlobalDSP input gain=" + targetGainDb + " dB");
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

    void apply(Preset preset, ProcessingMode mode, AdvancedModeConfig config) {
        selectProcessingMode(mode);
        dynamicsConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        applyInternal(preset, ApplyStrategy.AUTO);
    }

    void applyWithFullReset(Preset preset) {
        applyWithFullReset(preset, AdvancedModeConfig.DEFAULT);
    }

    void applyWithFullReset(Preset preset, AdvancedModeConfig config) {
        applyWithFullReset(preset, ProcessingMode.SYSTEM_EQ, config);
    }

    void applyWithFullReset(Preset preset, ProcessingMode mode, AdvancedModeConfig config) {
        selectProcessingMode(mode);
        dynamicsConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
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

    void reapplyStaged(Preset preset, ProcessingMode mode, AdvancedModeConfig config) {
        selectProcessingMode(mode);
        dynamicsConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        applyGeneration++;
        if (preset == null || !preset.enabled || !start()) {
            return;
        }

        pendingPreset = preset;
        try {
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

    void reapplyForRouteChange(Preset preset, ProcessingMode mode, AdvancedModeConfig config) {
        if (preset == null || !preset.enabled) {
            return;
        }
        selectProcessingMode(mode);
        dynamicsConfig = config == null ? AdvancedModeConfig.DEFAULT : config;

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

    private void onControlStatusChanged(AudioEffect effect, boolean controlGranted) {
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

    void setEnabled(boolean enabled) {
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
            if (dynamicsProcessing != null) {
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
            if (dynamicsProcessing != null) {
                return band >= 0 && band < dynamicsBandCenterHz.length
                        ? dynamicsBandCenterHz[band]
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
                int centerHz = dynamicsBandCenterHz[band];
                int beforeLevel = targetDynamicsLevelMb(centerHz, before);
                int afterLevel = targetDynamicsLevelMb(centerHz, after);
                if (afterLevel > 0 && afterLevel > beforeLevel) {
                    dynamicsPostEq.getBand(band).setGain(0f);
                }
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

    private void applyTargetLevels(Preset preset) {
        if (!hasActiveEffect() || preset == null || !preset.enabled) {
            return;
        }

        try {
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
                int levelMb = dynamicsProcessing != null
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
        for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
            DynamicsProcessing.EqBand eqBand = dynamicsPostEq.getBand(band);
            eqBand.setEnabled(true);
            eqBand.setCutoffFrequency(dynamicsBandCenterHz[band]);
            eqBand.setGain(targetDynamicsLevelMb(dynamicsBandCenterHz[band], preset) / 100f);
        }
        dynamicsProcessing.setPostEqAllChannelsTo(dynamicsPostEq);
        // DVC does not own or reinterpret limiter state. Input gain is always the preset pregain;
        // volume control must not inject compensation into this EQ/limiter instance.
        dynamicsProcessing.setLimiterAllChannelsTo(createLimiter(dynamicsConfig, 0f));
        applyAndVerifyInputGain(preset);
        if (!dynamicsProcessing.getEnabled()) {
            dynamicsProcessing.setEnabled(true);
        }
    }

    private void resetDynamicsBands() {
        if (dynamicsProcessing == null || dynamicsPostEq == null) {
            return;
        }
        for (int band = 0; band < dynamicsBandCenterHz.length; band++) {
            DynamicsProcessing.EqBand eqBand = dynamicsPostEq.getBand(band);
            eqBand.setEnabled(true);
            eqBand.setCutoffFrequency(dynamicsBandCenterHz[band]);
            eqBand.setGain(0f);
        }
        dynamicsProcessing.setPostEqAllChannelsTo(dynamicsPostEq);
        Preset targetPreset = pendingPreset != null ? pendingPreset : lastAppliedPreset;
        dynamicsProcessing.setLimiterAllChannelsTo(createLimiter(dynamicsConfig, 0f));
        if (targetPreset != null) {
            applyAndVerifyInputGain(targetPreset);
        } else {
            dynamicsProcessing.setInputGainAllChannelsTo(0f);
        }
    }

    private int targetDynamicsLevelMb(int frequencyHz, Preset preset) {
        if (preset == null) {
            return 0;
        }
        int levelMb = PeqMath.gainAtHzMb(frequencyHz, preset) - preset.pregainMb;
        if (preset.extraBassEnabled && preset.extraBassAmountPercent > 0) {
            int extraBassGainMb = Math.round(
                    preset.extraBassAmountPercent / 100f * EXTRA_BASS_MAX_GAIN_MB);
            ParametricBand extraBassBand = new ParametricBand(
                    FilterType.LOW_SHELF,
                    true,
                    preset.extraBassCutoffHz,
                    extraBassGainMb,
                    70);
            levelMb += PeqMath.bandGainAtHzMb(frequencyHz, extraBassBand);
        }
        return Math.max(DYNAMICS_MIN_LEVEL_MB, Math.min(DYNAMICS_MAX_LEVEL_MB, levelMb));
    }

    private int activeBandCount() {
        try {
            if (dynamicsProcessing != null) {
                return dynamicsBandCenterHz.length;
            }
            return equalizer == null ? 0 : equalizer.getNumberOfBands();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private int activeBandCenterHz(int band) {
        if (dynamicsProcessing != null) {
            return band >= 0 && band < dynamicsBandCenterHz.length
                    ? dynamicsBandCenterHz[band]
                    : 0;
        }
        if (equalizer == null || band < 0 || band >= equalizer.getNumberOfBands()) {
            return 0;
        }
        return equalizer.getCenterFreq((short) band) / 1000;
    }

    private int activeTargetLevelMb(int band, Preset preset) {
        int centerHz = activeBandCenterHz(band);
        if (dynamicsProcessing != null) {
            return targetDynamicsLevelMb(centerHz, preset) + (preset == null ? 0 : preset.pregainMb);
        }
        return targetLevelMb((short) band, preset);
    }

    private boolean hasActiveEffect() {
        return dynamicsProcessing != null || equalizer != null;
    }

    private boolean isActiveEffectEnabled() {
        if (dynamicsProcessing != null) {
            return dynamicsProcessing.getEnabled();
        }
        return equalizer != null && equalizer.getEnabled();
    }

    private void setActiveEffectEnabled(boolean enabled) {
        if (dynamicsProcessing != null) {
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

    private boolean sameLimiterConfig(AdvancedModeConfig before, AdvancedModeConfig after) {
        if (before == after) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        return before.limiterCeilingPermille == after.limiterCeilingPermille
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
            if (bassBoost == null) {
                bassBoost = new BassBoost(AUDIO_EFFECT_PRIORITY, GLOBAL_AUDIO_SESSION);
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
        }
    }

    private void releaseDynamicsProcessing() {
        if (dynamicsProcessing == null) {
            dynamicsPostEq = null;
            dynamicsBandCenterHz = new int[0];
            return;
        }
        try {
            dynamicsProcessing.setEnabled(false);
            dynamicsProcessing.release();
        } catch (RuntimeException ignored) {
        } finally {
            dynamicsProcessing = null;
            dynamicsPostEq = null;
            dynamicsBandCenterHz = new int[0];
        }
    }

    void release() {
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
    }
}
