package com.quickskin.mod.client.gui.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinImporterFormatTest {

    @Test
    void aSkinCanBeAPngWebpOrJpgUnderEitherJpgExtension() {
        assertTrue(SkinImporter.isSkinImage(Path.of("skin.png")));
        assertTrue(SkinImporter.isSkinImage(Path.of("skin.WebP")));
        assertTrue(SkinImporter.isSkinImage(Path.of("skin.jpg")));
        assertTrue(SkinImporter.isSkinImage(Path.of("folder.gif", "skin.JPEG")));
    }

    @Test
    void anythingElseIsNotASkinImage() {
        assertFalse(SkinImporter.isSkinImage(Path.of("cape.gif")));
        assertFalse(SkinImporter.isSkinImage(Path.of("model.cpmmodel")));
        assertFalse(SkinImporter.isSkinImage(Path.of("skin.png", "notes.txt")));
        assertFalse(SkinImporter.isSkinImage(Path.of("jpeg")));
        assertFalse(SkinImporter.isSkinImage(null));
    }
}
