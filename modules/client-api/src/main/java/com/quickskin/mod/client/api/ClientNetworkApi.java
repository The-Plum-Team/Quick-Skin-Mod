package com.quickskin.mod.client.api;

import java.util.Objects;

/** Process binding installed once by client bootstrap, before feature or network callbacks run. */
public final class ClientNetworkApi {
    private static volatile ClientNetworkActions actions;

    private ClientNetworkApi() {
    }

    public static synchronized void bind(ClientNetworkActions implementation) {
        if (actions != null) throw new IllegalStateException("Client network API is already bound");
        actions = Objects.requireNonNull(implementation, "implementation");
    }

    public static ClientNetworkActions actions() {
        return Objects.requireNonNull(actions, "Client network API has not been bound");
    }
}
