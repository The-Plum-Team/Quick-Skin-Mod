package com.quickskin.mod.client.gui.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FancyMenuWidgetsTest {

    /** Has the two methods FancyMenu's mixin adds to every widget. */
    public static class CustomizableWidget {
        String identifier;
        boolean hidden;

        public CustomizableWidget setWidgetIdentifierFancyMenu(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public boolean isHiddenFancyMenu() {
            return hidden;
        }
    }

    /** A widget without FancyMenu. */
    public static class PlainWidget {
    }

    /** FancyMenu's methods are there but fail. */
    public static class BrokenWidget {
        int identifierCalls;
        int hiddenCalls;

        public BrokenWidget setWidgetIdentifierFancyMenu(String identifier) {
            identifierCalls++;
            throw new IllegalStateException("broken");
        }

        public boolean isHiddenFancyMenu() {
            hiddenCalls++;
            throw new IllegalStateException("broken");
        }
    }

    @Test
    void theApiIsUsedWhenFancyMenuAddedIt() {
        FancyMenuWidgets.Api api = new FancyMenuWidgets.Api(CustomizableWidget.class);
        CustomizableWidget widget = new CustomizableWidget();

        assertTrue(api.isPresent());
        api.setIdentifier(widget, FancyMenuWidgets.PREVIEW_ID);
        assertEquals("quickskin_player_preview", widget.identifier);
        assertFalse(api.isHidden(widget));
        widget.hidden = true;
        assertTrue(api.isHidden(widget));
    }

    @Test
    void animationButtonIdsComeFromTheAnimationName() {
        assertEquals("quickskin_preview_animation_idle", FancyMenuWidgets.animationButtonId("idle"));
        assertEquals("quickskin_preview_animation_walk", FancyMenuWidgets.animationButtonId("walk"));
        assertEquals("quickskin_preview_animation_sit", FancyMenuWidgets.animationButtonId("sit"));
    }

    @Test
    void withoutFancyMenuEveryCallIsANoOp() {
        FancyMenuWidgets.Api api = new FancyMenuWidgets.Api(PlainWidget.class);
        PlainWidget widget = new PlainWidget();

        assertFalse(api.isPresent());
        api.setIdentifier(widget, FancyMenuWidgets.PREVIEW_ID);
        assertFalse(api.isHidden(widget));
    }

    @Test
    void aFailingMethodIsSwallowedAndThenNoLongerCalled() {
        FancyMenuWidgets.Api api = new FancyMenuWidgets.Api(BrokenWidget.class);
        BrokenWidget widget = new BrokenWidget();

        assertTrue(api.isPresent());
        api.setIdentifier(widget, FancyMenuWidgets.PREVIEW_ID);
        api.setIdentifier(widget, FancyMenuWidgets.ROTATE_ID);
        assertEquals(1, widget.identifierCalls);
        assertFalse(api.isPresent());

        assertFalse(api.isHidden(widget));
        assertFalse(api.isHidden(widget));
        assertEquals(1, widget.hiddenCalls);
    }

    @Test
    void aWidgetOfAnotherTypeIsNotMistakenForHidden() {
        FancyMenuWidgets.Api api = new FancyMenuWidgets.Api(CustomizableWidget.class);

        // Invoking a method of one class on an object of another is a failure, not "hidden".
        assertFalse(api.isHidden(new PlainWidget()));
    }

    @Test
    void theCompatibilityProbeReportsFancyMenuUnavailableWithoutIt() {
        assertFalse(FancyMenuWidgets.isAvailable());
        assertEquals(FancyMenuWidgets.isPresent(), FancyMenuWidgets.isAvailable());
    }
}
