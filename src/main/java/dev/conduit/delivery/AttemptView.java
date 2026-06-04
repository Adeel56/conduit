package dev.conduit.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a delivery's attempt history for {@code GET /deliveries/{id}}. Carries only the
 * safe-to-expose facts about a single try — when it ran, the response status (null when no response
 * was received, e.g. a timeout/connect error), a short error string, and how long it took. Never the
 * payload or any secret (the {@code error} string is the worker's already-sanitised class/message).
 */
public record AttemptView(
        UUID id,
        Instant attemptedAt,
        Integer responseStatus,
        String error,
        long durationMs) {

    public static AttemptView from(Attempt attempt) {
        return new AttemptView(
                attempt.getId(),
                attempt.getAttemptedAt(),
                attempt.getResponseStatus(),
                attempt.getError(),
                attempt.getDurationMs());
    }
}
