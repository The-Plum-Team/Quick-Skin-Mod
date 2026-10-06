package com.quickskin.mod.server.storage;

import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, expiring assembler for untrusted C2S texture chunks.
 * Assemblies are isolated by authenticated player and connection session.
 */
public class TextureChunkAssembler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TextureChunkAssembler.class);
    private static final int MAX_REJECTED_UPLOADS = 2 * TextureTransferLimits.MAX_SERVER_ASSEMBLIES;
    private static TextureChunkAssembler instance;

    private final Map<AssemblyKey, ChunkAssembly> assemblies = new LinkedHashMap<>();
    /**
     * Uploads refused for exceeding the texture limit, so their remaining chunks are dropped at
     * once instead of being accumulated again and the refusal is logged once.
     */
    private final LinkedHashMap<AssemblyKey, Long> rejectedUploads = new LinkedHashMap<>();
    private long retainedBytes;

    private TextureChunkAssembler() {
    }

    public static TextureChunkAssembler getInstance() {
        if (instance == null) {
            instance = new TextureChunkAssembler();
        }
        return instance;
    }

    /**
     * Adds one chunk using an authenticated player and an identity-stable connection object.
     */
    public synchronized byte @Nullable [] addChunk(
            UUID playerId,
            Object session,
            String textureType,
            String hash,
            int chunkIndex,
            int totalChunks,
            byte[] chunkData) {
        return addChunk(
                playerId, session, textureType, hash, chunkIndex, totalChunks, chunkData,
                TextureTransferLimits.MAX_TEXTURE_BYTES,
                TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
    }

    /** V2 entry point whose negotiated limits may only narrow the hard local caps. */
    public synchronized byte @Nullable [] addChunk(
            UUID playerId,
            Object session,
            String textureType,
            String hash,
            int chunkIndex,
            int totalChunks,
            byte[] chunkData,
            int maximumTextureBytes,
            int maximumChunkBytes) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        if (playerId == null || session == null || !NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash) || chunkData == null
                || chunkData.length == 0
                || maximumTextureBytes < 1
                || maximumTextureBytes > TextureTransferLimits.MAX_TEXTURE_BYTES
                || maximumChunkBytes < 1
                || maximumChunkBytes > TextureTransferLimits.MAX_WIRE_CHUNK_BYTES
                || maximumChunkBytes > maximumTextureBytes
                || chunkData.length > maximumChunkBytes
                || totalChunks < 1 || totalChunks > TextureTransferLimits.MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= totalChunks) {
            return null;
        }

        AssemblyKey key = new AssemblyKey(playerId, session, textureType, hash);
        if (rejectedUploads.containsKey(key)) {
            rejectedUploads.put(key, now);
            return null;
        }
        ChunkAssembly assembly = assemblies.get(key);
        if (assembly == null) {
            if (assemblies.size() >= TextureTransferLimits.MAX_SERVER_ASSEMBLIES
                    || countAssemblies(playerId) >= TextureTransferLimits.MAX_ASSEMBLIES_PER_PLAYER) {
                return null;
            }
            assembly = new ChunkAssembly(totalChunks, now);
            assemblies.put(key, assembly);
        } else if (assembly.totalChunks() != totalChunks) {
            removeAssembly(key, assembly);
            return null;
        }

        if (assembly.hasChunk(chunkIndex)) {
            return null;
        }
        long playerBytes = retainedBytesFor(playerId);
        if ((long) assembly.sizeBytes + chunkData.length > maximumTextureBytes) {
            removeAssembly(key, assembly);
            rejectOversizedUpload(key, now, totalChunks, chunkData.length, maximumTextureBytes);
            return null;
        }
        if (retainedBytes + chunkData.length > TextureTransferLimits.MAX_SERVER_ASSEMBLY_BYTES
                || playerBytes + chunkData.length > TextureTransferLimits.MAX_ASSEMBLY_BYTES_PER_PLAYER) {
            removeAssembly(key, assembly);
            return null;
        }

        byte[] copy = Arrays.copyOf(chunkData, chunkData.length);
        assembly.addChunk(chunkIndex, copy, now);
        retainedBytes += copy.length;
        if (!assembly.isComplete()) {
            return null;
        }

        byte[] result = assembly.assemble();
        removeAssembly(key, assembly);
        return result;
    }

    /** True while chunks of this exact upload are refused because it exceeded the limit. */
    public synchronized boolean isRejected(
            UUID playerId, Object session, String textureType, String hash) {
        if (playerId == null || session == null || textureType == null || hash == null) return false;
        purgeExpired(System.currentTimeMillis());
        return rejectedUploads.containsKey(new AssemblyKey(playerId, session, textureType, hash));
    }

    private void rejectOversizedUpload(
            AssemblyKey key, long now, int totalChunks, int chunkBytes, int maximumTextureBytes) {
        rejectedUploads.remove(key);
        rejectedUploads.put(key, now);
        while (rejectedUploads.size() > MAX_REJECTED_UPLOADS) {
            Iterator<AssemblyKey> eldest = rejectedUploads.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        LOGGER.warn("Rejected a {} upload ({}) from {}: its {} chunks of up to {} bytes exceed"
                        + " this server's {} byte texture limit (maxTextureUploadKilobytes)",
                key.textureType, key.hash, key.playerId, totalChunks, chunkBytes,
                maximumTextureBytes);
    }

    public synchronized void clear() {
        rejectedUploads.clear();
        assemblies.clear();
        retainedBytes = 0;
    }

    public synchronized void discardPlayer(UUID playerId) {
        if (playerId == null) return;
        rejectedUploads.keySet().removeIf(key -> key.playerId.equals(playerId));
        Iterator<Map.Entry<AssemblyKey, ChunkAssembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AssemblyKey, ChunkAssembly> entry = iterator.next();
            if (entry.getKey().playerId.equals(playerId)) {
                retainedBytes -= entry.getValue().sizeBytes;
                iterator.remove();
            }
        }
    }

    /** Discards only assemblies owned by the disconnecting connection identity. */
    public synchronized void discardSession(UUID playerId, Object session) {
        if (playerId == null || session == null) return;
        rejectedUploads.keySet().removeIf(
                key -> key.playerId.equals(playerId) && key.session == session);
        Iterator<Map.Entry<AssemblyKey, ChunkAssembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AssemblyKey, ChunkAssembly> entry = iterator.next();
            AssemblyKey key = entry.getKey();
            if (key.playerId.equals(playerId) && key.session == session) {
                retainedBytes -= entry.getValue().sizeBytes;
                iterator.remove();
            }
        }
    }

    private void purgeExpired(long now) {
        rejectedUploads.values().removeIf(
                rejectedAt -> now - rejectedAt > TextureTransferLimits.ASSEMBLY_TTL_MILLIS);
        Iterator<Map.Entry<AssemblyKey, ChunkAssembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AssemblyKey, ChunkAssembly> entry = iterator.next();
            if (now - entry.getValue().lastActivityMillis > TextureTransferLimits.ASSEMBLY_TTL_MILLIS) {
                retainedBytes -= entry.getValue().sizeBytes;
                iterator.remove();
            }
        }
    }

    private int countAssemblies(UUID playerId) {
        int count = 0;
        for (AssemblyKey key : assemblies.keySet()) {
            if (key.playerId.equals(playerId)) {
                count++;
            }
        }
        return count;
    }

    private long retainedBytesFor(UUID playerId) {
        long total = 0;
        for (Map.Entry<AssemblyKey, ChunkAssembly> entry : assemblies.entrySet()) {
            if (entry.getKey().playerId.equals(playerId)) {
                total += entry.getValue().sizeBytes;
            }
        }
        return total;
    }

    private void removeAssembly(AssemblyKey key, ChunkAssembly assembly) {
        if (assemblies.remove(key) != null) {
            retainedBytes -= assembly.sizeBytes;
        }
    }

    private static final class AssemblyKey {
        private final UUID playerId;
        private final Object session;
        private final String textureType;
        private final String hash;

        private AssemblyKey(UUID playerId, Object session, String textureType, String hash) {
            this.playerId = playerId;
            this.session = session;
            this.textureType = textureType;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof AssemblyKey)) return false;
            AssemblyKey key = (AssemblyKey) other;
            return playerId.equals(key.playerId) && session == key.session
                    && textureType.equals(key.textureType) && hash.equals(key.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, System.identityHashCode(session), textureType, hash);
        }
    }

    private static final class ChunkAssembly {
        private final byte[][] chunks;
        private int receivedChunks;
        private int sizeBytes;
        private long lastActivityMillis;

        private ChunkAssembly(int totalChunks, long now) {
            chunks = new byte[totalChunks][];
            lastActivityMillis = now;
        }

        private int totalChunks() {
            return chunks.length;
        }

        private boolean hasChunk(int index) {
            return chunks[index] != null;
        }

        private void addChunk(int index, byte[] data, long now) {
            chunks[index] = data;
            receivedChunks++;
            sizeBytes += data.length;
            lastActivityMillis = now;
        }

        private boolean isComplete() {
            return receivedChunks == chunks.length;
        }

        private byte[] assemble() {
            byte[] result = new byte[sizeBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }
}
