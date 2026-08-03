package com.example.globalpeq;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sends the DynamicsProcessing and Volume parameters that have no complete public API path. */
final class PowerampDvcRawBridge {
    private static final String TAG = "PowerampDvcRawBridge";
    private static final int EFFECT_CMD_SET_PARAM = 5;
    private static final int DP_PARAM_INPUT_GAIN = 32;
    private static final int VOLUME_PARAM_LEVEL = 0;
    private static final int EFFECT_PARAM_HEADER_BYTES = 12;
    private static final boolean NATIVE_BRIDGE_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("globalpeq_dvc");
            loaded = true;
        } catch (LinkageError error) {
            Log.w(TAG, "DVC raw-command bridge is unavailable", error);
        }
        NATIVE_BRIDGE_LOADED = loaded;
    }

    private boolean failureLogged;

    boolean setInputGainAllChannels(DynamicsProcessing effect,
                                    int channelCount,
                                    float gainDb) {
        if (!NATIVE_BRIDGE_LOADED || effect == null || channelCount <= 0
                || !Float.isFinite(gainDb)) {
            return false;
        }
        for (int channel = 0; channel < channelCount; channel++) {
            if (!send(effect, inputGainCommand(channel, gainDb))) {
                logFailure("Raw DynamicsProcessing input-gain command was rejected");
                return false;
            }
        }
        return true;
    }

    boolean setVolumeLevelMb(AudioEffect effect, int levelMb) {
        if (!NATIVE_BRIDGE_LOADED || effect == null) {
            return false;
        }
        boolean applied = send(effect, volumeLevelCommand(levelMb));
        if (!applied) {
            logFailure("Raw Volume-effect level command was rejected");
        }
        return applied;
    }

    private static boolean send(AudioEffect effect, byte[] command) {
        byte[] reply = new byte[Integer.BYTES];
        int result = nativeCommand(effect, EFFECT_CMD_SET_PARAM, command, reply);
        int effectStatus = ByteBuffer.wrap(reply)
                .order(ByteOrder.nativeOrder())
                .getInt(0);
        return result >= AudioEffect.SUCCESS && effectStatus >= AudioEffect.SUCCESS;
    }

    private static byte[] inputGainCommand(int channel, float gainDb) {
        ByteBuffer buffer = ByteBuffer.allocate(EFFECT_PARAM_HEADER_BYTES + 12)
                .order(ByteOrder.nativeOrder());
        buffer.putInt(0);
        buffer.putInt(8);
        buffer.putInt(4);
        buffer.putInt(DP_PARAM_INPUT_GAIN);
        buffer.putInt(channel);
        buffer.putFloat(gainDb);
        return buffer.array();
    }

    private static byte[] volumeLevelCommand(int levelMb) {
        ByteBuffer buffer = ByteBuffer.allocate(EFFECT_PARAM_HEADER_BYTES + 6)
                .order(ByteOrder.nativeOrder());
        buffer.putInt(0);
        buffer.putInt(4);
        buffer.putInt(2);
        buffer.putInt(VOLUME_PARAM_LEVEL);
        buffer.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, levelMb)));
        return buffer.array();
    }

    private void logFailure(String message) {
        if (!failureLogged) {
            failureLogged = true;
            Log.w(TAG, message);
        }
    }

    private static native int nativeCommand(AudioEffect effect,
                                            int commandCode,
                                            byte[] command,
                                            byte[] reply);
}
