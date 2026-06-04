package dev.conduit.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight list row for {@code GET /deliveries} — the delivery's identity, wiring, current status,
 * and scheduling, <b>without</b> any attempt history (that is the detail view) and without payloads or
 * secrets. Mirrors {@code EventSummary} (CON-9): a stable, hand-built projection so the JSON contract
 * is ours, not Hibernate's.
 */
public record DeliverySummary(
        UUID id,
        UUID eventId,
        UUID routeId,
        UUID destinationId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt) {

    public static DeliverySummary from(Delivery delivery) {
        return new DeliverySummary(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getRouteId(),
                delivery.getDestinationId(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getCreatedAt());
    }
}
