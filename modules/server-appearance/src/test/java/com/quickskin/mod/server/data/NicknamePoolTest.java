package com.quickskin.mod.server.data;

import com.quickskin.mod.common.data.Nickname;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The names {@code /quickskin nickname random} hands out, from an editable file. */
class NicknamePoolTest {
    @TempDir
    Path config;

    @Test
    void everyDefaultNameIsAValidNicknameThatReadsUnlikeTheOthers() {
        Set<String> readings = new HashSet<>();
        for (String name : NicknamePool.DEFAULT_NAMES) {
            assertTrue(Nickname.isCanonical(name), name);
            assertTrue(readings.add(Nickname.reading(name)), name);
        }
        assertTrue(NicknamePool.DEFAULT_NAMES.size() >= 64);
    }

    @Test
    void theDefaultsAreWrittenOnceForAdminsToEdit() throws IOException {
        Path file = config.resolve(NicknamePool.FILE_NAME);
        assertEquals(NicknamePool.DEFAULT_NAMES, NicknamePool.load(file), "a missing file means the defaults");

        NicknamePool.writeDefaultIfMissing(file);
        assertEquals(NicknamePool.DEFAULT_NAMES, NicknamePool.load(file));

        Files.writeString(file, "Alfa\nBravo\n", StandardCharsets.UTF_8);
        NicknamePool.writeDefaultIfMissing(file);
        assertEquals(List.of("Alfa", "Bravo"), NicknamePool.load(file), "an edited file is never replaced");
    }

    @Test
    void commentsBlankLinesInvalidNamesAndRepeatsAreSkipped() {
        assertEquals(List.of("Alfa", "Bravo Two", "Charlie"), NicknamePool.parse("""
                # Code names
                Alfa

                  Bravo   Two\s
                §cRed
                ALFA
                Charlie
                """));
    }

    @Test
    void anUnreadableListHandsOutNothing() throws IOException {
        Path file = config.resolve(NicknamePool.FILE_NAME);
        Files.createDirectories(file);

        assertEquals(List.of(), NicknamePool.load(file));
    }
}
