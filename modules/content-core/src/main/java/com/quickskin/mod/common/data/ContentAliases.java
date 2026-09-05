package com.quickskin.mod.common.data;

import java.util.Objects;

/** The two wire identities of one exact canonical byte sequence. */
public record ContentAliases(String sha1, String sha256) {
    public ContentAliases {
        ContentId legacy = ContentId.parse(sha1);
        ContentId strong = ContentId.parse(sha256);
        if (legacy == null || legacy.algorithm() != ContentId.Algorithm.SHA1
                || strong == null || strong.algorithm() != ContentId.Algorithm.SHA256) {
            throw new IllegalArgumentException("Invalid content alias pair");
        }
    }

    public static ContentAliases forBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new ContentAliases(
                ContentId.hash(bytes, ContentId.Algorithm.SHA1).externalForm(),
                ContentId.hash(bytes, ContentId.Algorithm.SHA256).externalForm());
    }

    public String forAlgorithm(ContentId.Algorithm algorithm) {
        return algorithm == ContentId.Algorithm.SHA1 ? sha1 : sha256;
    }

    public boolean contains(String contentId) {
        return sha1.equals(contentId) || sha256.equals(contentId);
    }
}
