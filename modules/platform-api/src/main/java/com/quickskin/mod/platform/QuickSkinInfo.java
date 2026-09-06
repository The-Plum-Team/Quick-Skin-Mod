package com.quickskin.mod.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Identity and diagnostics without initializing the mod's client or server runtime. */
public final class QuickSkinInfo {
    public static final String MOD_ID = "quickskin";
    public static final String MOD_NAME = "QuickSkin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private QuickSkinInfo() {
    }
}
