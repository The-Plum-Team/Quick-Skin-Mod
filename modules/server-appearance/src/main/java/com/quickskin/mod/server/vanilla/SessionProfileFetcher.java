package com.quickskin.mod.server.vanilla;

import java.io.IOException;
import java.util.UUID;

/** Blocking session-server lookup; always called on the sharing worker, never the server thread. */
@FunctionalInterface
public interface SessionProfileFetcher {
    Response fetch(UUID profileId) throws IOException;

    /**
     * One HTTP answer. {@code body} is the bounded UTF-8 body of a 200 answer and empty otherwise;
     * {@code retryAfterMillis} is the server's bounded Retry-After hint, or zero.
     */
    record Response(int statusCode, String body, long retryAfterMillis) {
        public Response {
            body = body == null ? "" : body;
            retryAfterMillis = Math.max(0L, retryAfterMillis);
        }
    }
}
