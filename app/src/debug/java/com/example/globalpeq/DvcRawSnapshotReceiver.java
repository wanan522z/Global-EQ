package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;

public final class DvcRawSnapshotReceiver extends BroadcastReceiver {
    private static final String TAG = "DvcRawSnapshot";
    private static final int GLOBAL_SESSION = 0;
    private static final int GET_PARAM = 8;
    private static final int PARAM_CHANNEL_COUNT = 16;
    private static final int PARAM_INPUT_GAIN = 32;
    private static final int PARAM_PRE_EQ_STAGE = 64;
    private static final int PARAM_PRE_EQ_BAND = 69;
    private static final int PARAM_POST_EQ_STAGE = 96;
    private static final int PARAM_POST_EQ_BAND = 101;
    private static final int PARAM_LIMITER = 112;
    private static final UUID TYPE =
            UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e");
    private static final UUID IMPLEMENTATION =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    static {
        System.loadLibrary("globalpeq_probe");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        AudioEffect effect = null;
        try {
            Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                    UUID.class, UUID.class, int.class, int.class);
            effect = constructor.newInstance(TYPE, IMPLEMENTATION, -10000, GLOBAL_SESSION);
            Log.i(TAG, "BEGIN id=" + effect.getId() + " control=" + effect.hasControl()
                    + " enabled=" + effect.getEnabled());
            int channels = readScalar(effect, PARAM_CHANNEL_COUNT);
            Log.i(TAG, "channels=" + channels);
            for (int channel = 0; channel < Math.max(0, Math.min(8, channels)); channel++) {
                Log.i(TAG, "ch=" + channel + " inputGain=" + readInputGain(effect, channel));
                dumpEq(effect, channel, PARAM_PRE_EQ_STAGE, PARAM_PRE_EQ_BAND, "pre");
                dumpEq(effect, channel, PARAM_POST_EQ_STAGE, PARAM_POST_EQ_BAND, "post");
                Log.i(TAG, "ch=" + channel + " limiter=" + readLimiter(effect, channel));
            }
            Log.i(TAG, "END");
        } catch (Throwable error) {
            Log.e(TAG, "snapshot failed", error);
        } finally {
            if (effect != null) {
                try {
                    effect.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static int readScalar(AudioEffect effect, int parameterId) {
        ByteBuffer response = get(effect, new int[]{parameterId}, 4);
        return response == null ? -1 : response.getInt(16);
    }

    private static String readInputGain(AudioEffect effect, int channel) {
        ByteBuffer response = get(effect, new int[]{PARAM_INPUT_GAIN, channel}, 4);
        return response == null ? "ERR" : format(response.getFloat(20));
    }

    private static void dumpEq(AudioEffect effect,
                               int channel,
                               int stageId,
                               int bandId,
                               String label) {
        ByteBuffer stage = get(effect, new int[]{stageId, channel}, 12);
        if (stage == null) {
            Log.i(TAG, "ch=" + channel + " " + label + "=ERR");
            return;
        }
        int valueOffset = 20;
        int inUse = stage.getInt(valueOffset);
        int enabled = stage.getInt(valueOffset + 4);
        int bandCount = Math.max(0, Math.min(512, stage.getInt(valueOffset + 8)));
        float maxGain = -Float.MAX_VALUE;
        float minGain = Float.MAX_VALUE;
        double hash = 0.0;
        StringBuilder lowBands = new StringBuilder();
        for (int band = 0; band < bandCount; band++) {
            ByteBuffer value = get(effect, new int[]{bandId, channel, band}, 12);
            if (value == null) {
                lowBands.append(" err@").append(band);
                break;
            }
            int offset = 24;
            int bandEnabled = value.getInt(offset);
            float cutoff = value.getFloat(offset + 4);
            float gain = value.getFloat(offset + 8);
            minGain = Math.min(minGain, gain);
            maxGain = Math.max(maxGain, gain);
            hash = hash * 1.000003 + cutoff * 0.0001 + gain * (band + 1);
            if (cutoff <= 250f) {
                lowBands.append(' ')
                        .append(Math.round(cutoff)).append(':')
                        .append(format(gain)).append(':').append(bandEnabled);
            }
        }
        Log.i(TAG, "ch=" + channel + " " + label
                + " inUse=" + inUse + " enabled=" + enabled + " bands=" + bandCount
                + " min=" + format(minGain) + " max=" + format(maxGain)
                + " hash=" + String.format(Locale.US, "%.5f", hash)
                + " low=" + lowBands);
    }

    private static String readLimiter(AudioEffect effect, int channel) {
        ByteBuffer value = get(effect, new int[]{PARAM_LIMITER, channel}, 32);
        if (value == null) {
            return "ERR";
        }
        int offset = 20;
        return "inUse=" + value.getInt(offset)
                + " enabled=" + value.getInt(offset + 4)
                + " link=" + value.getInt(offset + 8)
                + " attack=" + format(value.getFloat(offset + 12))
                + " release=" + format(value.getFloat(offset + 16))
                + " ratio=" + format(value.getFloat(offset + 20))
                + " threshold=" + format(value.getFloat(offset + 24))
                + " postGain=" + format(value.getFloat(offset + 28));
    }

    private static ByteBuffer get(AudioEffect effect, int[] parameters, int valueBytes) {
        int parameterBytes = parameters.length * Integer.BYTES;
        int alignedParameterBytes = (parameterBytes + 3) & ~3;
        ByteBuffer request = ByteBuffer.allocate(12 + alignedParameterBytes)
                .order(ByteOrder.nativeOrder());
        request.putInt(0);
        request.putInt(parameterBytes);
        request.putInt(valueBytes);
        for (int parameter : parameters) {
            request.putInt(parameter);
        }
        byte[] reply = new byte[12 + alignedParameterBytes + valueBytes];
        int result = nativeCommand(effect, GET_PARAM, request.array(), reply);
        ByteBuffer response = ByteBuffer.wrap(reply).order(ByteOrder.nativeOrder());
        if (result < AudioEffect.SUCCESS || response.getInt(0) < AudioEffect.SUCCESS
                || response.getInt(4) != parameterBytes || response.getInt(8) < valueBytes) {
            return null;
        }
        return response;
    }

    private static String format(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static native int nativeCommand(AudioEffect effect,
                                            int commandCode,
                                            byte[] command,
                                            byte[] reply);
}
