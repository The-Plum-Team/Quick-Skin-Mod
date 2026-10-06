package com.quickskin.mod.server.vanilla;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Checks that a {@code textures} property was signed by Mojang itself.
 *
 * <p>A server can be configured, through {@code minecraft.api.*} properties or a Java agent such
 * as authlib-injector, to authenticate players against another Yggdrasil service (Ely.by,
 * LittleSkin, Drasl, ...). Those services sign with their own keys and do not guarantee that a
 * profile id belongs to the Mojang account with that id. Quick Skin therefore checks signatures
 * against Mojang's own published keys, never against the keys the running authlib trusts.</p>
 */
@FunctionalInterface
public interface TexturesSignatureVerifier {
    /**
     * Mojang's profile property keys, as published in {@code profilePropertyKeys} of
     * {@code https://api.minecraftservices.com/publickeys}. Session-server textures are signed
     * with the first one. If Mojang ever signs with a new key, this check fails closed: sharing
     * stops (and logs why) until the key is added here.
     */
    List<String> MOJANG_PROFILE_PROPERTY_KEYS = List.of(
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1"
                    + "C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNB"
                    + "tb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3l"
                    + "nCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYc"
                    + "YOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/"
                    + "0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPP"
                    + "JL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePv"
                    + "tcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCx"
                    + "FbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAra4Y2wu3rWEW7cDTDRRd4IvUD140Y12SaG3k4V3UwT/p"
                    + "DnnX5itOcYiZA0qf4VCpJDp2PifOL+Pr/ph/G9/6ZoIxkBeGENo+S7i9BqizJy9cmZocpyx+RkZaw9+frCGNLuYL"
                    + "rxziNWiXFACJSg2mHACR7+6NkGN8d/16/3PxMnvGSyLT7JKGUgqj1Q3oW7k+NLXR9sw6oRELOcnUvZVa2bcglv8v"
                    + "lcyPqqnBhydLfHI85Z5WnIYZviZ3Bb4dv5Fme726BGOtEY7kz40RfiwjT3xYKYKPJUS3/crPX6eugmWyrWdddKae"
                    + "PrW88bp17Z5NIStlJ5KJJk4coha8O+P7onDqmbHwLqPTeR51njkgZ+DJWT6fz8ku9OWQn6I/FxqN14iYIghDJijm"
                    + "KvEwsI7FJ5X2ttPXEvBYLmpj2j0lQQcUIqH7hkiZ+mCW0GYawJgbAeNAraM9sP+76MyAGITtAsXv1IQmah+7OeDJ"
                    + "OToG2Kb1Dl0Va+HiP9MPpcnO7kbn6dqAyhNvRNmHnsUOiEcLhW9Rk7xz87IBV/cGKbUDgxu8cYY0P512DWt5+Jmr"
                    + "8W10FDFdLmkJt1taWxNxApM2CiFPCimk02koyLZDW9nqpWNw6qS/TOYPdz438qEuamtYUJ+u6WhBjK8xAJEAt5k3"
                    + "gDKX+nlTiG3N6se09D62fS8CAwEAAQ==");

    /** Whether {@code textures} carries a valid signature of one of the trusted keys. */
    boolean isTrusted(SignedTextures textures);

    /** Verifies against Mojang's published profile property keys. */
    static TexturesSignatureVerifier mojang() {
        return Holder.MOJANG;
    }

    /** Verifies against the given X.509 RSA public keys (base64), as Mojang publishes them. */
    static TexturesSignatureVerifier forKeys(List<String> base64X509Keys) {
        List<PublicKey> keys = new ArrayList<>();
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            for (String key : base64X509Keys) {
                keys.add(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key))));
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("Invalid profile property key", error);
        }
        List<PublicKey> trusted = List.copyOf(keys);
        return textures -> {
            if (textures == null) return false;
            byte[] signature;
            try {
                signature = Base64.getDecoder().decode(textures.signature());
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            byte[] value = textures.value().getBytes(StandardCharsets.US_ASCII);
            for (PublicKey key : trusted) {
                try {
                    // Mojang signs the base64 value text itself with SHA1withRSA, as authlib checks it.
                    Signature verifier = Signature.getInstance("SHA1withRSA");
                    verifier.initVerify(key);
                    verifier.update(value);
                    if (verifier.verify(signature)) return true;
                } catch (GeneralSecurityException wrongShape) {
                    // A signature of another length or format is simply not this key's.
                }
            }
            return false;
        };
    }

    /** Lazily parsed so that tests with their own verifier never touch Mojang's keys. */
    final class Holder {
        static final TexturesSignatureVerifier MOJANG = forKeys(MOJANG_PROFILE_PROPERTY_KEYS);

        private Holder() {
        }
    }
}
