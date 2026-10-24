package com.quickskin.mod.server.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.PoolSkinEntry;
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The skins admins gave players for now, and the world's skin pool they hand out, kept in
 * {@code quickskin/assigned-skins.json}.
 *
 * <p>An assigned skin shows to other players instead of the player's own skin and model until an
 * admin takes it back; the player's own appearance stays as it is underneath. The pool holds at
 * most {@link TextureTransferLimits#MAX_POOL_SKINS} named skins. Both name a skin by its
 * {@code local_skin:} id in the world's texture cache, with an explicit {@code classic} or
 * {@code slim} model. Like the nickname store, the file is read when the server starts, saved at
 * once on the cache-I/O worker after each change and again at stop, and never saved over when it
 * cannot be read. At most {@link #MAX_ASSIGNED} players keep an assigned skin; past that, no
 * other player is given one until one is taken back. A skin whose texture the world does not hold
 * (now) stays in the file and is simply not shown or handed out.</p>
 */
public final class ServerSkinAssignmentStore {
    public static final int MAX_ASSIGNED = 4096;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int SCHEMA = 1;
    private static final String FILE_NAME = "assigned-skins.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ServerSkinAssignmentStore INSTANCE = new ServerSkinAssignmentStore();

    /** A skin an admin gave a player: a {@code local_skin:} id and its model. */
    public record AssignedSkin(String skinId, String model) {
        /** The content id the skin id names. */
        public String hash() {
            return skinId.substring("local_skin:".length());
        }
    }

    /** What became of a skin offered to the pool. */
    public enum PoolAddResult { ADDED, NAME_TAKEN, DUPLICATE, FULL, INVALID }

    /** Insertion order is age: the first entry was given longest ago. */
    private final LinkedHashMap<UUID, AssignedSkin> assigned = new LinkedHashMap<>();
    /** The pool's skins by {@link PoolSkinEntry#key()}, in the order they were added. */
    private final LinkedHashMap<String, PoolSkinEntry> pool = new LinkedHashMap<>();
    private Path file;
    private boolean persistable;
    private byte[] pendingSave;

    ServerSkinAssignmentStore() {
    }

    public static ServerSkinAssignmentStore getInstance() {
        return INSTANCE;
    }

    /** Whether a skin id and model may be assigned or pooled: a strong local skin id, classic or slim. */
    public static boolean isValid(String skinId, String model) {
        return skinId != null && skinId.startsWith("local_skin:")
                && NetworkSecurity.isValidV2AppearanceId(skinId, "skin")
                && ("classic".equals(model) || "slim".equals(model));
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
            QuickSkinInfo.LOGGER.error("Unable to initialize assigned-skin storage in {}", directory, e);
            file = null;
        }
    }

    /**
     * Reads the world's assigned skins and pool. An entry that breaks the rules is dropped; a file
     * that is not such a document leaves the store empty and is never overwritten.
     */
    public synchronized void load() {
        assigned.clear();
        pool.clear();
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file");
            readDocument(BoundedFileReader.readUtf8(file, MAX_FILE_BYTES));
        } catch (IOException | RuntimeException e) {
            assigned.clear();
            pool.clear();
            persistable = false;
            QuickSkinInfo.LOGGER.error("Could not read the assigned skins in {}; they start empty "
                    + "and the file is left untouched until it is fixed", file, e);
        }
    }

    /** The skin an admin gave the player, or null. */
    @Nullable
    public synchronized AssignedSkin assigned(UUID playerId) {
        return playerId != null ? assigned.get(playerId) : null;
    }

    /** Every player with an assigned skin, oldest first. */
    public synchronized Map<UUID, AssignedSkin> assignedSkins() {
        return new LinkedHashMap<>(assigned);
    }

    /** Whether the player may be given a skin: they have one already, or the store has room. */
    public synchronized boolean hasRoomFor(UUID playerId) {
        return playerId != null && (assigned.containsKey(playerId) || assigned.size() < MAX_ASSIGNED);
    }

    /**
     * Gives the player this skin for now; a new player is refused while the store is full.
     *
     * @return whether it changed what they had
     */
    public synchronized boolean assign(UUID playerId, String skinId, String model) {
        if (playerId == null || !isValid(skinId, model) || !hasRoomFor(playerId)) return false;
        AssignedSkin skin = new AssignedSkin(skinId, model);
        AssignedSkin previous = assigned.put(playerId, skin);
        if (skin.equals(previous)) return false;
        scheduleSave();
        return true;
    }

    /** Takes back the skin an admin gave the player; returns it, or null when there was none. */
    @Nullable
    public synchronized AssignedSkin lift(UUID playerId) {
        AssignedSkin lifted = playerId != null ? assigned.remove(playerId) : null;
        if (lifted != null) scheduleSave();
        return lifted;
    }

    /** Takes back every assigned skin, of players online or not; returns whose. */
    public synchronized List<UUID> liftAll() {
        List<UUID> lifted = new ArrayList<>(assigned.keySet());
        if (!lifted.isEmpty()) {
            assigned.clear();
            scheduleSave();
        }
        return lifted;
    }

    /** The pool's skins, in the order they were added. */
    public synchronized List<PoolSkinEntry> pool() {
        return List.copyOf(pool.values());
    }

    /** The pool skin with this name, in any case, or null. */
    @Nullable
    public synchronized PoolSkinEntry poolSkin(String name) {
        return name != null ? pool.get(PoolSkinEntry.key(name)) : null;
    }

    /** Adds a skin to the pool under a name of its own. */
    public synchronized PoolAddResult addToPool(String name, String skinId, String model) {
        if (!PoolSkinEntry.isValidName(name) || !isValid(skinId, model)) return PoolAddResult.INVALID;
        if (pool.containsKey(PoolSkinEntry.key(name))) return PoolAddResult.NAME_TAKEN;
        for (PoolSkinEntry entry : pool.values()) {
            if (entry.skinId().equals(skinId)) return PoolAddResult.DUPLICATE;
        }
        if (pool.size() >= TextureTransferLimits.MAX_POOL_SKINS) return PoolAddResult.FULL;
        pool.put(PoolSkinEntry.key(name), new PoolSkinEntry(name, skinId, model));
        scheduleSave();
        return PoolAddResult.ADDED;
    }

    /** Removes the pool skin with this name; returns it, or null when there is none. */
    @Nullable
    public synchronized PoolSkinEntry removeFromPool(String name) {
        PoolSkinEntry removed = name != null ? pool.remove(PoolSkinEntry.key(name)) : null;
        if (removed != null) scheduleSave();
        return removed;
    }

    /** Writes the store now; called while the server stops. */
    public synchronized void saveNow() {
        pendingSave = null;
        if (file == null || !persistable) return;
        write(file, encode());
    }

    /** Releases the world and everything held for it. */
    public synchronized void clear() {
        assigned.clear();
        pool.clear();
        file = null;
        persistable = false;
        pendingSave = null;
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
            QuickSkinInfo.LOGGER.debug("Deferred saving assigned skins until the server stops: the save queue is unavailable");
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
        assigned.forEach((playerId, skin) -> {
            JsonObject json = new JsonObject();
            json.addProperty("skin", skin.skinId());
            json.addProperty("model", skin.model());
            players.add(playerId.toString(), json);
        });
        JsonArray poolJson = new JsonArray();
        for (PoolSkinEntry entry : pool.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", entry.name());
            json.addProperty("skin", entry.skinId());
            json.addProperty("model", entry.model());
            poolJson.add(json);
        }
        JsonObject document = new JsonObject();
        document.addProperty("schema", SCHEMA);
        document.add("players", players);
        document.add("pool", poolJson);
        return GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    private void readDocument(String json) throws IOException {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IOException("not a JSON object");
        JsonObject document = root.getAsJsonObject();
        JsonElement schema = document.get("schema");
        if (schema == null || !schema.isJsonPrimitive() || schema.getAsInt() != SCHEMA) {
            throw new IOException("unknown assigned-skin schema " + schema);
        }
        JsonElement players = document.get("players");
        if (players != null && players.isJsonObject()) {
            for (Map.Entry<String, JsonElement> player : players.getAsJsonObject().entrySet()) {
                try {
                    UUID playerId = UUID.fromString(player.getKey());
                    if (!playerId.toString().equals(player.getKey()) || !player.getValue().isJsonObject()) continue;
                    JsonObject skin = player.getValue().getAsJsonObject();
                    String skinId = string(skin, "skin");
                    String model = string(skin, "model");
                    if (isValid(skinId, model) && assigned.size() < MAX_ASSIGNED) {
                        assigned.put(playerId, new AssignedSkin(skinId, model));
                    }
                } catch (IllegalArgumentException invalid) {
                    // One bad entry is dropped on its own
                }
            }
        }
        JsonElement poolJson = document.get("pool");
        if (poolJson != null && poolJson.isJsonArray()) {
            for (JsonElement element : poolJson.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject skin = element.getAsJsonObject();
                addLoaded(string(skin, "name"), string(skin, "skin"), string(skin, "model"));
            }
        }
    }

    private void addLoaded(String name, String skinId, String model) {
        if (!PoolSkinEntry.isValidName(name) || !isValid(skinId, model)
                || pool.containsKey(PoolSkinEntry.key(name))
                || pool.size() >= TextureTransferLimits.MAX_POOL_SKINS) return;
        for (PoolSkinEntry entry : pool.values()) {
            if (entry.skinId().equals(skinId)) return;
        }
        pool.put(PoolSkinEntry.key(name), new PoolSkinEntry(name, skinId, model));
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
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
            QuickSkinInfo.LOGGER.error("Unable to save assigned skins {}", target, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                QuickSkinInfo.LOGGER.debug("Unable to remove assigned-skin temp file {}", temporary, cleanupError);
            }
        }
    }

    private static Path temporaryFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }
}
