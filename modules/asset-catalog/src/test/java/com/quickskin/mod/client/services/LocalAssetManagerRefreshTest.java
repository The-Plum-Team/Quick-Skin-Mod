package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the manager side of the external-folder poll. The screens tick before anything guarantees
 * {@code init()} has run, so a poll landing on an uninitialized manager must be inert rather than
 * walking null directories.
 */
class LocalAssetManagerRefreshTest {

    @Test
    void refreshIsSkippedUntilTheManagerHasBeenInitialized() {
        LocalAssetManager manager = LocalAssetManager.getInstance();
        LocalAssetManager.ScanRequest request = manager.snapshotScanRequest();

        // Any fingerprint at all, including one that differs from the initial zero.
        assertFalse(manager.refreshIfChanged(request, 0L));
        assertFalse(manager.refreshIfChanged(request, 1234567L));
    }

    /**
     * The watch list must stay empty before {@code init()}, which also keeps a pre-bootstrap poll
     * from triggering the optional CPM classpath probe.
     */
    @Test
    void nothingIsWatchedBeforeInitialization() {
        LocalAssetManager manager = LocalAssetManager.getInstance();

        assertTrue(manager.getScannedDirectories().isEmpty());
        assertEquals(0L, LocalAssetFolderWatch.fingerprint(manager.getScannedDirectories()));
    }

    @Test
    void scanRequestOwnsAnImmutableDirectorySnapshot() {
        LocalAssetManager manager = LocalAssetManager.getInstance();
        List<Path> mutableDirectories = new ArrayList<>();
        mutableDirectories.add(Path.of("skins"));

        LocalAssetManager.ScanRequest request =
                new LocalAssetManager.ScanRequest(manager, 7L, true, mutableDirectories);
        mutableDirectories.add(Path.of("capes"));

        assertEquals(List.of(Path.of("skins")), request.directories());
        assertThrows(UnsupportedOperationException.class,
                () -> request.directories().add(Path.of("models")));
    }

    @Test
    void lifecycleInvalidationRejectsAnOlderAsyncResult() {
        LocalAssetManager manager = LocalAssetManager.getInstance();
        LocalAssetManager.ScanRequest captured = manager.snapshotScanRequest();

        assertTrue(manager.isCurrentScanRequest(captured));

        manager.invalidatePendingScans();

        assertFalse(manager.isCurrentScanRequest(captured));
        assertFalse(manager.refreshIfChanged(captured, 1234567L));
    }

    @Test
    void catalogSnapshotDefensivelyCopiesBothIndexes() {
        String hash = "sha256-" + "0".repeat(64);
        Path source = Path.of("skin.png");
        AssetMetadata metadata = AssetMetadata.forSkin(
                hash, "skin", source, SkinResolution.STANDARD, 128L, "classic", 1L);
        Map<String, AssetMetadata> mutableMetadata = new HashMap<>();
        Map<String, Path> mutablePaths = new HashMap<>();
        mutableMetadata.put(hash, metadata);
        mutablePaths.put(hash, source);

        LocalAssetManager.CatalogSnapshot snapshot =
                LocalAssetManager.CatalogSnapshot.copyOf(mutableMetadata, mutablePaths);
        mutableMetadata.clear();
        mutablePaths.clear();

        assertSame(metadata, snapshot.metadata().get(hash));
        assertEquals(source, snapshot.sourcePaths().get(hash));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.metadata().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.sourcePaths().clear());
    }

    @Test
    void catalogSnapshotRejectsMismatchedIndexes() {
        String hash = "sha256-" + "0".repeat(64);
        AssetMetadata metadata = AssetMetadata.forSkin(
                hash, "skin", Path.of("skin.png"), SkinResolution.STANDARD,
                128L, "classic", 1L);

        assertThrows(IllegalArgumentException.class, () ->
                LocalAssetManager.CatalogSnapshot.copyOf(Map.of(hash, metadata), Map.of()));
    }

    @Test
    void catalogResolvesOnlyPublishedLegacyAliases() {
        String strong = "sha256-" + "1".repeat(64);
        String legacy = "2".repeat(40);
        Path source = Path.of("skin.png");
        AssetMetadata metadata = AssetMetadata.forSkin(
                strong, "skin", source, SkinResolution.STANDARD,
                128L, "classic", 1L);

        LocalAssetManager.CatalogSnapshot snapshot =
                LocalAssetManager.CatalogSnapshot.copyOf(
                        Map.of(strong, metadata), Map.of(strong, source), Map.of(legacy, strong));

        assertEquals(strong, snapshot.resolve(strong));
        assertEquals(strong, snapshot.resolve(legacy));
        assertEquals(null, snapshot.resolve("3".repeat(40)));
    }

    @Test
    void assetMetadataRejectsALegacyPrimary() {
        assertThrows(IllegalArgumentException.class, () -> AssetMetadata.forSkin(
                "a".repeat(40), "legacy", Path.of("legacy.png"),
                SkinResolution.STANDARD, 128L, "classic", 1L));
    }
}
