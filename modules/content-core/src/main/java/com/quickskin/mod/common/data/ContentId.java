package com.quickskin.mod.common.data;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Typed content identity used at persistence and protocol boundaries.
 *
 * <p>The historical Quick Skin wire format used a bare 40-character SHA-1 digest. It remains
 * parseable only so existing preferences, caches, and legacy peers can be migrated. New content
 * identities use the filesystem-safe {@code sha256-<digest>} form and never silently downgrade
 * to SHA-1.</p>
 */
public record ContentId(Algorithm algorithm, String digest) {
    public static final String SHA256_PREFIX = "sha256-";

    public ContentId {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(digest, "digest");
        if (!isLowerHex(digest, algorithm.hexLength())) {
            throw new IllegalArgumentException("Invalid " + algorithm.externalName() + " digest");
        }
    }

    /** Parses a legacy bare SHA-1 digest or the canonical SHA-256 external form. */
    public static ContentId parse(String value) {
        if (value == null) {
            return null;
        }
        if (isLowerHex(value, Algorithm.SHA1.hexLength())) {
            return new ContentId(Algorithm.SHA1, value);
        }
        if (value.startsWith(SHA256_PREFIX)) {
            String digest = value.substring(SHA256_PREFIX.length());
            if (isLowerHex(digest, Algorithm.SHA256.hexLength())) {
                return new ContentId(Algorithm.SHA256, digest);
            }
        }
        return null;
    }

    public static ContentId hash(byte[] data, Algorithm algorithm) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(algorithm, "algorithm");
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm.jcaName());
            return new ContentId(algorithm, toLowerHex(messageDigest.digest(data)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(algorithm.jcaName() + " is required by the Java runtime", error);
        }
    }

    /** Verifies bytes with the algorithm encoded by the supplied external identifier. */
    public static boolean matches(String expected, byte[] data) {
        ContentId parsed = parse(expected);
        return parsed != null && data != null
                && parsed.equals(hash(data, parsed.algorithm()));
    }

    public static ContentId hashDomainSeparated(
            byte[] domain, byte[] data, Algorithm algorithm) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(algorithm, "algorithm");
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm.jcaName());
            messageDigest.update(domain);
            messageDigest.update(data);
            return new ContentId(algorithm, toLowerHex(messageDigest.digest()));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(algorithm.jcaName() + " is required by the Java runtime", error);
        }
    }

    public boolean isLegacy() {
        return algorithm == Algorithm.SHA1;
    }

    /** Stable network/configuration representation; also safe as a single path component. */
    public String externalForm() {
        return algorithm == Algorithm.SHA1 ? digest : SHA256_PREFIX + digest;
    }

    @Override
    public String toString() {
        return externalForm();
    }

    private static boolean isLowerHex(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public enum Algorithm {
        SHA1("sha1", "SHA-1", 40),
        SHA256("sha256", "SHA-256", 64);

        private final String externalName;
        private final String jcaName;
        private final int hexLength;

        Algorithm(String externalName, String jcaName, int hexLength) {
            this.externalName = externalName;
            this.jcaName = jcaName;
            this.hexLength = hexLength;
        }

        public String externalName() {
            return externalName;
        }

        public String jcaName() {
            return jcaName;
        }

        public int hexLength() {
            return hexLength;
        }
    }
}
