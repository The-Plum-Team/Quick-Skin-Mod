package com.quickskin.mod.client.services;

/** Client-thread preview selection shared across screen rebuilds, reset at session boundaries. */
public final class PreviewAnimationState {
    private static String animation = "idle";

    private PreviewAnimationState() {
    }

    public static String get() {
        return animation;
    }

    public static void set(String selected) {
        if (selected != null && !selected.isEmpty()) animation = selected;
    }
}
