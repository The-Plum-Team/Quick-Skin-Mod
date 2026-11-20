package com.quickskin.mod.networking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Who has been told one player's immersive pose (ADR 0019): the Quick Skin clients that see the
 * player, as the server's entity tracking says. A client that starts to see the player is told
 * the pose as already under way, one that stops seeing them is told it ended, so no client keeps a
 * pose of a player it cannot see and none learns of a pose out of its view. Each recipient is
 * named by a key (its player) and a token that changes when it must be told again (another
 * session, player entity or world). Bounded by the players who see the posing one. Used on the
 * server thread only.
 *
 * @param <K> the key of a recipient
 * @param <T> what a recipient was told as, compared with {@link Object#equals}
 */
public final class ImmersivePoseAudience<K, T> {
    private final Map<K, T> told = new LinkedHashMap<>();

    /**
     * What a look at who sees the player now asks for.
     *
     * @param joined the recipients to tell the pose, as already under way
     * @param left   the recipients to tell the pose ended for them
     */
    public record Update<T>(List<T> joined, List<T> left) {
        public boolean isEmpty() {
            return joined.isEmpty() && left.isEmpty();
        }
    }

    /**
     * Follows who sees the player now ({@code seeing}, by key): those who see them and were not told,
     * or were told as something else, join; those told who no longer see them leave.
     */
    public Update<T> follow(Map<K, T> seeing) {
        List<T> left = new ArrayList<>();
        told.entrySet().removeIf(entry -> {
            if (seeing.containsKey(entry.getKey())) return false;
            left.add(entry.getValue());
            return true;
        });
        List<T> joined = new ArrayList<>();
        for (Map.Entry<K, T> entry : seeing.entrySet()) {
            T previous = told.put(entry.getKey(), entry.getValue());
            if (!entry.getValue().equals(previous)) joined.add(entry.getValue());
        }
        return new Update<>(joined, left);
    }

    /**
     * The pose changed: everyone who sees the player now ({@code seeing}) is told the change, and
     * returned as {@link Update#joined}; those told before who no longer see the player leave.
     */
    public Update<T> change(Map<K, T> seeing) {
        List<T> left = new ArrayList<>();
        for (Map.Entry<K, T> entry : told.entrySet()) {
            if (!seeing.containsKey(entry.getKey())) left.add(entry.getValue());
        }
        told.clear();
        told.putAll(seeing);
        return new Update<>(new ArrayList<>(seeing.values()), left);
    }

    /** The pose ended: returns everyone who was told it, who are to be told it ended, and forgets them. */
    public List<T> end() {
        List<T> ended = new ArrayList<>(told.values());
        told.clear();
        return ended;
    }

    /** {@code recipient} left the server as what {@code leaving} matches: it is told nothing more about this pose. */
    public void forget(K recipient, Predicate<? super T> leaving) {
        T previous = told.get(recipient);
        if (previous != null && leaving.test(previous)) told.remove(recipient);
    }

    public int size() {
        return told.size();
    }
}
