package com.example.globalpeq;

import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Minimal raw DynamicsProcessing client matching Poweramp Equalizer's Android effect protocol.
 *
 * <p>The framework {@code DynamicsProcessing} wrapper asks the device for its default
 * implementation. Poweramp instead selects the AOSP implementation UUID explicitly and writes
 * the DP parameter blocks through {@code AudioEffect.command}. That distinction matters on ROMs
 * whose default wrapper accepts readback while routing the audible processing differently.</p>
 */
final class PowerampDynamicsProcessing {
    private static final UUID IMPLEMENTATION_UUID =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");
    private static final int PRIORITY = 1337;
    private static final int COMMAND_SET_PARAMETER = 5;
    private static final int COMMAND_GET_PARAMETER = 8;

    private static final int PARAM_ENGINE_CHANNEL_COUNT = 16;
    private static final int PARAM_ENGINE_CONFIG = 48;
    private static final int PARAM_INPUT_GAIN = 32;
    private static final int PARAM_PRE_EQ_STAGE = 64;
    private static final int PARAM_PRE_EQ_BAND = 69;
    private static final int PARAM_POST_EQ_STAGE = 96;
    private static final int PARAM_POST_EQ_BAND = 101;
    private static final int PARAM_LIMITER = 112;

    private AudioEffect effect;
    private Method commandMethod;
    private final int audioSessionId;
    private int channelCount;
    private final int preEqBandCount;
    private final int postEqBandCount;

