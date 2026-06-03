package dev.conduit.source;

import java.time.Instant;
import java.util.UUID;

/**
 * The non-secret view of a source, returned by list and get (CON-11). It deliberately carries
 * {@code ingestKeyPrefix} (a short leading slice) instead of the full {@code ingestKey}: the full
 * key is shown only once, at create/rotate, and is never re-exposed afterwards. The prefix lets an
 * operator tell two keys apart without the API ever leaking a usable key.
 */
public record SourceSummary(
        UUID id,
        String name,
        boolean active,
        String ingestKeyPrefix,
        Instant createdAt,
        Instant updatedAt) {

    public static SourceSummary from(Source source) {
        return new SourceSummary(
                source.getId(),
                source.getName(),
                source.isActive(),
                source.ingestKeyPrefix(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
