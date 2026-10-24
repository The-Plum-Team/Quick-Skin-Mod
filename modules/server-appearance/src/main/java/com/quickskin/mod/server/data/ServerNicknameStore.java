package com.quickskin.mod.server.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.common.data.Nickname;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetic nicknames players chose on this world, and the account names of everyone who
 * joined it, kept in {@code quickskin/nicknames.json}.
 *
 * <p>Vanilla broadcasts a player's join message before the player's client can send anything, so
 * the server must already know the nickname of a returning player: the store is read when the
 * server starts and every change is saved at once on the cache-I/O worker. It remembers at most
 * {@link #MAX_ENTRIES} players and forgets whoever was seen longest ago. A file it cannot read is
 * left untouched and never saved over. Each player may change their nickname once every
 * {@link #CHANGE_COOLDOWN_MILLIS}, across reconnects.</p>
 *
 * <p>A nickname may not read like ({@link Nickname#readsAs}) another player's account name or
 * nickname that the store remembers, nor like a name the caller names (the players online, the
 * operators and the whitelist). When a player joins, every other player's nickname that reads
 * like the newcomer's account name is cleared. A locked player ({@code "locked": true}, set by a
 * command or in the file while the server is stopped) keeps their nickname as it is, however
 * their client asks to change it.</p>
 *
 * <p>An admin may also give a player a nickname for a while ({@link #assign}), which shows instead
 * of the one the player chose until the admin lifts it ({@link #lift}); the player cannot change
 * theirs meanwhile. It follows the same rules, but no cooldown.</p>
 */
public final class ServerNicknameStore {
    public static final int MAX_ENTRIES = 4096;
    public static final long CHANGE_COOLDOWN_MILLIS = 10_000L;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int SCHEMA = 1;
    private static final int MAX_NAME_LENGTH = 64;
    private static final String FILE_NAME = "nicknames.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ServerNicknameStore INSTANCE = new ServerNicknameStore();

    /** What the server made of a requested nickname. */
    public enum Status {
        /** The nickname is the player's from now on ({@code ""}: they have none). */
        ACCEPTED,
        /** The player already had exactly this nickname. */
        UNCHANGED,
        /** It breaks the {@link Nickname} rules. */
        INVALID,
        /** It reads like the name or nickname of another player this server knows. */
        TAKEN,
        /** The player changed their nickname moments ago; it can change again after a wait. */
        COOLDOWN,
        /** This server does not allow nicknames ({@code allowNicknames}). */
        DISABLED,
        /** A moderator locked this player's nickname. */
        LOCKED,
        /** An admin gave this player a nickname for now, which shows instead of theirs. */
        ASSIGNED
    }

    /**
     * @param nickname         the nickname the player shows after the request ({@code ""}: none),
     *                         an admin's if they gave one
     * @param previous         the one they showed before it
     * @param retryAfterMillis for {@link Status#COOLDOWN}, how long until it may change again
     */
    public record Result(Status status, String nickname, String previous, long retryAfterMillis) {
        public boolean changed() {
            return status == Status.ACCEPTED;
        }
    }

    /**
     * A nickname cleared because the player whose account name it reads like joined.
     *
     * @param assigned whether it was the nickname an admin gave, not the player's own
     */
    public record Released(UUID playerId, String name, String nickname, boolean assigned) {
    }

    /**
     * A player the world knows: the nickname they chose and the one an admin gave them
     * ({@code ""}: none), their account name, and how all three read ({@link Nickname#reading}),
     * kept so a request compares strings only.
     */
    private record Entry(String nickname, String assigned, String name, long lastSeen, boolean locked,
                         String nicknameReading, String assignedReading, String nameReading) {
        Entry(String nickname, String assigned, String name, long lastSeen, boolean locked) {
            this(nickname, assigned, name, lastSeen, locked,
                    Nickname.reading(nickname), Nickname.reading(assigned), Nickname.reading(name));
        }

        Entry withNickname(String newNickname) {
            return new Entry(newNickname, assigned, name, lastSeen, locked);
        }

        Entry withAssigned(String newAssigned) {
            return new Entry(nickname, newAssigned, name, lastSeen, locked);
        }

        Entry withLocked(boolean newLocked) {
            return new Entry(nickname, assigned, name, lastSeen, newLocked);
        }

        /** The nickname the player shows: an admin's, or else their own. */
        String shown() {
            return assigned.isEmpty() ? nickname : assigned;
        }

        boolean readsAs(String reading) {
            return reading.equals(nicknameReading) || reading.equals(assignedReading) || reading.equals(nameReading);
        }
    }

    /** Insertion order is recency: the first entry is the player seen longest ago. */
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    /** When each player last changed their nickname; bounded like the entries. */
    private final LinkedHashMap<UUID, Long> lastChanges = new LinkedHashMap<>();
    private Path file;
    private boolean persistable;
    private byte[] pendingSave;

    ServerNicknameStore() {
    }

    public static ServerNicknameStore getInstance() {
        return INSTANCE;
    }

    /** Uses the store of this world, dropping anything held for an earlier one. */
    public synchronized void init(Path worldPath) {
        clear();
        Path directory = worldPath.resolve("quickskin").toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            file = directory.resolve(FILE_NAME);
            Files.deleteIfExists(temporaryFile(file));
            persistable = true;
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to initialize nickname storage in {}", directory, e);
            file = null;
        }
    }

    /**
     * Reads the world's nicknames. An entry that breaks the rules is dropped; a file that is not
     * a nickname document leaves the store empty and is never overwritten.
     *
     * @return how many nicknames were read
     */
    public synchronized int load() {
        entries.clear();
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a regular file");
            }
            readDocument(BoundedFileReader.readUtf8(file, MAX_FILE_BYTES));
            return (int) entries.values().stream().filter(entry -> !entry.shown().isEmpty()).count();
        } catch (IOException | RuntimeException e) {
            entries.clear();
            persistable = false;
            QuickSkinInfo.LOGGER.error("Could not read the nicknames in {}; nicknames start empty "
                    + "and the file is left untouched until it is fixed", file, e);
            return 0;
        }
    }

    /** The nickname the player chose, or {@code ""} when they have none. */
    public synchronized String nickname(UUID playerId) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        return entry != null ? entry.nickname() : "";
    }

    /** The nickname an admin gave the player for now, or {@code ""} when none. */
    public synchronized String assignedNickname(UUID playerId) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        return entry != null ? entry.assigned() : "";
    }

    /** Whether the player's nickname is locked: they cannot change it themselves. */
    public synchronized boolean isLocked(UUID playerId) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        return entry != null && entry.locked();
    }

    /** Every player who chose a nickname, with it. */
    public synchronized Map<UUID, String> nicknames() {
        Map<UUID, String> nicknames = new LinkedHashMap<>();
        entries.forEach((playerId, entry) -> {
            if (!entry.nickname().isEmpty()) nicknames.put(playerId, entry.nickname());
        });
        return nicknames;
    }

    /**
     * Records that the player joined under this account name, making them the most recently
     * seen, and clears every other player's nickname that reads like that name.
     *
     * @return the nicknames cleared for the newcomer
     */
    public synchronized List<Released> playerJoined(UUID playerId, String name, long now) {
        List<Released> released = new ArrayList<>();
        if (playerId == null) return released;
        String accountName = validName(name, "");
        Entry entry = entries.remove(playerId);
        entries.put(playerId, entry == null
                ? new Entry("", "", accountName, now, false)
                : new Entry(entry.nickname(), entry.assigned(),
                        accountName.isEmpty() ? entry.name() : accountName, now, entry.locked()));
        evictEldest();
        String accountReading = Nickname.reading(accountName);
        if (!accountReading.isEmpty()) {
            for (Map.Entry<UUID, Entry> other : entries.entrySet()) {
                if (other.getKey().equals(playerId)) continue;
                Entry otherEntry = other.getValue();
                if (otherEntry.assignedReading().equals(accountReading)) {
                    released.add(new Released(other.getKey(), otherEntry.name(), otherEntry.assigned(), true));
                    otherEntry = otherEntry.withAssigned("");
                }
                if (otherEntry.nicknameReading().equals(accountReading)) {
                    released.add(new Released(other.getKey(), otherEntry.name(), otherEntry.nickname(), false));
                    otherEntry = otherEntry.withNickname("");
                }
                other.setValue(otherEntry);
            }
        }
        scheduleSave();
        return released;
    }

    /**
     * Applies a nickname the player asked for.
     *
     * @param name        the player's account name; asking for it clears the nickname
     * @param typed       the requested nickname, {@code ""} for none
     * @param allowed     whether this server allows nicknames
     * @param otherNames  more names a nickname may not read like: the account names of the other
     *                    players online, the operators and the whitelist
     */
    public synchronized Result request(UUID playerId, String name, String typed, boolean allowed,
                                       Collection<String> otherNames, long now) {
        Entry own = playerId != null ? entries.get(playerId) : null;
        String current = nickname(playerId);
        String shown = own != null ? own.shown() : "";
        String requested = Nickname.normalize(typed);
        if (requested == null || playerId == null) return new Result(Status.INVALID, shown, shown, 0L);
        if (requested.equals(name)) requested = "";
        // Asking again for the nickname already chosen changes nothing, whatever else holds
        if (requested.equals(current)) return new Result(Status.UNCHANGED, shown, shown, 0L);
        if (!allowed) return new Result(Status.DISABLED, shown, shown, 0L);
        if (own != null && own.locked()) return new Result(Status.LOCKED, shown, shown, 0L);
        if (own != null && !own.assigned().isEmpty()) return new Result(Status.ASSIGNED, shown, shown, 0L);
        Long lastChange = lastChanges.get(playerId);
        if (lastChange != null && now - lastChange < CHANGE_COOLDOWN_MILLIS && now >= lastChange) {
            return new Result(Status.COOLDOWN, current, current, CHANGE_COOLDOWN_MILLIS - (now - lastChange));
        }
        if (!requested.isEmpty() && readsAsAnotherPlayer(playerId, name, requested, otherNames)) {
            return new Result(Status.TAKEN, current, current, 0L);
        }
        Entry entry = entries.remove(playerId);
        entries.put(playerId, entry == null
                ? new Entry(requested, "", validName(name, ""), now, false)
                : new Entry(requested, entry.assigned(), entry.name().isEmpty() ? validName(name, "") : entry.name(),
                        Math.max(entry.lastSeen(), now), entry.locked()));
        evictEldest();
        lastChanges.remove(playerId);
        lastChanges.put(playerId, now);
        while (lastChanges.size() > MAX_ENTRIES) {
            Iterator<UUID> eldest = lastChanges.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        scheduleSave();
        return new Result(Status.ACCEPTED, requested, current, 0L);
    }

    /**
     * An admin gives the player this nickname for now. It shows instead of the one the player
     * chose, which comes back when the admin lifts it, and follows the same rules without a wait.
     *
     * @param name       the player's account name, which is no nickname to give
     * @param otherNames as for {@link #request}
     * @return {@link Status#ACCEPTED}, {@link Status#UNCHANGED}, {@link Status#INVALID} or
     *         {@link Status#TAKEN}
     */
    public synchronized Result assign(UUID playerId, String name, String typed,
                                      Collection<String> otherNames, long now) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        String shown = entry != null ? entry.shown() : "";
        String requested = Nickname.normalize(typed);
        if (requested == null || requested.isEmpty() || playerId == null || requested.equals(name)) {
            return new Result(Status.INVALID, shown, shown, 0L);
        }
        if (entry != null && requested.equals(entry.assigned())) return new Result(Status.UNCHANGED, shown, shown, 0L);
        if (!isAvailable(playerId, name, requested, otherNames)) return new Result(Status.TAKEN, shown, shown, 0L);
        entries.remove(playerId);
        entries.put(playerId, entry == null
                ? new Entry("", requested, validName(name, ""), now, false)
                : new Entry(entry.nickname(), requested, entry.name().isEmpty() ? validName(name, "") : entry.name(),
                        Math.max(entry.lastSeen(), now), entry.locked()));
        evictEldest();
        scheduleSave();
        return new Result(Status.ACCEPTED, requested, shown, 0L);
    }

    /**
     * Whether an admin may give the player this nickname: it reads like nobody else's name or
     * nickname. It must already be in its canonical form ({@link Nickname#normalize}).
     */
    public synchronized boolean isAvailable(UUID playerId, String name, String nickname,
                                            Collection<String> otherNames) {
        return Nickname.isCanonical(nickname) && !nickname.equals(name)
                && !readsAsAnotherPlayer(playerId, name, nickname, otherNames);
    }

    /**
     * Takes away the nickname an admin gave the player; the one they chose shows again.
     *
     * @return the nickname taken away, {@code ""} when there was none
     */
    public synchronized String lift(UUID playerId) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        if (entry == null || entry.assigned().isEmpty()) return "";
        entries.put(playerId, entry.withAssigned(""));
        scheduleSave();
        return entry.assigned();
    }

    /** Takes back every nickname admins gave, of players online or not; returns whose. */
    public synchronized List<UUID> liftAll() {
        List<UUID> lifted = new ArrayList<>();
        for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
            if (!entry.getValue().assigned().isEmpty()) {
                lifted.add(entry.getKey());
                entry.setValue(entry.getValue().withAssigned(""));
            }
        }
        if (!lifted.isEmpty()) scheduleSave();
        return lifted;
    }

    /**
     * Takes away both the nickname an admin gave the player and the one they chose, so their
     * account name shows. Their client may ask for a nickname again unless they are locked.
     *
     * @return whether they had either
     */
    public synchronized boolean clearNicknames(UUID playerId) {
        Entry entry = playerId != null ? entries.get(playerId) : null;
        if (entry == null || entry.shown().isEmpty()) return false;
        entries.put(playerId, entry.withNickname("").withAssigned(""));
        scheduleSave();
        return true;
    }

    /**
     * Locks the player's nickname as it is, or unlocks it: a locked player cannot change it.
     *
     * @return whether that changed anything
     */
    public synchronized boolean setLocked(UUID playerId, String name, boolean locked, long now) {
        if (playerId == null) return false;
        Entry entry = entries.get(playerId);
        if (entry != null && entry.locked() == locked) return false;
        if (entry == null && !locked) return false;
        if (entry == null) {
            entries.put(playerId, new Entry("", "", validName(name, ""), now, true));
            evictEldest();
        } else {
            entries.put(playerId, entry.withLocked(locked));
        }
        scheduleSave();
        return true;
    }

    private boolean readsAsAnotherPlayer(UUID playerId, String name, String requested,
                                         Collection<String> otherNames) {
        String wanted = Nickname.reading(requested);
        if (otherNames != null) {
            for (String otherName : otherNames) {
                // The player's own name, in another case, only changes how it reads
                if (otherName != null && !otherName.equalsIgnoreCase(name)
                        && wanted.equals(Nickname.reading(otherName))) return true;
            }
        }
        for (Map.Entry<UUID, Entry> other : entries.entrySet()) {
            if (!other.getKey().equals(playerId) && other.getValue().readsAs(wanted)) return true;
        }
        return false;
    }

    /** Writes the store now; called while the server stops. */
    public synchronized void saveNow() {
        pendingSave = null;
        if (file == null || !persistable) return;
        write(file, encode());
    }

    /** Releases the world, its nicknames and every cooldown. */
    public synchronized void clear() {
        entries.clear();
        lastChanges.clear();
        file = null;
        persistable = false;
        pendingSave = null;
    }

    private void evictEldest() {
        while (entries.size() > MAX_ENTRIES) {
            Iterator<UUID> eldest = entries.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    private void scheduleSave() {
        if (file == null || !persistable) return;
        boolean queued = pendingSave != null;
        pendingSave = encode();
        if (queued) return;
        Path target = file;
        if (!ServerCacheIoExecutor.getInstance().submit(() -> writePendingSave(target))) {
            // Written when the server stops
            pendingSave = null;
            QuickSkinInfo.LOGGER.debug("Deferred saving nicknames until the server stops: the save queue is unavailable");
        }
    }

    private synchronized void writePendingSave(Path target) {
        byte[] encoded = pendingSave;
        pendingSave = null;
        if (encoded != null && target.equals(file) && persistable) {
            write(target, encoded);
        }
    }

    private byte[] encode() {
        JsonObject players = new JsonObject();
        entries.forEach((playerId, entry) -> {
            JsonObject json = new JsonObject();
            json.addProperty("nickname", entry.nickname());
            if (!entry.assigned().isEmpty()) json.addProperty("assigned", entry.assigned());
            json.addProperty("name", entry.name());
            json.addProperty("lastSeen", entry.lastSeen());
            if (entry.locked()) json.addProperty("locked", true);
            players.add(playerId.toString(), json);
        });
        JsonObject document = new JsonObject();
        document.addProperty("schema", SCHEMA);
        document.add("players", players);
        return GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    private void readDocument(String json) throws IOException {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IOException("not a JSON object");
        JsonObject document = root.getAsJsonObject();
        JsonElement schema = document.get("schema");
        if (schema == null || !schema.isJsonPrimitive() || schema.getAsInt() != SCHEMA) {
            throw new IOException("unknown nickname schema " + schema);
        }
        JsonElement players = document.get("players");
        if (players == null || !players.isJsonObject()) throw new IOException("no players object");
        List<Map.Entry<UUID, Entry>> read = new ArrayList<>();
        for (Map.Entry<String, JsonElement> player : players.getAsJsonObject().entrySet()) {
            Map.Entry<UUID, Entry> entry = readEntry(player.getKey(), player.getValue());
            if (entry != null) read.add(entry);
        }
        read.sort(Comparator.comparingLong(entry -> entry.getValue().lastSeen()));
        for (Map.Entry<UUID, Entry> entry : read) {
            entries.put(entry.getKey(), entry.getValue());
        }
        evictEldest();
    }

    private static Map.Entry<UUID, Entry> readEntry(String key, JsonElement value) {
        try {
            UUID playerId = UUID.fromString(key);
            if (!playerId.toString().equals(key) || !value.isJsonObject()) return null;
            JsonObject json = value.getAsJsonObject();
            String nickname = storedNickname(json, "nickname");
            String assigned = storedNickname(json, "assigned");
            if (nickname == null || assigned == null) return null;
            String name = validName(string(json, "name"), "");
            if (nickname.isEmpty() && assigned.isEmpty() && name.isEmpty()) return null;
            JsonElement lastSeen = json.get("lastSeen");
            long seen = lastSeen != null && lastSeen.isJsonPrimitive()
                    && lastSeen.getAsJsonPrimitive().isNumber() ? lastSeen.getAsLong() : 0L;
            JsonElement locked = json.get("locked");
            boolean isLocked = locked != null && locked.isJsonPrimitive()
                    && locked.getAsJsonPrimitive().isBoolean() && locked.getAsBoolean();
            return Map.entry(playerId, new Entry(nickname, assigned, name, seen, isLocked));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** A nickname read from the file, {@code ""} when absent, or null when it breaks the rules. */
    private static String storedNickname(JsonObject json, String key) {
        String nickname = string(json, key);
        if (nickname == null) return "";
        // A server on a newer Java may have accepted a letter this one does not know yet
        return nickname.isEmpty() || Nickname.isCanonical(nickname) || Nickname.isSafeToShow(nickname)
                ? nickname : null;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static String validName(String name, String fallback) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH
                && name.chars().noneMatch(Character::isISOControl) ? name : fallback;
    }

    private static void write(Path target, byte[] encoded) {
        Path temporary = temporaryFile(target);
        try {
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to save nicknames {}", target, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                QuickSkinInfo.LOGGER.debug("Unable to remove nickname temp file {}", temporary, cleanupError);
            }
        }
    }

    private static Path temporaryFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }
}
