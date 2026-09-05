package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Wrapper class that can represent either a local cape or a known cape
 */
public class CapeEntry {
    private final AssetMetadata localCape;
    private final KnownCapes knownCape;
    private final boolean isLocal;

    private CapeEntry(AssetMetadata localCape, KnownCapes knownCape, boolean isLocal) {
        this.localCape = localCape;
        this.knownCape = knownCape;
        this.isLocal = isLocal;
    }

    public static CapeEntry fromLocal(AssetMetadata metadata) {
        return new CapeEntry(metadata, null, true);
    }

    public static CapeEntry fromKnown(KnownCapes cape) {
        return new CapeEntry(null, cape, false);
    }

    public boolean isLocal() {
        return isLocal;
    }

    public boolean isKnown() {
        return !isLocal;
    }

    @Nullable
    public AssetMetadata getLocalCape() {
        return localCape;
    }

    @Nullable
    public KnownCapes getKnownCape() {
        return knownCape;
    }

    public String getFriendlyName() {
        if (isLocal) {
            return localCape != null ? localCape.friendlyName() : "Unknown";
        } else {
            return knownCape != null ? knownCape.getDisplayName() : "Unknown";
        }
    }

    public String getDescription() {
        if (isLocal) {
            return "Custom cape";
        } else {
            return knownCape != null ? knownCape.getDescription() : "Unknown";
        }
    }

    public boolean isAnimated() {
        if (isLocal) {
            return localCape != null && localCape.isAnimated();
        } else {
            return knownCape != null && knownCape.isAnimated();
        }
    }

    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getTextureLocation() {
    //?} else {
    public Identifier getTextureLocation() {
    //?}
        if (isLocal) {
            return localCape != null ?
                    com.quickskin.mod.client.services.LocalAssetManager.getInstance()
                            .getTextureLocation(localCape.hash(), com.quickskin.mod.common.data.TextureQuality.FULL)
                    : null;
        } else {
            return knownCape != null ? knownCape.getTextureLocation() : null;
        }
    }

    public String getCapeId() {
        if (isLocal) {
            return localCape != null ? "local_cape:" + localCape.hash() : "";
        } else {
            return knownCape != null ? "known:" + knownCape.getId() : "";
        }
    }

    @Nullable
    public Path getPath() {
        return isLocal && localCape != null ? localCape.path() : null;
    }

    public boolean isCustom() {
        if (isLocal) {
            return true; // All local capes are custom
        } else {
            return knownCape != null && knownCape.isCustom();
        }
    }
}
