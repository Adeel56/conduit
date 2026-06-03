package dev.conduit.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight list row for {@code GET /events} — deliberately <b>without</b> the payload or headers
 * (which can be large × many rows). {@code payloadSize} is computed in the database via
 * {@code octet_length(payload)}, so the bytes are never read into the app for the list path.
 *
 * <p>Populated by an explicit JPQL constructor expression in {@link EventRepository}; the FQN is
 * referenced in that query, so this must stay a top-level public type.
 */
public record EventSummary(UUID id, UUID sourceId, int payloadSize, Instant receivedAt) {
}
