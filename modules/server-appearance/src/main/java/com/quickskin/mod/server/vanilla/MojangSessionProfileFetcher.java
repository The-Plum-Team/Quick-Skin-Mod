package com.quickskin.mod.server.vanilla;

import com.quickskin.mod.common.util.BoundedFileReader;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads a profile with its signed properties from Mojang's public session server.
 *
 * <p>The request is anonymous: it sends no player token, server key or other credential, only the
 * profile id that Mojang already authenticated when the player joined.</p>
 */
public final class MojangSessionProfileFetcher implements SessionProfileFetcher {
    public static final String SESSION_PROFILE_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final long MAX_RETRY_AFTER_MILLIS = 5L * 60 * 1000;

    private final String urlTemplate;

    public MojangSessionProfileFetcher() {
        this(SESSION_PROFILE_URL);
    }

    /** Visible for local tests that point the lookup at a fixture server. */
    public MojangSessionProfileFetcher(String urlTemplate) {
        if (urlTemplate == null || !urlTemplate.contains("%s")) {
            throw new IllegalArgumentException("The session profile URL needs a %s placeholder");
        }
        this.urlTemplate = urlTemplate;
    }

    @Override
    public Response fetch(UUID profileId) throws IOException {
        URI uri = URI.create(String.format(urlTemplate, AccountTextures.undashed(profileId)));
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                drain(connection.getErrorStream());
                return new Response(status, "", retryAfterMillis(connection.getHeaderField("Retry-After")));
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] body = BoundedFileReader.readBytes(input, SessionProfile.MAX_RESPONSE_BYTES);
                return new Response(status, new String(body, StandardCharsets.UTF_8), 0L);
            }
        } finally {
            connection.disconnect();
        }
    }

    static long retryAfterMillis(String header) {
        if (header == null) return 0L;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0L) return 0L;
            return Math.min(MAX_RETRY_AFTER_MILLIS, seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // An HTTP-date is legal too; the bounded backoff already waits long enough.
            return 0L;
        }
    }

    private static void drain(InputStream errorStream) {
        if (errorStream == null) return;
        try (InputStream input = errorStream) {
            BoundedFileReader.readBytes(input, SessionProfile.MAX_RESPONSE_BYTES);
        } catch (IOException ignored) {
            // Only frees the connection; the status code already says what happened.
        }
    }
}
