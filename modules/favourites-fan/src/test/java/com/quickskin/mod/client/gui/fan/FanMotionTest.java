package com.quickskin.mod.client.gui.fan;

import com.quickskin.mod.client.gui.fan.FanMotion.Phase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FanMotionTest {
    private static final float WIDTH = 480, HEIGHT = 270, HUD_TOP = HEIGHT - 50;

    private static FanMotion.Card[] cards(int n) {
        FanMotion.Card[] cards = new FanMotion.Card[n];
        for (int i = 0; i < n; i++) cards[i] = new FanMotion.Card();
        return cards;
    }

    @Test
    void openFanIsSymmetricAndStaysAboveTheHud() {
        for (int n = 1; n <= FanMotion.PAGE_SIZE; n++) {
            FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
            FanMotion.Card[] cards = cards(n);
            FanMotion.place(layout, n, Phase.OPEN, 1f, new float[n], -1, cards);
            for (int i = 0; i < n; i++) {
                FanMotion.Card left = cards[i], right = cards[n - 1 - i];
                assertEquals(WIDTH / 2f - left.x, right.x - WIDTH / 2f, 0.01f);
                assertEquals(left.y, right.y, 0.01f);
                assertEquals(1f, left.alpha, 0f);
                assertTrue(left.y + layout.cardHeight() / 2f <= HUD_TOP, "card " + i + " of " + n + " covers the hotbar");
                assertTrue(left.x - layout.cardWidth() / 2f >= 0, "card " + i + " of " + n + " leaves the screen");
            }
        }
    }

    @Test
    void pointerPicksTheCardInItsDirectionAndTheHotbarCancels() {
        int n = 8;
        FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
        FanMotion.Card[] cards = cards(n);
        FanMotion.place(layout, n, Phase.OPEN, 1f, new float[n], -1, cards);
        for (int i = 0; i < n; i++) {
            assertEquals(i, FanMotion.pick(layout, n, cards[i].x, cards[i].y));
        }
        assertEquals(0, FanMotion.pick(layout, n, 0, cards[0].y));
        assertEquals(n - 1, FanMotion.pick(layout, n, WIDTH, cards[0].y));
        assertEquals(-1, FanMotion.pick(layout, n, WIDTH / 2f, HEIGHT - 10));
    }

    @Test
    void pointedCardRisesWhileTheOthersDim() {
        int n = 5;
        FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
        FanMotion.Card[] rest = cards(n), pointed = cards(n);
        FanMotion.place(layout, n, Phase.OPEN, 1f, new float[n], -1, rest);
        float[] hover = new float[n];
        hover[2] = 1f;
        FanMotion.place(layout, n, Phase.OPEN, 1f, hover, -1, pointed);
        assertTrue(pointed[2].y < rest[2].y);
        assertTrue(pointed[2].scale > 1f);
        assertEquals(0f, pointed[2].dim, 0f);
        assertTrue(pointed[0].dim > 0.5f);
        assertTrue(pointed[0].scale < 1f);
    }

    @Test
    void openingGrowsFromTheClosedPivot() {
        int n = 6;
        FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
        FanMotion.Card[] start = cards(n), end = cards(n);
        FanMotion.place(layout, n, Phase.OPENING, 0f, new float[n], -1, start);
        FanMotion.place(layout, n, Phase.OPENING, 1f, new float[n], -1, end);
        assertEquals(0f, start[0].alpha, 0f);
        assertTrue(start[0].scale < 0.6f);
        assertTrue(Math.abs(start[0].angle) < Math.abs(end[0].angle) * 0.2f);
        FanMotion.Card[] open = cards(n);
        FanMotion.place(layout, n, Phase.OPEN, 1f, new float[n], -1, open);
        assertEquals(open[0].x, end[0].x, 0.01f);
        assertEquals(open[0].y, end[0].y, 0.01f);
    }

    @Test
    void chosenCardTurnsOverOnItsWayToTheCentreAndTheOthersFall() {
        int n = 6;
        FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
        FanMotion.Card[] cards = cards(n);
        float[] hover = new float[n];
        hover[1] = 1f;
        FanMotion.place(layout, n, Phase.CHOOSING, 0.5f, hover, 1, cards);
        assertEquals(WIDTH / 2f, cards[1].x, 0.01f);
        assertEquals(HEIGHT * 0.42f, cards[1].y, 0.01f);
        assertEquals(0f, cards[1].angle, 1e-4f);
        assertEquals(1f, cards[1].flip, 1e-4f);
        FanMotion.Card[] turning = cards(n);
        FanMotion.place(layout, n, Phase.CHOOSING, 0.45f / 2f, hover, 1, turning);
        assertEquals(-1f, turning[1].flip, 1e-3f, "half way it shows its back");
        assertTrue(cards[1].scale > 1.5f);
        assertEquals(0f, cards[0].alpha, 0f);
        assertTrue(FanMotion.WEAR_AT <= 0.5f);
    }

    @Test
    void closingDropsTheCardsRightToLeft() {
        int n = 6;
        FanMotion.Layout layout = FanMotion.layout(n, WIDTH, HEIGHT, HUD_TOP);
        FanMotion.Card[] open = cards(n), mid = cards(n), end = cards(n);
        FanMotion.place(layout, n, Phase.OPEN, 1f, new float[n], -1, open);
        FanMotion.place(layout, n, Phase.CLOSING, 0.4f, new float[n], -1, mid);
        FanMotion.place(layout, n, Phase.CLOSING, 1f, new float[n], -1, end);
        assertTrue(mid[n - 1].y - open[n - 1].y > mid[0].y - open[0].y);
        for (FanMotion.Card card : end) assertEquals(0f, card.alpha, 0f);
    }
}
