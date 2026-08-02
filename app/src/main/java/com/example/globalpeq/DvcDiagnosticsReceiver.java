package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

import java.lang.reflect.Constructor;
import java.util.UUID;

/** Temporary read-only on-device probe; removed after the Poweramp comparison. */
public final class DvcDiagnosticsReceiver extends BroadcastReceiver {
    private static final UUID DP_TYPE =
            UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e");
    private static final UUID NULL_IMPLEMENTATION =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    @Override
    public void onReceive(Context context, Intent intent) {
        AudioEffect effect = null;
        try {
            Constructor<AudioEffect> constructor = AudioEffect.class.getConstructor(
                    UUID.class, UUID.class, int.class, int.class);
            effect = constructor.newInstance(DP_TYPE, NULL_IMPLEMENTATION, 0, 0);
            PowerampDvcRawBridge bridge = new PowerampDvcRawBridge();
            Float left = bridge.readInputGain(effect, 0);
            Float right = bridge.readInputGain(effect, 1);
            setResultData("control=" + effect.hasControl() + " inputGain=[" + left + "," + right + "]");
        } catch (ReflectiveOperationException | RuntimeException error) {
            setResultData("error=" + error);
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
    }
}
