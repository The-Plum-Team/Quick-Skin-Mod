package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.ReplayModHelper;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.networking.ModNetworking;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import dev.architectury.utils.Env;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept custom payload packets for Replay Mod compatibility.
 *
 * When Replay Mod plays back a recording, it creates a fake network connection
 * (EmbeddedChannel) that bypasses the standard Architectury/Fabric/Forge packet
 * handlers. Intercepting the packet before it reaches {@code ClientPacketListener} also keeps
 * Fabric Networking's normal HEAD callback from consuming the recorded payload first.
 */
@Mixin(value = ClientboundCustomPayloadPacket.class, priority = 2000)
public class ReplayModCompatMixin {

    @Inject(
            method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1
    )
    private void quickskin$interceptReplayPackets(ClientGamePacketListener packetListener, CallbackInfo ci) {
        if (!(packetListener instanceof ClientPacketListener listener)) {
            return;
        }

        ClientboundCustomPayloadPacket packet = (ClientboundCustomPayloadPacket) (Object) this;
        ResourceLocation id = packet.getIdentifier();

        // Check if the packet belongs to QuickSkin
        if (!id.getNamespace().equals(QuickSkin.MOD_ID)) {
            return;
        }

        if (!listener.getConnection().getClass().getName()
                .startsWith("com.replaymod.replay.")) {
            return;
        }

        // Reconstruct data buffer - copy the internal data and reset reader index
        FriendlyByteBuf originalBuf = packet.getData();
        FriendlyByteBuf buf = new FriendlyByteBuf(originalBuf.copy());
        buf.readerIndex(0); // Ensure we read from the beginning

        // Create a dummy context for packet handling
        ClientNetworkHandler.ExplicitConnectionPacketContext context =
                new ClientNetworkHandler.ExplicitConnectionPacketContext() {
            @Override
            public net.minecraft.world.entity.player.Player getPlayer() {
                return Minecraft.getInstance().player;
            }

            @Override
            public void queue(Runnable runnable) {
                Minecraft.getInstance().execute(runnable);
            }

            @Override
            public Env getEnvironment() {
                return Env.CLIENT;
            }

            @Override
            public Object connectionIdentity() {
                return listener;
            }
        };
        java.util.UUID replayProfileId = listener.getLocalGameProfile().getId();

        // Manually route to ClientNetworkHandler based on packet ID
        boolean handled = false;
        try {
            if (id.equals(ModNetworking.SYNC_APPEARANCE)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, false);
                ClientNetworkHandler.handleSyncAppearance(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, false);
                ClientNetworkHandler.handleSendTexture(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE_CHUNK)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, false);
                ClientNetworkHandler.handleSendTextureChunk(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_ANIMATION_METADATA)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, false);
                ClientNetworkHandler.handleSendAnimationMetadata(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SYNC_APPEARANCE_V2)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, true);
                ClientNetworkHandler.handleSyncAppearanceV2(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE_V2)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, true);
                ClientNetworkHandler.handleSendTextureV2(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE_CHUNK_V2)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, true);
                ClientNetworkHandler.handleSendTextureChunkV2(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_ANIMATION_METADATA_V2)) {
                ProtocolSessions.getInstance().admitReplayClientSession(
                        replayProfileId, listener, true);
                ClientNetworkHandler.handleSendAnimationMetadataV2(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SYNC_SERVER_CONFIG)) {
                ClientNetworkHandler.handleSyncServerConfig(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.COOLDOWN_UPDATE)) {
                ClientNetworkHandler.handleCooldownUpdate(buf, context);
                handled = true;
            }
        } catch (Exception exception) {
            QuickSkin.LOGGER.warn(
                    "Could not route recorded Quick Skin payload " + id, exception);
        } finally {
            buf.release();
        }

        // Cancel original handler to prevent double-processing
        if (handled) {
            if (Boolean.getBoolean("quickskin.e2e.enabled")) {
                QuickSkin.LOGGER.info("[QS-E2E] ReplayMod bridge handled recorded payload {}", id);
            }
            ReplayModHelper.markQuickSkinPacketIntercepted();
            ci.cancel();
        }
    }
}
