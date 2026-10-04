package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/** Thrown when the backend returns {@code ACCOUNT_BLOCKED}. */
public final class RemoteAccountBlockedException extends IOException {
    @NonNull
    private final String serverMessage;

    public RemoteAccountBlockedException(@Nullable String message) {
        super(message != null && !message.isEmpty()
                ? message
                : "Your account has been disabled by an administrator.");
        this.serverMessage = getMessage() != null ? getMessage() : "";
    }

    @NonNull
    public String getServerMessage() {
        return serverMessage;
    }
}
