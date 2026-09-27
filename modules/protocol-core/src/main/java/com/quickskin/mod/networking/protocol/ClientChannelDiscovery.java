package com.quickskin.mod.networking.protocol;

/**
 * Decides when the server's advertised Quick Skin channels are conclusive for one client session.
 *
 * <p>A loader's channel query is not final at the client's join callback. Architectury on Forge
 * 1.20.1 fills the server channel list from a packet the server sends from its own login event.
 * Depending on the installed networking mods that packet is handled either on the network thread,
 * where the join callback can run before or while the list is being filled, or on the client
 * thread strictly after the join, queued behind other work: in a 370-mod pack it waited 40 seconds
 * for JEI to finish building its runtime. No deadline is therefore safe, and observing is cheap and
 * sends nothing, so a session keeps observing until it decides or its connection ends.</p>
 *
 * <p>A hello channel is decisive as soon as it appears. A legacy-only answer may be a partially
 * filled list of a v2 server, so it counts only when two consecutive observations agree.
 * Instances are single-threaded and hold only bounded state.</p>
 */
public final class ClientChannelDiscovery {
    public enum Decision {
        /** No conclusive evidence yet; observe again later. */
        WAIT,
        /** The server accepts the protocol hello. */
        HELLO,
        /** Two consecutive observations advertised only the immutable v1 channels. */
        LEGACY
    }

    private boolean legacyOnlyObserved;
    private boolean decided;

    /** Records one observation. Once a decision was returned, later calls return {@code WAIT}. */
    public Decision observe(boolean helloAvailable, boolean legacyAvailable) {
        if (decided) return Decision.WAIT;
        if (helloAvailable || (legacyAvailable && legacyOnlyObserved)) {
            decided = true;
            return helloAvailable ? Decision.HELLO : Decision.LEGACY;
        }
        legacyOnlyObserved = legacyAvailable;
        return Decision.WAIT;
    }

    public boolean decided() {
        return decided;
    }
}
