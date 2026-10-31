package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cape menu's favourites are kept in the order they were marked, once each. */
class ClientConfigFavoriteCapesTest {

    @Test
    void settingsFromBeforeFavouritesHaveNone() {
        assertEquals(List.of(), ClientConfig.parse("{}").favoriteCapes);
        assertEquals(List.of(), ClientConfig.parse("{\"favoriteCapes\": null}").favoriteCapes);
    }

    @Test
    void aSavedListDropsBlankAndRepeatedIds() {
        ClientConfig config = ClientConfig.parse(
                "{\"favoriteCapes\": [\"known:fox\", \"\", null, \"known:fox\", \"local_cape:a\"]}");
        assertEquals(List.of("known:fox", "local_cape:a"), config.favoriteCapes);
    }

    @Test
    void markingAddsAtTheEndAndUnmarkingRemoves() {
        ClientConfig config = ClientConfig.parse("{}");
        config.setFavoriteCape("known:fox", true);
        config.setFavoriteCape("known:bmo", true);
        config.setFavoriteCape("known:fox", true);
        assertEquals(List.of("known:bmo", "known:fox"), config.favoriteCapes);
        assertTrue(config.isFavoriteCape("known:bmo"));

        config.setFavoriteCape("known:bmo", false);
        assertFalse(config.isFavoriteCape("known:bmo"));
        assertEquals(List.of("known:fox"), config.favoriteCapes);
    }

    @Test
    void anEditedCapeKeepsItsPlaceUnderItsNewId() {
        ClientConfig config = ClientConfig.parse("{\"favoriteCapes\": [\"known:fox\", \"local_cape:old\", \"known:bmo\"]}");
        config.renameFavoriteCape("local_cape:old", "local_cape:new");
        assertEquals(List.of("known:fox", "local_cape:new", "known:bmo"), config.favoriteCapes);

        // An edit that lands on a cape already marked leaves that one, once
        config.renameFavoriteCape("local_cape:new", "known:bmo");
        assertEquals(List.of("known:fox", "known:bmo"), config.favoriteCapes);
    }
}
