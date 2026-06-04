package dev.conduit.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full single-delivery view for {@code GET /deliveries/{id}}: the same identity/status fields as the
 * list summary, plus the delivery's full {@code attempts} history (oldest-first — the order the worker
 * appended them). Mirrors {@code EventDetail} (CON-9): a hand-built, stable JSON contract that exposes
 * no payloads or secrets.
 */
public record DeliveryDetail(
        UUID id,
        UUID eventId,
        UUID routeId,
        UUID destinationId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        List<AttemptView> attempts) {

    public static DeliveryDetail from(Delivery delivery, List<Attempt> attempts) {
        return new DeliveryDetail(
                delivery.getId(),
                delivery.getEventId(),
                delivery.getRouteId(),
                delivery.getDestinationId(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getCreatedAt(),
                attempts.stream().map(AttemptView::from).toList());
    }
}
