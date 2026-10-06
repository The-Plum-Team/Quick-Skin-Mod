package com.quickskin.mod.server.vanilla;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Tells whether an online-mode server authenticates its players with Mojang's own session server.
 *
 * <p>Online mode alone does not prove it. authlib reads {@code minecraft.api.*} system properties
 * that point it at another Yggdrasil service, and the authlib-injector Java agent rewrites
 * Mojang's URLs in loaded classes (Ely.by, LittleSkin, Drasl and similar services work this way).
 * On such a server a profile id is not necessarily the Mojang account with that id, so a player
 * who can choose their id could otherwise have someone else's Mojang skin installed on them.</p>
 */
public final class MojangAuthenticationEnvironment {
    private static final String API_PROPERTY_PREFIX = "minecraft.api.";
    private static final String API_ENVIRONMENT_PROPERTY = "minecraft.api.env";
    private static final String INJECTOR_PROPERTY_PREFIX = "authlibinjector.";
    private static final List<String> INJECTOR_CLASSES = List.of(
            "moe.yushi.authlibinjector.AuthlibInjector",
            "moe.yushi.authlibinjector.Premain",
            "org.to2mbn.authlibinjector.AuthlibInjector");
    /**
     * Built at run time so that no URL-rewriting agent can change it together with the session
     * profile URL it is compared with.
     */
    private static final String MOJANG_SESSION_HOST = String.join(".", "sessionserver", "mojang", "com");

    private MojangAuthenticationEnvironment() {
    }

    /** What the checks read; the running JVM supplies it in production, tests supply their own. */
    public record Inputs(
            Map<String, String> systemProperties,
            boolean authlibEnvironmentOverridden,
            List<String> jvmArguments,
            Predicate<String> classPresent,
            String sessionProfileUrl) {
        public Inputs {
            systemProperties = Map.copyOf(systemProperties);
            jvmArguments = List.copyOf(jvmArguments);
            Objects.requireNonNull(classPresent, "classPresent");
            Objects.requireNonNull(sessionProfileUrl, "sessionProfileUrl");
        }
    }

    /**
     * Reads this JVM.
     *
     * @param authlibEnvironmentOverridden whether authlib's own environment parser reports a
     *                                     non-default environment for this server
     */
    public static Inputs ofThisJvm(boolean authlibEnvironmentOverridden) {
        Map<String, String> properties = new TreeMap<>();
        Properties system = System.getProperties();
        for (String key : system.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith(API_PROPERTY_PREFIX) || lower.startsWith(INJECTOR_PROPERTY_PREFIX)) {
                properties.put(key, system.getProperty(key));
            }
        }
        List<String> arguments;
        try {
            arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError unavailable) {
            arguments = List.of();
        }
        return new Inputs(properties, authlibEnvironmentOverridden, arguments,
                MojangAuthenticationEnvironment::systemClassPresent,
                MojangSessionProfileFetcher.SESSION_PROFILE_URL);
    }

    /**
     * Why the server does not use Mojang's own authentication, or empty when it does.
     * The reason is a short sentence fragment for the server log.
     */
    public static Optional<String> nonMojangReason(Inputs inputs) {
        for (Map.Entry<String, String> property : new TreeMap<>(inputs.systemProperties()).entrySet()) {
            String key = property.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith(INJECTOR_PROPERTY_PREFIX)) {
                return Optional.of("authlib-injector is configured (" + property.getKey() + ")");
            }
            if (!key.startsWith(API_PROPERTY_PREFIX)) continue;
            boolean production = API_ENVIRONMENT_PROPERTY.equals(key)
                    && "prod".equalsIgnoreCase(String.valueOf(property.getValue()).trim());
            if (!production) {
                return Optional.of("the system property " + property.getKey()
                        + " points authlib at another authentication service");
            }
        }
        if (inputs.authlibEnvironmentOverridden() && !onlyProductionSelected(inputs.systemProperties())) {
            return Optional.of("authlib uses a non-default authentication environment");
        }
        for (String argument : inputs.jvmArguments()) {
            String lower = argument.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-javaagent:")
                    && (lower.contains("authlib-injector") || lower.contains("authlibinjector"))) {
                return Optional.of("the authlib-injector Java agent is installed");
            }
        }
        for (String className : INJECTOR_CLASSES) {
            if (inputs.classPresent().test(className)) {
                return Optional.of("the authlib-injector Java agent is loaded");
            }
        }
        if (!isMojangSessionServer(inputs.sessionProfileUrl())) {
            return Optional.of("the session server URL was rewritten to another host");
        }
        return Optional.empty();
    }

    /** Whether {@code urlTemplate} queries Mojang's own session server over HTTPS. */
    public static boolean isMojangSessionServer(String urlTemplate) {
        if (urlTemplate == null) return false;
        try {
            URI uri = URI.create(urlTemplate.replace("%s", "0"));
            return "https".equalsIgnoreCase(uri.getScheme())
                    && MOJANG_SESSION_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean onlyProductionSelected(Map<String, String> properties) {
        String environment = null;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (API_ENVIRONMENT_PROPERTY.equalsIgnoreCase(property.getKey())) {
                environment = property.getValue();
            }
        }
        return environment != null && "prod".equalsIgnoreCase(environment.trim());
    }

    private static boolean systemClassPresent(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException absent) {
            return false;
        }
    }
}
