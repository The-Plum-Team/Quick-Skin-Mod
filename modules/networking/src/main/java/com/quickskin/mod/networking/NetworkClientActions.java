package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/** Loader/version-selected transport behind the feature API. */
@Environment(EnvType.CLIENT)
public final class NetworkClientActions implements ClientNetworkActions {
    @Override
    public void requestTexture(UUID playerId, String textureType, String contentId) {
        NetworkSyncService.getInstance().requestTexture(playerId, textureType, contentId);
    }

    @Override
    public void syncAppearance(UUID playerId, String skinId, String capeId, String model) {
        NetworkSyncService.getInstance().syncAppearance(playerId, skinId, capeId, model);
    }

    @Override
    public void activateStoredTexture(String textureType, String contentId) {
        ClientNetworkHandler.onTextureStored(textureType, contentId);
    }

    @Override
    public void flushPendingTransparencyReload() {
        ClientNetworkHandler.executePendingTransparencyReload();
    }

    @Override
    public void updateServerConfig(String key, boolean value) {
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendServerConfigUpdateToServer(key, value);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(
                new com.quickskin.mod.networking.payloads.UpdateServerConfigPayload(key, value));
        //?}
    }

    @Override
    public AccountSkinVisibility notifyAccountSkinChanged() {
        Minecraft minecraft = Minecraft.getInstance();
        Object connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) return AccountSkinVisibility.UNKNOWN;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(connection);
        if (profile.negotiated() && profile.supports(ProtocolCapability.ACCOUNT_SKIN_REFRESH)) {
            UUID playerId = minecraft.player.getUUID();
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAccountSkinChangedToServer(playerId);
            //?} else {
            if (NetworkTransport.INSTANCE.canServerReceive(
                    com.quickskin.mod.networking.payloads.AccountSkinChangedPayload.TYPE)) {
                NetworkTransport.INSTANCE.sendToServer(
                        new com.quickskin.mod.networking.payloads.AccountSkinChangedPayload(playerId));
            }
            //?}
        }
        return visibility(ClientConfig.getInstance().getServerOverride());
    }

    @Override
    public NicknameDelivery updateNickname(String nickname) {
        return NicknameSyncService.getInstance().update(nickname);
    }

    @Override
    public NicknameAnswer lastNicknameAnswer() {
        return NicknameSyncService.getInstance().lastAnswer();
    }

    @Override
    public String nicknameShownByServer() {
        return NicknameSyncService.getInstance().shownByServer();
    }

    @Override
    public NicknameAnswer.Outcome nicknameFixedByAdmin() {
        return NicknameSyncService.getInstance().fixedByAdmin();
    }

    @Override
    public ArmorHiding armorHiding() {
        return HiddenArmorSyncService.getInstance().hiding();
    }

    @Override
    public void updateHiddenArmor(int hidden) {
        HiddenArmorSyncService.getInstance().update(hidden);
    }

    @Override
    public int hiddenArmorInEffect() {
        return HiddenArmorSyncService.getInstance().inEffect();
    }

    @Override
    public SkinAdminView skinAdminView() {
        return SkinAdminClient.getInstance().view();
    }

    @Override
    public boolean giveLocalSkin(java.util.Collection<UUID> players, String localHash, String model) {
        return SkinAdminClient.getInstance().giveLocalSkin(players, localHash, model);
    }

    @Override
    public boolean addLocalSkinToPool(String name, String localHash, String model) {
        return SkinAdminClient.getInstance().addLocalSkinToPool(name, localHash, model);
    }

    @Override
    public boolean skinAdminAction(SkinAdminRequest request, java.util.Collection<UUID> players, String poolSkinName) {
        SkinAdminAction action = switch (request) {
            case GIVE_POOL_SKIN -> SkinAdminAction.APPLY;
            case RANDOM -> SkinAdminAction.RANDOM;
            case SHUFFLE -> SkinAdminAction.SHUFFLE;
            case RESET -> SkinAdminAction.RESET;
            case REMOVE_FROM_POOL -> SkinAdminAction.POOL_REMOVE;
        };
        return SkinAdminClient.getInstance().action(action, players, poolSkinName == null ? "" : poolSkinName);
    }

    @Override
    public int skinAdminAnswerSequence() {
        return SkinAdminClient.getInstance().answerSequence();
    }

    @Override
    public java.util.List<SkinAdminAnswer> skinAdminAnswersAfter(int sequence) {
        return SkinAdminClient.getInstance().answersAfter(sequence);
    }

    /** Reads what the connected server announced in its synchronized configuration. */
    static AccountSkinVisibility visibility(ServerConfig serverConfig) {
        String announced = serverConfig == null ? null : serverConfig.accountSkinVisibility;
        if (ServerConfig.ACCOUNT_SKIN_SHARED.equals(announced)) return AccountSkinVisibility.SHARED;
        if (ServerConfig.ACCOUNT_SKIN_AFTER_REJOIN.equals(announced)) return AccountSkinVisibility.AFTER_REJOIN;
        if (ServerConfig.ACCOUNT_SKIN_UNAVAILABLE.equals(announced)) return AccountSkinVisibility.UNAVAILABLE;
        return AccountSkinVisibility.UNKNOWN;
    }
}
