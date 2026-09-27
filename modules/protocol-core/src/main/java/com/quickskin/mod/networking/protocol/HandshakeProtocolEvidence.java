package com.quickskin.mod.networking.protocol;

/**
 * Reads a loader login handshake as early evidence that the server accepts the protocol hello.
 *
 * <p>Forge's FML login handshake delivers the server's mod list, with versions, and its network
 * channels before the client joins the world, while Architectury's own channel list can arrive long
 * after the join (see {@link ClientChannelDiscovery}). Quick Skin 3.0 introduced the protocol hello
 * and registers its receiver at start-up, so a server that declares Quick Skin 3 or newer together
 * with the channel that carries Quick Skin's packets can receive a hello at once.</p>
 *
 * <p>The declaration comes from the peer and is not authoritative. It only allows a hello to be
 * sent; the protocol is still negotiated only by the server's authenticated acknowledgement, and a
 * server that declares nothing is left to channel discovery.</p>
 */
public final class HandshakeProtocolEvidence {
    /** The first Quick Skin major version whose server accepts the protocol hello. */
    static final int FIRST_HELLO_MAJOR_VERSION = 3;
    private static final int MAX_VERSION_LENGTH = 256;

    private HandshakeProtocolEvidence() {
    }

    /**
     * @param declaredModVersion the Quick Skin version the server declared, or {@code null}
     * @param transportChannelDeclared whether the server declared the channel carrying Quick Skin
     */
    public static boolean declaresProtocolHello(
            String declaredModVersion, boolean transportChannelDeclared) {
        if (!transportChannelDeclared) return false;
        int major = majorVersion(declaredModVersion);
        return major >= FIRST_HELLO_MAJOR_VERSION;
    }

    /** The leading decimal component of a version such as {@code 3.0.0-beta}, or -1. */
    static int majorVersion(String version) {
        if (version == null || version.isEmpty() || version.length() > MAX_VERSION_LENGTH) return -1;
        int end = 0;
        while (end < version.length() && end < 9 && isAsciiDigit(version.charAt(end))) end++;
        if (end == 0 || (end < version.length() && isAsciiDigit(version.charAt(end)))) return -1;
        if (end < version.length() && version.charAt(end) != '.'
                && version.charAt(end) != '-' && version.charAt(end) != '+') return -1;
        return Integer.parseInt(version, 0, end, 10);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
