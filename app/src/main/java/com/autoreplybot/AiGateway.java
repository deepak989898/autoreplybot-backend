package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;

/**
 * Provider-neutral text generation boundary. Implementations may perform blocking network I/O;
 * callers must invoke this API from a worker thread.
 */
public interface AiGateway {
    @WorkerThread
    @Nullable
    String completeJson(@NonNull String apiKey,
                        @NonNull String systemPrompt,
                        @NonNull String userContent,
                        int maxTokens,
                        double temperature) throws IOException;
}
