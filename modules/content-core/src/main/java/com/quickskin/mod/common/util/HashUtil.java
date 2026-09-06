package com.quickskin.mod.common.util;

import com.quickskin.mod.common.data.ContentId;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Content-hash helpers. SHA-1 methods are retained only for legacy alias migration; new local
 * assets and protocol-v2 content use the canonical SHA-256 methods.
 */
public class HashUtil {

    private static final int BUFFER_SIZE = 8192;
    private static final byte[] CAPE_DOMAIN =
            "quickskin:cape\0".getBytes(StandardCharsets.UTF_8);

    /**
     * Compute a historical SHA-1 alias of a file.
     * Optimized for large files with buffered reading
     *
     * @param path Path to file
     * @return Hex string of SHA1 hash, or null on error
     */
    public static String computeFileHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");

            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return bytesToHex(digest.digest());

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute a historical SHA-1 alias of a byte array.
     */
    public static String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Computes the canonical strong content identity used by protocol v2 and new storage. */
    public static String computeContentId(byte[] data) {
        return ContentId.hash(data, ContentId.Algorithm.SHA256).externalForm();
    }

    /** Computes the canonical strong identity for a file without loading it all into memory. */
    public static String computeFileContentId(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ContentId.Algorithm.SHA256.jcaName());
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return new ContentId(ContentId.Algorithm.SHA256, bytesToHex(digest.digest()))
                    .externalForm();
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Produces the historical local SHA-1 alias. Cape aliases are domain-separated from raw skin IDs,
     * because the same valid PNG bytes can legitimately be imported in both rendering roles.
     * Network content hashes remain hashes of the canonical transmitted PNG itself.
     */
    public static String computeAssetHash(byte[] data, String assetType) {
        if (!"cape".equals(assetType)) return computeHash(data);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(CAPE_DOMAIN);
            digest.update(data);
            return bytesToHex(digest.digest());
        } catch (Exception error) {
            return null;
        }
    }

    /** Strong local asset identity, domain-separated when identical bytes are used as a cape. */
    public static String computeAssetContentId(byte[] data, String assetType) {
        if (!"cape".equals(assetType)) return computeContentId(data);
        return ContentId.hashDomainSeparated(CAPE_DOMAIN, data, ContentId.Algorithm.SHA256)
                .externalForm();
    }

    /**
     * Convert byte array to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
