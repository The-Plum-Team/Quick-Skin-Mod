package com.quickskin.mod.client.api;

import com.quickskin.mod.common.data.AssetMetadata;

import java.nio.file.Path;

/** Catalog and session-cache access supplied to the optional CPM integration at startup. */
public interface CpmAssetAccess {
    AssetMetadata metadata(String contentId);

    Path localSource(String contentId);

    /** Returns a bounded, session-owned PNG file, or null when the texture is unavailable. */
    Path networkSkinFile(String contentId);
}
