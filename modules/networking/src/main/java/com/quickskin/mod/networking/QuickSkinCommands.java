package com.quickskin.mod.networking;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.quickskin.mod.common.data.Nickname;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.server.data.NicknamePool;
import com.quickskin.mod.server.data.ServerNicknameStore;
import com.quickskin.mod.server.data.ServerSkinAssignmentStore;
import com.quickskin.mod.server.data.SkinPoolFolder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /quickskin}, for admins (permission level 2, like Quick Skin's server settings):
 *
 * <ul>
 *   <li>{@code nickname set <player> <nickname>} gives a player a nickname for now, shown instead
 *       of their own until it is taken back;</li>
 *   <li>{@code nickname random <players>} gives each a different name from
 *       {@link NicknamePool#FILE_NAME};</li>
 *   <li>{@code nickname reset [players]} takes those back, so players show their own again (with
 *       no players, of everyone, also those who are not online);</li>
 *   <li>{@code nickname clear <players>} removes every nickname they have;</li>
 *   <li>{@code nickname lock|unlock <players>} stops or lets them change their own;</li>
 *   <li>{@code skin set <players> <pool skin>}, {@code skin copy <players> <player>},
 *       {@code skin random <players>} and {@code skin shuffle <players>} give players a skin for
 *       now, which others see instead of theirs ({@link ServerSkinAssignments});</li>
 *   <li>{@code skin reset [players]} takes those back (with no players, of everyone);</li>
 *   <li>{@code skin pool list|add|remove|reload} keeps the world's skin pool, which also takes
 *       the PNG files in {@code config/quickskin-skins/}.</li>
 * </ul>
 *
 * Messages carry English for clients without Quick Skin and for the server console.
 */
public final class QuickSkinCommands {
    private QuickSkinCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext context, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("quickskin")
                .requires(QuickSkinCommands::isAdmin)
                .then(Commands.literal("nickname")
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("nickname", StringArgumentType.greedyString())
                                                .executes(c -> setNickname(c.getSource(),
                                                        EntityArgument.getPlayer(c, "player"),
                                                        StringArgumentType.getString(c, "nickname"))))))
                        .then(Commands.literal("random")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> randomNicknames(c.getSource(), players(c)))))
                        .then(Commands.literal("reset")
                                .executes(c -> resetAllNicknames(c.getSource()))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> resetNicknames(c.getSource(), players(c)))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> clearNicknames(c.getSource(), players(c)))))
                        .then(Commands.literal("lock")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> lockNicknames(c.getSource(), players(c), true))))
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> lockNicknames(c.getSource(), players(c), false)))))
                .then(Commands.literal("skin")
                        .then(Commands.literal("set")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .then(Commands.argument("skin", StringArgumentType.word())
                                                .suggests(QuickSkinCommands::suggestPoolSkins)
                                                .executes(c -> setSkin(c.getSource(), players(c),
                                                        StringArgumentType.getString(c, "skin"))))))
                        .then(Commands.literal("copy")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .then(Commands.argument("from", EntityArgument.player())
                                                .executes(c -> copySkin(c.getSource(), players(c),
                                                        EntityArgument.getPlayer(c, "from"))))))
                        .then(Commands.literal("random")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> randomSkins(c.getSource(), players(c)))))
                        .then(Commands.literal("shuffle")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> shuffleSkins(c.getSource(), players(c)))))
                        .then(Commands.literal("reset")
                                .executes(c -> resetAllSkins(c.getSource()))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(c -> resetSkins(c.getSource(), players(c)))))
                        .then(Commands.literal("pool")
                                .then(Commands.literal("list")
                                        .executes(c -> listPool(c.getSource())))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("from", EntityArgument.player())
                                                        .executes(c -> addToPool(c.getSource(),
                                                                StringArgumentType.getString(c, "name"),
                                                                EntityArgument.getPlayer(c, "from"))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("skin", StringArgumentType.word())
                                                .suggests(QuickSkinCommands::suggestPoolSkins)
                                                .executes(c -> removeFromPool(c.getSource(),
                                                        StringArgumentType.getString(c, "skin")))))
                                .then(Commands.literal("reload")
                                        .executes(c -> reloadPool(c.getSource()))))));
    }

    /** Admins, here, are those with permission level 2, who also change Quick Skin's server settings. */
    static boolean isAdmin(CommandSourceStack source) {
        //? if <1.21.11 {
        return source.hasPermission(2);
        //?} else {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        //?}
    }

    private static Collection<ServerPlayer> players(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return EntityArgument.getPlayers(context, "players");
    }

    private static int setNickname(CommandSourceStack source, ServerPlayer player, String typed) {
        String nickname = Nickname.normalize(typed);
        if (nickname == null || nickname.isEmpty()) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.invalid",
                    "That is not a valid nickname: letters, digits, spaces and _ - . ' , only, at most 32 characters"));
            return 0;
        }
        if (nickname.equals(player.getScoreboardName())) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.account_name",
                    "%s is that player's account name; to show their own nickname again, use reset",
                    nickname));
            return 0;
        }
        ServerNicknameStore.Result result = ServerNicknames.assign(player, nickname, source.getTextName());
        switch (result.status()) {
            case ACCEPTED -> {
                source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.nickname.set",
                        "%s now goes by %s", player.getScoreboardName(), result.nickname()), true);
                return 1;
            }
            case UNCHANGED -> source.sendFailure(Component.translatableWithFallback(
                    "quickskin.command.nickname.unchanged", "%s already goes by %s",
                    player.getScoreboardName(), result.nickname()));
            case TAKEN -> source.sendFailure(Component.translatableWithFallback(
                    "quickskin.command.nickname.taken",
                    "%s reads like the name or nickname of another player", nickname));
            default -> source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.invalid",
                    "That is not a valid nickname: letters, digits, spaces and _ - . ' , only, at most 32 characters"));
        }
        return 0;
    }

    private static int randomNicknames(CommandSourceStack source, Collection<ServerPlayer> players) {
        Path file = poolFile();
        List<String> pool = NicknamePool.load(file);
        if (pool.isEmpty()) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.random.empty",
                    "The nickname list %s has no valid names", file.toString()));
            return 0;
        }
        int named = ServerNicknames.assignRandom(players, pool, ThreadLocalRandom.current(), source.getTextName());
        int unnamed = players.size() - named;
        if (named > 0) {
            if (players.size() == 1) {
                ServerPlayer player = players.iterator().next();
                String nickname = ServerNicknameStore.getInstance().assignedNickname(player.getUUID());
                source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.nickname.set",
                        "%s now goes by %s", player.getScoreboardName(), nickname), true);
            } else {
                source.sendSuccess(() -> Component.translatableWithFallback(
                        "quickskin.command.nickname.random.multiple",
                        "Gave %s players random nicknames", named), true);
            }
        }
        if (unnamed > 0) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.random.short",
                    "%s players got no nickname: no free name is left in %s", unnamed, file.toString()));
        }
        return named;
    }

    private static int resetNicknames(CommandSourceStack source, Collection<ServerPlayer> players) {
        int lifted = ServerNicknames.lift(players, source.getTextName());
        return report(source, players, lifted, "quickskin.command.nickname.reset",
                "%s shows their own nickname again", "%s players show their own nicknames again",
                "No admin gave these players a nickname");
    }

    /** Takes back every nickname admins gave, also of players who are not online. */
    private static int resetAllNicknames(CommandSourceStack source) {
        int lifted = ServerNicknames.liftAll(source.getServer(), source.getTextName());
        if (lifted == 0) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.nickname.reset.none",
                    "No admin gave these players a nickname"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.nickname.reset.all",
                "Took back every nickname admins gave (%s players)", lifted), true);
        return lifted;
    }

    private static int clearNicknames(CommandSourceStack source, Collection<ServerPlayer> players) {
        int cleared = ServerNicknames.clear(players, source.getTextName());
        return report(source, players, cleared, "quickskin.command.nickname.clear",
                "Removed the nicknames of %s", "Removed the nicknames of %s players",
                "These players have no nickname");
    }

    private static int lockNicknames(CommandSourceStack source, Collection<ServerPlayer> players, boolean locked) {
        int changed = ServerNicknames.setLocked(players, locked, source.getTextName());
        return locked
                ? report(source, players, changed, "quickskin.command.nickname.lock",
                        "Locked the nickname of %s", "Locked the nicknames of %s players",
                        "Their nicknames were locked already")
                : report(source, players, changed, "quickskin.command.nickname.unlock",
                        "Unlocked the nickname of %s", "Unlocked the nicknames of %s players",
                        "Their nicknames were not locked");
    }

    /**
     * Tells the admin how many players changed: by name when the command named one player, by
     * count otherwise ({@code <key>.single} and {@code <key>.multiple}), or that none did
     * ({@code <key>.none}).
     */
    private static int report(CommandSourceStack source, Collection<ServerPlayer> players, int changed,
                              String key, String single, String multiple, String none) {
        if (changed == 0) {
            source.sendFailure(Component.translatableWithFallback(key + ".none", none));
        } else if (players.size() == 1) {
            String name = players.iterator().next().getScoreboardName();
            source.sendSuccess(() -> Component.translatableWithFallback(key + ".single", single, name), true);
        } else {
            source.sendSuccess(() -> Component.translatableWithFallback(key + ".multiple", multiple, changed), true);
        }
        return changed;
    }

    private static Path poolFile() {
        return PlatformHelper.getConfigDirectory().resolve(NicknamePool.FILE_NAME);
    }

    // ----- Skins -----

    private static CompletableFuture<Suggestions> suggestPoolSkins(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (PoolSkinEntry skin : ServerSkinAssignmentStore.getInstance().pool()) names.add(skin.name());
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static int setSkin(CommandSourceStack source, Collection<ServerPlayer> players, String name) {
        PoolSkinEntry skin = ServerSkinAssignmentStore.getInstance().poolSkin(name);
        if (skin == null) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.unknown",
                    "The skin pool has no skin called %s", name));
            return 0;
        }
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.assign(
                players, skin.skinId(), skin.model(), source.getTextName());
        return skinReport(source, players, outcome, "quickskin.command.skin.set",
                "%s now wears %s", "%s players now wear %s", skin.name());
    }

    private static int copySkin(CommandSourceStack source, Collection<ServerPlayer> players, ServerPlayer from) {
        String[] skin = ServerSkinAssignments.shownSkin(from.getUUID());
        if (skin == null) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.copy.none",
                    "%s has no Quick Skin skin to copy", from.getScoreboardName()));
            return 0;
        }
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.assign(
                players, skin[0], skin[1], source.getTextName());
        return skinReport(source, players, outcome, "quickskin.command.skin.copy",
                "%s now wears the skin of %s", "%s players now wear the skin of %s", from.getScoreboardName());
    }

    private static int randomSkins(CommandSourceStack source, Collection<ServerPlayer> players) {
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.assignRandom(
                players, ThreadLocalRandom.current(), source.getTextName());
        return skinReport(source, players, outcome, "quickskin.command.skin.random",
                "%s now wears a random skin from the pool", "Gave %s players random skins from the pool", null);
    }

    private static int shuffleSkins(CommandSourceStack source, Collection<ServerPlayer> players) {
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.shuffle(
                players, ThreadLocalRandom.current(), source.getTextName());
        if (outcome.status() == SkinAdminStatus.NOTHING) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.shuffle.none",
                    "Swapping needs at least two of these players with a Quick Skin skin of their own"));
            return 0;
        }
        return skinReport(source, players, outcome, "quickskin.command.skin.shuffle",
                "Swapped the skins of %s players", "Swapped the skins of %s players", null);
    }

    private static int resetSkins(CommandSourceStack source, Collection<ServerPlayer> players) {
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.lift(players, source.getTextName());
        if (outcome.status() == SkinAdminStatus.NOTHING) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.reset.none",
                    "No admin gave these players a skin"));
            return 0;
        }
        return skinReport(source, players, outcome, "quickskin.command.skin.reset",
                "%s wears their own skin again", "%s players wear their own skins again", null);
    }

    /** Takes back every assigned skin, also of players who are not online. */
    private static int resetAllSkins(CommandSourceStack source) {
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.liftAll(source.getServer(), source.getTextName());
        if (outcome.status() == SkinAdminStatus.NOTHING) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.reset.none",
                    "No admin gave these players a skin"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.skin.reset.all",
                "Took back every skin admins gave (%s players)", outcome.count()), true);
        return outcome.count();
    }

    private static int listPool(CommandSourceStack source) {
        List<PoolSkinEntry> pool = ServerSkinAssignmentStore.getInstance().pool();
        if (pool.isEmpty()) {
            source.sendFailure(poolEmpty());
            return 0;
        }
        List<String> names = new ArrayList<>();
        for (PoolSkinEntry skin : pool) names.add(skin.name());
        source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.skin.pool.list",
                "Skin pool (%s): %s", pool.size(), String.join(", ", names)), false);
        return pool.size();
    }

    private static int addToPool(CommandSourceStack source, String name, ServerPlayer from) {
        if (!PoolSkinEntry.isValidName(name)) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.pool.invalid_name",
                    "A pool skin's name uses letters, digits, _ - and . only, at most 32 characters"));
            return 0;
        }
        String[] skin = ServerSkinAssignments.shownSkin(from.getUUID());
        if (skin == null) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.copy.none",
                    "%s has no Quick Skin skin to copy", from.getScoreboardName()));
            return 0;
        }
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.addToPool(
                name, skin[0], skin[1], source.getTextName());
        switch (outcome.status()) {
            case DONE -> {
                source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.skin.pool.added",
                        "Added %s to the skin pool", name), true);
                return 1;
            }
            case NAME_TAKEN -> source.sendFailure(Component.translatableWithFallback(
                    "quickskin.command.skin.pool.taken",
                    "The skin pool already has a skin called %s, or that very skin", name));
            case POOL_FULL -> source.sendFailure(Component.translatableWithFallback(
                    "quickskin.command.skin.pool.full", "The skin pool is full (%s skins)",
                    TextureTransferLimits.MAX_POOL_SKINS));
            default -> source.sendFailure(failure(outcome.status()));
        }
        return 0;
    }

    private static int removeFromPool(CommandSourceStack source, String name) {
        ServerSkinAssignments.Outcome outcome = ServerSkinAssignments.removeFromPool(name, source.getTextName());
        if (outcome.status() != SkinAdminStatus.DONE) {
            source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.unknown",
                    "The skin pool has no skin called %s", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.skin.pool.removed",
                "Removed %s from the skin pool", name), true);
        return 1;
    }

    private static int reloadPool(CommandSourceStack source) {
        Path folder = skinFolder();
        ServerSkinAssignments.reloadFolder(source.getServer(), folder, count -> {
            if (count < 0) {
                source.sendFailure(Component.translatableWithFallback("quickskin.command.skin.pool.reload_failed",
                        "The skin folder could not be read now; try again"));
            } else {
                source.sendSuccess(() -> Component.translatableWithFallback("quickskin.command.skin.pool.reloaded",
                        "%s gave the skin pool %s skins", folder.toString(), count), true);
            }
        });
        return 1;
    }

    /**
     * Tells the admin what became of a skin command: by name when it named one player, by count
     * otherwise ({@code <key>.single}, {@code <key>.multiple}), or why nothing happened.
     *
     * @param detail what the players now wear, after the player or count, or null
     */
    private static int skinReport(CommandSourceStack source, Collection<ServerPlayer> players,
                                  ServerSkinAssignments.Outcome outcome, String key, String single,
                                  String multiple, String detail) {
        if (outcome.status() != SkinAdminStatus.DONE) {
            source.sendFailure(failure(outcome.status()));
            return 0;
        }
        int count = outcome.count();
        if (players.size() == 1) {
            String name = players.iterator().next().getScoreboardName();
            source.sendSuccess(() -> detail == null
                    ? Component.translatableWithFallback(key + ".single", single, name)
                    : Component.translatableWithFallback(key + ".single", single, name, detail), true);
        } else {
            source.sendSuccess(() -> detail == null
                    ? Component.translatableWithFallback(key + ".multiple", multiple, count)
                    : Component.translatableWithFallback(key + ".multiple", multiple, count, detail), true);
        }
        return Math.max(count, 1);
    }

    private static Component failure(SkinAdminStatus status) {
        return switch (status) {
            case POOL_EMPTY -> poolEmpty();
            case NO_ROOM -> Component.translatableWithFallback("quickskin.command.skin.no_room",
                    "The server has no room to keep more skins loaded");
            case MISSING_TEXTURE -> Component.translatableWithFallback("quickskin.command.skin.missing",
                    "The server no longer holds that skin");
            case NOTHING -> Component.translatableWithFallback("quickskin.command.skin.nothing",
                    "Nothing changed");
            default -> Component.translatableWithFallback("quickskin.command.skin.invalid",
                    "That skin cannot be given");
        };
    }

    private static Component poolEmpty() {
        return Component.translatableWithFallback("quickskin.command.skin.pool.empty",
                "The skin pool is empty: add skins from the Player skins panel, with /quickskin skin pool add, or as PNG files in %s",
                skinFolder().toString());
    }

    private static Path skinFolder() {
        return PlatformHelper.getConfigDirectory().resolve(SkinPoolFolder.DIRECTORY_NAME);
    }
}
