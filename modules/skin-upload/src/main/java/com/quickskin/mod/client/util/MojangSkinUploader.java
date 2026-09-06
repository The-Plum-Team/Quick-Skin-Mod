package com.quickskin.mod.client.util;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.SafeImageReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles uploading skins to Mojang's API
 */
public class MojangSkinUploader {
    private static final String UPLOAD_URL = "https://api.minecraftservices.com/minecraft/profile/skins";
    static final int MAX_SKIN_BYTES = (int) SafeImageReader.MAX_ENCODED_BYTES;
    static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    public static class UploadResult {
        public final boolean success;
        public final String message;
        public final int statusCode;

        public UploadResult(boolean success, String message, int statusCode) {
            this.success = success;
            this.message = message;
            this.statusCode = statusCode;
        }
    }

    /**
     * Upload a skin to Mojang's servers
     * @param metadata The skin metadata containing file path and model info
     * @return UploadResult containing success status and message
     */
    public static UploadResult uploadSkin(AssetMetadata metadata) {
        HttpURLConnection connection = null;
        try {
            // Get access token from Minecraft session
            Minecraft minecraft = Minecraft.getInstance();
            User user = minecraft.getUser();
            String accessToken = user.getAccessToken();

            if (accessToken == null || accessToken.isEmpty()) {
                return new UploadResult(false, Component.translatable("quickskin.error.no_access_token").getString(), 401);
            }

            // Read skin file
            byte[] skinData = readSkinData(metadata.path());

            // Determine skin variant
            String variant = "slim".equals(metadata.skinModel() != null ? metadata.skinModel().toLowerCase(Locale.ROOT) : null) ? "slim" : "classic";

            // Generate unique boundary for this upload
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");

            // Create HTTP connection
            connection = (HttpURLConnection) URI.create(UPLOAD_URL).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            // Build multipart form data
            try (OutputStream os = connection.getOutputStream();
                 DataOutputStream writer = new DataOutputStream(os)) {

                // Write variant field
                writer.writeBytes("--" + boundary + "\r\n");
                writer.writeBytes("Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
                writer.writeBytes(variant + "\r\n");

                // Write file field
                writer.writeBytes("--" + boundary + "\r\n");
                writer.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
                writer.writeBytes("Content-Type: image/png\r\n\r\n");
                writer.write(skinData);
                writer.writeBytes("\r\n");

                // End boundary
                writer.writeBytes("--" + boundary + "--\r\n");
                writer.flush();
            }

            // Get response
            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                return new UploadResult(true, Component.translatable("quickskin.error.upload_success").getString(), responseCode);
            } else if (responseCode == 400) {
                String error = readErrorStream(connection);
                return new UploadResult(false, Component.translatable("quickskin.error.invalid_request", error).getString(), responseCode);
            } else if (responseCode == 401) {
                return new UploadResult(false, Component.translatable("quickskin.error.auth_failed").getString(), responseCode);
            } else if (responseCode == 429) {
                return new UploadResult(false, Component.translatable("quickskin.error.rate_limit").getString(), responseCode);
            } else {
                String error = readErrorStream(connection);
                return new UploadResult(false, Component.translatable("quickskin.error.upload_failed", responseCode, error).getString(), responseCode);
            }

        } catch (IOException e) {
            return new UploadResult(false, Component.translatable("quickskin.error.network_error", e.getMessage()).getString(), 0);
        } catch (Exception e) {
            return new UploadResult(false, Component.translatable("quickskin.error.unexpected_error", e.getMessage()).getString(), 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static byte[] readSkinData(Path path) throws IOException {
        return BoundedFileReader.readBytes(path, MAX_SKIN_BYTES);
    }

    static String readErrorBody(InputStream errorStream) throws IOException {
        byte[] body = BoundedFileReader.readBytes(errorStream, MAX_ERROR_BODY_BYTES);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private static String readErrorStream(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) return Component.translatable("quickskin.error.unknown").getString();
            return readErrorBody(errorStream);
        } catch (IOException e) {
            return Component.translatable("quickskin.error.read_error_message").getString();
        }
    }
}
