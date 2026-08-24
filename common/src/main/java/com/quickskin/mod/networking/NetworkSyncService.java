package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.*;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side service for syncing appearance changes to the server
 * Uses Architectury 13.x CustomPacketPayload system for MC 1.21.1
 */
@Environment(EnvType.CLIENT)
public class NetworkSyncService {

    private static NetworkSyncService instance;

    // Maximum chunk size for texture uploads (30KB - safe for network transmission)
    // This matches the old mod's chunk size for better compatibility
    private static final int MAX_CHUNK_SIZE = TextureTransferLimits.CHUNK_BYTES;
    private static final int MAX_PACKETS_PER_TICK = 16;
    private static final int MAX_BYTES_PER_TICK = 256 * 1024;
    private static final long IN_FLIGHT_TTL_MILLIS = 65_000L;
    private static final long INITIAL_RETRY_MILLIS = 5_000L;
    private static final long MAX_RETRY_MILLIS = 65_000L;
    private static final int MAX_SESSION_CONFIRMATIONS = 512;
    private static final long SNAPSHOT_REQUEST_RETRY_MILLIS = 15_000L;
    private static final long PROTOCOL_HELLO_RETRY_MILLIS = 3_000L;
    private static final int MAX_PROTOCOL_HELLO_ATTEMPTS = 5;

    private final AtomicLong syncSequence = new AtomicLong();
    private final AtomicLong snapshotRequestSequence = new AtomicLong();
    private final Map<UploadKey, String> confirmedUploadHashes = new ConcurrentHashMap<>();
    private final Map<UploadKey, SentUpload> sentUploadHashes = new ConcurrentHashMap<>();
    /** Last exact metadata document acknowledged by the server for each atlas hash. */
    private final Map<String, String> confirmedMetadata = new ConcurrentHashMap<>();
    private final Map<String, SentMetadata> sentMetadata = new ConcurrentHashMap<>();
    private PreparedSync activeSync;
    private PreparedSync queuedSync;
    private DesiredSync latestDesired;
    private AwaitingAcknowledgement awaitingAcknowledgement;
    private long latestAcknowledgedSyncToken;
    private long preparingToken;
    private long retryAtMillis;
    private int retryAttempt;
    private UUID snapshotPlayerId;
    private Object snapshotConnection;
    private long snapshotRequestId;
    private long snapshotRetryAtMillis;
    private UUID protocolPlayerId;
    private Object protocolConnection;
    private ProtocolSessions.ClientHello protocolHello;
    private int protocolHelloAttempts;
    private long protocolHelloRetryAtMillis;

    private NetworkSyncService() {
    }

    public static NetworkSyncService getInstance() {
        if (instance == null) {
            instance = new NetworkSyncService();
        }
        return instance;
    }

    /** Starts one exact, retryable full-roster request for the new connection session. */
    public synchronized void beginAppearanceSnapshotRequest(
            UUID playerId, Object connection) {
        if (playerId == null || connection == null) return;
        //? if <1.21 {
        boolean helloAvailable = NetworkTransport.INSTANCE.canServerReceiveProtocolHello();
        boolean legacyAvailable = NetworkTransport.INSTANCE.canServerReceiveLegacyProtocol();
        //?} else {
        boolean helloAvailable = NetworkTransport.INSTANCE.canServerReceive(ProtocolHelloPayload.TYPE);
        boolean legacyAvailable = NetworkTransport.INSTANCE.canServerReceive(UpdateAppearancePayload.TYPE)
                && NetworkTransport.INSTANCE.canServerReceive(RequestTexturePayload.TYPE);
        //?}
        ProtocolSessions.ClientHello hello = ProtocolSessions.getInstance().beginClientSession(
                playerId, connection, helloAvailable, legacyAvailable);
        protocolPlayerId = playerId;
        protocolConnection = connection;
        protocolHello = hello != null && hello.sendHello() ? hello : null;
        protocolHelloAttempts = 0;
        protocolHelloRetryAtMillis = 0L;
        tickProtocolHandshake();
        long requestId = snapshotRequestSequence.incrementAndGet();
        if (requestId <= 0L) {
            snapshotRequestSequence.set(1L);
            requestId = 1L;
        }
        snapshotPlayerId = playerId;
        snapshotConnection = connection;
        snapshotRequestId = requestId;
        snapshotRetryAtMillis = 0L;
    }

