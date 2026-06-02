package dev.conduit.source;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Generates the high-entropy, unguessable ingest key that forms a source's public URL
 * ({@code /ingest/{ingestKey}}).
 *
 * <p>Security (docs/security/security-baseline.md): a CSPRNG ({@link SecureRandom}) with 256 bits
 * of entropy, Base64URL-encoded without padding (~43 URL-safe chars). 256 bits makes the key
 * infeasible to guess or enumerate, which is what lets the secret URL route requests before HMAC
 * authenticity (a later ticket) exists.
 */
@Component
public class IngestKeyGenerator {

    private static final int ENTROPY_BYTES = 32; // 256 bits

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] buffer = new byte[ENTROPY_BYTES];
        random.nextBytes(buffer);
        return encoder.encodeToString(buffer);
    }
}
