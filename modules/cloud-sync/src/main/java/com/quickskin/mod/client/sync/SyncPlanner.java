package com.quickskin.mod.client.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides one sync round between the local library and the cloud, without touching either.
 *
 * <p>{@code known} is what both sides last agreed on, entry by entry. Against it a change on
 * either side is recognised: a cloud entry this computer never had is downloaded; one it had
 * and no longer has was deleted here and is deleted in the cloud; a cloud tombstone of an entry
 * this computer had deletes it here; a local entry the cloud never confirmed is uploaded. When
 * both sides changed the same entry, the cloud's newer change wins for a deletion made here
 * only if the entry is still unchanged here; otherwise the change made here is sent again.
 */
public final class SyncPlanner {
    private SyncPlanner() {
    }

    public record Plan(
            List<LibraryEntry> downloads,
            List<LibraryEntry> localDeletes,
            List<LibraryEntry> localUpdates,
            List<LibraryEntry> uploads,
            List<LibraryEntry> cloudDeletes,
            Map<String, LibraryEntry> known
    ) {
        public boolean isEmpty() {
            return downloads.isEmpty() && localDeletes.isEmpty() && localUpdates.isEmpty()
                    && uploads.isEmpty() && cloudDeletes.isEmpty();
        }
    }

    /**
     * @param local the local library now, by key
     * @param known the agreed state after the previous round, by key
     * @param remote the cloud entries changed since that round, tombstones included
     * @param ignored keys that are never sent to the cloud this session (refused or kept here)
     */
    public static Plan plan(
            Map<String, LibraryEntry> local,
            Map<String, LibraryEntry> known,
            List<LibraryEntry> remote,
            Set<String> ignored
    ) {
        Map<String, LibraryEntry> agreed = new HashMap<>(known);
        List<LibraryEntry> downloads = new ArrayList<>();
        List<LibraryEntry> localDeletes = new ArrayList<>();
        List<LibraryEntry> localUpdates = new ArrayList<>();
        Set<String> downloading = new java.util.HashSet<>();

        // The latest cloud change of each entry, in revision order
        Map<String, LibraryEntry> latest = new LinkedHashMap<>();
        remote.stream().sorted(Comparator.comparingLong(LibraryEntry::rev))
                .forEach(entry -> latest.put(entry.key(), entry));

        for (LibraryEntry cloud : latest.values()) {
            String key = cloud.key();
            LibraryEntry here = local.get(key);
            LibraryEntry before = known.get(key);
            boolean hadIt = before != null && !before.deleted();
            if (cloud.deleted()) {
                // Deleted in the cloud: it goes here too if this computer had it as agreed.
                // A local entry the cloud never confirmed is a new one and is uploaded below.
                if (here != null && hadIt) {
                    localDeletes.add(here);
                    agreed.put(key, cloud);
                } else if (here == null) {
                    agreed.put(key, cloud);
                }
            } else if (here == null) {
                if (hadIt) continue; // deleted here: the deletion is sent below
                downloads.add(cloud);
                downloading.add(key);
                // Agreed only once the download is stored
            } else {
                boolean changedHere = hadIt && !here.sameChoices(before);
                if (!changedHere) {
                    if (!here.sameChoices(cloud)) localUpdates.add(cloud);
                    agreed.put(key, cloud);
                } else {
                    // Changed on both sides: the change made here is sent again
                    agreed.put(key, cloud);
                }
            }
        }

        Set<String> deletedHere = new java.util.HashSet<>();
        for (LibraryEntry entry : localDeletes) deletedHere.add(entry.key());

        List<LibraryEntry> uploads = new ArrayList<>();
        for (LibraryEntry here : local.values()) {
            String key = here.key();
            if (ignored.contains(key) || deletedHere.contains(key)) continue;
            LibraryEntry agreedEntry = agreed.get(key);
            boolean changedHere = latestLocalChange(here, agreedEntry, known.get(key), latest.get(key));
            if (agreedEntry == null || agreedEntry.deleted() || changedHere) uploads.add(here);
        }

        List<LibraryEntry> cloudDeletes = new ArrayList<>();
        for (LibraryEntry entry : agreed.values()) {
            String key = entry.key();
            if (entry.deleted() || local.containsKey(key) || downloading.contains(key)
                    || ignored.contains(key)) {
                continue;
            }
            cloudDeletes.add(LibraryEntry.tombstone(entry.kind(), entry.id(), entry.rev()));
        }

        return new Plan(List.copyOf(downloads), List.copyOf(localDeletes),
                List.copyOf(localUpdates), List.copyOf(uploads), List.copyOf(cloudDeletes),
                Map.copyOf(agreed));
    }

    /**
     * Whether the local entry carries a choice the agreed entry lacks and that was made here:
     * either nothing came from the cloud for it this round and it differs from the agreed
     * state, or the cloud changed it too while it had already changed here.
     */
    private static boolean latestLocalChange(
            LibraryEntry here, LibraryEntry agreed, LibraryEntry before, LibraryEntry cloud) {
        if (agreed == null || agreed.deleted()) return false; // uploaded as new anyway
        if (here.sameChoices(agreed)) return false;
        if (cloud == null) return true;
        // The cloud changed it this round: only a change made here as well is sent again
        return before != null && !before.deleted() && !here.sameChoices(before);
    }
}
