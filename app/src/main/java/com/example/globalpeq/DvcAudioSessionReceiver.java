package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** Receives player audio-effect session events even while the foreground service is not running. */
public final class DvcAudioSessionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            DvcAudioSessionRegistry.clear(context);
            return;
        }
        int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        String ownerPackage = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            DvcAudioSessionRegistry.recordOpen(context, sessionId, ownerPackage);
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            DvcAudioSessionRegistry.recordClose(context, sessionId, ownerPackage);
        }
    }
}
