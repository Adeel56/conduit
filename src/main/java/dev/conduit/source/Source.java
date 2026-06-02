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

    // Hibernate assigns a random UUID at INSERT time, so the id is null until persisted and Spring
    // Data treats a new Source as new (a clean INSERT, no pre-SELECT).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "ingest_key", nullable = false, updatable = false)
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
