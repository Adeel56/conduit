package dev.conduit.delivery;

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
 * The effort to deliver ONE stored {@code Event} to ONE {@code Destination} via a {@code Route}
 * (CON-13, ADR-0008). Status and retry bookkeeping live here — never on the immutable Event — because
 * one event fans out to many destinations, each with its own independent outcome.
 *
 * <p><b>Worker contract:</b> the worker claims a {@code PENDING} row that is due
 * ({@code next_attempt_at <= now}) and flips it to {@code IN_FLIGHT} ({@link #markInFlight()}) under a
 * {@code FOR UPDATE SKIP LOCKED} lock so no two workers take the same row. After the HTTP attempt it
 * calls exactly one of {@link #markDelivered()} (2xx), {@link #failAndRetryAt(Instant)} (retry left),
 * or {@link #failPermanently()} (cap reached). The two failure methods each bump {@link #attemptCount}.
 *
 * <p>Schema owned by Flyway ({@code V6__deliveries_and_attempts.sql}); {@code ddl-auto} is
 * {@code validate}, so this mapping must match the migration exactly.
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "route_id", nullable = false, updatable = false)
    private UUID routeId;

    @Column(name = "destination_id", nullable = false, updatable = false)
    private UUID destinationId;

    // Mapped via DeliveryStatusConverter (autoApply) to the lowercase DB tokens.
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Delivery() {
        // for JPA
    }

    /** A fresh, immediately-due delivery for an (event, route, destination) — the fan-out output. */
    public static Delivery pending(UUID orgId, UUID eventId, UUID routeId, UUID destinationId) {
        Delivery d = new Delivery();
        d.orgId = orgId;
        d.eventId = eventId;
        d.routeId = routeId;
        d.destinationId = destinationId;
        d.status = DeliveryStatus.PENDING;
        d.attemptCount = 0;
        d.nextAttemptAt = Instant.now();
        return d;
    }

    /** Claim: mark this row as being delivered right now (set under the claim's row lock). */
    public void markInFlight() {
        this.status = DeliveryStatus.IN_FLIGHT;
    }

    /** Success: a 2xx was received. Terminal. */
    public void markDelivered() {
        this.status = DeliveryStatus.DELIVERED;
    }

    /** This attempt failed but retries remain: count it and schedule the next one (back to pending). */
    public void failAndRetryAt(Instant next) {
        this.attemptCount++;
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = next;
    }

    /** This attempt failed and the cap is reached: count it and dead-letter. Terminal (replayable). */
    public void failPermanently() {
        this.attemptCount++;
        this.status = DeliveryStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
