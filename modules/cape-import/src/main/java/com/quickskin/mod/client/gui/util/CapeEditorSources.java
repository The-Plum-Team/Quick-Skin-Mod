package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.networking.NetworkSecurity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Original PNG and GIF files of the capes that were saved through the cape editor.
 *
 * <p>A source is kept in a cache subdirectory that no asset scan visits, named after the content
 * ID of the cape it produced. It exists only to reopen the editor: it is never a cape itself, and
 * a cape without one has no editable source.</p>
 */
@Environment(EnvType.CLIENT)
public final class CapeEditorSources {
    private static final String DIRECTORY = "cape_editor_sources";
    private static final String[] EXTENSIONS = {".png", ".gif"};

    private CapeEditorSources() {
    }

    /** Returns the readable source retained for a catalogued cape, or {@code null}. */
    public static Path find(Path cacheDirectory, String capeContentId) {
        for (String extension : EXTENSIONS) {
            Path candidate = resolve(cacheDirectory, capeContentId, extension);
            if (candidate != null && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Copies {@code source} under the content ID of {@code savedCape} and returns the copy. */
    public static Path retain(Path cacheDirectory, Path savedCape, Path source) throws IOException {
        if (!CapeImportProcessor.isSupported(source) || !Files.isRegularFile(source)
                || !Files.isReadable(source)) {
            throw new IOException("Cape editor source does not exist or is not readable");
        }
        long sourceBytes = Files.size(source);
        if (sourceBytes <= 0 || sourceBytes > CapeImportProcessor.MAX_SOURCE_BYTES) {
            throw new IOException("Cape editor source must be between 1 byte and 32 MB");
        }

        String contentId = HashUtil.computeAssetContentId(BoundedFileReader.readBytes(
                savedCape, (int) CapeImportProcessor.MAX_SOURCE_BYTES), "cape");
        if (!NetworkSecurity.isValidStrongContentId(contentId)) {
            throw new IOException("Could not compute cape content hash");
        }
        boolean gif = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif");
        Path target = resolve(cacheDirectory, contentId, gif ? ".gif" : ".png");
        Path other = resolve(cacheDirectory, contentId, gif ? ".png" : ".gif");
        if (target == null || other == null) {
            throw new IOException("Cape editor source cache is not initialized");
        }

        Path root = target.getParent();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(target)) {
            throw new IOException("Unsafe cape editor source path");
        }
        // Re-saving an edit that produced the same bytes keeps the source it was opened from.
        if (!target.equals(source.toAbsolutePath().normalize())) {
            Path temporary = Files.createTempFile(root, ".quickskin-source-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                CapeImportProcessor.atomicReplace(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Files.deleteIfExists(other);
        return target;
    }

    /** Removes the source retained for one cape content ID; every other source is untouched. */
    public static void delete(Path cacheDirectory, String capeContentId) throws IOException {
        for (String extension : EXTENSIONS) {
            Path candidate = resolve(cacheDirectory, capeContentId, extension);
            if (candidate != null) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static Path resolve(Path cacheDirectory, String capeContentId, String extension) {
        return cacheDirectory == null ? null : NetworkSecurity.resolveContained(
                cacheDirectory.resolve(DIRECTORY), capeContentId, extension);
    }
}
