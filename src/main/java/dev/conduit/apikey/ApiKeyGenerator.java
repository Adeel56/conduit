package dev.conduit.apikey;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Generates and parses API keys. A key has the shape {@code cdt_<prefix>.<secret>}:
 * <ul>
 *   <li>{@code cdt_} — a fixed scheme so the key is recognizable (e.g. to secret scanners);</li>
 *   <li>{@code prefix} — a non-secret lookup id (Base64URL of 9 random bytes);</li>
 *   <li>{@code secret} — 256 bits of CSPRNG entropy (Base64URL of 32 random bytes), hashed at rest.</li>
 * </ul>
 * The {@code '.'} separator is unambiguous because Base64URL never contains a dot.
 */
@Component
public class ApiKeyGenerator {

    static final String SCHEME = "cdt_";
    private static final String SEPARATOR = ".";
    private static final int PREFIX_BYTES = 9;   // 12 Base64URL chars
    private static final int SECRET_BYTES = 32;  // 256 bits

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /** The pieces of a freshly minted key. {@link #fullKey} is what the user sees exactly once. */
    public record Material(String prefix, String secret, String fullKey) {
    }

    public Material generate() {
        String prefix = encoder.encodeToString(randomBytes(PREFIX_BYTES));
        String secret = encoder.encodeToString(randomBytes(SECRET_BYTES));
        return new Material(prefix, secret, SCHEME + prefix + SEPARATOR + secret);
    }

    /** Split a presented key into [prefix, secret], or empty if it isn't well-formed. */
    public static Optional<String[]> parse(String presented) {
        if (presented == null || !presented.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String body = presented.substring(SCHEME.length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new String[] {body.substring(0, dot), body.substring(dot + 1)});
    }

    private byte[] randomBytes(int n) {
        byte[] buffer = new byte[n];
        random.nextBytes(buffer);
        return buffer;
    }
}
