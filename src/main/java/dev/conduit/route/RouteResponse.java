package dev.conduit.route;

import java.time.Instant;
import java.util.UUID;

/**
 * The stable JSON view of a route. {@code orgId} is intentionally not exposed (implied by the
 * authenticated caller). {@code sourceId} and {@code destinationId} are the caller's own ids, so
 * echoing them leaks nothing across tenants.
 */
public record RouteResponse(
        UUID id,
        UUID sourceId,
        UUID destinationId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static RouteResponse from(Route r) {
        return new RouteResponse(r.getId(), r.getSourceId(), r.getDestinationId(), r.isActive(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
