package io.github.domsimus.keychain.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** OAuth PKCE pair (RFC 7636, S256). */
record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();

    static Pkce create() {
        String verifier = randomUrlSafe(48);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String randomUrlSafe(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
