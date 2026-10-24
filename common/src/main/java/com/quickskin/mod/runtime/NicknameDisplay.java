package com.quickskin.mod.runtime;

import com.quickskin.mod.networking.ServerNicknames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where the nickname mixins learn what a player is called (ADR 0015). On the server it is the
 * nickname the player chose, while the server allows nicknames; on a client it is the nickname
 * the connected server announced, so name tags show it. Account names stay what commands,
 * selectors and scoreboards use: only display names change.
 */
public final class NicknameDisplay {
    /** The client's lookup, installed by its bootstrap; a dedicated server never loads it. */
    private static volatile Function<UUID, String> clientNicknames = playerId -> null;
    /** Whether the display name just built on this thread showed a nickname. */
    private static final ThreadLocal<Boolean> SHOWED_NICKNAME = ThreadLocal.withInitial(() -> false);

    private NicknameDisplay() {
    }

    public static void bindClientNicknames(Function<UUID, String> lookup) {
        clientNicknames = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * The name {@code Player.getDisplayName} decorates: the nickname, unless the player has none
     * or another mod already renamed the player (Forge and NeoForge {@code NameFormat}).
     */
    public static Component displayName(Player player, Component name) {
        String nickname = nickname(player);
        if (nickname == null || !name.getString().equals(player.getScoreboardName())) return name;
        SHOWED_NICKNAME.set(true);
        return Component.literal(nickname);
    }

    /**
     * The name in a join or leave message: the display name, followed by the account name in
     * grey when it shows a nickname, so everyone knows who it is.
     */
    public static Component withAccountName(ServerPlayer player) {
        // Only a display name that really shows the nickname gets the account name after it
        SHOWED_NICKNAME.set(false);
        Component displayName = player.getDisplayName();
        boolean showedNickname = SHOWED_NICKNAME.get();
        SHOWED_NICKNAME.set(false);
        return showedNickname ? displayName.copy().append(accountName(player)) : displayName;
    }

    /**
     * The player's tab-list name: the nickname in the team's colours, and for an operator the
     * account name after it. A name another mod set is kept.
     */
    @Nullable
    public static Component tabListName(ServerPlayer player, @Nullable Component current) {
        if (current != null) return current;
        String nickname = ServerNicknames.displayedNickname(player.getUUID());
        if (nickname.isEmpty()) return null;
        MutableComponent name = PlayerTeam.formatNameForTeam(player.getTeam(), Component.literal(nickname));
        if (!ServerNicknames.buildingOperatorView()) return name;
        return Component.empty().append(name).append(accountName(player));
    }

    @Nullable
    private static String nickname(Player player) {
        String nickname = player.level().isClientSide()
                ? clientNicknames.apply(player.getUUID())
                : ServerNicknames.displayedNickname(player.getUUID());
        return nickname == null || nickname.isEmpty() ? null : nickname;
    }

    private static Component accountName(Player player) {
        return Component.literal(" (" + player.getScoreboardName() + ")").withStyle(ChatFormatting.GRAY);
    }
}
