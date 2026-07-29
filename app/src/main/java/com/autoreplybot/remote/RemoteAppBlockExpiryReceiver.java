package com.autoreplybot.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Alarm callback: expire timed app / camera blocks. */
public final class RemoteAppBlockExpiryReceiver extends BroadcastReceiver {
    public static final String ACTION_EXPIRE = "com.autoreplybot.remote.APP_BLOCK_EXPIRE";
    public static final String EXTRA_PACKAGE = "packageName";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!ACTION_EXPIRE.equals(intent.getAction())) return;
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        if (pkg == null || pkg.isEmpty()) {
            new RemoteAppBlockManager(context).purgeExpiredAndSync();
        } else {
            new RemoteAppBlockManager(context).expireIfDue(pkg);
        }
    }
}
