package com.quickskin.mod.server.data;

import com.quickskin.mod.common.data.Nickname;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.platform.QuickSkinInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The names {@code /quickskin nickname random} hands out: one per line of a text file in the
 * configuration directory, which the server writes with {@link #DEFAULT_NAMES} when it is missing
 * so admins can edit it. Each line is a nickname under the usual rules ({@link Nickname});
 * blank lines, lines starting with {@code #} and lines that break the rules are skipped, and a
 * name that reads like an earlier one ({@link Nickname#readsAs}) counts once.
 */
public final class NicknamePool {
    public static final String FILE_NAME = "quickskin-nicknames.txt";
    public static final int MAX_NAMES = 1024;
    static final int MAX_FILE_BYTES = 64 * 1024;

    /** Common given names that suit anyone, so they fit whatever skin a player wears. */
    public static final List<String> DEFAULT_NAMES = List.of(
            "Alex", "Ash", "Avery", "Bailey", "Blake", "Bobbie", "Brook", "Cameron", "Carmen", "Casey",
            "Charlie", "Corey", "Dakota", "Dana", "Devon", "Drew", "Eden", "Elliot", "Ellis", "Emerson",
            "Emery", "Finley", "Frankie", "Gale", "Glenn", "Harper", "Hayden", "Hollis", "Indigo", "Jade",
            "Jamie", "Jesse", "Jody", "Jordan", "Jules", "Kai", "Kelly", "Kendall", "Kim", "Kit",
            "Lane", "Lee", "Lennon", "Lou", "Lynn", "Marley", "Max", "Mel", "Micah", "Morgan",
            "Nico", "Noel", "Oakley", "Pat", "Paris", "Parker", "Peyton", "Phoenix", "Quinn", "Rae",
            "Ray", "Reese", "Remy", "Riley", "River", "Robin", "Rory", "Rowan", "Sage", "Sam",
            "Sasha", "Shay", "Sidney", "Skyler", "Spencer", "Stevie", "Sunny", "Tatum", "Taylor", "Terry",
            "Toby", "Tony", "Tracy", "Val", "Wren", "Wynn", "Yael", "Zion");

    private static final String HEADER = """
            # Names that /quickskin nickname random hands out, one per line.
            # Each player gets a different one that reads like nobody else's name or nickname.
            # Letters, digits, spaces and _ - . ' , only, at most 32 characters.
            # Lines starting with # are ignored. Changes apply the next time the command runs.
            """;

    private NicknamePool() {
    }

    /** Writes the default names when the file is missing; an existing file is never replaced. */
    public static void writeDefaultIfMissing(Path file) {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, HEADER + String.join("\n", DEFAULT_NAMES) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.warn("Unable to write the default nickname list {}", file, e);
        }
    }

    /**
     * The names in the file, in its order: the defaults when it is missing, and none when it
     * cannot be read, which is logged.
     */
    public static List<String> load(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return DEFAULT_NAMES;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file");
            return parse(BoundedFileReader.readUtf8(file, MAX_FILE_BYTES));
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.error("Could not read the nickname list {}", file, e);
            return List.of();
        }
    }

    static List<String> parse(String text) {
        List<String> names = new ArrayList<>();
        Set<String> readings = new HashSet<>();
        for (String line : text.split("\\R")) {
            if (names.size() >= MAX_NAMES) break;
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            String name = Nickname.normalize(line);
            if (name != null && !name.isEmpty() && readings.add(Nickname.reading(name))) names.add(name);
        }
        return List.copyOf(names);
    }
}
