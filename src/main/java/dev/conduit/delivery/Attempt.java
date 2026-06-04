package dev.conduit.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One individual delivery try (CON-13) — append-only history of "what happened" for a {@link Delivery}.
 * Records the response status (null if no response was received, e.g. a timeout/connect error), a short
 * error string (never the payload or secrets), and how long the try took.
 *
 * <p>Schema owned by Flyway ({@code V6__deliveries_and_attempts.sql}); {@code ddl-auto} is
 * {@code validate}.
 */
@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column
    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    protected Attempt() {
        // for JPA
    }

    /** Record a try. {@code responseStatus}/{@code error} are mutually exclusive in practice. */
    public static Attempt record(UUID deliveryId, Integer responseStatus, String error, long durationMs) {
        Attempt a = new Attempt();
        a.deliveryId = deliveryId;
        a.attemptedAt = Instant.now();
        a.responseStatus = responseStatus;
        a.error = error;
        a.durationMs = durationMs;
        return a;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
