package com.quickskin.mod.client.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MojangSkinUploaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsUploadSkinThroughTheConfiguredBound() throws IOException {
        byte[] png = "bounded-png".getBytes(StandardCharsets.UTF_8);
        Path skin = temporaryDirectory.resolve("skin.png");
        java.nio.file.Files.write(skin, png);

        assertArrayEquals(png, MojangSkinUploader.readSkinData(skin));
    }

    @Test
    void rejectsUploadSkinLargerThanTheConfiguredBound() throws IOException {
        Path skin = temporaryDirectory.resolve("oversized.png");
        try (FileChannel channel = FileChannel.open(skin, CREATE_NEW, WRITE)) {
            channel.position(MojangSkinUploader.MAX_SKIN_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertThrows(IOException.class, () -> MojangSkinUploader.readSkinData(skin));
    }

    @Test
    void readsErrorBodiesWithoutChangingExistingLineJoining() throws IOException {
        ByteArrayInputStream body = new ByteArrayInputStream(
                "first line\r\nsecond line\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("first linesecond line", MojangSkinUploader.readErrorBody(body));
    }

    @Test
    void rejectsErrorBodiesLargerThanTheConfiguredBound() {
        ByteArrayInputStream body = new ByteArrayInputStream(
                new byte[MojangSkinUploader.MAX_ERROR_BODY_BYTES + 1]);

        assertThrows(IOException.class, () -> MojangSkinUploader.readErrorBody(body));
    }

    @Test
    void readsTheActiveSkinMojangKeptFromTheUploadReply() {
        byte[] reply = ("{\"id\":\"032787a029eb4c7ea7ab7e3125ec0754\",\"name\":\"AkaNebur\",\"skins\":["
                + "{\"id\":\"a\",\"state\":\"INACTIVE\",\"url\":\"http://textures.minecraft.net/texture/old\","
                + "\"variant\":\"CLASSIC\"},"
                + "{\"id\":\"b\",\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/new\","
                + "\"variant\":\"SLIM\"}],\"capes\":[]}").getBytes(StandardCharsets.UTF_8);

        String[] active = MojangSkinUploader.activeSkin(reply);

        assertEquals("http://textures.minecraft.net/texture/new", active[0]);
        assertEquals("slim", active[1]);
    }

    @Test
    void aReplyWithoutAnActiveSkinNamesNone() {
        assertNull(MojangSkinUploader.activeSkin("{\"skins\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertNull(MojangSkinUploader.activeSkin("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(MojangSkinUploader.activeSkin("[1]".getBytes(StandardCharsets.UTF_8)));
    }
}
