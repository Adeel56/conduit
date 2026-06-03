package dev.conduit.destination;

/**
 * Create/update payload for a destination. Only the caller-settable fields — {@code id}, {@code
 * orgId}, and timestamps are never accepted from the client (org comes from the principal). {@code
 * active} is intentionally not settable here: deactivation is a dedicated endpoint, not a free-form
 * field, so a destination is created active and only ever turned off explicitly.
 */
public record DestinationRequest(String name, String url) {
}
