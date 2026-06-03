package dev.conduit.destination;

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
 * An org-owned, reusable outbound target Conduit will later POST events to (CON-13). The
 * {@link #url} is user-supplied; it is validated as an absolute http/https URL before persist
 * (SSRF hardening of internal/link-local/metadata ranges is a separate follow-up ticket).
 *
 * <p>Org-owned and reusable across sources (ADR-0008): a destination is not owned by one source —
 * it is wired to sources via {@code Route} rows.
 *
 * <p>Schema owned by Flyway ({@code V5__destinations_and_routes.sql}); {@code ddl-auto} is
 * {@code validate}, so this mapping must match the migration exactly.
 */
@Entity
@Table(name = "destinations")
public class Destination {

    // Hibernate assigns a random UUID at INSERT time, so the id is null until persisted and Spring
    // Data treats a new Destination as new (a clean INSERT, no pre-SELECT).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Destination() {
        // for JPA
    }

    public Destination(UUID orgId, String name, String url) {
        this.orgId = orgId;
        this.name = name;
        this.url = url;
        this.active = true;
    }

    /** Rename and re-target the destination (an update from the owning org). */
    public void update(String name, String url) {
        this.name = name;
        this.url = url;
    }

    /** Deactivate the destination: routes referencing it stop delivering (delivery engine, CON-13). */
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

    public String getUrl() {
        return url;
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
