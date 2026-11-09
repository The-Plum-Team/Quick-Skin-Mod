package com.quickskin.mod.client.services;

import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.platform.QuickSkinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps the shared library's CPM models and an instance's {@code player_models} folder in step.
 * CPM selects a model only by its path inside {@code player_models}, so the library's models are
 * copied into the managed subfolder {@value #MIRROR_FOLDER}; a model the player keeps anywhere
 * else in {@code player_models} is copied into the library, so every instance lists it too.
 *
 * <p>A copy in the managed subfolder whose model the library no longer holds was deleted from
 * another instance and is deleted here as well; nothing outside that subfolder is ever deleted
 * by the mirror. Models are matched by content id, never by file name.
 */
public final class CpmLibraryMirror {
    public static final String MIRROR_FOLDER = "quickskin-library";
    private static final String EXTENSION = ".cpmmodel";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FILES = 2048;

    private CpmLibraryMirror() {
    }

    /** What one pass changed. */
    public record Result(int addedToLibrary, int addedToInstance, int removedFromInstance) {
        public boolean changed() {
            return addedToLibrary + addedToInstance + removedFromInstance > 0;
        }
    }

    public static Result sync(Path library, Path playerModels, int maxBytes) {
        int toLibrary = 0;
        int toInstance = 0;
        int removed = 0;
        try {
            Files.createDirectories(playerModels);
            Path mirror = playerModels.resolve(MIRROR_FOLDER);
            Map<String, Path> shared = models(library, 1, maxBytes);
            Map<String, Path> own = new HashMap<>();
            Map<String, Path> mirrored = new HashMap<>();
            for (Map.Entry<String, Path> model : models(playerModels, MAX_DEPTH, maxBytes).entrySet()) {
                (model.getValue().startsWith(mirror) ? mirrored : own).put(model.getKey(), model.getValue());
            }

            // A model the player keeps in this instance joins the library
            for (Map.Entry<String, Path> model : own.entrySet()) {
                if (shared.containsKey(model.getKey())) continue;
                Path copy = copyInto(model.getValue(), library);
                if (copy != null) {
                    shared.put(model.getKey(), copy);
                    toLibrary++;
                }
            }
            // A managed copy whose model left the library was deleted elsewhere
            for (Map.Entry<String, Path> model : mirrored.entrySet()) {
                if (shared.containsKey(model.getKey())) continue;
                try {
                    if (Files.deleteIfExists(model.getValue())) removed++;
                } catch (IOException e) {
                    QuickSkinInfo.LOGGER.debug("Unable to remove the library copy {}", model.getValue(), e);
                }
            }
            // The library's models this instance lacks
            for (Map.Entry<String, Path> model : shared.entrySet()) {
                if (own.containsKey(model.getKey()) || mirrored.containsKey(model.getKey())) continue;
                Files.createDirectories(mirror);
                if (copyInto(model.getValue(), mirror) != null) toInstance++;
            }
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to share CPM models between {} and {}", library, playerModels, e);
        }
        return new Result(toLibrary, toInstance, removed);
    }

    /** Deletes the library's copy of a model, so the other instances drop it too. */
    public static void deleteFromLibrary(Path library, String contentId, int maxBytes) {
        for (Map.Entry<String, Path> model : models(library, 1, maxBytes).entrySet()) {
            if (!model.getKey().equals(contentId)) continue;
            try {
                Files.deleteIfExists(model.getValue());
            } catch (IOException e) {
                QuickSkinInfo.LOGGER.warn("Unable to delete the shared CPM model {}", model.getValue(), e);
            }
        }
    }

    private static Map<String, Path> models(Path directory, int depth, int maxBytes) {
        Map<String, Path> found = new HashMap<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return found;
        try (Stream<Path> paths = Files.walk(directory, depth)) {
            List<Path> candidates = paths
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .limit(MAX_FILES)
                    .toList();
            for (Path path : candidates) {
                try {
                    String id = HashUtil.computeContentId(BoundedFileReader.readBytes(path, maxBytes));
                    if (id != null) found.putIfAbsent(id, path);
                } catch (IOException | RuntimeException ignored) {
                    // over the size limit or unreadable: not shared
                }
            }
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.debug("Unable to list CPM models in {}", directory, e);
        }
        return found;
    }

    private static Path copyInto(Path source, Path directory) {
        try {
            Files.createDirectories(directory);
            Path target = freeName(directory, source.getFileName().toString());
            Path temporary = Files.createTempFile(directory, ".copy-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.debug("Unable to copy the CPM model {} into {}", source, directory, e);
            return null;
        }
    }

    private static Path freeName(Path directory, String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? fileName : fileName + EXTENSION;
        String base = name.substring(0, name.length() - EXTENSION.length());
        Set<String> tried = new HashSet<>();
        Path candidate = directory.resolve(name);
        for (int i = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && i < 1000; i++) {
            String next = base + " (" + i + ")" + EXTENSION;
            if (!tried.add(next)) break;
            candidate = directory.resolve(next);
        }
        return candidate;
    }
}
