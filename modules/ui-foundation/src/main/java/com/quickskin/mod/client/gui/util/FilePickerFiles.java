package com.quickskin.mod.client.gui.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, read-only directory discovery; callers run it on the client I/O executor. */
public final class FilePickerFiles {
    public static final int MAX_DIRECTORY_ENTRIES = 4096;

    private FilePickerFiles() {}

    public record Entry(Path path, boolean directory) {}

    public static boolean isAndroidRuntime() {
        // Mojang's Java is OpenJDK on Pojav/MJ, so os.name says Linux, not Android.
        return System.getenv("POJAV_NATIVEDIR") != null
                || System.getenv("MOD_ANDROID_RUNTIME") != null
                || "/system".equals(System.getenv("ANDROID_ROOT"));
    }

    public static boolean accepts(Path path, Set<String> extensions) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions.contains(name.substring(dot + 1));
    }

    public static List<Entry> list(Path directory, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a readable directory");
        }
        List<Entry> entries = new ArrayList<>();
        int visited = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++visited > MAX_DIRECTORY_ENTRIES) {
                    throw new IOException("Too many directory entries (maximum " + MAX_DIRECTORY_ENTRIES + ")");
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException inaccessible) {
                    continue;
                }
                if (attributes.isDirectory() || attributes.isRegularFile() && accepts(path, extensions)) {
                    entries.add(new Entry(path, attributes.isDirectory()));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(entry -> entry.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }
}