    /** Makes pending bootstrap work eligible after the exact connection negotiates v2. */
    public synchronized void onProtocolAcknowledged(Object connection) {
        if (connection == protocolConnection && isCurrentConnection(connection)) {
            protocolHello = null;
            protocolHelloAttempts = 0;
            protocolHelloRetryAtMillis = 0L;
            snapshotRetryAtMillis = 0L;
        }
    }

    /**
     * Sync appearance change to server
     * @param playerId Player UUID
     * @param skinId Skin ID (e.g., "local_skin:hash" or "username")
     * @param capeId Cape ID (e.g., "local_cape:hash" or "known:id")
     * @param model Model type ("classic", "slim", or "auto")
     */
    public void syncAppearance(UUID playerId, String skinId, String capeId, String model) {
        if (playerId == null || !NetworkSecurity.isValidModel(model != null ? model : "classic")
                || !NetworkSecurity.isValidLocalAppearanceId(skinId, "skin")
                || !NetworkSecurity.isValidLocalAppearanceId(capeId, "cape")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        //? if <1.21 {
        if (mc.player != null && mc.player.getClass().getName().equals("com.replaymod.replay.camera.CameraEntity")) {
        //?} else {
        if (mc.getConnection() == null) {
        //?}
            return;
        }

        Object sourceConnection = mc.getConnection();
        if (sourceConnection == null) return;
        ProtocolProfile protocolProfile =
                ProtocolSessions.getInstance().clientProfile(sourceConnection);
        if (!isUsableProfile(protocolProfile)) return;
        //? if >=1.21 {
        if (protocolProfile.negotiated()
                && (!NetworkTransport.INSTANCE.canServerReceive(UpdateAppearanceV2Payload.TYPE)
                || !NetworkTransport.INSTANCE.canServerReceive(TextureChunkV2Payload.TYPE))) return;
        //?}
        long token = syncSequence.incrementAndGet();
        String safeSkinId = skinId != null ? skinId : "";
        String safeCapeId = capeId != null ? capeId : "";
        String safeModel = model != null ? model : "classic";
        DesiredSync desired = new DesiredSync(
                token, sourceConnection, playerId,
                safeSkinId, safeCapeId, safeModel, protocolProfile);
        synchronized (this) {
            latestDesired = desired;
            awaitingAcknowledgement = null;
            latestAcknowledgedSyncToken = 0L;
            retryAtMillis = 0L;
            retryAttempt = 0;
        }
        startPreparation(desired);
    }

    /**
     * Returns whether the server echoed and authorized the latest exact skin selection for this
     * live connection. A locally applied texture or an in-flight upload is not acknowledgement.
     */
    public synchronized boolean isLatestAppearanceAcknowledged(
            UUID playerId, String skinId) {
        DesiredSync desired = latestDesired;
        return playerId != null && skinId != null && desired != null
                && desired.token == latestAcknowledgedSyncToken
                && desired.token == syncSequence.get()
                && desired.playerId.equals(playerId)
                && desired.skinId.equals(skinId)
                && isCurrentConnection(desired.sourceConnection);
    }

    private synchronized void startPreparation(DesiredSync desired) {
        if (desired == null || desired.token != syncSequence.get()
                || latestDesired != desired || preparingToken == desired.token
                || !isCurrentConnection(desired.sourceConnection)) return;
        preparingToken = desired.token;
        ClientIoExecutor.supplyAsync(() -> prepareSync(
                desired.token, desired.sourceConnection, desired.playerId,
                desired.skinId, desired.capeId, desired.model, desired.protocolProfile))
                .whenComplete((prepared, error) -> {
                    if (error != null) {
                        QuickSkin.LOGGER.warn("Unable to prepare appearance network sync", error);
                    }
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft == null) return;
                    minecraft.execute(() -> finishPreparation(desired, prepared));
                });
    }

    private synchronized void finishPreparation(
            DesiredSync desired, PreparedSync prepared) {
        if (preparingToken == desired.token) preparingToken = 0L;
        if (desired.token != syncSequence.get() || latestDesired != desired
                || !isCurrentConnection(desired.sourceConnection)) return;
        if (prepared == null) {
            scheduleRetry();
            return;
        }
        enqueuePreparedSync(prepared);
    }

    private PreparedSync prepareSync(
            long token, Object sourceConnection, UUID playerId,
            String skinId, String capeId, String model,
            ProtocolProfile protocolProfile) {
        if (syncSequence.get() != token || !isCurrentConnection(sourceConnection)) return null;
        List<PreparedUpload> uploads = new ArrayList<>(2);
        String serverSkinId = skinId;
        String serverCapeId = capeId;
        PreparedMetadata metadata = null;
        PreparedMetadata expectedMetadata = null;

        if (skinId.startsWith("local_skin:")) {
            PreparedUpload upload = prepareUpload(
                    skinId.substring("local_skin:".length()), "skin", protocolProfile);
            if (upload == null) return null;
            serverSkinId = "local_skin:" + upload.networkHash;
            if (!upload.alreadySent) uploads.add(upload);
        }
        if (syncSequence.get() != token || !isCurrentConnection(sourceConnection)) return null;
        if (capeId.startsWith("local_cape:")) {
            String localHash = capeId.substring("local_cape:".length());
            PreparedUpload upload = prepareUpload(localHash, "cape", protocolProfile);
            if (upload == null) return null;
            serverCapeId = "local_cape:" + upload.networkHash;
            if (!upload.alreadySent) uploads.add(upload);
            AnimationMetadata animation =
                    LocalAssetManager.getInstance().getAnimationMetadata(localHash);
            if (animation != null && (!protocolProfile.negotiated()
                    || protocolProfile.supports(ProtocolCapability.ANIMATION_METADATA))) {
                String json = animation.toJson();
                if (NetworkSecurity.isValidAnimationMetadata(json)) {
                    expectedMetadata = new PreparedMetadata(upload.networkHash, json);
                    if (!json.equals(confirmedMetadata.get(upload.networkHash))
                            && !isRecentMetadataSend(upload.networkHash, json)) {
                        metadata = expectedMetadata;
                    }
                }
            }
        }
        if (syncSequence.get() != token || !isCurrentConnection(sourceConnection)) return null;
        return new PreparedSync(
                token, sourceConnection, playerId, serverSkinId, serverCapeId,
                model, uploads, metadata, expectedMetadata, protocolProfile);
    }

    private PreparedUpload prepareUpload(
            String localHash, String textureType, ProtocolProfile protocolProfile) {
        UploadKey key = new UploadKey(localHash, textureType);
        // Recompute canonical bytes before trusting session state: animated timing is embedded in
        // the PNG identity and can change while the local source hash remains stable.
        byte[] textureData = LocalAssetManager.getInstance()
                .loadCanonicalTexture(localHash, textureType);
        if (textureData == null) return null;
        if (textureData.length > protocolProfile.maximumTextureBytes()) return null;
        String networkHash = protocolProfile.negotiated()
                ? HashUtil.computeContentId(textureData)
                : HashUtil.computeHash(textureData);
        if (protocolProfile.negotiated()
                ? !NetworkSecurity.isValidStrongContentId(networkHash)
                : !NetworkSecurity.isValidLegacyContentId(networkHash)) return null;
        String confirmedHash = confirmedUploadHashes.get(key);
        if (networkHash.equals(confirmedHash)) {
            return new PreparedUpload(
                    key, networkHash, textureType, new byte[0][], true, protocolProfile);
        }
        if (confirmedHash != null) confirmedUploadHashes.remove(key, confirmedHash);
        SentUpload sent = sentUploadHashes.get(key);
        if (sent != null && networkHash.equals(sent.networkHash)
                && System.currentTimeMillis() - sent.sentAtMillis < IN_FLIGHT_TTL_MILLIS) {
            return new PreparedUpload(
                    key, networkHash, textureType, new byte[0][], true, protocolProfile);
        }
        if (sent != null) sentUploadHashes.remove(key, sent);
        int chunkSize = Math.min(MAX_CHUNK_SIZE, protocolProfile.maximumChunkBytes());
        if (chunkSize < 1) return null;
        int totalChunks = (textureData.length + chunkSize - 1) / chunkSize;
        if (totalChunks < 1 || totalChunks > TextureTransferLimits.MAX_CHUNKS) return null;
        byte[][] chunks = new byte[totalChunks][];
        for (int index = 0; index < totalChunks; index++) {
            int offset = index * chunkSize;
            int length = Math.min(chunkSize, textureData.length - offset);
            chunks[index] = java.util.Arrays.copyOfRange(textureData, offset, offset + length);
        }
        return new PreparedUpload(
                key, networkHash, textureType, chunks, false, protocolProfile);
    }

    private synchronized void enqueuePreparedSync(PreparedSync prepared) {
        if (prepared.token != syncSequence.get()
                || !isCurrentConnection(prepared.sourceConnection)) return;
        if (activeSync == null) activeSync = prepared;
        else queuedSync = prepared;
    }

    /** Emits a bounded number of already-prepared packets from the client tick. */
    public synchronized void tick() {
        tickProtocolHandshake();
        tickAppearanceSnapshotRequest();
        retryIfDue();
        if (activeSync == null) {
            activeSync = queuedSync;
            queuedSync = null;
        }
        PreparedSync sync = activeSync;
        if (sync == null) return;
        if (!isCurrentConnection(sync.sourceConnection)) {
            activeSync = null;
            queuedSync = null;
            return;
        }
        if (sync.token != syncSequence.get() && !sync.uploadStarted) {
            activeSync = null;
            return;
        }

        int packets = 0;
        int bytes = 0;
        while (sync.uploadIndex < sync.uploads.size() && packets < MAX_PACKETS_PER_TICK) {
            PreparedUpload upload = sync.uploads.get(sync.uploadIndex);
            if (isUploadConfirmedOrRecent(upload.key, upload.networkHash)) {
                sync.uploadIndex++;
                sync.chunkIndex = 0;
                continue;
            }
            if (sync.chunkIndex >= upload.chunks.length) {
                putBounded(sentUploadHashes, upload.key,
                        new SentUpload(upload.networkHash, System.currentTimeMillis()));
                sync.uploadIndex++;
                sync.chunkIndex = 0;
                continue;
            }
            byte[] chunk = upload.chunks[sync.chunkIndex];
            if (bytes > 0 && bytes + chunk.length > MAX_BYTES_PER_TICK) break;
            try {
                sendTextureChunk(upload, sync.chunkIndex, chunk);
            } catch (RuntimeException | LinkageError error) {
                QuickSkin.LOGGER.warn("Unable to send texture upload chunk", error);
                activeSync = null;
                scheduleRetry();
                return;
            }
            sync.uploadStarted = true;
            sync.chunkIndex++;
            packets++;
            bytes += chunk.length;
        }
        if (sync.uploadIndex < sync.uploads.size()) return;

        // A newer selection supersedes only the final dependent messages; completed uploads stay reusable.
        if (sync.token != syncSequence.get()) {
            activeSync = null;
            return;
        }
        if (!sync.metadataHandled && packets < MAX_PACKETS_PER_TICK) {
            if (sync.metadata != null
                    && !sync.metadata.json.equals(
                            confirmedMetadata.get(sync.metadata.networkHash))
                    && !isRecentMetadataSend(
                            sync.metadata.networkHash, sync.metadata.json)) {
                try {
                    sendAnimationMetadata(sync.metadata, sync.protocolProfile);
                } catch (RuntimeException | LinkageError error) {
                    QuickSkin.LOGGER.warn("Unable to send animation metadata", error);
                    activeSync = null;
                    scheduleRetry();
                    return;
                }
                putBounded(sentMetadata, sync.metadata.networkHash,
                        new SentMetadata(sync.metadata.json, System.currentTimeMillis()));
                packets++;
            }
            sync.metadataHandled = true;
        }
        if (!sync.metadataHandled || packets >= MAX_PACKETS_PER_TICK) return;
        armAcknowledgement(sync);
        try {
            sendAppearance(sync);
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.warn("Unable to send appearance update", error);
        }
        activeSync = null;
    }

    private void tickProtocolHandshake() {
        ProtocolSessions.ClientHello hello = protocolHello;
        if (hello == null || protocolConnection == null
                || !isCurrentConnection(protocolConnection)) return;
        ProtocolProfile profile = ProtocolSessions.getInstance()
                .clientProfile(protocolConnection);
        if (profile.mode() != ProtocolProfile.Mode.LOCAL_ONLY) {
            protocolHello = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (protocolHelloAttempts >= MAX_PROTOCOL_HELLO_ATTEMPTS
                || now < protocolHelloRetryAtMillis) return;
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendProtocolHelloToServer(hello.nonce(), hello.offer());
            //?} else {
            NetworkTransport.INSTANCE.sendToServer(
                    new ProtocolHelloPayload(hello.nonce(), hello.offer()));
            //?}
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.debug("Unable to send QuickSkin protocol hello", error);
        }
        protocolHelloAttempts++;
        protocolHelloRetryAtMillis = now + PROTOCOL_HELLO_RETRY_MILLIS;
    }

    private void tickAppearanceSnapshotRequest() {
        if (snapshotRequestId <= 0L || snapshotPlayerId == null
                || snapshotConnection == null) return;
        if (!isCurrentConnection(snapshotConnection)) {
            clearAppearanceSnapshotRequest();
            return;
        }
        ProtocolProfile profile =
                ProtocolSessions.getInstance().clientProfile(snapshotConnection);
        if (!isUsableProfile(profile)) {
            snapshotRetryAtMillis = System.currentTimeMillis()
                    + SNAPSHOT_REQUEST_RETRY_MILLIS;
            return;
        }
        if (profile.negotiated()
                && !profile.supports(ProtocolCapability.APPEARANCE_SNAPSHOT_ACK)) {
            clearAppearanceSnapshotRequest();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < snapshotRetryAtMillis) return;
        //? if <1.21 {
        if (!NetworkTransport.INSTANCE.canServerReceiveAppearanceSnapshot()) {
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(
                RequestAppearanceSnapshotPayload.TYPE)) {
        //?}
            snapshotRetryAtMillis = now + SNAPSHOT_REQUEST_RETRY_MILLIS;
            return;
        }
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.requestAppearanceSnapshotFromServer(
                    snapshotPlayerId, snapshotRequestId);
            //?} else {
            NetworkTransport.INSTANCE.sendToServer(
                    new RequestAppearanceSnapshotPayload(
                            snapshotPlayerId, snapshotRequestId));
            //?}
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.debug("Unable to request the paced appearance snapshot", error);
        }
        snapshotRetryAtMillis = now + SNAPSHOT_REQUEST_RETRY_MILLIS;
    }

