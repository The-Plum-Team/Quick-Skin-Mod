package com.quickskin.mod.common.data;

public enum SkinSortMode {
    LATEST_LAST("latest_last", "↓"),
    LATEST_FIRST("latest_first", "↑"),
    ALPHABETICAL("alphabetical", "ABC");

    private final String translationKey;
    private final String icon;

    SkinSortMode(String translationKey, String icon) {
        this.translationKey = translationKey;
        this.icon = icon;
    }

    public String getTranslationKey() {
        return "quickskin.sort." + translationKey;
    }

    public String getIcon() {
        return icon;
    }

    public SkinSortMode next() {
        SkinSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
