package com.quickskin.mod.common.data;

public enum BackgroundStyle {
    OPAQUE_STARS("opaque_stars", "quickskin.background.opaque_stars"),
    VANILLA_BLUR("vanilla_blur", "quickskin.background.vanilla_blur");

    private final String id;
    private final String translationKey;

    BackgroundStyle(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public String getId() {
        return id;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public static BackgroundStyle fromId(String id) {
        for (BackgroundStyle style : values()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return OPAQUE_STARS; // default
    }
}
