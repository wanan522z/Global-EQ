package com.example.globalpeq;

import android.media.AudioManager;

final class DvcVolumeMapper {
    static final class Curve {
        final boolean fixedVolume;
        final boolean meaningful;
        final int minIndex;
        final int maxIndex;
        final int currentIndex;
        final float currentDb;
        final String failure;

        Curve(boolean fixedVolume,
              boolean meaningful,
              int minIndex,
              int maxIndex,
              int currentIndex,
              float currentDb,
              String failure) {
            this.fixedVolume = fixedVolume;
            this.meaningful = meaningful;
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            this.currentIndex = currentIndex;
            this.currentDb = currentDb;
            this.failure = failure == null ? "" : failure;
        }

        float headroomDb() {
            if (!meaningful || !Float.isFinite(currentDb)) {
                return 0f;
            }
            // A finite minimum-volume step still represents real attenuation at the output-mix
            // input and is therefore usable EQ headroom. A muted/-infinity step has no bounded
            // budget that can be compensated safely.
            return clamp(-currentDb, 0f, 96f);
        }

    }

    private DvcVolumeMapper() {
    }

    static Curve probe(AudioManager audioManager, int deviceType) {
        if (audioManager == null) {
            return failed("AudioManager unavailable");
        }
        try {
            int min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean fixed = audioManager.isVolumeFixed();
            if (fixed) {
                return new Curve(true, false, min, max, current, 0f, "Fixed hardware volume");
            }
            if (max <= min) {
                return new Curve(false, false, min, max, current, 0f, "No variable media-volume range");
            }

            float maxDb = readDb(audioManager, max, deviceType);
            float lowerDb = Float.NaN;
            for (int index = max - 1; index >= min; index--) {
                float candidate = readDb(audioManager, index, deviceType);
                if (Float.isFinite(candidate) && candidate < maxDb - 0.5f) {
                    lowerDb = candidate;
                    break;
                }
            }
            if (!Float.isFinite(maxDb) || !Float.isFinite(lowerDb)) {
                return new Curve(false, false, min, max, current, 0f,
                        "No meaningful media-volume dB curve");
            }

            float currentDb = readDb(audioManager, current, deviceType);
            if (!Float.isFinite(currentDb)) {
                // A muted minimum index commonly reports negative infinity. Keep it muted and
                // avoid trying to compensate an unbounded value.
                if (current <= min) {
                    currentDb = Float.NEGATIVE_INFINITY;
                } else {
                    return new Curve(false, false, min, max, current, 0f,
                            "Current media-volume dB is unavailable");
                }
            }
            return new Curve(false, true, min, max, current, currentDb, "");
        } catch (RuntimeException error) {
            return failed(error.getClass().getSimpleName());
        }
    }

    private static float readDb(AudioManager audioManager, int index, int deviceType) {
        return audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType);
    }

    private static Curve failed(String reason) {
        return new Curve(false, false, 0, 0, 0, 0f, reason);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
