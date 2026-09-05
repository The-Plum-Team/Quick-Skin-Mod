package com.quickskin.mod.client.rendering;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Preview-scoped cape authority for the GUI player preview.
 *
 * <p>The preview renders the real player entity, so the cape layer would otherwise resolve the
 * player's <em>applied</em> cape and ignore whatever the editor has selected. A binding registered
 * against the render key that the cape layer receives makes the editor's cape authoritative for
 * that draw only; the same player rendered in the world behind the screen carries no binding and
 * keeps its applied cape.
 *
 * <p>Keys are compared by reference identity: two previews may legitimately select the same cape
 * texture, and the era-specific keys (the previewed entity before 1.21.11, the submitted entity
 * render state afterwards) are already distinct objects per draw. The map is bounded so a key that
 * is never unbound - a preview whose render was dropped before the layer ran - cannot grow it
 * without limit.
 *
 * <p>Deliberately free of Minecraft types. Each era supplies its own key and texture types, which
 * keeps the precedence and bounding rules unit testable in the loader-independent test source set.
 */
public final class PreviewCapeBindings<K, T> {

    /** What the cape layer should draw for the player it was handed. */
    public enum Decision {
        /** Nothing is being previewed for this key; draw the applied cape as usual. */
        WORN,
        /** A preview cape is bound; draw it instead of the applied cape. */
        PREVIEW,
        /** A preview is bound with no cape selected; draw no cape at all. */
        HIDDEN
    }

    private static final int DEFAULT_MAX_ENTRIES = 128;

    private final int maxEntries;

    /** Identity-keyed; a present key with a {@code null} value means "previewing no cape". */
    private final Map<K, T> bindings = new IdentityHashMap<>();

    public PreviewCapeBindings() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public PreviewCapeBindings(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Make {@code texture} authoritative for draws made against {@code key}. A {@code null}
     * texture binds "the editor has no cape selected", which hides the cape rather than falling
     * back to the applied one.
     */
    public void bind(K key, T texture) {
        if (key == null) {
            return;
        }
        synchronized (bindings) {
            if (!bindings.containsKey(key) && bindings.size() >= maxEntries) {
                // Identity maps have no insertion order, so the evicted entry is arbitrary rather
                // than oldest. That is acceptable here only because the cap is a backstop against
                // a key whose draw never happened: a live preview binds and is consumed within the
                // same frame, so the map holds one or two entries in normal operation.
                Iterator<K> victim = bindings.keySet().iterator();
                if (victim.hasNext()) {
                    victim.next();
                    victim.remove();
                }
            }
            bindings.put(key, texture);
        }
    }

    /** Release {@code key}. Safe to call for a key that was never bound. */
    public void unbind(K key) {
        if (key == null) {
            return;
        }
        synchronized (bindings) {
            bindings.remove(key);
        }
    }

    /**
     * What the cape layer should draw, plus the texture to draw when the decision is
     * {@link Decision#PREVIEW}.
     */
    public record Resolution<T>(Decision decision, T texture) {
        public boolean overridesWornCape() {
            return decision != Decision.WORN;
        }
    }

    /**
     * Resolve {@code key} and release it in one step. The cape layer consumes its binding so a
     * render key that is minted fresh every frame cannot accumulate.
     */
    public Resolution<T> consume(K key) {
        if (key == null) {
            return worn();
        }
        synchronized (bindings) {
            if (!bindings.containsKey(key)) {
                return worn();
            }
            return resolve(bindings.remove(key));
        }
    }

    /** Resolve {@code key} without releasing it. */
    public Resolution<T> peek(K key) {
        if (key == null) {
            return worn();
        }
        synchronized (bindings) {
            if (!bindings.containsKey(key)) {
                return worn();
            }
            return resolve(bindings.get(key));
        }
    }

    private Resolution<T> resolve(T texture) {
        return texture == null
                ? new Resolution<>(Decision.HIDDEN, null)
                : new Resolution<>(Decision.PREVIEW, texture);
    }

    private Resolution<T> worn() {
        return new Resolution<>(Decision.WORN, null);
    }

    /** Drop every binding. Called when the client session resets. */
    public void clear() {
        synchronized (bindings) {
            bindings.clear();
        }
    }

    /** Live binding count, for bounding assertions. */
    public int size() {
        synchronized (bindings) {
            return bindings.size();
        }
    }
}
