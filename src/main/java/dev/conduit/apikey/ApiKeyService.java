package dev.conduit.apikey;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, verifies, and revokes API keys.
 *
 * <p>{@link #create} is also the minimal seed path the tests use (like {@code SourceService}) — there
 * is intentionally NO unauthenticated key-minting HTTP endpoint in this ticket.
 */
@Service
public class ApiKeyService {

    /** Default scope until RBAC/scoping is fleshed out (least privilege: read-only dashboard). */
    static final String DEFAULT_SCOPES = "dashboard:read";

    private final ApiKeyRepository apiKeys;
    private final ApiKeyGenerator generator;
    private final ApiKeyHasher hasher;
    // A throwaway hash compared against when no key matches, so a missing prefix and a wrong secret
    // take a similar amount of time (don't leak prefix existence via timing).
    private final String dummyHash;

    public ApiKeyService(ApiKeyRepository apiKeys, ApiKeyGenerator generator, ApiKeyHasher hasher) {
        this.apiKeys = apiKeys;
        this.generator = generator;
        this.hasher = hasher;
        this.dummyHash = hasher.hash("conduit-timing-equalizer");
    }

    @Transactional
    public CreatedApiKey create(UUID orgId, String name) {
        ApiKeyGenerator.Material material = generator.generate();
        ApiKey key = new ApiKey(orgId, name, material.prefix(), hasher.hash(material.secret()), DEFAULT_SCOPES);
        apiKeys.save(key);
        return new CreatedApiKey(key, material.fullKey());
    }

    /**
     * Verify a presented key. Returns the {@link ApiKey} (and stamps {@code last_used_at}) only when
     * it is well-formed, matches a stored hash, and is not revoked. Every failure mode returns empty,
     * so the caller emits an identical 401 — existence and revocation are never revealed.
     */
    @Transactional
    public Optional<ApiKey> authenticate(String presentedKey) {
        Optional<String[]> parsed = ApiKeyGenerator.parse(presentedKey);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String prefix = parsed.get()[0];
        String secret = parsed.get()[1];

        Optional<ApiKey> found = apiKeys.findByKeyPrefix(prefix);
        if (found.isEmpty()) {
            hasher.matches(secret, dummyHash); // equalize timing vs the "found" path
            return Optional.empty();
        }

        ApiKey key = found.get();
        // Constant-time hash check first, then the revoked check — so a revoked key and a
        // wrong-secret key cost the same (no timing oracle on revocation).
        if (!hasher.matches(secret, key.getKeyHash())) {
            return Optional.empty();
        }
        if (!key.isActive()) {
            return Optional.empty();
        }

        key.markUsed(); // flushed on commit (this method is transactional)
        return Optional.of(key);
    }

    @Transactional
    public void revoke(UUID apiKeyId) {
        apiKeys.findById(apiKeyId).ifPresent(ApiKey::revoke);
    }
}
