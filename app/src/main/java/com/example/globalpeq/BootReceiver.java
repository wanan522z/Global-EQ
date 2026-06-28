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
        if (repository.loadProcessingMode() == ProcessingMode.SHIZUKU_MUTE) {
            repository.clearRuntimeAudioState(ShizukuCompat.describeState(context));
            return;
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
