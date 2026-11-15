package com.quickskin.mod.common.util;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Lists the regular files below a folder through hard depth and entry caps, without giving up on
 * the folder because one entry in it cannot be read.
 *
 * <p>{@link Files#walk} throws an unchecked exception from the middle of its stream when a
 * subfolder is unreadable or a file listed a moment ago is gone, which another game instance
 * writing to the same folder causes. This walk leaves that entry out and goes on.</p>
 */
public final class BoundedFileWalk {
    private BoundedFileWalk() {
    }

    /**
     * What a walk found.
     *
     * @param files   the regular files, in the order the folders list them
     * @param capped  whether the walk stopped at its entry cap with entries left to visit
     * @param skipped how many entries could not be read and were left out
     */
    public record Result(List<Path> files, boolean capped, int skipped) {
    }

    /**
     * Walks {@code directory} at most {@code maxDepth} folders deep and visits at most
     * {@code maxEntries} entries, folders included. Links are not followed into folders; a link
     * to a regular file counts as that file.
     *
     * @throws IOException only when {@code directory} itself cannot be read
     */
    public static Result regularFiles(Path directory, int maxDepth, int maxEntries) throws IOException {
        if (directory == null || maxDepth < 0 || maxEntries < 1) {
            throw new IOException("Folder is missing or the walk limits are invalid");
        }
        Visitor visitor = new Visitor(directory, maxEntries);
        Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), maxDepth, visitor);
        return new Result(List.copyOf(visitor.files), visitor.capped, visitor.skipped);
    }

    private static final class Visitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final int maxEntries;
        private final List<Path> files = new ArrayList<>();
        private int visited;
        private int skipped;
        private boolean capped;

        private Visitor(Path root, int maxEntries) {
            this.root = root;
            this.maxEntries = maxEntries;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
            return admit() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!admit()) {
                return FileVisitResult.TERMINATE;
            }
            if (attributes.isRegularFile() || (attributes.isSymbolicLink() && Files.isRegularFile(file))) {
                files.add(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path entry, IOException error) throws IOException {
            if (entry.equals(root)) {
                throw error;
            }
            if (!admit()) {
                return FileVisitResult.TERMINATE;
            }
            skipped++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path folder, IOException error) {
            // Not null when listing the folder failed part way: what it listed until then is kept
            if (error != null) {
                skipped++;
            }
            return FileVisitResult.CONTINUE;
        }

        /** Counts one more entry, or refuses it once the cap is reached. */
        private boolean admit() {
            if (visited >= maxEntries) {
                capped = true;
                return false;
            }
            visited++;
            return true;
        }
    }
}