    public synchronized boolean confirmAppearanceSnapshot(
            Object connection, long requestId) {
        if (connection == null || connection != snapshotConnection
                || requestId <= 0L || requestId != snapshotRequestId) return false;
        clearAppearanceSnapshotRequest();
        return true;
    }

    private void clearAppearanceSnapshotRequest() {
        snapshotPlayerId = null;
        snapshotConnection = null;
        snapshotRequestId = 0L;
        snapshotRetryAtMillis = 0L;
    }

    private void retryIfDue() {
        long now = System.currentTimeMillis();
        if (retryAtMillis <= 0L || now < retryAtMillis || preparingToken != 0L
                || activeSync != null || queuedSync != null || latestDesired == null
                || latestDesired.token != syncSequence.get()
                || !isCurrentConnection(latestDesired.sourceConnection)) return;
        if (awaitingAcknowledgement != null) {
            if (!awaitingAcknowledgement.appearanceAcknowledged) {
                invalidateConfirmedUpload(
                        latestDesired.skinId, "local_skin:", "skin");
                invalidateConfirmedUpload(
                        latestDesired.capeId, "local_cape:", "cape");
            } else if (!awaitingAcknowledgement.metadataAcknowledged
                    && awaitingAcknowledgement.expectedMetadata != null) {
                SentMetadata sent = sentMetadata.get(
                        awaitingAcknowledgement.expectedMetadata.networkHash);
                if (sent != null && sent.json.equals(
                        awaitingAcknowledgement.expectedMetadata.json)) {
                    sentMetadata.remove(
                            awaitingAcknowledgement.expectedMetadata.networkHash, sent);
                }
            }
        }
        awaitingAcknowledgement = null;
        retryAtMillis = 0L;
        retryAttempt = Math.min(retryAttempt + 1, 30);
        startPreparation(latestDesired);
    }

