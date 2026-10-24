package com.quickskin.mod.client.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Client feature operations; packet formats, negotiation, and session checks belong to networking. */
public interface ClientNetworkActions {
    void requestTexture(UUID playerId, String textureType, String contentId);

    void syncAppearance(UUID playerId, String skinId, String capeId, String model);

    void activateStoredTexture(String textureType, String contentId);

    void flushPendingTransparencyReload();

    void updateServerConfig(String key, boolean value);

    /**
     * Reports a successful, user-initiated upload to the player's own Mojang account to the
     * connected server, when it negotiated {@code account-skin-refresh}.
     *
     * @return what players without Quick Skin on the connected server will see, as that server
     *         announced it
     */
    AccountSkinVisibility notifyAccountSkinChanged();

    /**
     * Tells the connected server the player's cosmetic nickname ({@code ""}: none), when that
     * session negotiated {@code nicknames}. Its answer arrives later, as
     * {@link #lastNicknameAnswer()}; a session that has not negotiated yet sends the nickname
     * once it has.
     */
    NicknameDelivery updateNickname(String nickname);

    /** The connected server's latest answer to the player's nickname, or null for none yet. */
    NicknameAnswer lastNicknameAnswer();

    /**
     * The nickname the connected server shows for the player ({@code ""}: their account name),
     * or null when not on a server that shows nicknames or before it said.
     */
    String nicknameShownByServer();

    /**
     * Why the player cannot change their nickname on the connected server for now: an admin gave
     * them one ({@link NicknameAnswer.Outcome#ASSIGNED}) or locked theirs
     * ({@link NicknameAnswer.Outcome#LOCKED}). Null when they can, or the server did not say.
     */
    NicknameAnswer.Outcome nicknameFixedByAdmin();

    /** Whether hiding armour pieces from everyone works where the player is now. */
    ArmorHiding armorHiding();

    /**
     * Tells the connected server which armour pieces the player hides from everyone, one bit per
     * piece ({@code HiddenArmor}), when the session negotiated {@code hidden-armor}.
     */
    void updateHiddenArmor(int hidden);

    /** The armour pieces nobody sees now: those the player hides, where the server hides them. */
    int hiddenArmorInEffect();

    /**
     * The server's skin pool and who has a skin an admin gave them, while the player is an admin
     * (permission level 2) on a server that negotiated {@code assigned-skins}; null otherwise.
     */
    SkinAdminView skinAdminView();

    /**
     * An admin gives these players a skin from their own catalog, for now: it is uploaded, and
     * the server answers once it has it (see {@link #skinAdminAnswersAfter}).
     *
     * @param model {@code classic} or {@code slim}
     * @return false when it cannot go to this server (not an admin, no such skin, too large)
     */
    boolean giveLocalSkin(Collection<UUID> players, String localHash, String model);

    /**
     * An admin adds a skin of their catalog to the server's pool, under a pool name made from the
     * skin's name.
     */
    boolean addLocalSkinToPool(String skinName, String localHash, String model);

    /**
     * An admin asks for one of the other actions: giving a pool skin (its name) or random pool
     * skins, swapping the players' own skins, taking skins back, or removing a pool skin (its name).
     */
    boolean skinAdminAction(SkinAdminRequest request, Collection<UUID> players, String poolSkinName);

    /** The sequence number of the latest answer to an admin's skin request (0: none yet). */
    int skinAdminAnswerSequence();

    /** The answers to an admin's skin requests newer than this sequence number, oldest first. */
    List<SkinAdminAnswer> skinAdminAnswersAfter(int sequence);

    /** The server's skin pool and who has an assigned skin, as an admin's client last learned. */
    record SkinAdminView(List<PoolSkin> pool, Set<UUID> assigned) {
        /** A pool skin: its name, its content id and model, as the server keeps it. */
        public record PoolSkin(String name, String contentId, String model) {
        }
    }

    /** The skin requests an admin's client makes besides giving one of its own skins. */
    enum SkinAdminRequest { GIVE_POOL_SKIN, RANDOM, SHUFFLE, RESET, REMOVE_FROM_POOL }

    /**
     * The server's answer to an admin's skin request: what the request did, the outcome and how
     * many players or skins it touched. The sequence grows with every answer. A request the server
     * never answered within a while is answered {@code NO_ANSWER} here.
     */
    record SkinAdminAnswer(int sequence, Kind kind, Outcome outcome, int count) {
        public enum Kind { GIVE, RANDOM, SHUFFLE, RESTORE, ADD_TO_POOL, REMOVE_FROM_POOL }

        public enum Outcome { DONE, NOT_ALLOWED, MISSING_TEXTURE, POOL_EMPTY, POOL_FULL, NAME_TAKEN, NO_ROOM, INVALID, NOTHING, NO_ANSWER }
    }

    /** Whether hiding armour pieces from everyone works where the player is now. */
    enum ArmorHiding {
        /** The server hides them from every other player. */
        ACTIVE,
        /** The server turned it off: everyone's armour shows. */
        NOT_ALLOWED,
        /** The server runs no or an older Quick Skin, so it cannot hide armour. */
        UNSUPPORTED,
        /** Not in a world. */
        NOT_CONNECTED
    }

    /** Whether a changed nickname could go to the server the player is on. */
    enum NicknameDelivery {
        /** Sent; the answer follows. */
        SENT,
        /** The server runs Quick Skin and is still being greeted; it is sent then, and answered. */
        PENDING,
        /** Not in a world: the nickname is shown once the player joins a server that takes it. */
        NOT_CONNECTED,
        /** The server runs no or an older Quick Skin, so it shows no nicknames. */
        UNSUPPORTED,
        /** The server turned nicknames off. */
        DISABLED
    }

    /**
     * The server's answer to a nickname: what it decided, the nickname the player has there now
     * and, after a refusal for changing too often, how long until it may change. The sequence
     * grows with every answer, so a screen can tell a new one from one it already showed.
     */
    record NicknameAnswer(int sequence, Outcome outcome, String nickname, long retryAfterMillis) {
        public enum Outcome { ACCEPTED, UNCHANGED, INVALID, TAKEN, COOLDOWN, DISABLED, LOCKED, ASSIGNED }
    }

    /** What players without Quick Skin see after an upload to Mojang, per the connected server. */
    enum AccountSkinVisibility {
        /** The server shares account skins: they see it within minutes, without a rejoin. */
        SHARED,
        /** An online-mode server that does not share them: they see it after a rejoin. */
        AFTER_REJOIN,
        /** Offline mode or another login service: they never see Mojang account skins there. */
        UNAVAILABLE,
        /** The server did not say (it runs no or an older Quick Skin). */
        UNKNOWN
    }
}
