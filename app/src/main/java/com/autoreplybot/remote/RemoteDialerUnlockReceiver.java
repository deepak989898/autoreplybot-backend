package com.autoreplybot.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Unlocks the hidden app from the Phone dialer:
 * <ul>
 *   <li>{@code android.provider.Telephony.SECRET_CODE} — dial {@code *#*#PASSCODE#*#*}</li>
 *   <li>{@code NEW_OUTGOING_CALL} — dial {@code *#PASSCODE#} / passcode and place call
 *       (best-effort; many Android 10+ builds no longer deliver this to 3rd-party apps)</li>
 * </ul>
 */
public final class RemoteDialerUnlockReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteDialerUnlockRx";
    private static final String ACTION_NEW_OUTGOING_CALL = "android.intent.action.NEW_OUTGOING_CALL";
    private static final String ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        try {
            if (ACTION_SECRET_CODE.equals(action)) {
                String code = RemoteDialerUnlock.secretCodeFromIntent(intent);
                Log.i(TAG, "SECRET_CODE received host=" + code);
                if ("456789".equals(code)) {
                    Intent pin = new Intent(context, RemoteDialerPinActivity.class);
                    pin.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(pin);
                    return;
                }
                if (RemoteDialerUnlock.tryOpenFromDialInput(context, code)) {
                    return;
                }
                return;
            }
            if (ACTION_NEW_OUTGOING_CALL.equals(action)) {
                String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
                if (number == null) {
                    number = getResultData();
                }
                // Vivo often ignores *#*#SECRET_CODE#*#* — allow Call on *#PASSCODE# / *PASSCODE# / PASSCODE#
                if (RemoteDialerUnlock.matchesOutgoingUnlock(context, number)) {
                    Log.i(TAG, "Outgoing dial matched unlock pattern; cancelling call and opening app");
                    setResultData(null);
                    abortBroadcast();
                    RemoteDialerUnlock.openApp(context);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onReceive failed", t);
        }
    }
}
