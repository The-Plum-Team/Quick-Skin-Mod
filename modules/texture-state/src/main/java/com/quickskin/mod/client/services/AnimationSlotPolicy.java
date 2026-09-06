package com.quickskin.mod.client.services;

import java.util.LinkedHashMap;
import java.util.Set;

/** Visibility-aware admission policy for the bounded animated-texture slots. */
final class AnimationSlotPolicy {
    enum Kind {
        ALREADY_ACTIVE,
        FREE_SLOT,
        REPLACE_STALE,
        STATIC_FALLBACK
    }

    record Admission(Kind kind, String victimId) {
        static Admission of(Kind kind) {
            return new Admission(kind, null);
        }
    }

    private static final long NEVER_VISIBLE = Long.MIN_VALUE;

    private final int maxSlots;
    private final int maxTrackedIds;
    private final long visibilityGraceTicks;
    private final LinkedHashMap<String, Long> lastVisible =
            new LinkedHashMap<>(16, 0.75f, true);
    private long tick;

    AnimationSlotPolicy(int maxSlots, int maxTrackedIds, long visibilityGraceTicks) {
        if (maxSlots < 1 || maxTrackedIds < maxSlots || visibilityGraceTicks < 0L) {
            throw new IllegalArgumentException("invalid animation slot limits");
        }
        this.maxSlots = maxSlots;
        this.maxTrackedIds = maxTrackedIds;
        this.visibilityGraceTicks = visibilityGraceTicks;
    }

    void markVisible(String animationId) {
        if (animationId == null || animationId.isEmpty()) return;
        lastVisible.put(animationId, tick);
        while (lastVisible.size() > maxTrackedIds) {
            lastVisible.remove(lastVisible.keySet().iterator().next());
        }
    }

    void advanceTick() {
        if (tick < Long.MAX_VALUE) tick++;
    }

    Admission plan(String candidateId, Set<String> activeIds) {
        if (activeIds.contains(candidateId)) {
            return Admission.of(Kind.ALREADY_ACTIVE);
        }
        if (activeIds.size() < maxSlots) {
            return Admission.of(Kind.FREE_SLOT);
        }

        long candidateSeen = visibleAt(candidateId);
        if (candidateSeen == NEVER_VISIBLE) {
            return Admission.of(Kind.STATIC_FALLBACK);
        }

        String oldestId = null;
        long oldestSeen = Long.MAX_VALUE;
        for (String activeId : activeIds) {
            long seen = visibleAt(activeId);
            if (oldestId == null || seen < oldestSeen) {
                oldestId = activeId;
                oldestSeen = seen;
            }
        }

        if (oldestId != null && (oldestSeen == NEVER_VISIBLE
                || (tick >= oldestSeen && tick - oldestSeen > visibilityGraceTicks))) {
            return new Admission(Kind.REPLACE_STALE, oldestId);
        }
        return Admission.of(Kind.STATIC_FALLBACK);
    }

    void forget(String animationId) {
        lastVisible.remove(animationId);
    }

    void clear() {
        lastVisible.clear();
        tick = 0L;
    }

    long currentTick() {
        return tick;
    }

    private long visibleAt(String animationId) {
        Long seen = lastVisible.get(animationId);
        return seen == null ? NEVER_VISIBLE : seen;
    }
}
