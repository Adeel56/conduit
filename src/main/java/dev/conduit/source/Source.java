package dev.conduit.source;

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
 * A webhook receive endpoint owned by an organization. A third party POSTs to
 * {@code /ingest/{ingestKey}}; the {@link #ingestKey} routes the request to this source.
 *
 * <p>Schema owned by Flyway ({@code V2__sources_and_events.sql}); {@code ddl-auto} is
 * {@code validate}, so this mapping must match the migration exactly.
 */
@Entity
@Table(name = "sources")
public class Source {

    // How many leading chars of the ingest key are safe to surface as a non-secret identifier.
    // 8 of ~43 Base64URL chars (~48 bits) is enough to distinguish keys in a UI but far too little
    // to brute-force the 256-bit secret.
    private static final int INGEST_KEY_PREFIX_LENGTH = 8;

    // Hibernate assigns a random UUID at INSERT time, so the id is null until persisted and Spring
    // Data treats a new Source as new (a clean INSERT, no pre-SELECT).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    // Updatable so rotation can overwrite the key in place (see rotateIngestKey). The value is the
    // public ingest-URL piece, stored in plaintext by design (V2 migration comment): there is no
    // separate key table and no hashing — rotation simply replaces the secret, after which the old
    // key no longer resolves. CON-11 needs no schema change for this; only this mapping flag changed
    // (the V2 column was always a plain TEXT with no immutability constraint).
    @Column(name = "ingest_key", nullable = false)
    private String ingestKey;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Source() {
        // for JPA
    }

    public Source(UUID orgId, String name, String ingestKey) {
        this.orgId = orgId;
        this.name = name;
        this.ingestKey = ingestKey;
        this.active = true;
    }

    /** Deactivate the source: ingest to its key then returns 404 (same as an unknown key). */
    public void deactivate() {
        this.active = false;
    }

    /** Rename the source (management metadata; does not affect ingest routing). */
    public void rename(String name) {
        this.name = name;
    }

    /**
     * Replace the ingest key with a freshly generated one. The previous key is overwritten in place,
     * so ingest on the old key immediately stops resolving (returns 404). The new key is returned to
     * the caller once by the service/controller and is never re-shown.
     */
    public void rotateIngestKey(String newIngestKey) {
        this.ingestKey = newIngestKey;
    }

    /**
     * A short, non-secret identifier for the ingest key, safe to show on list/get (the full key is
     * shown only once, at create/rotate). It is a leading slice — not enough to reconstruct the
     * 256-bit key — so the operator can tell two keys apart without the API ever re-exposing one.
     */
    public String ingestKeyPrefix() {
        return ingestKey.length() <= INGEST_KEY_PREFIX_LENGTH
                ? ingestKey
                : ingestKey.substring(0, INGEST_KEY_PREFIX_LENGTH);
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

    public String getIngestKey() {
        return ingestKey;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
