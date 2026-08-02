package com.example.globalpeq;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes the DynamicsProcessing limiter with the same raw effect command and native-order
 * effect_param_t layout used by Poweramp Equalizer. This is intentionally limited to the DVC
 * limiter; EQ configuration remains owned by GlobalEqualizerEngine.
 */
final class PowerampDvcRawBridge {
    private static final String TAG = "PowerampDvcRawBridge";
    private static final int EFFECT_CMD_SET_PARAM = 5;
    private static final int DP_PARAM_LIMITER = 112;
    private static final int EFFECT_PARAM_HEADER_BYTES = 12;
    private static final int LIMITER_PARAM_BYTES = 8;
    private static final int LIMITER_VALUE_BYTES = 32;
    private static final boolean NATIVE_BRIDGE_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("globalpeq_dvc");
            loaded = true;
        } catch (LinkageError error) {
            Log.w(TAG, "JNI raw command bridge unavailable", error);
        }
        NATIVE_BRIDGE_LOADED = loaded;
    }

    private Method commandMethod;
    private boolean lookupAttempted;
    private boolean failureLogged;
    private boolean nativePathLogged;

    boolean setLimiterAllChannels(DynamicsProcessing effect,
                                  int channelCount,
                                  DynamicsProcessing.Limiter limiter) {
        if (effect == null || limiter == null || channelCount <= 0) {
            return false;
        }
        try {
            for (int channel = 0; channel < channelCount; channel++) {
                byte[] command = limiterCommand(channel, limiter);
                byte[] reply = new byte[Integer.BYTES];
                int result = invokeCommand(effect, command, reply);
                int effectStatus = ByteBuffer.wrap(reply)
                        .order(ByteOrder.nativeOrder())
                        .getInt(0);
                if (result < AudioEffect.SUCCESS || effectStatus < AudioEffect.SUCCESS) {
                    logFailure("raw limiter command rejected: result=" + result
                            + " status=" + effectStatus);
                    return false;
                }
            }
            if (NATIVE_BRIDGE_LOADED && !nativePathLogged) {
                nativePathLogged = true;
                Log.i(TAG, "Using JNI Poweramp-compatible raw command bridge");
            }
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            logFailure("raw limiter command unavailable", error);
            return false;
        }
    }

    private int invokeCommand(DynamicsProcessing effect, byte[] command, byte[] reply)
            throws IllegalAccessException, InvocationTargetException {
        if (NATIVE_BRIDGE_LOADED) {
            return nativeCommand(effect, EFFECT_CMD_SET_PARAM, command, reply);
        }
        Method method = commandMethod();
        if (method == null) {
            return AudioEffect.ERROR_INVALID_OPERATION;
        }
        Object resultObject = method.invoke(effect, EFFECT_CMD_SET_PARAM, command, reply);
        return resultObject instanceof Number
                ? ((Number) resultObject).intValue()
                : AudioEffect.ERROR;
    }

    private Method commandMethod() {
        if (lookupAttempted) {
            return commandMethod;
        }
        lookupAttempted = true;
        try {
            Method method = AudioEffect.class.getDeclaredMethod(
                    "command", int.class, byte[].class, byte[].class);
            method.setAccessible(true);
            commandMethod = method;
        } catch (NoSuchMethodException | RuntimeException error) {
            logFailure("AudioEffect raw command API unavailable", error);
        }
        return commandMethod;
    }

    private static byte[] limiterCommand(int channel,
                                         DynamicsProcessing.Limiter limiter) {
        ByteBuffer buffer = ByteBuffer.allocate(
                        EFFECT_PARAM_HEADER_BYTES + LIMITER_PARAM_BYTES + LIMITER_VALUE_BYTES)
                .order(ByteOrder.nativeOrder());
        buffer.putInt(0); // effect_param_t status
        buffer.putInt(LIMITER_PARAM_BYTES);
        buffer.putInt(LIMITER_VALUE_BYTES);
        buffer.putInt(DP_PARAM_LIMITER);
        buffer.putInt(channel);
        buffer.putInt(limiter.isInUse() ? 1 : 0);
        buffer.putInt(limiter.isEnabled() ? 1 : 0);
        buffer.putInt(limiter.getLinkGroup());
        buffer.putFloat(limiter.getAttackTime());
        buffer.putFloat(limiter.getReleaseTime());
        buffer.putFloat(limiter.getRatio());
        buffer.putFloat(limiter.getThreshold());
        buffer.putFloat(limiter.getPostGain());
        return buffer.array();
    }

    private void logFailure(String message) {
        if (!failureLogged) {
            failureLogged = true;
            Log.w(TAG, message);
        }
    }

    private void logFailure(String message, Throwable error) {
        if (!failureLogged) {
            failureLogged = true;
            Log.w(TAG, message, error);
        }
    }

    private static native int nativeCommand(AudioEffect effect,
                                            int commandCode,
                                            byte[] command,
                                            byte[] reply);
}
