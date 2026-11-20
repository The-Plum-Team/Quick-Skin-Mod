package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pose reaches exactly the clients that see the player, and ends for those who stop seeing them. */
class ImmersivePoseAudienceTest {
    private static Map<String, String> seeing(String... recipients) {
        Map<String, String> seeing = new LinkedHashMap<>();
        for (String recipient : recipients) seeing.put(recipient.substring(0, 1), recipient);
        return seeing;
    }

    @Test
    void aClientThatStartsToSeeThePlayerIsToldOnceAndOneThatStopsIsToldItEnded() {
        ImmersivePoseAudience<String, String> audience = new ImmersivePoseAudience<>();

        assertEquals(List.of("a1", "b1"), audience.change(seeing("a1", "b1")).joined());
        assertTrue(audience.follow(seeing("a1", "b1")).isEmpty(), "nobody new, nobody gone");

        ImmersivePoseAudience.Update<String> update = audience.follow(seeing("a1", "c1"));
        assertEquals(List.of("c1"), update.joined(), "a client coming into view is told");
        assertEquals(List.of("b1"), update.left(), "a client out of view is told it ended");
        assertEquals(2, audience.size());
    }

    @Test
    void aClientToldAsSomethingElseIsToldAgain() {
        ImmersivePoseAudience<String, String> audience = new ImmersivePoseAudience<>();
        audience.change(seeing("a1"));

        // The same player in another world or with a new entity: its client must hear it again
        ImmersivePoseAudience.Update<String> update = audience.follow(seeing("a2"));
        assertEquals(List.of("a2"), update.joined());
        assertEquals(List.of(), update.left());
    }

    @Test
    void aChangeGoesToEveryoneWhoSeesThePlayerAndEndsForWhoNoLongerDoes() {
        ImmersivePoseAudience<String, String> audience = new ImmersivePoseAudience<>();
        audience.change(seeing("a1", "b1"));

        ImmersivePoseAudience.Update<String> update = audience.change(seeing("b1", "c1"));
        assertEquals(List.of("b1", "c1"), update.joined());
        assertEquals(List.of("a1"), update.left());
    }

    @Test
    void theEndGoesToEveryoneToldAndForgetsThem() {
        ImmersivePoseAudience<String, String> audience = new ImmersivePoseAudience<>();
        audience.change(seeing("a1", "b1"));

        assertEquals(List.of("a1", "b1"), audience.end());
        assertEquals(0, audience.size());
        assertEquals(List.of(), audience.end());
    }

    @Test
    void aRecipientThatLeftIsForgottenOnlyForItsOwnSession() {
        ImmersivePoseAudience<String, String> audience = new ImmersivePoseAudience<>();
        audience.change(seeing("a1", "b1"));

        audience.forget("a", "a0"::equals);
        assertEquals(2, audience.size(), "an older session leaving does not forget the newer one");
        audience.forget("a", "a1"::equals);
        assertEquals(List.of("b1"), audience.end());
    }
}
