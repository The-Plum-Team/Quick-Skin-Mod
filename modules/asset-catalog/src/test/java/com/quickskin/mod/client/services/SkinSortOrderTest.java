package com.quickskin.mod.client.services;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.SkinSortMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkinSortOrderTest {

    private static AssetMetadata skin(char id, String name, long modified) {
        return AssetMetadata.forSkin("sha256-" + String.valueOf(id).repeat(64), name,
                Path.of(name + ".png"), SkinResolution.STANDARD, 128L, "classic", modified);
    }

    private static final AssetMetadata OWN = skin('0', "zed", 50L);
    private static final AssetMetadata FAVOURITE = skin('1', "yak", 40L);
    private static final AssetMetadata OLD = skin('2', "beta", 10L);
    private static final AssetMetadata NEW = skin('3', "alpha", 30L);
    private static final AssetMetadata SAME_TIME = skin('4', "carl", 30L);

    private static List<String> sorted(SkinSortMode mode) {
        Set<String> favourites = Set.of(FAVOURITE.hash());
        return List.of(NEW, OLD, SAME_TIME, FAVOURITE, OWN).stream()
                .sorted(LocalAssetManager.sortComparator(mode, OWN.hash(), favourites::contains))
                .map(AssetMetadata::friendlyName)
                .toList();
    }

    @Test
    void latestLastListsTheOldestFirstAfterThePinnedEntries() {
        assertEquals(List.of("zed", "yak", "beta", "alpha", "carl"), sorted(SkinSortMode.LATEST_LAST));
    }

    @Test
    void latestFirstListsTheNewestFirstAfterThePinnedEntries() {
        assertEquals(List.of("zed", "yak", "alpha", "carl", "beta"), sorted(SkinSortMode.LATEST_FIRST));
    }

    @Test
    void alphabeticalSortsByName() {
        assertEquals(List.of("zed", "yak", "alpha", "beta", "carl"), sorted(SkinSortMode.ALPHABETICAL));
    }

    @Test
    void everyModeGivesItsOwnOrder() {
        assertEquals(3, Set.of(sorted(SkinSortMode.LATEST_LAST), sorted(SkinSortMode.LATEST_FIRST),
                sorted(SkinSortMode.ALPHABETICAL)).size());
    }
}
