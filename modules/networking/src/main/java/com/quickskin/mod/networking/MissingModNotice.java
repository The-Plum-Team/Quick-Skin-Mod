package com.quickskin.mod.networking;

import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.server.storage.MissingModNoticeLedger;
import com.quickskin.mod.server.vanilla.MissingModNoticeLayout;
import com.quickskin.mod.server.vanilla.MissingModNoticeText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
//? if <1.21 {
import java.util.WeakHashMap;
//?}
//? if >=1.21.5 {
import java.net.URI;
//?}

/**
 * Tells a player who joins without Quick Skin, once per world, that the server uses it and where
 * to get it ({@code notifyPlayersWithoutQuickSkin} in {@code quickskin-server.json}).
 *
 * <p>A Quick Skin client is not recognisable at the join: it says hello once it has seen the
 * server's channels, which a busy Forge client can take tens of seconds to handle. So the check
 * waits {@link #GRACE_TICKS} and then tells the player only if their exact connection still has
 * no protocol and registers no Quick Skin receiver. Bedrock players (Floodgate) are never told,
 * since they cannot install it. Server thread only.</p>
 */
public final class MissingModNotice {
    /** One minute: well past the 40-second late hello measured in a 370-mod Forge pack. */
    static final int GRACE_TICKS = 60 * 20;
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** Joins awaiting their check; past it, further joins wait for their next session. */
    private static final int MAX_PENDING = 1024;
    private static final MissingModNoticeText TEXT = MissingModNoticeText.bundled();

    /** Exact connections awaiting the check; they leave on disconnect, at the check or at stop. */
    private static final Map<Object, Pending> PENDING = new HashMap<>();
    //? if <1.21 {
    /** The language each client reported; 1.20.1 servers keep no copy of it. */
    private static final Map<ServerPlayer, String> LANGUAGES = new WeakHashMap<>();
    //?}

    private record Pending(UUID playerId, int dueTick) {
    }

    private MissingModNotice() {
    }

    /** Schedules the check for a player whose client has shown no Quick Skin at the join. */
    public static void playerJoined(ServerPlayer player) {
        if (!ServerConfig.getInstance().notifyPlayersWithoutQuickSkin) return;
        UUID playerId = player.getUUID();
        if (MissingModNoticeText.isBedrockPlayer(playerId)) return;
        if (PENDING.size() >= MAX_PENDING) return;
        if (MissingModNoticeLedger.getInstance().hasBeenNotified(playerId)) return;
        MinecraftServer server = player.level().getServer();
        PENDING.put(player.connection, new Pending(playerId, server.getTickCount() + GRACE_TICKS));
    }

    public static void playerDisconnected(Object connection) {
        PENDING.remove(connection);
    }

    public static void clear() {
        PENDING.clear();
        //? if <1.21 {
        LANGUAGES.clear();
        //?}
    }

    //? if <1.21 {
    /** Called by {@code ServerPlayerLanguageMixin} with each language the client reports. */
    public static void languageReported(ServerPlayer player, String language) {
        if (language != null) LANGUAGES.put(player, language);
    }
    //?}

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty() || server.getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
        int now = server.getTickCount();
        Iterator<Map.Entry<Object, Pending>> entries = PENDING.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Object, Pending> entry = entries.next();
            Pending pending = entry.getValue();
            if (now - pending.dueTick() < 0) continue;
            entries.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null || player.connection != entry.getKey() || runsQuickSkin(player)) continue;
            tell(player);
        }
    }

    private static boolean runsQuickSkin(ServerPlayer player) {
        return ProtocolNetwork.profile(player).mode() != ProtocolProfile.Mode.LOCAL_ONLY
                || ProtocolNetwork.advertisesQuickSkinChannels(player);
    }

    private static void tell(ServerPlayer player) {
        ServerConfig config = ServerConfig.getInstance();
        String custom = config.playersWithoutQuickSkinMessage;
        String text = custom == null || custom.isBlank() ? TEXT.text(language(player)) : custom;
        String link = MissingModNoticeText.validLink(config.playersWithoutQuickSkinLink);
        player.sendSystemMessage(message(text, link));
        MissingModNoticeLedger.getInstance().recordNotified(player.getUUID());
        QuickSkinInfo.LOGGER.info("Told {} that this server uses Quick Skin: their client does not run it",
                player.getName().getString());
    }

    /** The mascot beside the text, one chat line per row of {@link MissingModNoticeLayout}. */
    static Component message(String text, String link) {
        MutableComponent message = Component.empty();
        List<List<MissingModNoticeLayout.Segment>> lines = MissingModNoticeLayout.lines(text, link);
        for (int row = 0; row < lines.size(); row++) {
            if (row > 0) message.append("\n");
            for (MissingModNoticeLayout.Segment segment : lines.get(row)) {
                switch (segment.kind()) {
                    // A space advances 4 pixels and a bold one 5: together, one empty pixel.
                    case BLANK -> {
                        String spaces = " ".repeat(segment.cells());
                        message.append(spaces).append(Component.literal(spaces)
                                .withStyle(style -> style.withBold(true)));
                    }
                    case PIXEL -> message.append(Component.literal(segment.text())
                            .withStyle(style -> pixelStyle(style, segment.rgb())));
                    case TITLE -> message.append(Component.literal(segment.text())
                            .withStyle(style -> style.withColor(segment.rgb()).withBold(true)));
                    case LINK -> message.append(Component.literal(segment.text()).withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(openUrl(link))));
                    case TEXT -> message.append(segment.text());
                }
            }
        }
        return message;
    }

    private static Style pixelStyle(Style style, int rgb) {
        //? if >=1.21.4 {
        // A shadow of the pixel's own colour fills the gap to the next pixel and the next line.
        return style.withColor(rgb).withShadowColor(0xFF000000 | rgb);
        //?} else {
        return style.withColor(rgb);
        //?}
    }

    private static String language(ServerPlayer player) {
        //? if <1.21 {
        return LANGUAGES.get(player);
        //?} else {
        return player.clientInformation().language();
        //?}
    }

    private static ClickEvent openUrl(String link) {
        //? if <1.21.5 {
        return new ClickEvent(ClickEvent.Action.OPEN_URL, link);
        //?} else {
        return new ClickEvent.OpenUrl(URI.create(link));
        //?}
    }
}
