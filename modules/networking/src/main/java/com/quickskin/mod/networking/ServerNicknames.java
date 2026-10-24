package com.quickskin.mod.networking;

import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.server.data.ServerNicknameStore;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.NicknameResultPayload;
import com.quickskin.mod.networking.payloads.SyncNicknamesPayload;
//?}
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of nicknames (ADR 0015), on the server thread: applies the nickname a player asks
 * for, tells Quick Skin clients the nicknames of the players online so their name tags show
 * them, and keeps every player's tab-list name, which operators see with the account name.
 *
 * <p>Chat, join and leave messages read the nickname through {@link #displayedNickname}; a
 * change the player makes is announced to everyone. One an admin makes with a command (see
 * {@code QuickSkinCommands}) is told only to that player and the operators, so a game can hand
 * out names without saying who is who. Vanilla sends tab-list names in its join packets, built from
 * {@code getTabListDisplayName}, and nothing else changes them, so this class sends operators
 * their view after a join and every viewer theirs after a nickname, team or operator change,
 * which it notices by looking every second.</p>
 */
public final class ServerNicknames {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerNicknames.class);
    /** Key of the announcement, with English for clients that do not run Quick Skin. */
    private static final String NOW_KNOWN_AS = "quickskin.nickname.now_known_as";
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final ThreadLocal<Boolean> BUILDING_OPERATOR_VIEW = ThreadLocal.withInitial(() -> false);
    /** Players whose nickname Quick Skin clients must learn this tick. */
    private static final Set<UUID> CHANGED = new LinkedHashSet<>();
    /** Whether each online player was an operator when last looked at, by exact connection. */
    private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
    /** The team each player with a nickname had when last looked at. */
    private static final Map<UUID, TeamLook> TEAMS = new HashMap<>();
    private static int ticks;

    private record Viewer(Object connection, boolean operator) {
    }

    /**
     * What a team adds to a name in the tab list: its colour (a {@code ChatFormatting}, or from 26.3
     * an optional {@code TeamColor}), prefix and suffix, styles included.
     */
    private record TeamLook(String name, Object color, Component prefix, Component suffix) {
    }

    private ServerNicknames() {
    }

    /**
     * The nickname a player shows: the one an admin gave them, or else their own unless this
     * server has nicknames off; {@code ""} for none.
     */
    public static String displayedNickname(UUID playerId) {
        ServerNicknameStore store = ServerNicknameStore.getInstance();
        String assigned = store.assignedNickname(playerId);
        if (!assigned.isEmpty()) return assigned;
        return ServerConfig.getInstance().allowNicknames ? store.nickname(playerId) : "";
    }

    /** Whether the tab-list name being built is for an operator, who also sees the account name. */
    public static boolean buildingOperatorView() {
        return BUILDING_OPERATOR_VIEW.get();
    }

    /** Operators, here, are players with permission level 2, who also change Quick Skin's settings. */
    public static boolean isOperator(ServerPlayer player) {
        //? if <1.21.11 {
        return player.hasPermissions(2);
        //?} else {
        return player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        //?}
    }

    /** Whether this exact session negotiated {@code nicknames}, so it takes the nickname packets. */
    public static boolean canReceiveNicknames(ServerPlayer player) {
        if (player == null || !ProtocolNetwork.isReady(player)) return false;
        ProtocolProfile profile = ProtocolNetwork.profile(player);
        return ProtocolNetwork.acceptsV2(profile) && profile.supports(ProtocolCapability.NICKNAMES);
    }

    /** Network thread: whether this exact session may ask for this player's nickname. */
    static boolean admits(ServerPlayer sender, UUID playerId) {
        return sender != null && admits(ProtocolNetwork.profile(sender), sender.getUUID(), playerId);
    }

    /**
     * A request is admitted only from a negotiated v2 session with {@code nicknames}, and only
     * about the sender itself: a client never names another player.
     */
    static boolean admits(ProtocolProfile senderProfile, UUID senderId, UUID playerId) {
        return senderProfile != null
                && ProtocolNetwork.acceptsV2(senderProfile)
                && senderProfile.supports(ProtocolCapability.NICKNAMES)
                && senderId != null
                && senderId.equals(playerId);
    }

    /** Server thread: the player asked for a nickname ({@code ""}: none). */
    static void request(ServerPlayer player, int requestId, String nickname) {
        if (player == null) return;
        MinecraftServer server = server(player);
        ServerNicknameStore.Result result = ServerNicknameStore.getInstance().request(
                player.getUUID(), player.getScoreboardName(), nickname,
                ServerConfig.getInstance().allowNicknames, otherNames(server, player), System.currentTimeMillis());
        sendResult(player, requestId, result);
        if (result.changed()) {
            String name = player.getScoreboardName();
            if (result.nickname().isEmpty()) {
                LOGGER.info("{} ({}) no longer uses the nickname {}", name, player.getUUID(), result.previous());
            } else {
                LOGGER.info("{} ({}) is now known as {}", name, player.getUUID(), result.nickname());
            }
            shownNameChanged(server, List.of(player));
            announce(server, name, result.previous(), result.nickname());
        }
    }

    /**
     * Names no nickname may read like, besides those the store remembers: the players online
     * other than this one, the operators and the whitelist (never a profile lookup, which can block).
     */
    private static List<String> otherNames(MinecraftServer server, ServerPlayer player) {
        List<String> otherNames = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != player) otherNames.add(other.getScoreboardName());
        }
        otherNames.addAll(Arrays.asList(server.getPlayerList().getOpNames()));
        otherNames.addAll(Arrays.asList(server.getPlayerList().getWhiteListNames()));
        return otherNames;
    }

    /** Quick Skin clients learn these players' nicknames, and every viewer their tab-list names. */
    private static void shownNameChanged(MinecraftServer server, List<ServerPlayer> players) {
        if (players.isEmpty()) return;
        for (ServerPlayer player : players) {
            CHANGED.add(player.getUUID());
            TEAMS.put(player.getUUID(), team(player));
        }
        refreshTabNames(server, players);
    }

    /**
     * An admin gives one player this nickname for now (it shows instead of the player's own until
     * lifted). The player is told; nobody else is, so a game can hand out names without saying
     * who is who.
     */
    public static ServerNicknameStore.Result assign(ServerPlayer player, String nickname, String admin) {
        MinecraftServer server = server(player);
        ServerNicknameStore.Result result = ServerNicknameStore.getInstance().assign(
                player.getUUID(), player.getScoreboardName(), nickname,
                otherNames(server, player), System.currentTimeMillis());
        if (result.changed()) {
            LOGGER.info("{} gave {} ({}) the nickname {}", admin, player.getScoreboardName(),
                    player.getUUID(), result.nickname());
            shownNameChanged(server, List.of(player));
            tellAssigned(player, result.nickname());
        }
        return result;
    }

    /**
     * An admin gives each player a different name from {@code pool}, in random order, that reads
     * like nobody else's. Names an admin gave these players before are taken back first, so a new
     * round can hand the same names out again.
     *
     * @return how many players got a name; the rest found none free
     */
    public static int assignRandom(Collection<ServerPlayer> players, List<String> pool,
                                   java.util.Random random, String admin) {
        if (players.isEmpty()) return 0;
        MinecraftServer server = server(players.iterator().next());
        ServerNicknameStore store = ServerNicknameStore.getInstance();
        for (ServerPlayer player : players) store.lift(player.getUUID());
        List<String> names = new ArrayList<>(pool);
        java.util.Collections.shuffle(names, random);
        List<ServerPlayer> named = new ArrayList<>();
        List<ServerPlayer> unnamed = new ArrayList<>();
        int next = 0;
        long now = System.currentTimeMillis();
        for (ServerPlayer player : players) {
            List<String> others = otherNames(server, player);
            ServerNicknameStore.Result result = null;
            while (next < names.size()) {
                String candidate = names.get(next++);
                if (store.isAvailable(player.getUUID(), player.getScoreboardName(), candidate, others)) {
                    result = store.assign(player.getUUID(), player.getScoreboardName(), candidate, others, now);
                    break;
                }
            }
            if (result != null && result.changed()) {
                named.add(player);
            } else {
                unnamed.add(player);
            }
        }
        LOGGER.info("{} gave {} players random nicknames", admin, named.size());
        List<ServerPlayer> changed = new ArrayList<>(named);
        changed.addAll(unnamed);
        shownNameChanged(server, changed);
        for (ServerPlayer player : named) tellAssigned(player, store.assignedNickname(player.getUUID()));
        for (ServerPlayer player : unnamed) tellLifted(player);
        return named.size();
    }

    /**
     * Takes back the nickname an admin gave each player, who show their own again.
     *
     * @return how many had one
     */
    public static int lift(Collection<ServerPlayer> players, String admin) {
        List<ServerPlayer> lifted = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (!ServerNicknameStore.getInstance().lift(player.getUUID()).isEmpty()) lifted.add(player);
        }
        if (lifted.isEmpty()) return 0;
        LOGGER.info("{} took back the nicknames they gave {} players", admin, lifted.size());
        shownNameChanged(server(lifted.get(0)), lifted);
        for (ServerPlayer player : lifted) tellLifted(player);
        return lifted.size();
    }

    /**
     * Takes back every nickname admins gave, of the players online and of those who left (whose
     * names are then free again).
     *
     * @return how many there were
     */
    public static int liftAll(MinecraftServer server, String admin) {
        List<UUID> lifted = ServerNicknameStore.getInstance().liftAll();
        if (lifted.isEmpty()) return 0;
        LOGGER.info("{} took back every nickname admins gave ({} players)", admin, lifted.size());
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID playerId : lifted) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) online.add(player);
        }
        shownNameChanged(server, online);
        for (ServerPlayer player : online) tellLifted(player);
        return lifted.size();
    }

    /**
     * Takes away every nickname of each player, the one an admin gave and their own, so their
     * account name shows.
     *
     * @return how many had one
     */
    public static int clear(Collection<ServerPlayer> players, String admin) {
        List<ServerPlayer> cleared = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (ServerNicknameStore.getInstance().clearNicknames(player.getUUID())) cleared.add(player);
        }
        if (cleared.isEmpty()) return 0;
        LOGGER.info("{} removed the nicknames of {} players", admin, cleared.size());
        shownNameChanged(server(cleared.get(0)), cleared);
        for (ServerPlayer player : cleared) {
            sendOwnState(player);
            player.sendSystemMessage(Component.translatableWithFallback(
                    "quickskin.nickname.cleared_by_admin",
                    "An admin removed your nickname: other players see your account name.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return cleared.size();
    }

    /**
     * Locks or unlocks each player's nickname: a locked player cannot change theirs.
     *
     * @return how many changed
     */
    public static int setLocked(Collection<ServerPlayer> players, boolean locked, String admin) {
        int changed = 0;
        for (ServerPlayer player : players) {
            if (ServerNicknameStore.getInstance().setLocked(
                    player.getUUID(), player.getScoreboardName(), locked, System.currentTimeMillis())) {
                changed++;
                sendOwnState(player);
                player.sendSystemMessage((locked
                        ? Component.translatableWithFallback("quickskin.nickname.locked_by_admin",
                                "An admin locked your nickname: you cannot change it for now.")
                        : Component.translatableWithFallback("quickskin.nickname.unlocked_by_admin",
                                "An admin unlocked your nickname: you can change it again."))
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        if (changed > 0) {
            LOGGER.info("{} {} the nicknames of {} players", admin, locked ? "locked" : "unlocked", changed);
        }
        return changed;
    }

    private static void tellAssigned(ServerPlayer player, String nickname) {
        sendOwnState(player);
        player.sendSystemMessage(Component.translatableWithFallback("quickskin.nickname.assigned_to_you",
                "An admin gave you a nickname: other players now see you as %s.",
                Component.literal(nickname).withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.YELLOW));
    }

    private static void tellLifted(ServerPlayer player) {
        sendOwnState(player);
        String shown = displayedNickname(player.getUUID());
        player.sendSystemMessage(Component.translatableWithFallback("quickskin.nickname.lifted_for_you",
                "An admin took back the nickname they gave you: other players see you as %s again.",
                Component.literal(shown.isEmpty() ? player.getScoreboardName() : shown)
                        .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Tells everyone who is who now: "Account is now known as Nick", or "Old (Account) is now
     * known as New" or "... as Account" when a nickname changes or goes. English is sent along
     * for clients without Quick Skin's translation.
     */
    private static void announce(MinecraftServer server, String name, String previous, String nickname) {
        Component from = previous.isEmpty()
                ? Component.literal(name)
                : Component.literal(previous).append(Component.literal(" (" + name + ")").withStyle(ChatFormatting.GRAY));
        Component to = Component.literal(nickname.isEmpty() ? name : nickname);
        server.getPlayerList().broadcastSystemMessage(
                Component.translatableWithFallback(NOW_KNOWN_AS, "%s is now known as %s", from, to)
                        .withStyle(ChatFormatting.YELLOW),
                false);
    }

    /**
     * Tells the player's own client whether they may change their nickname now, so its menu does
     * not offer what the server would refuse: a notice ({@link NicknameStatus#NOTICE_REQUEST_ID}).
     */
    private static void sendOwnState(ServerPlayer player) {
        ServerNicknameStore store = ServerNicknameStore.getInstance();
        NicknameStatus status = !store.assignedNickname(player.getUUID()).isEmpty() ? NicknameStatus.ASSIGNED
                : store.isLocked(player.getUUID()) ? NicknameStatus.LOCKED
                : NicknameStatus.UNCHANGED;
        sendStatus(player, NicknameStatus.NOTICE_REQUEST_ID, status, 0L);
    }

    private static void sendResult(ServerPlayer player, int requestId, ServerNicknameStore.Result result) {
        NicknameStatus status = switch (result.status()) {
            case ACCEPTED -> NicknameStatus.ACCEPTED;
            case UNCHANGED -> NicknameStatus.UNCHANGED;
            case INVALID -> NicknameStatus.INVALID;
            case TAKEN -> NicknameStatus.TAKEN;
            case COOLDOWN -> NicknameStatus.COOLDOWN;
            case DISABLED -> NicknameStatus.DISABLED;
            case LOCKED -> NicknameStatus.LOCKED;
            case ASSIGNED -> NicknameStatus.ASSIGNED;
        };
        sendStatus(player, requestId, status, result.retryAfterMillis());
    }

    private static void sendStatus(ServerPlayer player, int requestId, NicknameStatus status, long retryAfterMillis) {
        if (!canReceiveNicknames(player)) return;
        // What the player shows now, which the client puts on its own name tag
        String shown = displayedNickname(player.getUUID());
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendNicknameResultToPlayer(
                    player, requestId, status.code(), shown, retryAfterMillis);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(player, new NicknameResultPayload(
                    requestId, status.code(), shown, retryAfterMillis));
            //?}
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to answer the nickname request of {}", player.getScoreboardName(), error);
        }
    }

    /** A Quick Skin session became ready: it learns every nickname of the players online. */
    static void sendSnapshot(ServerPlayer recipient) {
        if (!canReceiveNicknames(recipient)) return;
        List<NicknameEntry> entries = new ArrayList<>();
        for (ServerPlayer player : server(recipient).getPlayerList().getPlayers()) {
            String nickname = displayedNickname(player.getUUID());
            if (!nickname.isEmpty()) entries.add(new NicknameEntry(player.getUUID(), nickname));
        }
        send(recipient, entries);
        sendOwnState(recipient);
    }

    /** After vanilla's join packets: operators see the newcomer's account name, and the reverse. */
    public static void playerJoined(ServerPlayer player) {
        MinecraftServer server = server(player);
        // Whoever goes by the newcomer's account name gives it back
        for (ServerNicknameStore.Released released : ServerNicknameStore.getInstance().playerJoined(
                player.getUUID(), player.getScoreboardName(), System.currentTimeMillis())) {
            LOGGER.info("{} ({}) no longer uses the nickname {}, which reads like the name of {}",
                    released.name(), released.playerId(), released.nickname(), player.getScoreboardName());
            ServerPlayer holder = server.getPlayerList().getPlayer(released.playerId());
            if (holder == null) continue;
            shownNameChanged(server, List.of(holder));
            if (released.assigned()) {
                tellLifted(holder);
            } else if (ServerConfig.getInstance().allowNicknames
                    && ServerNicknameStore.getInstance().assignedNickname(holder.getUUID()).isEmpty()) {
                // Only a nickname that showed is announced
                announce(server, holder.getScoreboardName(), released.nickname(), "");
            }
        }
        boolean operator = isOperator(player);
        VIEWERS.put(player.getUUID(), new Viewer(player.connection, operator));
        // Quick Skin clients learn the newcomer's nickname, or that they have none any more: a
        // client keeps what it learned in an earlier visit of the same player
        CHANGED.add(player.getUUID());
        List<ServerPlayer> nicknamed = nicknamedPlayers(server);
        if (nicknamed.contains(player)) {
            TEAMS.put(player.getUUID(), team(player));
            ClientboundPlayerInfoUpdatePacket operatorView = tabNames(List.of(player), true);
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (viewer != player && isOperator(viewer)) viewer.connection.send(operatorView);
            }
        }
        if (operator && !nicknamed.isEmpty()) {
            player.connection.send(tabNames(nicknamed, true));
        }
    }

    /** The exact session ended; whoever reconnects is looked at afresh. */
    public static void playerLeft(UUID playerId, Object connection) {
        Viewer viewer = VIEWERS.get(playerId);
        if (viewer != null && viewer.connection() == connection) {
            VIEWERS.remove(playerId);
            TEAMS.remove(playerId);
            CHANGED.remove(playerId);
        }
    }

    /**
     * {@code allowNicknames} changed: every nickname a player chose shows or hides at once. One an
     * admin gave shows either way.
     */
    static void nicknamesToggled(MinecraftServer server) {
        ServerNicknameStore store = ServerNicknameStore.getInstance();
        List<ServerPlayer> withNickname = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!store.nickname(player.getUUID()).isEmpty() && store.assignedNickname(player.getUUID()).isEmpty()) {
                withNickname.add(player);
            }
        }
        shownNameChanged(server, withNickname);
    }

    /** Every server tick: sends this tick's changes, and every second looks for team and operator changes. */
    static void tick(MinecraftServer server) {
        if (!CHANGED.isEmpty()) {
            List<NicknameEntry> entries = new ArrayList<>();
            for (UUID playerId : CHANGED) {
                if (server.getPlayerList().getPlayer(playerId) != null) {
                    entries.add(new NicknameEntry(playerId, displayedNickname(playerId)));
                }
            }
            CHANGED.clear();
            if (!entries.isEmpty()) {
                for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                    if (canReceiveNicknames(recipient)) send(recipient, entries);
                }
            }
        }
        if (++ticks % POLL_INTERVAL_TICKS == 0) {
            lookForTeamAndOperatorChanges(server);
        }
    }

    private static void lookForTeamAndOperatorChanges(MinecraftServer server) {
        List<ServerPlayer> nicknamed = nicknamedPlayers(server);
        Set<UUID> nicknamedIds = new HashSet<>();
        List<ServerPlayer> teamChanged = new ArrayList<>();
        for (ServerPlayer player : nicknamed) {
            nicknamedIds.add(player.getUUID());
            TeamLook team = team(player);
            TeamLook previous = TEAMS.put(player.getUUID(), team);
            if (previous != null && !previous.equals(team)) teamChanged.add(player);
        }
        TEAMS.keySet().retainAll(nicknamedIds);
        if (!teamChanged.isEmpty()) refreshTabNames(server, teamChanged);

        Set<UUID> online = new HashSet<>();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            online.add(viewer.getUUID());
            boolean operator = isOperator(viewer);
            Viewer previous = VIEWERS.put(viewer.getUUID(), new Viewer(viewer.connection, operator));
            if (previous != null && previous.connection() == viewer.connection
                    && previous.operator() != operator && !nicknamed.isEmpty()) {
                viewer.connection.send(tabNames(nicknamed, operator));
            }
        }
        VIEWERS.keySet().retainAll(online);
    }

    /**
     * After another sender re-listed this player for some viewers (the account-skin refresh of
     * ADR 0012 sends vanilla's join packets again), the operators among them see the account
     * name again.
     */
    public static void sendOperatorView(ServerPlayer subject, Collection<ServerPlayer> viewers) {
        if (displayedNickname(subject.getUUID()).isEmpty()) return;
        ClientboundPlayerInfoUpdatePacket operatorView = null;
        for (ServerPlayer viewer : viewers) {
            if (!isOperator(viewer)) continue;
            if (operatorView == null) operatorView = tabNames(List.of(subject), true);
            viewer.connection.send(operatorView);
        }
    }

    /** Sends each viewer the tab-list names of these players, as that viewer sees them. */
    private static void refreshTabNames(MinecraftServer server, Collection<ServerPlayer> subjects) {
        ClientboundPlayerInfoUpdatePacket everyone = tabNames(subjects, false);
        ClientboundPlayerInfoUpdatePacket operators = tabNames(subjects, true);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(isOperator(viewer) ? operators : everyone);
        }
    }

    private static ClientboundPlayerInfoUpdatePacket tabNames(Collection<ServerPlayer> subjects, boolean operator) {
        boolean previous = BUILDING_OPERATOR_VIEW.get();
        BUILDING_OPERATOR_VIEW.set(operator);
        try {
            // The packet reads each player's getTabListDisplayName while it is built
            return new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), subjects);
        } finally {
            BUILDING_OPERATOR_VIEW.set(previous);
        }
    }

    static MinecraftServer server(ServerPlayer player) {
        //? if <1.21 {
        return player.server;
        //?} else {
        return player.level().getServer();
        //?}
    }

    private static List<ServerPlayer> nicknamedPlayers(MinecraftServer server) {
        List<ServerPlayer> nicknamed = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!displayedNickname(player.getUUID()).isEmpty()) nicknamed.add(player);
        }
        return nicknamed;
    }

    private static TeamLook team(ServerPlayer player) {
        Team team = player.getTeam();
        if (team == null) return new TeamLook("", null, null, null);
        if (team instanceof PlayerTeam playerTeam) {
            // Components compare their text, style and siblings
            return new TeamLook(team.getName(), team.getColor(),
                    playerTeam.getPlayerPrefix().copy(), playerTeam.getPlayerSuffix().copy());
        }
        return new TeamLook(team.getName(), team.getColor(), null, null);
    }

    private static void send(ServerPlayer recipient, List<NicknameEntry> entries) {
        try {
            for (int start = 0; start < entries.size(); start += TextureTransferLimits.MAX_NICKNAME_ENTRIES) {
                List<NicknameEntry> packet = List.copyOf(entries.subList(
                        start, Math.min(entries.size(), start + TextureTransferLimits.MAX_NICKNAME_ENTRIES)));
                //? if <1.21 {
                NetworkTransport.INSTANCE.sendNicknamesToPlayer(recipient, packet);
                //?} else {
                NetworkTransport.INSTANCE.sendToPlayer(recipient, new SyncNicknamesPayload(packet));
                //?}
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to send nicknames to {}", recipient.getScoreboardName(), error);
        }
    }

    /** Forgets every session and change; the server stopped. */
    public static void clear() {
        CHANGED.clear();
        VIEWERS.clear();
        TEAMS.clear();
        ticks = 0;
    }
}
