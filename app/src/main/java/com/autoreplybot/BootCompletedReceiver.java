package com.autoreplybot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * Restores the next Facebook post run after device reboot (WorkManager persists work, but this realigns timing).
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        FacebookPostScheduler.scheduleNext(context.getApplicationContext());
    }
}
