package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.server.vanilla.AccountSkinShareService;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Server side of the {@code account-skin-refresh} capability, shared by every packet era. */
final class AccountSkinRefreshRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSkinRefreshRequests.class);

    private AccountSkinRefreshRequests() {
    }

    /** Network thread: whether this exact negotiated session may report its own account skin. */
    static boolean admits(ServerPlayer sender, UUID reportedPlayerId) {
        return sender != null
                && ProtocolNetwork.acceptsV2(sender)
                && ProtocolNetwork.profile(sender).supports(ProtocolCapability.ACCOUNT_SKIN_REFRESH)
                && sender.getUUID().equals(reportedPlayerId);
    }

    /** Server thread: hands the report to the sharing service and logs what it decided. */
    static void submit(ServerPlayer player) {
        if (player == null) return;
        AccountSkinShareService.Admission admission = AccountSkinShareService.getInstance()
                .request(player.getUUID(), player.connection);
        String name = player.getName().getString();
        switch (admission) {
            case ACCEPTED -> LOGGER.info(
                    "{} uploaded a skin to their Mojang account; reading the signed skin from "
                            + "Mojang for players without Quick Skin", name);
            case OFFLINE_MODE -> LOGGER.info(
                    "Not sharing the Mojang account skin of {}: this server runs in offline mode, "
                            + "so Mojang never signed its profiles", name);
            case DISABLED -> LOGGER.debug(
                    "Not sharing the Mojang account skin of {}: shareAccountSkinWithVanillaClients "
                            + "is off", name);
            case COALESCED -> LOGGER.debug(
                    "{} reported another account skin upload; one more refresh follows", name);
            case CAPACITY -> LOGGER.warn(
                    "Not sharing the Mojang account skin of {}: too many refreshes are pending", name);
            default -> LOGGER.debug("Account skin report of {} ignored: {}", name, admission);
        }
    }
}
