package com.autoreplybot;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Sends a reply through a notification's inline reply action (RemoteInput), when the messaging app exposes it.
 */
public final class NotificationReplyHelper {

    private static final String TAG = "NotificationRelay";

    private NotificationReplyHelper() {}

    /** Called on the main thread after {@link PendingIntent#send} returns (success or cancellation). */
    public interface ReplySendListener {
        void onSendFinished(boolean pendingIntentSucceeded);
    }

    /**
     * Holds the reply action extracted on the listener thread before any async work (OpenAI network).
     */
    public static final class ReplyPayload {
        @NonNull public final PendingIntent actionIntent;
        @NonNull public final RemoteInput[] remoteInputs;

        ReplyPayload(@NonNull PendingIntent actionIntent, @NonNull RemoteInput[] remoteInputs) {
            this.actionIntent = actionIntent;
            this.remoteInputs = remoteInputs;
        }
    }

    public static boolean hasReplyAction(@Nullable Notification notification) {
        return extractReplyPayload(notification) != null;
    }

    @Nullable
    public static ReplyPayload extractReplyPayload(@Nullable Notification notification) {
        if (notification == null || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs != null && inputs.length > 0 && action.actionIntent != null) {
                return new ReplyPayload(action.actionIntent, inputs);
            }
        }
        return null;
    }

    /**
     * Sends inline reply using payload captured earlier. Prefer calling from the main thread after OpenAI returns.
     */
    public static boolean sendReplyWithPayload(@NonNull Context context,
                                               @NonNull ReplyPayload payload,
                                               @NonNull String replyText) {
        Intent intent = new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle bundle = new Bundle();
        for (RemoteInput ri : payload.remoteInputs) {
            bundle.putCharSequence(ri.getResultKey(), replyText);
        }
        RemoteInput.addResultsToIntent(payload.remoteInputs, intent, bundle);
        try {
            payload.actionIntent.send(context, 0, intent);
            Log.i(TAG, "Reply PendingIntent.send executed");
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "Reply PendingIntent canceled (notification may have been replaced or dismissed)", e);
            return false;
        }
    }

    /**
     * Posts {@link #sendReplyWithPayload} to the main looper if not already on it.
     * {@code listener} runs on the main thread after send returns (whether or not it succeeded).
     */
    public static void sendReplyWithPayloadMain(@NonNull Context context,
                                                @NonNull ReplyPayload payload,
                                                @NonNull String replyText,
                                                @Nullable ReplySendListener listener) {
        Context app = context.getApplicationContext();
        Runnable runSend = () -> {
            boolean ok = sendReplyWithPayload(app, payload, replyText);
            if (listener != null) {
                listener.onSendFinished(ok);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runSend.run();
            return;
        }
        new Handler(Looper.getMainLooper()).post(runSend);
    }

    /** @see #sendReplyWithPayloadMain(Context, ReplyPayload, String, ReplySendListener) */
    public static void sendReplyWithPayloadMain(@NonNull Context context,
                                                @NonNull ReplyPayload payload,
                                                @NonNull String replyText) {
        sendReplyWithPayloadMain(context, payload, replyText, (ReplySendListener) null);
    }

    /**
     * @deprecated Prefer {@link #extractReplyPayload} + {@link #sendReplyWithPayloadMain} after async work.
     */
    @Deprecated
    public static boolean sendReply(@NonNull Context context,
                                    @NonNull Notification notification,
                                    @NonNull String replyText) {
        ReplyPayload p = extractReplyPayload(notification);
        if (p == null) return false;
        return sendReplyWithPayload(context.getApplicationContext(), p, replyText);
    }
}
