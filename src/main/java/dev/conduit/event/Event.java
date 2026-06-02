package dev.conduit.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An immutable record of a received webhook (docs/data-model.md). Written once at ingest and never
 * mutated — it is the source of truth for "what came in". There are intentionally no setters.
 *
 * <p>{@code org_id} is copied from the owning source so every event is tenant-scoped and can be
 * queried/filtered by organization without joining through {@code sources} (tenant isolation).
 *
 * <p>Schema owned by Flyway ({@code V2__sources_and_events.sql}); {@code ddl-auto} is
 * {@code validate}.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    // Raw request body, stored faithfully as bytes — never parsed or transformed (the untrusted
    // payload is data, not code).
    @Column(nullable = false, updatable = false)
    private byte[] payload;

    // Request headers as received, serialized to a JSON object and stored in a jsonb column.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String headers;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
        // for JPA
    }

    /** Build a new event from a received webhook. {@code receivedAt} is stamped now. */
    public static Event received(UUID orgId, UUID sourceId, byte[] payload, String headers) {
        Event event = new Event();
        event.orgId = orgId;
        event.sourceId = sourceId;
        event.payload = payload;
        event.headers = headers;
        event.receivedAt = Instant.now();
        return event;
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

    public byte[] getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
