package com.quickskin.mod.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Chooses who receives an account skin refresh; plain identity logic, kept apart for tests. */
final class AccountSkinObservers {
    private AccountSkinObservers() {
    }

    /**
     * The online players, in list order, who see the subject through vanilla profiles: everyone
     * except the subject itself and players whose session runs Quick Skin.
     */
    static <P> List<P> withoutQuickSkin(
            Collection<? extends P> online, P subject, Predicate<? super P> runsQuickSkin) {
        List<P> observers = new ArrayList<>();
        for (P candidate : online) {
            if (candidate != null && candidate != subject && !runsQuickSkin.test(candidate)) {
                observers.add(candidate);
            }
        }
        return observers;
    }

    /**
     * The chosen observers, in tracker order, whose client currently has the subject's entity
     * and so needs a new entity pairing to read the new skin.
     */
    static <P> List<P> pairedObservers(Collection<? extends P> seenBy, Collection<? extends P> observers) {
        Set<P> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.addAll(observers);
        List<P> paired = new ArrayList<>();
        for (P viewer : seenBy) {
            if (chosen.remove(viewer)) paired.add(viewer);
        }
        return paired;
    }
}
