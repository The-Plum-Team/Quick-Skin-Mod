package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static com.quickskin.mod.client.services.MojangApiService.Reason.*;
import static com.quickskin.mod.client.services.MojangApiService.Stage.*;

class MojangApiServiceTest {
    private static final String ID = "10920508d5d83eed93d292f193afe7d7";
    private static final String NAME_URL = "https://api.mojang.com/users/profiles/minecraft/Alice";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/" + ID;
    private static final String TEXTURE_URL = "https://textures.minecraft.net/texture/" + "a".repeat(64);
    private static final String UUID_BODY = "{\"id\":\"" + ID + "\"}";
    private static final MojangApiService.Stage[] STAGES = {LOOKUP, PROFILE, DOWNLOAD};

    @Test
    void httpFailuresTerminateAtTheirActualStage() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            for (int status : new int[]{204, 400, 404, 429, 503}) {
                var transport = successfulTransport(false);
                transport.responses.set(stage, new Reply(status, utf8("untrusted upstream body")));
                var failure = requireFailure(transport.service().fetchSkinByUsername("Alice"), STAGES[stage],
                        stage == 0 && (status == 404 || status == 204) ? NOT_FOUND : HTTP, status);
                assertFalse(failure.getMessage().contains("untrusted upstream body"));
                assertEquals(stage + 1, transport.opened.size(), "no request after failed stage " + stage);
                transport.assertClosed();
            }
        }
    }

    @Test
    void transportFailureIsExceptionalAndDoesNotContinue() {
        List<URL> attempted = new ArrayList<>();
        MojangApiService service = new MojangApiService(url -> {
            attempted.add(url);
            throw new IOException("controlled transport failure");
        });
        requireFailure(service.fetchSkinByUsername("Alice"), LOOKUP, NETWORK, null);
        assertEquals(List.of(NAME_URL), attempted.stream().map(URL::toString).toList());
    }

    @Test
    void invalidNamesDoNotIssueRequests() {
        for (String name : new String[]{null, "", "Alice Smith", "../Alice", "name-too-long-12345", "A?x=y", "\u00c1lice"}) {
            var transport = new Transport();
            requireFailure(transport.service().fetchSkinByUsername(name), LOOKUP, INVALID_USERNAME, null);
            assertTrue(transport.opened.isEmpty());
        }
    }

    @Test
    void existingAsciiNameRangeIsPreserved() throws Exception {
        for (String name : new String[]{"A", "_", "Abc123__________"}) {
            var transport = new Transport(new Reply(200, utf8(UUID_BODY)));
            assertEquals(UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7"),
                    transport.service().getUuidFromUsername(name).get(5, TimeUnit.SECONDS));
            assertEquals("https://api.mojang.com/users/profiles/minecraft/" + name,
                    transport.opened.get(0).getURL().toString());
            transport.assertClosed();
        }
    }

    @Test
    void responseReadFailuresRetainStageAndHttpStatus() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            var transport = successfulTransport(false);
            transport.responses.get(stage).readFailure = new IOException("private transport detail");
            var failure = requireFailure(transport.service().fetchSkinByUsername("Alice"), STAGES[stage], NETWORK, 200);
            assertFalse(failure.getMessage().contains("private transport detail"));
            assertEquals(stage + 1, transport.opened.size());
            transport.assertClosed();
        }
    }

    @Test
    void malformedNameResponsesDoNotReachProfileLookup() {
        for (String body : new String[]{"{", "[]", "{}", "{\"id\":true}", "{\"id\":123}",
                "{\"id\":null}", "{\"id\":\"x\"}", "{\"id\":\"10920508-d5d8-3eed-93d2-92f193afe7d7\"}"}) {
            var transport = new Transport(new Reply(200, utf8(body)));
            requireFailure(transport.service().fetchSkinByUsername("Alice"), LOOKUP, INVALID_RESPONSE, 200);
            assertEquals(1, transport.opened.size());
            transport.assertClosed();
        }
    }

    @Test
    void malformedProfilesAreFailuresWithoutImageRequests() {
        for (String body : new String[]{"{", "[]", "{}", "{\"properties\":[]}",
                "{\"properties\":[{\"name\":\"other\",\"value\":\"ignored\"}]}",
                "{\"properties\":[{\"name\":\"textures\",\"value\":true}]}",
                "{\"properties\":[{\"name\":\"textures\",\"value\":\"!\"}]}",
                profile("not-json", false), profile("{}", false),
                profile("{\"textures\":[]}", false), profile("{\"textures\":{\"SKIN\":{}}}", false),
                profile("{\"textures\":{\"SKIN\":null}}", false),
                profile(texture(TEXTURE_URL).replace("\"slim\"", "true"), false)}) {
            var transport = new Transport(new Reply(200, utf8(UUID_BODY)), new Reply(200, utf8(body)));
            requireFailure(transport.service().fetchSkinByUsername("Alice"), PROFILE, INVALID_RESPONSE, 200);
            assertEquals(2, transport.opened.size());
            transport.assertClosed();
        }
    }

    @Test
    void validProfileWithoutCustomSkinIsAnExplicitFailure() {
        var transport = new Transport(new Reply(200, utf8(UUID_BODY)),
                new Reply(200, utf8(profile("{\"textures\":{}}", false))));
        requireFailure(transport.service().fetchSkinByUsername("Alice"), PROFILE, NO_CUSTOM_SKIN, 200);
        assertEquals(2, transport.opened.size());
        transport.assertClosed();
    }

    @Test
    void namedTexturesPropertyNeedNotBeFirst() throws Exception {
        var transport = successfulTransport(true);
        assertSuccess(transport);
    }

    @Test
    void successPreservesUuidPixelsModelAndTransportProtections() throws Exception {
        var transport = successfulTransport(false);
        assertSuccess(transport);
        assertEquals(List.of(NAME_URL, PROFILE_URL, TEXTURE_URL),
                transport.opened.stream().map(connection -> connection.getURL().toString()).toList());
        for (int index = 0; index < transport.opened.size(); index++) {
            var connection = transport.opened.get(index);
            assertFalse(connection.getInstanceFollowRedirects());
            assertEquals("GET", connection.getRequestMethod());
            assertEquals(index == 2 ? 10000 : 5000, connection.getReadTimeout());
            assertEquals(connection.getReadTimeout(), connection.getConnectTimeout());
            assertEquals("application/json,image/png", connection.getRequestProperty("Accept"));
        }
    }

    @Test
    void rejectedTextureUrlsNeverReachDownloadTransport() {
        for (String url : new String[]{"https://example.com/texture/" + "a".repeat(64),
                "https://textures.minecraft.net.evil.test/texture/" + "a".repeat(64),
                TEXTURE_URL + "?redirect=yes", TEXTURE_URL.replace("https:", "file:")}) {
            var transport = new Transport(new Reply(200, utf8(UUID_BODY)),
                    new Reply(200, utf8(profile(texture(url), false))));
            requireFailure(transport.service().fetchSkinByUsername("Alice"), PROFILE, INVALID_RESPONSE, 200);
            assertEquals(2, transport.opened.size());
        }
    }

    @Test
    void historicalHttpTextureIsUpgradedBeforeRequest() throws Exception {
        var transport = successfulTransport(false);
        transport.responses.set(1, new Reply(200,
                utf8(profile(texture(TEXTURE_URL.replace("https:", "http:")), false))));
        assertSuccess(transport);
        assertEquals(TEXTURE_URL, transport.opened.get(2).getURL().toString());
    }

    @Test
    void advertisedAndStreamingBodyBoundsRemainEnforced() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            int maximum = stage == 2 ? 2 * 1024 * 1024 : 64 * 1024;
            for (boolean advertised : new boolean[]{true, false}) {
                var transport = successfulTransport(false);
                Reply oversized = new Reply(200, new byte[maximum + 1]);
                oversized.advertisedLength = advertised ? maximum + 1 : -1;
                transport.responses.set(stage, oversized);
                requireFailure(transport.service().fetchSkinByUsername("Alice"), STAGES[stage], RESPONSE_TOO_LARGE, 200);
                assertEquals(stage + 1, transport.opened.size());
                assertTrue(transport.opened.get(stage).bytesRead <= maximum + 1);
                transport.assertClosed();
            }
        }
    }

    @Test
    void invalidPngIsNotAnAbsentPlayer() throws Exception {
        var transport = successfulTransport(false);
        transport.responses.set(2, new Reply(200, utf8("not a PNG")));
        requireFailure(transport.service().fetchSkinByUsername("Alice"), DOWNLOAD, INVALID_RESPONSE, 200);
        assertEquals(3, transport.opened.size());
        transport.assertClosed();
    }

    @Test
    void directInvalidProfileOrTextureInputDoesNotRequestAnything() {
        var transport = new Transport();
        requireFailure(transport.service().getSkinTextureData(null), PROFILE, INVALID_RESPONSE, null);
        requireFailure(transport.service().downloadSkinImage("https://example.com/skin.png"), DOWNLOAD, INVALID_RESPONSE, null);
        assertTrue(transport.opened.isEmpty());
    }

    private static MojangApiService.ImportFailure requireFailure(CompletableFuture<?> future,
            MojangApiService.Stage stage, MojangApiService.Reason reason, Integer status) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS),
                "a failed import must complete exceptionally, never as a successful null result");
        var failure = MojangApiService.findFailure(thrown);
        assertNotNull(failure, "the failure must preserve its classified service cause");
        assertEquals(stage, failure.stage());
        assertEquals(reason, failure.reason());
        assertEquals(status, failure.httpStatus());
        return failure;
    }

    private static void assertSuccess(Transport transport) throws Exception {
        var result = transport.service().fetchSkinByUsername("Alice").get(5, TimeUnit.SECONDS);
        assertNotNull(result, "valid named textures must import");
        assertEquals(UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7"), result.uuid);
        assertEquals("Alice", result.username);
        assertEquals("slim", result.modelType);
        assertEquals(64, result.image.getWidth());
        assertEquals(64, result.image.getHeight());
        assertArrayEquals(fixtureImage().getRGB(0, 0, 64, 64, null, 0, 64),
                result.image.getRGB(0, 0, 64, 64, null, 0, 64));
        transport.assertClosed();
    }

    private static Transport successfulTransport(boolean reordered) throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(fixtureImage(), "PNG", bytes);
        return new Transport(new Reply(200, utf8(UUID_BODY)),
                new Reply(200, utf8(profile(texture(TEXTURE_URL), reordered))), new Reply(200, bytes.toByteArray()));
    }

    private static BufferedImage fixtureImage() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            image.setRGB(x, y, 0xff000000 | (x << 16) | (y << 8) | 0x55);
        }
        return image;
    }

    private static String texture(String url) {
        return "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\",\"metadata\":{\"model\":\"slim\"}}}}";
    }

    private static String profile(String decoded, boolean reordered) {
        String extra = reordered ? "{\"name\":\"other\",\"value\":\"not-textures\"}," : "";
        return "{\"properties\":[" + extra + "{\"name\":\"textures\",\"value\":\""
                + Base64.getEncoder().encodeToString(utf8(decoded)) + "\"}]}";
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class Reply {
        final int status;
        final byte[] body;
        long advertisedLength;
        IOException readFailure;
        Reply(int status, byte[] body) {
            this.status = status;
            this.body = body;
            advertisedLength = body.length;
        }
    }

    private static final class Transport {
        final List<Reply> responses = new ArrayList<>();
        final List<Connection> opened = new ArrayList<>();
        Transport(Reply... replies) { responses.addAll(List.of(replies)); }
        MojangApiService service() {
            return new MojangApiService(url -> {
                if (opened.size() >= responses.size()) throw new AssertionError("unexpected request " + url);
                var connection = new Connection(url, responses.get(opened.size()));
                opened.add(connection);
                return connection;
            });
        }
        void assertClosed() { assertTrue(opened.stream().allMatch(connection -> connection.disconnected)); }
    }

    private static final class Connection extends HttpURLConnection {
        final Reply reply;
        boolean disconnected;
        int bytesRead;
        Connection(URL url, Reply reply) { super(url); this.reply = reply; }
        @Override public int getResponseCode() { return reply.status; }
        @Override public long getContentLengthLong() { return reply.advertisedLength; }
        @Override public InputStream getInputStream() throws IOException {
            if (reply.readFailure != null) throw reply.readFailure;
            return new FilterInputStream(new ByteArrayInputStream(reply.body)) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    if (count > 0) bytesRead += count;
                    return count;
                }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
