package com.quickskin.mod.common.util;

import java.io.IOException;
import java.util.List;

/**
 * An {@link IOException} the player should read: besides its English message, which goes to the
 * log, it carries the {@link UserMessage} a screen translates for the player. A failure without one
 * is an internal detail, which a screen reports with its own generic text instead.
 */
public class UserFacingIOException extends IOException {
    /** How many causes {@link #find} follows before giving up. */
    private static final int MAX_CAUSES = 16;

    private final UserMessage userMessage;

    public UserFacingIOException(String message, String translationKey, Object... args) {
        super(message);
        this.userMessage = UserMessage.of(translationKey, args);
    }

    public UserMessage userMessage() {
        return userMessage;
    }

    /** The user message of the first such exception in {@code error}'s cause chain, or {@code null}. */
    public static UserMessage find(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++) {
            if (cause instanceof UserFacingIOException facing) {
                return facing.userMessage();
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /** A translation key and its arguments, already turned into text. */
    public record UserMessage(String translationKey, List<String> args) {
        public UserMessage {
            if (translationKey == null || translationKey.isBlank()) {
                throw new IllegalArgumentException("translation key is required");
            }
            args = List.copyOf(args);
        }

        public static UserMessage of(String translationKey, Object... args) {
            String[] text = new String[args == null ? 0 : args.length];
            for (int i = 0; i < text.length; i++) {
                text[i] = String.valueOf(args[i]);
            }
            return new UserMessage(translationKey, List.of(text));
        }
    }
}
