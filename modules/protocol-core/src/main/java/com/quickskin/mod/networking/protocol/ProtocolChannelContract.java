package com.quickskin.mod.networking.protocol;

/**
 * Server-to-client channels guaranteed by a successfully negotiated protocol profile.
 *
 * <p>Architectury's 1.20.1 Forge channel probe can return false even after the same
 * connection has sent a valid Quick Skin v2 hello and the server has selected its profile.
 * Emitting that hello asserts that all mandatory receivers were registered before the hello.
 * Likewise, a classified v1 profile exists only after an exact loader-channel probe or an
 * authenticated packet on the immutable v1 channel family. The connection-scoped profile is
 * therefore the authority after classification; loader probing remains necessary before it.</p>
 */
public final class ProtocolChannelContract {
    private ProtocolChannelContract() {
    }

    public static boolean guarantees(ProtocolProfile profile, ServerPacket packet) {
        if (profile == null || packet == null) return false;
        if (profile.mode() == ProtocolProfile.Mode.LEGACY_V1 && profile.version() == 1) {
            return true;
        }
        if (!profile.negotiated() || profile.version() != ProtocolNegotiator.CURRENT_VERSION
                || (profile.capabilityMask() & QuickSkinProtocol.REQUIRED_CAPABILITIES)
                        != QuickSkinProtocol.REQUIRED_CAPABILITIES) {
            return false;
        }
        return switch (packet) {
            case APPEARANCE, TEXTURE, SERVER_CONFIG, COOLDOWN -> true;
            case TEXTURE_CHUNK ->
                    profile.supports(ProtocolCapability.CHUNKED_TEXTURE_TRANSFER);
            case ANIMATION_METADATA ->
                    profile.supports(ProtocolCapability.ANIMATION_METADATA);
            case APPEARANCE_SNAPSHOT_COMPLETE ->
                    profile.supports(ProtocolCapability.APPEARANCE_SNAPSHOT_ACK);
        };
    }

    public enum ServerPacket {
        APPEARANCE,
        TEXTURE,
        TEXTURE_CHUNK,
        ANIMATION_METADATA,
        SERVER_CONFIG,
        COOLDOWN,
        APPEARANCE_SNAPSHOT_COMPLETE
    }
}
