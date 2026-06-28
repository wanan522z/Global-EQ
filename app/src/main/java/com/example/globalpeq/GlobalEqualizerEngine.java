package com.example.globalpeq;

import android.media.audiofx.BassBoost;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

final class GlobalEqualizerEngine {
    private static final String TAG = "GlobalEqualizerEngine";
    private static final int GLOBAL_AUDIO_SESSION = 0;
    private static final int AUDIO_EFFECT_PRIORITY = 1000;
    private static final long ARM_DELAY_MS = 120;
    private static final long CONTROL_REARM_DELAY_MS = 180;
    private static final long CONTROL_REARM_GUARD_MS = 1000;
    private static final long ROUTE_REAPPLY_DELAY_MS = 220;
    private static final long ROUTE_REAPPLY_GUARD_MS = 350;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private short minLevelMb = -1800;
    private short maxLevelMb = 1800;
    private Preset pendingPreset;
    private Preset lastAppliedPreset;
    private boolean armedWithZeroBands;
    private int applyGeneration;
    private long lastControlRearmElapsedMs;
    private long lastRouteReapplyElapsedMs;

    private enum ApplyStrategy {
        AUTO,
        FORCE_FULL_RESET
    }

    boolean start() {
        if (equalizer != null) {
            return true;
        }

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

    void apply(Preset preset) {
        applyInternal(preset, ApplyStrategy.AUTO);
    }

    void applyWithFullReset(Preset preset) {
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
        if (preset == null || !preset.enabled) {
            return;
        }

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
                reapplyStaged(pendingPreset);
            }
        }, CONTROL_REARM_DELAY_MS);
    }

    void setEnabled(boolean enabled) {
        if (!enabled) {
            release();
            return;
        }
        if (pendingPreset != null) {
            apply(pendingPreset.withEnabled(true));
        }
    }

    int getBandCount() {
        if (!start()) {
            return Preset.DEFAULT_FILTER_COUNT;
        }

        try {
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
        short bandCount = equalizer.getNumberOfBands();
        equalizer.setEnabled(false);
        resetBands(bandCount);
        equalizer.setEnabled(true);
        armedWithZeroBands = true;
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
        if (equalizer == null || before == null || after == null) {
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
    }

    private void applyTargetLevels(Preset preset) {
        if (equalizer == null || preset == null || !preset.enabled) {
            return;
        }

        try {
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
        } catch (RuntimeException ex) {
            Log.w(TAG, "Failed to write target EQ levels", ex);
        }
    }

    private boolean shouldStageThroughZero(Preset preset) {
        if (equalizer == null || !armedWithZeroBands) {
            return true;
        }
        try {
            if (!equalizer.getEnabled()) {
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
        if (equalizer == null || preset == null || lastAppliedPreset == null) {
            return false;
        }
        try {
            if (!equalizer.getEnabled()) {
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
        if (strategy != ApplyStrategy.AUTO || equalizer == null || preset == null || lastAppliedPreset == null) {
            return false;
        }
        try {
            if (!equalizer.getEnabled()) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        return samePresetState(lastAppliedPreset, preset);
    }

    private boolean hasAnyEqGain(Preset preset) {
        if (preset == null) {
            return false;
        }
        short bandCount;
        try {
            bandCount = equalizer == null ? 0 : equalizer.getNumberOfBands();
        } catch (RuntimeException ex) {
            bandCount = 0;
        }
        for (short band = 0; band < bandCount; band++) {
            try {
                int centerHz = equalizer.getCenterFreq(band) / 1000;
                if (PeqMath.gainAtHzMb(centerHz, preset) != 0) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRaisedPositiveEqGain(Preset before, Preset after) {
        short bandCount;
        try {
            bandCount = equalizer == null ? 0 : equalizer.getNumberOfBands();
        } catch (RuntimeException ex) {
            return true;
        }
        for (short band = 0; band < bandCount; band++) {
            int beforeLevel = targetLevelMb(band, before);
            int afterLevel = targetLevelMb(band, after);
            if (afterLevel > 0 && afterLevel > beforeLevel) {
                return true;
            }
        }
        return false;
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

    private void applySystemVirtualBass(Preset preset) {
        int systemBassAmountPercent = preset == null ? 0 : preset.systemVirtualBassAmountPercent;
        Log.d(TAG, "applySystemVirtualBass modeIndex="
                + (preset == null ? -1 : preset.virtualBassModeIndex)
                + " systemAmount=" + systemBassAmountPercent
                + " activeAmount=" + (preset == null ? -1 : preset.virtualBassAmountPercent));
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

    void release() {
        handler.removeCallbacksAndMessages(null);
        pendingPreset = null;
        lastAppliedPreset = null;
        applyGeneration++;
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
        lastControlRearmElapsedMs = 0;
        lastRouteReapplyElapsedMs = 0;
    }
}
