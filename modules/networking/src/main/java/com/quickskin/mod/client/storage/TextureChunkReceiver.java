package com.quickskin.mod.client.storage;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.ClientTextureIngressLimiter;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.networking.TextureRequestCoordinator;
import com.quickskin.mod.networking.TextureTransferLimits;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, expiring client assembler for texture chunks received from a server. */
@Environment(EnvType.CLIENT)
public class TextureChunkReceiver {
    private static TextureChunkReceiver instance;

    private final Map<AssemblyKey, ChunkAssembly> incompleteTextures = new LinkedHashMap<>();
    private long retainedBytes;

    private TextureChunkReceiver() {
    }

    public static TextureChunkReceiver getInstance() {
        if (instance == null) instance = new TextureChunkReceiver();
        return instance;
    }

    public synchronized void receiveChunk(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData,
            Object sourceConnection) {
        receiveChunk(
                hash, textureType, chunkIndex, totalChunks, chunkData, sourceConnection,
                TextureTransferLimits.MAX_TEXTURE_BYTES,
                TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
    }

    /** V2 entry point whose peer-advertised limits can only narrow local hard caps. */
    public synchronized void receiveChunk(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData,
            Object sourceConnection, int maximumTextureBytes, int maximumChunkBytes) {
        if (!ClientNetworkHandler.isCurrentConnection(sourceConnection)) return;
        long now = System.currentTimeMillis();
        purgeExpired(now);
        if (!NetworkSecurity.isValidContentId(hash) || !NetworkSecurity.isValidTextureType(textureType)
                || chunkData == null || chunkData.length == 0
                || maximumTextureBytes < 1
                || maximumTextureBytes > TextureTransferLimits.MAX_TEXTURE_BYTES
                || maximumChunkBytes < 1
                || maximumChunkBytes > TextureTransferLimits.MAX_WIRE_CHUNK_BYTES
                || maximumChunkBytes > maximumTextureBytes
                || chunkData.length > maximumChunkBytes
                || totalChunks < 1 || totalChunks > TextureTransferLimits.MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= totalChunks) {
            return;
        }

        AssemblyKey key = new AssemblyKey(hash, textureType);
        if (NetworkTextureCache.getInstance().containsTexture(hash, textureType)) {
            ChunkAssembly duplicate = incompleteTextures.get(key);
            if (duplicate != null) remove(key, duplicate);
            TextureRequestCoordinator.getInstance().markFulfilled(textureType, hash);
            return;
        }
        ChunkAssembly assembly = incompleteTextures.get(key);
        if (assembly == null) {
            if (incompleteTextures.size() >= TextureTransferLimits.MAX_CLIENT_ASSEMBLIES) return;
            assembly = new ChunkAssembly(totalChunks, now);
            incompleteTextures.put(key, assembly);
        } else if (assembly.chunks.length != totalChunks) {
            remove(key, assembly);
            return;
        }

        if (assembly.chunks[chunkIndex] != null) return;
        if ((long) assembly.sizeBytes + chunkData.length > maximumTextureBytes
                || retainedBytes + chunkData.length > TextureTransferLimits.MAX_CLIENT_ASSEMBLY_BYTES) {
            remove(key, assembly);
            return;
        }

        byte[] copy = Arrays.copyOf(chunkData, chunkData.length);
        assembly.chunks[chunkIndex] = copy;
        assembly.receivedCount++;
        assembly.sizeBytes += copy.length;
        assembly.lastActivityMillis = now;
        retainedBytes += copy.length;
        if (assembly.receivedCount != assembly.chunks.length) return;

        byte[] completeData = assembly.assemble();
        remove(key, assembly);
        if (completeData == null) return;

        if (!ClientTextureIngressLimiter.getInstance().allowDecode(completeData, textureType)) return;
        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        long generation = cache.generation();
        ClientIoExecutor.supplyAsyncRetaining(completeData.length,
                        () -> cache.prepareTextureIfCurrent(
                        generation, hash, textureType, completeData))
                .whenComplete((prepared, error) -> {
                    if (error != null) {
                        QuickSkinInfo.LOGGER.warn("Unable to process chunked network texture {}", hash, error);
                    } else if (prepared != null) {
                        Minecraft minecraft = Minecraft.getInstance();
                        if (minecraft != null) {
                            minecraft.execute(() -> {
                                if (!ClientNetworkHandler.isCurrentConnection(sourceConnection)) {
                                    cache.discardPreparedTexture(prepared);
                                    return;
                                }
                                if (cache.commitPreparedTextureIfCurrent(
                                        generation, hash, textureType, prepared)) {
                                    ClientNetworkHandler.onTextureStored(textureType, hash);
                                }
                            });
                        } else {
                            cache.discardPreparedTexture(prepared);
                        }
                    }
                });
    }

    public synchronized void clear() {
        incompleteTextures.clear();
        retainedBytes = 0;
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<AssemblyKey, ChunkAssembly>> iterator = incompleteTextures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AssemblyKey, ChunkAssembly> entry = iterator.next();
            if (now - entry.getValue().lastActivityMillis > TextureTransferLimits.ASSEMBLY_TTL_MILLIS) {
                retainedBytes -= entry.getValue().sizeBytes;
                iterator.remove();
            }
        }
    }

    private void remove(AssemblyKey key, ChunkAssembly assembly) {
        if (incompleteTextures.remove(key) != null) retainedBytes -= assembly.sizeBytes;
    }

    private static final class AssemblyKey {
        private final String hash;
        private final String textureType;

        private AssemblyKey(String hash, String textureType) {
            this.hash = hash;
            this.textureType = textureType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof AssemblyKey)) return false;
            AssemblyKey key = (AssemblyKey) other;
            return hash.equals(key.hash) && textureType.equals(key.textureType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hash, textureType);
        }
    }

    private static final class ChunkAssembly {
        private final byte[][] chunks;
        private int receivedCount;
        private int sizeBytes;
        private long lastActivityMillis;

        private ChunkAssembly(int totalChunks, long now) {
            chunks = new byte[totalChunks][];
            lastActivityMillis = now;
        }

        private byte[] assemble() {
            byte[] result = new byte[sizeBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) return null;
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }
}
