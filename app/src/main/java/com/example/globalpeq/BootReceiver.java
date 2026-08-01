package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PresetRepository repository = new PresetRepository(context);
        if (!repository.loadMasterEnabled()) {
            return;
        }
        ProcessingMode processingMode = repository.loadProcessingMode();
        if (processingMode.requiresShizukuMute()) {
            repository.clearRuntimeAudioState(ShizukuCompat.describeState(context));
            return;
        }
        if (processingMode == ProcessingMode.GLOBAL_DSP) {
            repository.clearRuntimeAudioState("Shizuku mute is idle.");
        }

        Intent service = new Intent(context, GlobalEqForegroundService.class);
        service.setAction(GlobalEqForegroundService.ACTION_APPLY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
