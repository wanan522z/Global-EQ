package com.example.globalpeq;

import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

/**
 * Poweramp Equalizer-style DVC companion effect.
 *
 * <p>The equalizer pipeline attaches Android's Volume effect to the same non-zero player session
 * as DynamicsProcessing. The Volume effect then moves the stream-volume operation into that
 * player effect path. It does not use the separate output-mix positive compensation employed by
 * Poweramp Player's Direct Volume Controller.</p>
 */
final class PowerampDvcVolumeChain {
    private static final UUID VOLUME_TYPE_UUID =
            UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");
    private static final UUID AOSP_VOLUME_IMPLEMENTATION_UUID =
            UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");
    private static final UUID DEFAULT_IMPLEMENTATION_UUID =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");
    private static final UUID POWERAMP_DP_TYPE_UUID =
            UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e");
    private static final int PRIORITY = 1337;
    private static final int CREATE_ATTEMPTS = 3;
    private static final long VOLUME_PULSE_DELAY_MS = 50L;

    private final int audioSessionId;
    private final AudioManager audioManager;
    private AudioEffect volumeEffect;
    private String implementationMode = "AOSP UUID";
    private boolean initializationPulseApplied;

    PowerampDvcVolumeChain(AudioManager audioManager, int audioSessionId) {
        if (audioSessionId < 0) {
            throw new IllegalArgumentException("DVC VolumeFX requires a valid audio session");
        }
        if (audioManager == null) {
            throw new IllegalArgumentException("DVC VolumeFX requires AudioManager");
        }
        this.audioManager = audioManager;
        this.audioSessionId = audioSessionId;
        try {
            Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                    UUID.class,
                    UUID.class,
                    int.class,
                    int.class);

            RuntimeException explicitFailure;
            try {
                volumeEffect = createUsableEffect(
                        constructor,
                        AOSP_VOLUME_IMPLEMENTATION_UUID,
                        audioSessionId);
                initializePowerampVolumePlacement(constructor);
                return;
            } catch (RuntimeException error) {
                release();
                explicitFailure = error;
            }

            try {
                volumeEffect = createUsableEffect(
                        constructor,
                        DEFAULT_IMPLEMENTATION_UUID,
                        audioSessionId);
                implementationMode = "device default UUID";
                initializePowerampVolumePlacement(constructor);
            } catch (RuntimeException defaultFailure) {
                throw new IllegalStateException(
                        "VolumeFX creation failed; AOSP UUID: "
                                + describe(explicitFailure)
                                + "; device default UUID: "
                                + describe(defaultFailure),
                        defaultFailure);
            }
        } catch (ReflectiveOperationException error) {
            release();
            throw new IllegalStateException(
                    "VolumeFX reflection failed: " + describe(unwrap(error)),
                    unwrap(error));
        } catch (RuntimeException error) {
            release();
            throw error;
        }
    }

    int getAudioSessionId() {
        return audioSessionId;
    }

    String describeAttachment() {
        return "attached with control (" + implementationMode
                + ", Poweramp DP restart + volume pulse "
                + (initializationPulseApplied ? "applied" : "not applied") + ")";
    }

    void release() {
        if (volumeEffect == null) {
            return;
        }
        try {
            // Poweramp's common Equalizer helper releases VolumeFX directly; it does not modify
            // the stream-volume index or write a replacement level during teardown.
            volumeEffect.release();
        } catch (RuntimeException ignored) {
        } finally {
            volumeEffect = null;
        }
    }

    private static AudioEffect createUsableEffect(Constructor<AudioEffect> constructor,
                                                  UUID implementationUuid,
                                                  int audioSessionId) {
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            AudioEffect candidate = null;
            try {
                candidate = constructor.newInstance(
                        VOLUME_TYPE_UUID,
                        implementationUuid,
                        PRIORITY,
                        audioSessionId);
                if (!candidate.hasControl()) {
                    throw new IllegalStateException("created but has no control");
                }
                return candidate;
            } catch (ReflectiveOperationException error) {
                lastFailure = unwrap(error);
            } catch (RuntimeException error) {
                lastFailure = error;
            }
            if (candidate != null) {
                try {
                    candidate.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
        throw new IllegalStateException(describe(lastFailure), lastFailure);
    }

    /**
     * Poweramp creates VolumeFX before the real DynamicsProcessing instance, briefly creates and
     * disables its DP type on the same session, then moves STREAM_MUSIC by one index and restores
     * it after 50 ms. The pulse makes AudioFlinger re-apply current stream attenuation through the
     * new VolumeFX. It is initialization only; release() never writes the system volume.
     */
    private void initializePowerampVolumePlacement(Constructor<AudioEffect> constructor) {
        if (!restartPowerampDynamicsEffect(constructor)) {
            return;
        }

        int originalIndex;
        try {
            originalIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException ignored) {
            return;
        }
        int temporaryIndex = originalIndex == 0 ? 1 : originalIndex - 1;
        boolean temporaryApplied = false;
        boolean restored = false;
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, temporaryIndex, 0);
            temporaryApplied = true;
            SystemClock.sleep(VOLUME_PULSE_DELAY_MS);
        } catch (RuntimeException ignored) {
        } finally {
            if (temporaryApplied) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalIndex, 0);
                    restored = true;
                } catch (RuntimeException ignored) {
                }
            }
        }
        initializationPulseApplied = restored;
    }

    private boolean restartPowerampDynamicsEffect(Constructor<AudioEffect> constructor) {
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            AudioEffect restartEffect = null;
            try {
                restartEffect = constructor.newInstance(
                        POWERAMP_DP_TYPE_UUID,
                        DEFAULT_IMPLEMENTATION_UUID,
                        PRIORITY,
                        audioSessionId);
                restartEffect.setEnabled(false);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                SystemClock.sleep(10L);
            } finally {
                if (restartEffect != null) {
                    try {
                        restartEffect.release();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause()
                : error;
    }
}
