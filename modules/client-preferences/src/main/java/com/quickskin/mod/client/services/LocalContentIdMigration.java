package com.quickskin.mod.client.services;

import com.quickskin.mod.client.storage.LocalAppearanceStorage;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.data.SkinPreferences;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-way migration from authenticated local SHA-1 aliases to SHA-256 primaries. */
@Environment(EnvType.CLIENT)
public final class LocalContentIdMigration {
    private static final String LOCAL_CAPE_PREFIX = "local_cape:";

    private LocalContentIdMigration() {
    }

    public static void migrate(
            Map<String, String> skinAliases,
            Map<String, String> capeAliases,
            Map<String, String> cpmAliases,
            SkinPreferences skinPreferences,
            Path skinPreferencesFile
    ) {
        Map<String, String> validSkinAliases = validatedAliases(skinAliases);
        Map<String, String> validCapeAliases = validatedAliases(capeAliases);
        Map<String, String> validCpmAliases = validatedAliases(cpmAliases);
        if (validSkinAliases.isEmpty() && validCapeAliases.isEmpty()
                && validCpmAliases.isEmpty()) return;

        migrateClientConfig(validSkinAliases, validCapeAliases, validCpmAliases);
        if (skinPreferences != null && skinPreferencesFile != null) {
            skinPreferences.migrateAliases(validSkinAliases, skinPreferencesFile);
        }
        LocalAppearanceStorage.getInstance().migrateContentIds(validSkinAliases);
    }

    private static void migrateClientConfig(
            Map<String, String> skinAliases,
            Map<String, String> capeAliases,
            Map<String, String> cpmAliases
    ) {
        ClientConfig config = ClientConfig.getInstance();
        String oldActiveSkin = config.activeSkinHash;
        String oldActiveCpm = config.activeCpmModelHash;
        String oldActiveCape = config.activeCapeHash;
        String oldOwnSkin = config.playerOwnSkinHash;
        Map<String, Float> oldSpeeds = config.capeAnimationSpeeds == null
                ? null : new HashMap<>(config.capeAnimationSpeeds);

        config.activeSkinHash = migrateBareId(oldActiveSkin, skinAliases);
        config.activeCpmModelHash = migrateBareId(oldActiveCpm, cpmAliases);
        config.activeCapeHash = migrateCapeReference(oldActiveCape, capeAliases);
        config.playerOwnSkinHash = migrateBareId(oldOwnSkin, skinAliases);
        config.capeAnimationSpeeds = migrateCapeSpeeds(oldSpeeds, capeAliases);

        boolean changed = !config.activeSkinHash.equals(oldActiveSkin)
                || !config.activeCpmModelHash.equals(oldActiveCpm)
                || !config.activeCapeHash.equals(oldActiveCape)
                || !config.playerOwnSkinHash.equals(oldOwnSkin)
                || !java.util.Objects.equals(config.capeAnimationSpeeds, oldSpeeds);
        if (changed && !config.save()) {
            // The verified replacement did not commit: retain the same runtime view as the old
            // on-disk document so a later scan can retry the migration coherently.
            config.activeSkinHash = oldActiveSkin;
            config.activeCpmModelHash = oldActiveCpm;
            config.activeCapeHash = oldActiveCape;
            config.playerOwnSkinHash = oldOwnSkin;
            config.capeAnimationSpeeds = oldSpeeds;
        }
    }

    static String migrateBareId(String value, Map<String, String> aliases) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String migrated = aliases.get(value);
        return migrated != null ? migrated : value;
    }

    static String migrateCapeReference(String value, Map<String, String> aliases) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String direct = aliases.get(value);
        if (direct != null) return direct;
        if (!value.startsWith(LOCAL_CAPE_PREFIX)) return value;
        String migrated = aliases.get(value.substring(LOCAL_CAPE_PREFIX.length()));
        return migrated == null ? value : LOCAL_CAPE_PREFIX + migrated;
    }

    private static Map<String, Float> migrateCapeSpeeds(
            Map<String, Float> speeds, Map<String, String> aliases) {
        if (speeds == null || speeds.isEmpty()) {
            return speeds == null ? new HashMap<>() : speeds;
        }
        Map<String, Float> migrated = new LinkedHashMap<>(speeds);
        for (Map.Entry<String, Float> entry : speeds.entrySet()) {
            String newKey = migrateCapeReference(entry.getKey(), aliases);
            if (!newKey.equals(entry.getKey())) {
                migrated.putIfAbsent(newKey, entry.getValue());
                migrated.remove(entry.getKey());
            }
        }
        return migrated;
    }

    static Map<String, String> validatedAliases(Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            ContentId legacy = ContentId.parse(entry.getKey());
            ContentId strong = ContentId.parse(entry.getValue());
            if (legacy != null && legacy.algorithm() == ContentId.Algorithm.SHA1
                    && strong != null && strong.algorithm() == ContentId.Algorithm.SHA256) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }
}
