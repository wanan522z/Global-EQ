package com.example.globalpeq;

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

/** Owns the post-DP attenuation half of the global DVC gain pair. */
final class PowerampVolumeEffect {
    private static final String TAG = "PowerampVolumeEffect";
    private static final UUID TYPE =
            UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");
    private static final UUID IMPLEMENTATION =
            UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");
    private static final int PRIORITY = 1337;
    private static final int GLOBAL_AUDIO_SESSION = 0;
    private static final int SILENCE_MB = -9600;
    private static final int UNITY_MB = 0;
    private static final int CREATE_ATTEMPTS = 3;

    private final PowerampDvcRawBridge rawBridge = new PowerampDvcRawBridge();
    private final Runnable controlLostCallback;
    private AudioEffect effect;
    private int appliedLevelMb;

    PowerampVolumeEffect(Runnable controlLostCallback) {
        this.controlLostCallback = controlLostCallback;
    }

    boolean openMuted() {
        if (effect != null) {
            return effect.hasControl() && effect.getEnabled();
        }
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            AudioEffect candidate = null;
            try {
                Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                        UUID.class, UUID.class, int.class, int.class);
                candidate = constructor.newInstance(
                        TYPE, IMPLEMENTATION, PRIORITY, GLOBAL_AUDIO_SESSION);
                candidate.setControlStatusListener((audioEffect, controlGranted) -> {
                    if (!controlGranted && effect == audioEffect && controlLostCallback != null) {
                        controlLostCallback.run();
                    }
                });
                if (!candidate.hasControl()
                        || !rawBridge.setVolumeLevelMb(candidate, SILENCE_MB)
                        || candidate.setEnabled(true) < AudioEffect.SUCCESS
                        || !candidate.getEnabled()) {
                    release(candidate);
                    continue;
                }
                effect = candidate;
                appliedLevelMb = SILENCE_MB;
                Log.i(TAG, "Post-DP DVC Volume effect is active");
                return true;
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                     | InvocationTargetException | RuntimeException error) {
                release(candidate);
                Log.w(TAG, "Volume-effect creation failed on attempt " + (attempt + 1), error);
            }
        }
        return false;
    }

    boolean setLevelDb(float levelDb) {
        if (effect == null || !effect.hasControl() || !Float.isFinite(levelDb)) {
            return false;
        }
        int targetMb = Math.round(Math.max(SILENCE_MB / 100f, Math.min(0f, levelDb)) * 100f);
        if (targetMb == appliedLevelMb) {
            return true;
        }
        if (!rawBridge.setVolumeLevelMb(effect, targetMb)) {
            return false;
        }
        appliedLevelMb = targetMb;
        return true;
    }

    boolean isActive() {
        return effect != null && effect.hasControl() && effect.getEnabled();
    }

    float appliedLevelDb() {
        return appliedLevelMb / 100f;
    }

    void releaseToUnity() {
        AudioEffect current = effect;
        effect = null;
        appliedLevelMb = UNITY_MB;
        if (current == null) {
            return;
        }
        try {
            rawBridge.setVolumeLevelMb(current, UNITY_MB);
            current.setEnabled(false);
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to restore the DVC Volume effect to unity", error);
        } finally {
            release(current);
        }
    }

    private static void release(AudioEffect candidate) {
        if (candidate == null) {
            return;
        }
        try {
            candidate.release();
        } catch (RuntimeException ignored) {
        }
    }
}
