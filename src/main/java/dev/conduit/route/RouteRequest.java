package dev.conduit.route;

import java.util.UUID;

/**
 * Create payload for a route: the source and destination to wire together. Both must belong to the
 * caller's org (validated in {@code RouteService}); {@code org_id} is never accepted from the client
 * — it comes from the authenticated principal.
 */
public record RouteRequest(UUID sourceId, UUID destinationId) {
}
