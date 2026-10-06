package com.quickskin.mod.server.vanilla;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangAuthenticationEnvironmentTest {
    private static final List<String> PLAIN_ARGUMENTS = List.of("-Xmx4G", "-Dfile.encoding=UTF-8");

    @Test
    void aDefaultServerUsesMojang() {
        assertEquals(Optional.empty(), reason(Map.of(), false, PLAIN_ARGUMENTS, ""));
        assertEquals(Optional.empty(), reason(Map.of("minecraft.api.env", "PROD"), true, PLAIN_ARGUMENTS, ""),
                "selecting the production environment explicitly is still Mojang");
        assertEquals(Optional.empty(), reason(Map.of("minecraft.api.env", " prod "), true, PLAIN_ARGUMENTS, ""));
    }

    @Test
    void authlibPropertiesThatSelectAnotherServiceAreNotMojang() {
        assertTrue(reason(Map.of("minecraft.api.env", "STAGING"), true, PLAIN_ARGUMENTS, "").isPresent());
        assertTrue(reason(Map.of("minecraft.api.session.host", "https://drasl.example"), false,
                PLAIN_ARGUMENTS, "").isPresent());
        assertTrue(reason(Map.of("minecraft.api.services.host", "https://ely.example",
                "minecraft.api.env", "PROD"), true, PLAIN_ARGUMENTS, "").isPresent());
        assertTrue(reason(Map.of("minecraft.api.discovery.host", "https://other.example"), true,
                PLAIN_ARGUMENTS, "").isPresent());
        assertTrue(reason(Map.of(), true, PLAIN_ARGUMENTS, "").isPresent(),
                "authlib's own parser reporting an override is enough");
    }

    @Test
    void authlibInjectorIsNotMojang() {
        assertTrue(reason(Map.of(), false,
                List.of("-javaagent:/srv/authlib-injector-1.2.5.jar=https://littleskin.example/api/yggdrasil"),
                "").isPresent());
        assertTrue(reason(Map.of(), false, List.of("-javaagent:C:\\mc\\AuthlibInjector.jar=ely.by"), "")
                .isPresent());
        assertTrue(reason(Map.of("authlibinjector.yggdrasil.prefetched", "e30="), false, PLAIN_ARGUMENTS, "")
                .isPresent());
        assertTrue(reason(Map.of(), false, PLAIN_ARGUMENTS, "moe.yushi.authlibinjector.AuthlibInjector")
                .isPresent(), "an agent loaded without a recognisable argument is still detected");
        assertEquals(Optional.empty(), reason(Map.of(), false,
                List.of("-javaagent:/srv/spark-agent.jar"), ""));
    }

    @Test
    void aRewrittenSessionServerUrlIsNotMojang() {
        assertTrue(MojangAuthenticationEnvironment.isMojangSessionServer(
                MojangSessionProfileFetcher.SESSION_PROFILE_URL));
        assertFalse(MojangAuthenticationEnvironment.isMojangSessionServer(
                "https://littleskin.example/api/yggdrasil/sessionserver/session/minecraft/profile/%s"));
        assertFalse(MojangAuthenticationEnvironment.isMojangSessionServer(
                "http://sessionserver.mojang.com/session/minecraft/profile/%s"));
        assertFalse(MojangAuthenticationEnvironment.isMojangSessionServer(
                "https://sessionserver.mojang.com.example/session/minecraft/profile/%s"));
        assertFalse(MojangAuthenticationEnvironment.isMojangSessionServer(
                "https://sessionserver.mojang.com:8443/session/minecraft/profile/%s"));
        assertFalse(MojangAuthenticationEnvironment.isMojangSessionServer(null));

        assertTrue(MojangAuthenticationEnvironment.nonMojangReason(new MojangAuthenticationEnvironment.Inputs(
                Map.of(), false, PLAIN_ARGUMENTS, name -> false,
                "https://127.0.0.1:25596/session/minecraft/profile/%s")).isPresent());
    }

    private static Optional<String> reason(
            Map<String, String> properties, boolean overridden, List<String> arguments, String presentClass) {
        return MojangAuthenticationEnvironment.nonMojangReason(new MojangAuthenticationEnvironment.Inputs(
                properties, overridden, arguments, presentClass::equals,
                MojangSessionProfileFetcher.SESSION_PROFILE_URL));
    }
}
