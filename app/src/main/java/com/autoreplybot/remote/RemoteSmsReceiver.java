package com.autoreplybot.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * Live SMS mirror for the website Messages tab.
 * Does not interfere with Auto Reply / WhatsApp NotificationListener flow.
 */
public class RemoteSmsReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteSmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (!new RemoteModulePrefs(context).isMessagesSharingEnabled()) return;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            Object pdusObj = extras.get("pdus");
            if (!(pdusObj instanceof Object[])) return;
            Object[] pdus = (Object[]) pdusObj;
            String format = extras.getString("format");
            StringBuilder body = new StringBuilder();
            String address = "";
            long when = System.currentTimeMillis();
            for (Object pdu : pdus) {
                if (!(pdu instanceof byte[])) continue;
                SmsMessage msg;
                if (Build.VERSION.SDK_INT >= 23 && format != null) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg == null) continue;
                if (address.isEmpty() && msg.getDisplayOriginatingAddress() != null) {
                    address = msg.getDisplayOriginatingAddress();
                }
                String part = msg.getMessageBody();
                if (part != null) body.append(part);
                if (msg.getTimestampMillis() > 0) when = msg.getTimestampMillis();
            }
            RemoteSmsMirror.onIncomingSms(context, address, body.toString(), when);
        } catch (Exception e) {
            Log.w(TAG, "SMS receive mirror failed", e);
        }
    }
}
