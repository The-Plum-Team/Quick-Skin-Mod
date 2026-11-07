package com.quickskin.mod.client.sync;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.SharedLibrary;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the skin library of a premium Minecraft account in the Quick Skin cloud, so every
 * modpack and computer the player signs in on lists the same skins and capes.
 *
 * <p>On by default ({@link ClientConfig#cloudSkinSync}). An account Mojang does not accept, such
 * as an offline one, is never synced: its library stays on this computer as before. Rounds run
 * on one daemon thread of their own, one at a time: a round reads what changed in the cloud,
 * downloads what this computer lacks, uploads what the cloud lacks and sends deletions both ways
 * ({@link SyncPlanner}). The library is read and changed only on the client thread. Downloads
 * land in the library folder imports use, are verified against their content id and decoded
 * before they are kept, and are never larger than {@link SkinSyncApi#MAX_BLOB_BYTES}. A skin or
 * cape the player wears, or the own skin, is never deleted by a deletion made elsewhere.
 */
@Environment(EnvType.CLIENT)
public final class CloudSkinSync {
    private static final String DISABLE_PROPERTY = "quickskin.cloudSync";
    private static final String URL_PROPERTY = "quickskin.cloudSyncUrl";
    private static final long FIRST_ROUND_DELAY_MS = 15_000;
    private static final long LOCAL_CHECK_MS = 20_000;
    private static final long CLOUD_CHECK_MS = 5 * 60_000;
    private static final long REFUSED_ACCOUNT_RETRY_MS = 60 * 60_000;
    private static final long FAILURE_BACKOFF_MS = 2 * 60_000;
    private static final long REQUEST_SPACING_MS = 600;
    private static final long TOKEN_RENEW_MARGIN_MS = 2L * 24 * 60 * 60_000;
    private static final int MAX_DOWNLOADS_PER_ROUND = 64;

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "quickskin-cloud-sync");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean closed;
    private static volatile boolean noticePending;
    private static long startedAt = -1;
    private static volatile long nextLocalCheck;
    private static volatile long nextCloudCheck;
    private static volatile Map<String, LibraryEntry> lastPushedLocal;
    private static UUID lastAccount;
    private static final Map<UUID, Long> refusedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    // Keys never sent this session: refused by the cloud, or kept here against a deletion
    private static final Set<String> ignored = new HashSet<>();

    private CloudSkinSync() {
    }

    /** Whether rounds may run at all: off in the packaged E2E harness and on request. */
    public static boolean isEnabled() {
        if (closed || Boolean.getBoolean("quickskin.e2e.enabled")) return false;
        if ("false".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY))) return false;
        return ClientConfig.getInstance().cloudSkinSync;
    }

    /**
     * True once, after the first round that signed in, when the player has not been told yet
     * that their library is kept in the cloud. The skin menu shows the notice.
     */
    public static boolean takeNotice() {
        if (!noticePending) return false;
        noticePending = false;
        ClientConfig config = ClientConfig.getInstance();
        if (config.cloudSyncNoticeShown) return false;
        config.cloudSyncNoticeShown = true;
        config.save();
        return true;
    }

    /** Called every client tick; starts a round when one is due. Cheap otherwise. */
    public static void tick() {
        if (!isEnabled() || RUNNING.get()) return;
        long now = System.currentTimeMillis();
        if (startedAt < 0) {
            startedAt = now;
            nextLocalCheck = now + FIRST_ROUND_DELAY_MS;
            nextCloudCheck = nextLocalCheck;
            return;
        }
        if (now < nextLocalCheck) return;
        nextLocalCheck = now + LOCAL_CHECK_MS;

        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft.getUser();
        if (user == null || user.getProfileId() == null) return;
        UUID account = user.getProfileId();
        Long refused = refusedUntil.get(account);
        if (refused != null && now < refused) return;
        if (!account.equals(lastAccount)) {
            lastAccount = account;
            lastPushedLocal = null;
            ignored.clear();
            nextCloudCheck = now;
        }

        Map<String, LibraryEntry> local = snapshotLocal();
        if (local == null) return;
        boolean cloudDue = now >= nextCloudCheck;
        if (!cloudDue && local.equals(lastPushedLocal)) return;

        Account who = new Account(account, user.getName(), user.getAccessToken());
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            CompletableFuture.runAsync(() -> round(who, local), WORKER)
                    .whenComplete((ignoredResult, error) -> {
                        if (error != null) {
                            QuickSkinInfo.LOGGER.warn("Cloud skin sync round failed", error);
                        }
                    });
        } catch (RejectedExecutionException busy) {
            RUNNING.set(false);
        }
    }

    /** Stops scheduling rounds; a running round finishes its current request and ends. */
    public static void close() {
        closed = true;
    }

    private record Account(UUID uuid, String name, String accessToken) {
    }

    /** The local library as the cloud sees it, or null before the catalog is ready. */
    private static Map<String, LibraryEntry> snapshotLocal() {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        if (assets.getSkinsDirectory() == null) return null;
        Map<String, LibraryEntry> local = new HashMap<>();
        for (AssetMetadata skin : assets.getAllSkins()) {
            if (!skin.isSkin() || skin.isCpmModel() || !syncable(skin)) continue;
            LibraryEntry entry = LibraryEntry.local(LibraryEntry.SKIN, skin.hash(),
                    LibraryEntry.fitName(skin.friendlyName(), "skin"),
                    assets.getSkinModelPreference(skin.hash()), assets.isFavorite(skin.hash()));
            local.put(entry.key(), entry);
        }
        for (AssetMetadata cape : assets.getAssetsByType("cape")) {
            if (!cape.isCape() || cape.isAnimated() || !syncable(cape)) continue;
            LibraryEntry entry = LibraryEntry.local(LibraryEntry.CAPE, cape.hash(),
                    LibraryEntry.fitName(cape.friendlyName(), "cape"), null, false);
            local.put(entry.key(), entry);
        }
        return Map.copyOf(local);
    }

    /** Only a PNG file the cloud can hold travels; anything else stays on this computer. */
    private static boolean syncable(AssetMetadata asset) {
        Path path = LocalAssetManager.getInstance().getSourcePath(asset.hash());
        return path != null && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                && asset.fileSize() > 0 && asset.fileSize() <= SkinSyncApi.MAX_BLOB_BYTES;
    }

    private static SkinSyncApi api() {
        String base = System.getProperty(URL_PROPERTY, SkinSyncApi.DEFAULT_BASE_URL);
        return new SkinSyncApi(base, SkinSyncApi.DEFAULT_MOJANG_JOIN_URL,
                "QuickSkin/" + PlatformHelper.getModVersion() + " (cloud skin sync)");
    }

    private static Path stateFile(UUID account) {
        Path shared = SharedLibrary.root();
        Path root = shared != null ? shared : PlatformHelper.getConfigDirectory().resolve("quickskin");
        return root.resolve("cloud-sync").resolve(SkinSyncApi.undashed(account) + ".json");
    }

    // ===== Worker thread =====

    private static void round(Account account, Map<String, LibraryEntry> local) {
        long backoff = 0;
        try {
            Path file = stateFile(account.uuid());
            SyncState state = SyncState.load(file);
            SkinSyncApi api = api();
            String token = session(api, account, state, file);
            if (token == null) return;

            SkinSyncApi.LibraryPage page;
            try {
                page = api.library(token, state.rev);
            } catch (SkinSyncApi.ApiException expired) {
                if (!expired.unauthorized()) throw expired;
                state.token = null;
                token = session(api, account, state, file);
                if (token == null) return;
                page = api.library(token, state.rev);
            }
            // A cloud that cannot say what changed hands the whole library: both sides merge,
            // and nothing here is deleted for an entry the cloud no longer lists.
            Map<String, LibraryEntry> known = page.reset() ? Map.of() : state.known;
            SyncPlanner.Plan plan;
            synchronized (ignored) {
                plan = SyncPlanner.plan(local, known, page.entries(), Set.copyOf(ignored));
            }
            Map<String, LibraryEntry> agreed = new HashMap<>(plan.known());

            // 1. Downloads: verified bytes written beside the imports, catalogued on the client thread
            List<LibraryEntry> downloads = plan.downloads();
            boolean allDownloaded = downloads.size() <= MAX_DOWNLOADS_PER_ROUND;
            Map<LibraryEntry, Path> stored = new HashMap<>();
            for (LibraryEntry entry : downloads.subList(0, Math.min(downloads.size(), MAX_DOWNLOADS_PER_ROUND))) {
                pace();
                try {
                    Path path = storeDownload(entry, api.getBlob(token, entry.kind(), entry.id()));
                    if (path != null) stored.put(entry, path);
                    else allDownloaded = false;
                } catch (SkinSyncApi.ApiException missing) {
                    if (missing.status() == 404) continue; // gone meanwhile: the next round sees its tombstone
                    allDownloaded = false;
                    if (missing.status() == 429 || missing.status() == 0) break;
                }
            }

            // 2. Local changes the cloud made, applied where the library lives
            CompletableFuture<Set<String>> keptHere = onClientThread(() -> applyLocally(plan, stored));
            Set<String> kept = keptHere.get(30, TimeUnit.SECONDS);
            for (LibraryEntry entry : stored.keySet()) agreed.put(entry.key(), entry);
            synchronized (ignored) {
                ignored.addAll(kept);
            }

            // 3. Uploads and deletions this computer made
            List<LibraryEntry> changes = new ArrayList<>();
            for (LibraryEntry upload : plan.uploads()) {
                if (kept.contains(upload.key())) continue;
                changes.add(upload);
            }
            changes.addAll(plan.cloudDeletes());
            pushChanges(api, token, changes, local, agreed);

            if (allDownloaded) state.rev = Math.max(state.rev, page.rev());
            state.known = agreed;
            state.save(file);
            lastPushedLocal = local;
            nextCloudCheck = System.currentTimeMillis() + CLOUD_CHECK_MS;
            if (!ClientConfig.getInstance().cloudSyncNoticeShown) noticePending = true;
        } catch (SkinSyncApi.ApiException error) {
            backoff = error.retryAfterSeconds() > 0 ? error.retryAfterSeconds() * 1000 : FAILURE_BACKOFF_MS;
            QuickSkinInfo.LOGGER.info("Cloud skin sync paused: {}", error.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            backoff = FAILURE_BACKOFF_MS;
            QuickSkinInfo.LOGGER.warn("Cloud skin sync round failed", error);
        } finally {
            if (backoff > 0) {
                long resume = System.currentTimeMillis() + backoff;
                nextLocalCheck = Math.max(nextLocalCheck, resume);
                nextCloudCheck = Math.max(nextCloudCheck, resume);
            }
            RUNNING.set(false);
        }
    }

    /** A website session for the account, signing in through Mojang when there is none. */
    private static String session(SkinSyncApi api, Account account, SyncState state, Path file)
            throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (state.token != null && state.tokenExpiresAt - TOKEN_RENEW_MARGIN_MS > now) return state.token;
        if (account.accessToken() == null || account.accessToken().isEmpty()) {
            refusedUntil.put(account.uuid(), now + REFUSED_ACCOUNT_RETRY_MS);
            return null;
        }
        try {
            SkinSyncApi.Session session = api.signIn(account.uuid(), account.name(), account.accessToken());
            state.token = session.token();
            state.tokenExpiresAt = session.expiresAt();
            state.save(file);
            return session.token();
        } catch (SkinSyncApi.ApiException refused) {
            if (refused.status() == 403 || refused.status() == 401) {
                // Not a premium account Mojang accepts: its library stays on this computer
                refusedUntil.put(account.uuid(), now + REFUSED_ACCOUNT_RETRY_MS);
                QuickSkinInfo.LOGGER.info("Cloud skin sync is off for this account: Mojang did not accept its session");
                return null;
            }
            throw refused;
        }
    }

    private static void pushChanges(SkinSyncApi api, String token, List<LibraryEntry> changes,
                                    Map<String, LibraryEntry> local, Map<String, LibraryEntry> agreed)
            throws IOException, InterruptedException {
        for (int start = 0; start < changes.size(); start += SkinSyncApi.MAX_CHANGES_PER_REQUEST) {
            List<LibraryEntry> batch = changes.subList(start,
                    Math.min(changes.size(), start + SkinSyncApi.MAX_CHANGES_PER_REQUEST));
            pace();
            SkinSyncApi.ChangeResult result = api.change(token, batch);
            List<LibraryEntry> retry = new ArrayList<>();
            for (String missing : result.missingBlobs()) {
                LibraryEntry entry = find(batch, missing);
                if (entry == null || entry.deleted() || !uploadBlob(api, token, entry)) continue;
                retry.add(entry);
            }
            if (!retry.isEmpty()) {
                pace();
                SkinSyncApi.ChangeResult second = api.change(token, retry);
                record(retry, second, agreed);
            }
            record(batch, result, agreed);
        }
    }

    private static void record(List<LibraryEntry> batch, SkinSyncApi.ChangeResult result,
                               Map<String, LibraryEntry> agreed) {
        Set<String> applied = new HashSet<>(result.applied());
        for (LibraryEntry entry : batch) {
            if (applied.contains(entry.id())) {
                agreed.put(entry.key(), entry.withRev(Math.max(0, result.rev())));
            } else if (result.rejected().contains(entry.id())) {
                synchronized (ignored) {
                    ignored.add(entry.key()); // refused (quota, invalid): kept here this session
                }
            }
        }
    }

    private static LibraryEntry find(List<LibraryEntry> batch, String id) {
        for (LibraryEntry entry : batch) if (entry.id().equals(id)) return entry;
        return null;
    }

    /** Uploads the file of a local entry after checking it still holds that content. */
    private static boolean uploadBlob(SkinSyncApi api, String token, LibraryEntry entry)
            throws IOException, InterruptedException {
        Path path = LocalAssetManager.getInstance().getSourcePath(entry.id());
        if (path == null) return false;
        byte[] bytes;
        try {
            bytes = BoundedFileReader.readBytes(path, SkinSyncApi.MAX_BLOB_BYTES);
        } catch (IOException unreadable) {
            return false;
        }
        if (!entry.id().equals(HashUtil.computeAssetContentId(bytes, entry.kind()))) return false;
        pace();
        api.putBlob(token, entry.kind(), entry.id(), bytes);
        return true;
    }

    /** Writes a downloaded file beside the imports once it proves to be what the cloud named. */
    private static Path storeDownload(LibraryEntry entry, byte[] bytes) throws IOException {
        if (bytes.length == 0 || !entry.id().equals(HashUtil.computeAssetContentId(bytes, entry.kind()))) {
            QuickSkinInfo.LOGGER.warn("Cloud skin sync discarded a download that does not match its id");
            return null;
        }
        if (SafeImageReader.readPng(bytes) == null) return null;
        LocalAssetManager assets = LocalAssetManager.getInstance();
        Path directory = LibraryEntry.SKIN.equals(entry.kind())
                ? assets.getSkinsDirectory() : assets.getCapesDirectory();
        if (directory == null) return null;
        Files.createDirectories(directory);
        Path root = directory.toAbsolutePath().normalize();
        String base = LibraryEntry.fitName(entry.name(), entry.kind());
        Path temporary = Files.createTempFile(root, ".quickskin-sync-", ".tmp");
        try {
            Files.write(temporary, bytes);
            for (int counter = 0; counter < 1000; counter++) {
                Path target = root.resolve(base + (counter == 0 ? "" : "_" + counter) + ".png").normalize();
                if (!target.getParent().equals(root)) throw new IOException("download escaped its folder");
                try {
                    Files.createFile(target);
                } catch (FileAlreadyExistsException taken) {
                    continue;
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            }
            return null;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void pace() throws InterruptedException {
        if (closed) throw new InterruptedException("closed");
        Thread.sleep(REQUEST_SPACING_MS);
    }

    // ===== Client thread =====

    private static <T> CompletableFuture<T> onClientThread(java.util.function.Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    /**
     * Catalogues the downloads and applies the cloud's renames, model and favourite choices and
     * deletions. Returns the keys kept here against a deletion made elsewhere.
     */
    private static Set<String> applyLocally(SyncPlanner.Plan plan, Map<LibraryEntry, Path> stored) {
        LocalAssetManager assets = LocalAssetManager.getInstance();
        Set<String> kept = new HashSet<>();
        if (!stored.isEmpty()) assets.reload();
        for (LibraryEntry downloaded : stored.keySet()) applyChoices(assets, downloaded, false);
        for (LibraryEntry update : plan.localUpdates()) applyChoices(assets, update, true);

        ClientConfig config = ClientConfig.getInstance();
        for (LibraryEntry gone : plan.localDeletes()) {
            String id = gone.id();
            boolean worn = id.equals(primary(assets, config.activeSkinHash))
                    || config.activeCapeHash.endsWith(id);
            boolean own = id.equals(primary(assets, config.playerOwnSkinHash));
            boolean last = LibraryEntry.SKIN.equals(gone.kind()) && !assets.keepsAListedSkin(List.of(id));
            if (worn || own || last || !assets.deleteAsset(id)) kept.add(gone.key());
        }
        return kept;
    }

    private static String primary(LocalAssetManager assets, String hash) {
        AssetMetadata metadata = hash == null || hash.isEmpty() ? null : assets.getMetadata(hash);
        return metadata == null ? null : metadata.hash();
    }

    private static void applyChoices(LocalAssetManager assets, LibraryEntry entry, boolean rename) {
        AssetMetadata metadata = assets.getMetadata(entry.id());
        if (metadata == null) return;
        if (rename && !entry.name().equals(LibraryEntry.fitName(metadata.friendlyName(), entry.kind()))) {
            assets.renameLocalAsset(entry.id(), entry.name());
        }
        if (LibraryEntry.SKIN.equals(entry.kind())) {
            if (!entry.model().equals(assets.getSkinModelPreference(entry.id()))) {
                assets.setSkinModelPreference(entry.id(), entry.model());
            }
            if (entry.favorite() != assets.isFavorite(entry.id())) {
                assets.setFavorite(entry.id(), entry.favorite());
            }
        }
    }
}
