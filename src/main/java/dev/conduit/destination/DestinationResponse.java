package dev.conduit.destination;

import java.time.Instant;
import java.util.UUID;

/**
 * The stable JSON view of a destination. We expose only what a client should see — notably NOT
 * {@code orgId} (it is implied by the authenticated caller and never echoed back).
 */
public record DestinationResponse(
        UUID id,
        String name,
        String url,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static DestinationResponse from(Destination d) {
        return new DestinationResponse(d.getId(), d.getName(), d.getUrl(), d.isActive(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
