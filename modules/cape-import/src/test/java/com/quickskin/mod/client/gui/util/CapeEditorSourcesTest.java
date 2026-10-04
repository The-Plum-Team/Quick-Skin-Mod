package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.util.GifDecoder;
import com.quickskin.mod.common.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeEditorSourcesTest {
    private static final GifDecoder NO_GIF = input -> {
        throw new AssertionError("PNG input must not invoke the native GIF decoder");
    };

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsTheOriginalPngUnderTheSavedCapeContentId() throws IOException {
        Path source = writePng("photo.png", 100, 80, 0xff224466);
        Path cape = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, NO_GIF), atlas(1, 0xff117788), capes(), cache(), null);

        Path retained = CapeEditorSources.retain(cache(), cape, source);

        assertEquals(retained, CapeEditorSources.find(cache(), contentId(cape)));
        assertTrue(retained.getFileName().toString().endsWith(".png"));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(retained));
        assertEquals(List.of(cape), files(capes()), "the source must not become a second cape");

        CapeImportProcessor.PreparedCape reopened = CapeImportProcessor.prepare(retained, NO_GIF);
        assertEquals(100, reopened.atlas().getWidth());
        assertEquals(80, reopened.atlas().getHeight());
        assertFalse(reopened.standardFormat());
    }

    @Test
    void retainsTheOriginalGifWithItsFramesAndTiming() throws IOException {
        Path source = temporaryDirectory.resolve("animated.gif");
        Files.write(source, new byte[] {42});
        AnimationMetadata metadata = new AnimationMetadata(List.of(
                new AnimationMetadata.FrameData(50, 0),
                new AnimationMetadata.FrameData(125, 1)), 2);
        GifDecoder decoder = input -> {
            assertEquals(42, input.read());
            return new GifDecoder.DecodedGif(
                    new BufferedImage(100, 160, BufferedImage.TYPE_INT_ARGB), 2, metadata);
        };
        Path cape = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, decoder), atlas(2, 0xff117788), capes(), cache(), null);

        Path retained = CapeEditorSources.retain(cache(), cape, source);

        assertEquals(retained, CapeEditorSources.find(cache(), contentId(cape)));
        assertTrue(retained.getFileName().toString().endsWith(".gif"));
        assertTrue(Files.isRegularFile(cache().resolve(contentId(cape) + ".json")));
        CapeImportProcessor.PreparedCape reopened = CapeImportProcessor.prepare(retained, decoder);
        assertEquals(2, reopened.frameCount());
        assertSame(metadata, reopened.animationMetadata());
    }

    @Test
    void replacingAnAdjustedCapeMovesItsSourceToTheNewContentId() throws IOException {
        Path source = writePng("photo.png", 100, 80, 0xff224466);
        Path cape = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, NO_GIF), atlas(1, 0xff117788), capes(), cache(), null);
        String oldId = contentId(cape);
        Path oldSource = CapeEditorSources.retain(cache(), cape, source);

        Path replaced = CapeImportProcessor.replaceAdjusted(
                CapeImportProcessor.prepare(oldSource, NO_GIF), atlas(1, 0xff884422), cape, cache(), null);

        String newId = contentId(cape);
        assertEquals(cape, replaced);
        assertNotEquals(oldId, newId);
        assertEquals(List.of(cape), files(capes()), "the edit must not add or leave another file");
        assertArrayEquals(Files.readAllBytes(source),
                Files.readAllBytes(CapeEditorSources.find(cache(), newId)));
        assertEquals(oldSource, CapeEditorSources.find(cache(), oldId),
                "a byte-identical twin may still need the old source; the caller decides");

        CapeEditorSources.delete(cache(), oldId);

        assertNull(CapeEditorSources.find(cache(), oldId));
        assertArrayEquals(Files.readAllBytes(source),
                Files.readAllBytes(CapeEditorSources.find(cache(), newId)));
    }

    @Test
    void aFailedEditLeavesTheCapeAndItsSourceUntouched() throws IOException {
        Path source = writePng("photo.png", 100, 80, 0xff224466);
        Path cape = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, NO_GIF), atlas(1, 0xff117788), capes(), cache(), null);
        Path retained = CapeEditorSources.retain(cache(), cape, source);
        byte[] capeBytes = Files.readAllBytes(cape);
        CapeImportProcessor.PreparedCape prepared = CapeImportProcessor.prepare(retained, NO_GIF);

        assertThrows(IOException.class, () -> CapeImportProcessor.replaceAdjusted(
                prepared, new BufferedImage(64, 33, BufferedImage.TYPE_INT_ARGB), cape, cache(), null));

        assertEquals(List.of(retained), files(retained.getParent()));

        // The source vanishes after the editor decoded it: the new association cannot be written.
        Files.delete(retained);
        assertThrows(IOException.class, () -> CapeImportProcessor.replaceAdjusted(
                prepared, atlas(1, 0xff884422), cape, cache(), null));

        assertArrayEquals(capeBytes, Files.readAllBytes(cape));
        assertEquals(List.of(cape), files(capes()), "no temporary file may be left behind");
    }

    @Test
    void findsOnlyARegularFileStoredUnderAValidContentId() throws IOException {
        Path source = writePng("photo.png", 100, 80, 0xff224466);
        Path first = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, NO_GIF), atlas(1, 0xff117788), capes(), cache(), null);
        Path second = CapeImportProcessor.saveAdjusted(
                CapeImportProcessor.prepare(source, NO_GIF), atlas(1, 0xff884422), capes(), cache(), null);
        Path firstSource = CapeEditorSources.retain(cache(), first, source);
        Path secondSource = CapeEditorSources.retain(cache(), second, source);

        assertNull(CapeEditorSources.find(null, contentId(first)));
        assertNull(CapeEditorSources.find(cache(), "../photo"));
        assertNull(CapeEditorSources.find(cache(), HashUtil.computeAssetContentId(new byte[] {1}, "cape")));

        CapeEditorSources.delete(cache(), contentId(first));
        Files.createDirectory(firstSource);

        assertNull(CapeEditorSources.find(cache(), contentId(first)),
                "a directory in place of the source is not a source");
        assertEquals(secondSource, CapeEditorSources.find(cache(), contentId(second)));
    }

    @Test
    void onlyAnImportThatWentThroughTheEditorRetainsItsSource() throws Exception {
        Path adjusted = writePng("photo.png", 100, 80, 0xff224466);
        Path standard = writePng("standard.png", 64, 32, 0xff224466);
        CompletableFuture<CapeImportWorkflow.Summary> finished = new CompletableFuture<>();

        new CapeImportWorkflow(List.of(standard, adjusted), capes(), cache(), null, NO_GIF,
                Runnable::run,
                (prepared, apply, cancel) -> apply.accept(atlas(1, 0xff117788)),
                finished::complete).start();

        assertEquals(2, finished.get(30, TimeUnit.SECONDS).succeeded());
        List<Path> retained = files(cache().resolve("cape_editor_sources"));
        assertEquals(1, retained.size());
        assertArrayEquals(Files.readAllBytes(adjusted), Files.readAllBytes(retained.get(0)));
        assertEquals(retained.get(0),
                CapeEditorSources.find(cache(), contentId(capes().resolve("photo.png"))));
        assertNull(CapeEditorSources.find(cache(), contentId(capes().resolve("standard.png"))));
    }

    private Path capes() {
        return temporaryDirectory.resolve("capes");
    }

    private Path cache() {
        return temporaryDirectory.resolve("cache");
    }

    private Path writePng(String name, int width, int height, int argb) throws IOException {
        Path file = temporaryDirectory.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, argb);
        assertTrue(ImageIO.write(image, "png", file.toFile()));
        return file;
    }

    /** An editor output of 64x32 frames; the colour makes two edits differ in content. */
    private static BufferedImage atlas(int frameCount, int argb) {
        BufferedImage atlas = new BufferedImage(64, 32 * frameCount, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frameCount; frame++) {
            atlas.setRGB(1, 32 * frame + 1, argb);
        }
        return atlas;
    }

    private static String contentId(Path cape) throws IOException {
        return HashUtil.computeAssetContentId(Files.readAllBytes(cape), "cape");
    }

    private static List<Path> files(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        }
    }
}
