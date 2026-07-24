package com.autoreplybot;

import androidx.annotation.NonNull;

/** One turn in the chat-completions message list (user or assistant). */
public final class ChatHistoryMessage {

    @NonNull public final String role;
    @NonNull public final String content;

    public ChatHistoryMessage(@NonNull String role, @NonNull String content) {
        this.role = role;
        this.content = content;
    }
}
