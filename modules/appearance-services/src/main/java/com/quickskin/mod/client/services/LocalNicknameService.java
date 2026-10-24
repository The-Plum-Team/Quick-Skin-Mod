package com.quickskin.mod.client.services;

import com.quickskin.mod.client.api.ClientNetworkActions;
import com.quickskin.mod.client.api.ClientNetworkApi;
import com.quickskin.mod.common.data.Nickname;
import com.quickskin.mod.config.AccountSkinPreferences;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.jetbrains.annotations.Nullable;

/**
 * The local player's cosmetic nickname: kept per launcher account in the client configuration,
 * like the account's skin selection, and handed to the server the player is on, which shows it
 * (ADR 0015). The account name stays the player's name for commands.
 */
@Environment(EnvType.CLIENT)
public final class LocalNicknameService {
    private LocalNicknameService() {
    }

    /** The account's own name: the player's name in a world, the launcher account's elsewhere. */
    public static String accountName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) return minecraft.player.getScoreboardName();
        return minecraft.getUser().getName();
    }

    /** The nickname this account chose, or {@code ""} for none. */
    public static String nickname() {
        return ClientConfig.getInstance().accountNicknames.nickname(accountKey());
    }

    /**
     * The name this account goes by: on a server that shows nicknames, the one that server shows
     * (it may have refused the one chosen); elsewhere, the chosen nickname or the account name.
     */
    public static String shownName() {
        String shown = ClientNetworkApi.actions().nicknameShownByServer();
        String nickname = shown != null ? shown : nickname();
        return nickname.isEmpty() ? accountName() : nickname;
    }

    /**
     * Why the player cannot change their nickname on the connected server for now: an admin gave
     * them one ({@code ASSIGNED}) or locked theirs ({@code LOCKED}); null when they can.
     */
    @Nullable
    public static ClientNetworkActions.NicknameAnswer.Outcome fixedByAdmin() {
        return ClientNetworkApi.actions().nicknameFixedByAdmin();
    }

    /** Whether this account can keep a nickname here: its identity can be stored. */
    public static boolean canChoose() {
        return accountKey() != null;
    }

    /**
     * Keeps a typed nickname for this account and hands it to the connected server; a blank one,
     * or the account's own name, removes the nickname.
     *
     * @return where the nickname went, or null when the text is not a valid nickname
     */
    @Nullable
    public static ClientNetworkActions.NicknameDelivery choose(String typed) {
        String key = accountKey();
        String nickname = Nickname.normalize(typed);
        if (key == null || nickname == null) return null;
        ClientConfig config = ClientConfig.getInstance();
        if (config.accountNicknames.set(key, nickname)) config.save();
        return ClientNetworkApi.actions().updateNickname(config.accountNicknames.nickname(key));
    }

    /** The connected server's latest answer to this player's nickname, or null. */
    @Nullable
    public static ClientNetworkActions.NicknameAnswer lastAnswer() {
        return ClientNetworkApi.actions().lastNicknameAnswer();
    }

    @Nullable
    private static String accountKey() {
        User user = Minecraft.getInstance().getUser();
        return user == null ? null : AccountSkinPreferences.key(user.getProfileId(), user.getName());
    }
}
