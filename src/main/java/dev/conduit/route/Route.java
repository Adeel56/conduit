package dev.conduit.route;

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
 * The many-to-many join wiring a {@code Source} to a {@code Destination} (ADR-0008): "events
 * arriving on this source go to this destination". It is the future home for per-route config.
 *
 * <p><b>Tenant isolation:</b> {@code org_id}, {@code source_id}, and {@code destination_id} must all
 * belong to the SAME org — enforced at creation time in {@code RouteService} (a route spanning two
 * tenants would be a cross-tenant hole). The DB enforces uniqueness on {@code (source_id,
 * destination_id)} so the same wiring can't be created twice.
 *
 * <p>Schema owned by Flyway ({@code V5__destinations_and_routes.sql}); {@code ddl-auto} is
 * {@code validate}, so this mapping must match the migration exactly.
 */
@Entity
@Table(name = "routes")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "destination_id", nullable = false, updatable = false)
    private UUID destinationId;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Route() {
        // for JPA
    }

    public Route(UUID orgId, UUID sourceId, UUID destinationId) {
        this.orgId = orgId;
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.active = true;
    }

    /** Deactivate the wiring: the delivery engine (CON-13) skips inactive routes. */
    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getDestinationId() {
        return destinationId;
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