    private void invalidateConfirmedUpload(
            String appearanceId, String prefix, String textureType) {
        if (appearanceId == null || !appearanceId.startsWith(prefix)) return;
        String localHash = appearanceId.substring(prefix.length());
        if (NetworkSecurity.isValidContentId(localHash)) {
            confirmedUploadHashes.remove(new UploadKey(localHash, textureType));
        }
    }

    private void armAcknowledgement(PreparedSync sync) {
        boolean metadataAcknowledged = sync.expectedMetadata == null
                || sync.expectedMetadata.json.equals(
                        confirmedMetadata.get(sync.expectedMetadata.networkHash));
        awaitingAcknowledgement = new AwaitingAcknowledgement(
                sync.token, sync.serverSkinId, sync.serverCapeId, sync.model,
                sync.expectedMetadata, false, metadataAcknowledged);
        scheduleRetry();
    }

    private void scheduleRetry() {
        long delay = INITIAL_RETRY_MILLIS
                * (1L << Math.min(retryAttempt, 4));
        retryAtMillis = System.currentTimeMillis()
                + Math.min(delay, MAX_RETRY_MILLIS);
    }

    private void sendTextureChunk(
            PreparedUpload upload, int chunkIndex, byte[] chunk) {
        if (upload.protocolProfile.negotiated()) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendTextureChunkV2ToServer(
                    upload.networkHash, upload.textureType,
                    chunkIndex, upload.chunks.length, chunk);
            //?} else {
            NetworkTransport.INSTANCE.sendToServer(new TextureChunkV2Payload(
                    upload.networkHash, upload.textureType,
                    chunkIndex, upload.chunks.length, chunk));
            //?}
            return;
        }
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendTextureChunkToServer(
                upload.networkHash, upload.textureType, chunkIndex, upload.chunks.length, chunk);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(new TextureChunkPayload(
                upload.networkHash, upload.textureType,
                chunkIndex, upload.chunks.length, chunk));
        //?}
    }

    private void sendAnimationMetadata(
            PreparedMetadata metadata, ProtocolProfile protocolProfile) {
        if (protocolProfile.negotiated()) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAnimationMetadataV2ToServer(
                    metadata.networkHash, metadata.json);
            //?} else {
            NetworkTransport.INSTANCE.sendToServer(new UploadAnimationMetadataV2Payload(
                    metadata.networkHash, metadata.json));
            //?}
            return;
        }
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendAnimationMetadataToServer(
                metadata.networkHash, metadata.json);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(new UploadAnimationMetadataPayload(
                metadata.networkHash, metadata.json));
        //?}
    }

    private void sendAppearance(PreparedSync sync) {
        if (sync.protocolProfile.negotiated()) {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAppearanceV2ToServer(
                    sync.playerId, sync.serverSkinId, sync.serverCapeId, sync.model);
            //?} else {
            NetworkTransport.INSTANCE.sendToServer(new UpdateAppearanceV2Payload(
                    sync.playerId, sync.serverSkinId, sync.serverCapeId, sync.model));
            //?}
            return;
        }
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendAppearanceToServer(
                sync.playerId, sync.serverSkinId, sync.serverCapeId, sync.model);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(new UpdateAppearancePayload(
                sync.playerId, sync.serverSkinId, sync.serverCapeId, sync.model));
        //?}
    }

    private boolean isCurrentConnection(Object expectedConnection) {
        Minecraft minecraft = Minecraft.getInstance();
        return expectedConnection != null && minecraft != null
                && minecraft.getConnection() == expectedConnection;
    }

    private boolean isUsableProfile(ProtocolProfile profile) {
        return profile != null && (profile.mode() == ProtocolProfile.Mode.LEGACY_V1
                || (profile.negotiated()
                && profile.version() == 2
                && profile.supports(ProtocolCapability.SHA256_CONTENT_IDS)
                && profile.supports(ProtocolCapability.CHUNKED_TEXTURE_TRANSFER)));
    }

    /** Confirms uploads only after the server echoes an appearance it actually authorized. */
    public synchronized boolean confirmAppearance(
            String skinId, String capeId, String model) {
        boolean skinConfirmed = confirmTextureId(skinId, "local_skin:", "skin");
        boolean capeConfirmed = confirmTextureId(capeId, "local_cape:", "cape");
        boolean appearanceConfirmed = false;
        AwaitingAcknowledgement awaiting = awaitingAcknowledgement;
        if (awaiting != null && awaiting.token == syncSequence.get()
                && awaiting.serverSkinId.equals(skinId)
                && awaiting.serverCapeId.equals(capeId)
                && awaiting.model.equals(model)) {
            awaiting.appearanceAcknowledged = true;
            appearanceConfirmed = true;
            completeAcknowledgementIfReady(awaiting);
        }
        return skinConfirmed || capeConfirmed || appearanceConfirmed;
    }

    private boolean confirmTextureId(String textureId, String prefix, String textureType) {
        if (textureId == null || !textureId.startsWith(prefix)) return false;
        String networkHash = textureId.substring(prefix.length());
        if (!NetworkSecurity.isValidContentId(networkHash)) return false;
        boolean confirmed = false;
        for (Map.Entry<UploadKey, SentUpload> entry : sentUploadHashes.entrySet()) {
            if (textureType.equals(entry.getKey().textureType)
                    && networkHash.equals(entry.getValue().networkHash)) {
                putBounded(confirmedUploadHashes, entry.getKey(), networkHash);
                sentUploadHashes.remove(entry.getKey(), entry.getValue());
                confirmed = true;
            }
        }
        return confirmed;
    }

    public boolean hasPendingMetadata(String networkHash, String json) {
        SentMetadata sent = sentMetadata.get(networkHash);
        return sent != null && sent.json.equals(json);
    }

    /** Confirms metadata only from the server's exact successful S2C echo. */
    public synchronized boolean confirmMetadata(String networkHash, String json) {
        if (!NetworkSecurity.isValidContentId(networkHash) || json == null) return false;
        SentMetadata sent = sentMetadata.get(networkHash);
        if (sent == null || !sent.json.equals(json)
                || !sentMetadata.remove(networkHash, sent)) return false;
        putBounded(confirmedMetadata, networkHash, json);
        AwaitingAcknowledgement awaiting = awaitingAcknowledgement;
        if (awaiting != null && awaiting.expectedMetadata != null
                && awaiting.expectedMetadata.networkHash.equals(networkHash)
                && awaiting.expectedMetadata.json.equals(json)) {
            awaiting.metadataAcknowledged = true;
            completeAcknowledgementIfReady(awaiting);
        }
        return true;
    }

    private void completeAcknowledgementIfReady(
            AwaitingAcknowledgement awaiting) {
        if (awaitingAcknowledgement == awaiting
                && awaiting.appearanceAcknowledged
                && awaiting.metadataAcknowledged) {
            latestAcknowledgedSyncToken = awaiting.token;
            awaitingAcknowledgement = null;
            retryAtMillis = 0L;
            retryAttempt = 0;
        }
    }

    private boolean isUploadConfirmedOrRecent(UploadKey key, String networkHash) {
        if (networkHash.equals(confirmedUploadHashes.get(key))) return true;
        SentUpload sent = sentUploadHashes.get(key);
        if (sent == null || !networkHash.equals(sent.networkHash)) return false;
        if (System.currentTimeMillis() - sent.sentAtMillis < IN_FLIGHT_TTL_MILLIS) return true;
        sentUploadHashes.remove(key, sent);
        return false;
    }

    private boolean isRecentMetadataSend(String networkHash, String json) {
        SentMetadata sent = sentMetadata.get(networkHash);
        if (sent == null) return false;
        if (!sent.json.equals(json)) {
            sentMetadata.remove(networkHash, sent);
            return false;
        }
        if (System.currentTimeMillis() - sent.sentAtMillis < IN_FLIGHT_TTL_MILLIS) return true;
        sentMetadata.remove(networkHash, sent);
        return false;
    }

    private <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_SESSION_CONFIRMATIONS) {
            var iterator = map.keySet().iterator();
            if (iterator.hasNext()) map.remove(iterator.next());
        }
        map.put(key, value);
    }

    public synchronized void clearSession() {
        syncSequence.incrementAndGet();
        activeSync = null;
        queuedSync = null;
        latestDesired = null;
        awaitingAcknowledgement = null;
        latestAcknowledgedSyncToken = 0L;
        preparingToken = 0L;
        retryAtMillis = 0L;
        retryAttempt = 0;
        confirmedUploadHashes.clear();
        sentUploadHashes.clear();
        confirmedMetadata.clear();
        sentMetadata.clear();
        ProtocolSessions.getInstance().clearClientSession(
                protocolPlayerId, protocolConnection);
        protocolPlayerId = null;
        protocolConnection = null;
        protocolHello = null;
        protocolHelloAttempts = 0;
        protocolHelloRetryAtMillis = 0L;
        clearAppearanceSnapshotRequest();
    }

    private record UploadKey(String localHash, String textureType) {
    }

    private record SentUpload(String networkHash, long sentAtMillis) {
    }

    private record SentMetadata(String json, long sentAtMillis) {
    }

    private static final class PreparedUpload {
        private final UploadKey key;
        private final String networkHash;
        private final String textureType;
        private final byte[][] chunks;
        private final boolean alreadySent;
        private final ProtocolProfile protocolProfile;

        private PreparedUpload(
                UploadKey key, String networkHash, String textureType,
                byte[][] chunks, boolean alreadySent,
                ProtocolProfile protocolProfile) {
            this.key = key;
            this.networkHash = networkHash;
            this.textureType = textureType;
            this.chunks = chunks;
            this.alreadySent = alreadySent;
            this.protocolProfile = protocolProfile;
        }
    }

    private record PreparedMetadata(String networkHash, String json) {
    }

    private record DesiredSync(
            long token,
            Object sourceConnection,
            UUID playerId,
            String skinId,
            String capeId,
            String model,
            ProtocolProfile protocolProfile) {
    }

    private static final class AwaitingAcknowledgement {
        private final long token;
        private final String serverSkinId;
        private final String serverCapeId;
        private final String model;
        private final PreparedMetadata expectedMetadata;
        private boolean appearanceAcknowledged;
        private boolean metadataAcknowledged;

        private AwaitingAcknowledgement(
                long token, String serverSkinId, String serverCapeId, String model,
                PreparedMetadata expectedMetadata,
                boolean appearanceAcknowledged, boolean metadataAcknowledged) {
            this.token = token;
            this.serverSkinId = serverSkinId;
            this.serverCapeId = serverCapeId;
            this.model = model;
            this.expectedMetadata = expectedMetadata;
            this.appearanceAcknowledged = appearanceAcknowledged;
            this.metadataAcknowledged = metadataAcknowledged;
        }
    }

    private static final class PreparedSync {
        private final long token;
        private final Object sourceConnection;
        private final UUID playerId;
        private final String serverSkinId;
        private final String serverCapeId;
        private final String model;
        private final List<PreparedUpload> uploads;
        private final PreparedMetadata metadata;
        private final PreparedMetadata expectedMetadata;
        private final ProtocolProfile protocolProfile;
        private int uploadIndex;
        private int chunkIndex;
        private boolean metadataHandled;
        private boolean uploadStarted;

        private PreparedSync(
                long token, Object sourceConnection, UUID playerId,
                String serverSkinId, String serverCapeId, String model,
                List<PreparedUpload> uploads, PreparedMetadata metadata,
                PreparedMetadata expectedMetadata,
                ProtocolProfile protocolProfile) {
            this.token = token;
            this.sourceConnection = sourceConnection;
            this.playerId = playerId;
            this.serverSkinId = serverSkinId;
            this.serverCapeId = serverCapeId;
            this.model = model;
            this.uploads = List.copyOf(uploads);
            this.metadata = metadata;
            this.expectedMetadata = expectedMetadata;
            this.protocolProfile = protocolProfile;
        }
    }

    /**
     * Clear appearance (reset to default)
     */
    public void clearAppearance(UUID playerId) {
        syncAppearance(playerId, "", "", "classic");
    }

    /**
     * Request a texture from the server (fallback mechanism for missed broadcasts)
     * @param playerId Player UUID making the request
     * @param textureType Type of texture ("skin" or "cape")
     * @param hash Texture hash to request
     */
    public void requestTexture(UUID playerId, String textureType, String hash) {
        if (playerId == null || !NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Object connection = mc.getConnection();
        if (connection == null) return;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        if (!isUsableProfile(profile)) return;

        if (profile.negotiated()) {
            if (!NetworkSecurity.isValidStrongContentId(hash)) return;
            //? if <1.21 {
            NetworkTransport.INSTANCE.requestTextureV2FromServer(
                    playerId, textureType, hash);
            //?} else {
            if (!NetworkTransport.INSTANCE.canServerReceive(RequestTextureV2Payload.TYPE)) return;
            NetworkTransport.INSTANCE.sendToServer(
                    new RequestTextureV2Payload(playerId, textureType, hash));
            //?}
            return;
        }
        if (!NetworkSecurity.isValidLegacyContentId(hash)) return;

        //? if <1.21 {
        NetworkTransport.INSTANCE.requestTextureFromServer(playerId, textureType, hash);
        //?} else {
        // Check if server supports QuickSkin packets
        if (!NetworkTransport.INSTANCE.canServerReceive(RequestTexturePayload.TYPE)) {
            return;
        }

        RequestTexturePayload payload = new RequestTexturePayload(playerId, textureType, hash);
        NetworkTransport.INSTANCE.sendToServer(payload);
        //?}
    }
}
