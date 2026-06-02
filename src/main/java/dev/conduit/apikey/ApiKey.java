package dev.conduit.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An API key that authenticates a caller and resolves their owning organization. Only the salted
 * hash of the secret is stored ({@link #keyHash}) — the raw key is shown once at creation and never
 * recoverable. A key is identified/looked up by its non-secret {@link #keyPrefix}.
 *
 * <p>Schema owned by Flyway ({@code V3__api_keys.sql}); {@code ddl-auto} is {@code validate}.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_prefix", nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Column(nullable = false)
    private String scopes;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiKey() {
        // for JPA
    }

    public ApiKey(UUID orgId, String name, String keyPrefix, String keyHash, String scopes) {
        this.orgId = orgId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.scopes = scopes;
    }

    /** A key is usable iff it has not been revoked. */
    public boolean isActive() {
        return revokedAt == null;
    }

    /** Soft-revoke: the key fails verification from now on (auditable, reversible-ish). */
    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    /** Record that the key was just used for a successful authentication. */
    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getScopes() {
        return scopes;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