    static boolean isRawCommandApiAvailable() {
        try {
            AudioEffect.class.getMethod(
                    "command",
                    int.class,
                    byte[].class,
                    byte[].class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    PowerampDynamicsProcessing(int audioSessionId,
                               int preEqBandCount,
                               int postEqBandCount) {
        if (audioSessionId < 0 || preEqBandCount < 0 || postEqBandCount <= 0) {
            throw new IllegalArgumentException("Invalid Poweramp DP session/configuration");
        }
        this.audioSessionId = audioSessionId;
        this.preEqBandCount = preEqBandCount;
        this.postEqBandCount = postEqBandCount;
        try {
            Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                    UUID.class,
                    UUID.class,
                    int.class,
                    int.class);
            effect = constructor.newInstance(
                    AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING,
                    IMPLEMENTATION_UUID,
                    PRIORITY,
                    audioSessionId);
            commandMethod = AudioEffect.class.getMethod(
                    "command",
                    int.class,
                    byte[].class,
                    byte[].class);
            channelCount = queryChannelCount();
            writeEngineConfiguration();
            setInputGain(0f);
        } catch (ReflectiveOperationException error) {
            releaseAfterConstructionFailure();
            throw new IllegalStateException("Poweramp raw DP API is unavailable", unwrap(error));
        } catch (RuntimeException error) {
            releaseAfterConstructionFailure();
            throw error;
        }
    }

    int getAudioSessionId() {
        return audioSessionId;
    }

    int getChannelCount() {
        return channelCount;
    }

    boolean getEnabled() {
        return effect.getEnabled();
    }

    void setEnabled(boolean enabled) {
        checkStatus(effect.setEnabled(enabled), "setEnabled");
    }

    void setInputGain(float gainDb) {
        // DynamicsProcessing input gain is evaluated before its pre/post EQ stages. Keep user
        // pregain here rather than baking it into EQ bands or the limiter's post-gain field.
        for (int channel = 0; channel < channelCount; channel++) {
            ByteBuffer parameter = buffer(24);
            parameter.putInt(0);
            parameter.putInt(8);
            parameter.putInt(4);
            parameter.putInt(PARAM_INPUT_GAIN);
            parameter.putInt(channel);
            parameter.putFloat(gainDb);
            sendSetParameter(parameter);
        }
    }

    void setEq(float[] centerFrequenciesHz, float[] gainsDb) {
        int totalBandCount = preEqBandCount + postEqBandCount;
        if (totalBandCount <= 0 || centerFrequenciesHz == null || gainsDb == null
                || centerFrequenciesHz.length != totalBandCount
                || gainsDb.length != totalBandCount) {
            throw new IllegalArgumentException("Poweramp DP EQ array length mismatch");
        }

        for (int channel = 0; channel < channelCount; channel++) {
            if (preEqBandCount > 0) {
                writeEqStage(PARAM_PRE_EQ_STAGE, channel, preEqBandCount);
            }
            writeEqStage(PARAM_POST_EQ_STAGE, channel, postEqBandCount);
        }

        // Poweramp writes the post bank first and the pre bank second, band-major/channel-minor.
        for (int band = 0; band < postEqBandCount; band++) {
            int sourceBand = preEqBandCount + band;
            for (int channel = 0; channel < channelCount; channel++) {
                writeEqBand(
                        PARAM_POST_EQ_BAND,
                        channel,
                        band,
                        centerFrequenciesHz[sourceBand],
                        gainsDb[sourceBand]);
            }
        }
        for (int band = 0; band < preEqBandCount; band++) {
            for (int channel = 0; channel < channelCount; channel++) {
                writeEqBand(
                        PARAM_PRE_EQ_BAND,
                        channel,
                        band,
                        centerFrequenciesHz[band],
                        gainsDb[band]);
            }
        }
    }

    void setLimiter(boolean enabled,
                    float attackMs,
                    float releaseMs,
                    float ratio,
                    float thresholdDb,
                    float postGainDb) {
        for (int channel = 0; channel < channelCount; channel++) {
            ByteBuffer parameter = buffer(52);
            parameter.putInt(0);
            parameter.putInt(8);
            parameter.putInt(32);
            parameter.putInt(PARAM_LIMITER);
            parameter.putInt(channel);
            parameter.putInt(1); // in use
            parameter.putInt(enabled ? 1 : 0);
            parameter.putInt(0); // stereo link group
            parameter.putFloat(attackMs);
            parameter.putFloat(releaseMs);
            parameter.putFloat(ratio);
            parameter.putFloat(thresholdDb);
            parameter.putFloat(postGainDb);
            sendSetParameter(parameter);
        }
    }

    void release() {
        if (effect == null) {
            return;
        }
        try {
            effect.setEnabled(false);
        } catch (RuntimeException ignored) {
        }
        effect.release();
        effect = null;
    }

    private int queryChannelCount() {
        ByteBuffer command = buffer(16);
        command.putInt(0);
        command.putInt(4);
        command.putInt(4);
        command.putInt(PARAM_ENGINE_CHANNEL_COUNT);

        ByteBuffer reply = buffer(20);
        reply.putInt(0);
        reply.putInt(4);
        reply.putInt(4);
        reply.putInt(PARAM_ENGINE_CHANNEL_COUNT);
        reply.putInt(0);
        invokeCommand(COMMAND_GET_PARAMETER, command.array(), reply.array());
        int resolved = reply.getInt(16);
        if (resolved <= 0 || resolved > 32) {
            throw new IllegalStateException("Invalid Poweramp DP channel count: " + resolved);
        }
        return resolved;
    }

    private void writeEngineConfiguration() {
        ByteBuffer parameter = buffer(52);
        parameter.putInt(0);
        parameter.putInt(4);
        parameter.putInt(36);
        parameter.putInt(PARAM_ENGINE_CONFIG);
        parameter.putInt(0); // variant
        parameter.putFloat(resolvePreferredFrameDurationMs());
        parameter.putInt(preEqBandCount > 0 ? 1 : 0);
        parameter.putInt(preEqBandCount);
        parameter.putInt(0); // MBC in use
        parameter.putInt(0); // MBC bands
        parameter.putInt(1); // post EQ in use
        parameter.putInt(postEqBandCount);
        parameter.putInt(1); // limiter in use
        sendSetParameter(parameter);
    }

    private void writeEqStage(int parameterId, int channel, int bandCount) {
        ByteBuffer parameter = buffer(32);
        parameter.putInt(0);
        parameter.putInt(8);
        parameter.putInt(12);
        parameter.putInt(parameterId);
        parameter.putInt(channel);
        parameter.putInt(1); // in use
        parameter.putInt(1); // enabled
        parameter.putInt(bandCount);
        sendSetParameter(parameter);
    }

    private void writeEqBand(int parameterId,
                             int channel,
                             int band,
                             float cutoffFrequencyHz,
                             float gainDb) {
        ByteBuffer parameter = buffer(36);
        parameter.putInt(0);
        parameter.putInt(12);
        parameter.putInt(12);
        parameter.putInt(parameterId);
        parameter.putInt(channel);
        parameter.putInt(band);
        parameter.putInt(1); // enabled
        parameter.putFloat(cutoffFrequencyHz);
        parameter.putFloat(gainDb);
        sendSetParameter(parameter);
    }

    private void sendSetParameter(ByteBuffer parameter) {
        invokeCommand(COMMAND_SET_PARAMETER, parameter.array(), buffer(4).array());
    }

    private void invokeCommand(int commandCode, byte[] command, byte[] reply) {
        try {
            Object result = commandMethod.invoke(effect, commandCode, command, reply);
            if (!(result instanceof Integer)) {
                throw new IllegalStateException("Poweramp DP command returned " + result);
            }
            checkStatus((Integer) result, "command " + commandCode);
            // AudioEffect.command reports the transport result. DP's own operation status is the
            // first native-order int in the reply p_status field and can reject a parameter even
            // when the transport call itself succeeded.
            if (reply != null && reply.length >= 4) {
                int effectStatus = ByteBuffer.wrap(reply)
                        .order(ByteOrder.nativeOrder())
                        .getInt(0);
                checkStatus(effectStatus, "command " + commandCode + " effect status");
            }
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Poweramp DP command is inaccessible", error);
        } catch (InvocationTargetException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Poweramp DP command failed", cause);
        }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
    }

    private void releaseAfterConstructionFailure() {
        if (effect == null) {
            return;
        }
        try {
            effect.release();
        } catch (RuntimeException ignored) {
        } finally {
            effect = null;
        }
    }

    static float resolvePreferredFrameDurationMs() {
        try {
            int sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            return sampleRate > 0 ? 4096f * 1000f / sampleRate : 0f;
        } catch (RuntimeException ignored) {
            return 0f;
        }
    }

    private static void checkStatus(int status, String operation) {
        if (status < 0) {
            throw new IllegalStateException("Poweramp DP " + operation + " failed: " + status);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause()
                : error;
    }
}
