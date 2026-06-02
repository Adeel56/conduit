package dev.conduit.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Hashes and verifies the secret half of an API key.
 *
 * <p>A per-key random salt + SHA-256 is the right tool here (unlike Argon2 for passwords): the
 * secret is already 256 bits of CSPRNG entropy, so it is infeasible to brute-force or precompute —
 * a fast salted hash is cryptographically sufficient and keeps per-request verification cheap.
 * Stored form is {@code "<saltB64>:<hashB64>"}. Verification is <b>constant-time</b>
 * ({@link MessageDigest#isEqual}) so an attacker can't recover the hash byte-by-byte via timing.
 */
@Component
public class ApiKeyHasher {

    private static final int SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    /** Produce the stored hash for a secret: a fresh salt and SHA-256(salt || secret). */
    public String hash(String secret) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return encoder.encodeToString(salt) + ":" + encoder.encodeToString(digest(salt, secret));
    }

    /** Constant-time check that {@code secret} produced the {@code stored} hash. */
    public boolean matches(String secret, String stored) {
        int sep = stored.indexOf(':');
        if (sep < 0) {
            return false;
        }
        byte[] salt = decoder.decode(stored.substring(0, sep));
        byte[] expected = decoder.decode(stored.substring(sep + 1));
        return MessageDigest.isEqual(expected, digest(salt, secret));
    }

    private byte[] digest(byte[] salt, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(secret.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
