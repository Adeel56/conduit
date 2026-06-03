package dev.conduit.source;

import java.time.Instant;
import java.util.UUID;

/**
 * The one-time response for create and rotate-key (CON-11). This is the <b>only</b> place the API
 * returns the full {@code ingestKey} (and the absolute {@code ingestUrl} built from it) — analogous
 * to the show-once contract for API keys ({@code CreatedApiKey}). Subsequent list/get calls return a
 * {@link SourceSummary}, which carries only the non-secret prefix. The full key is never logged.
 */
public record CreatedSource(
        UUID id,
        String name,
        boolean active,
        String ingestKey,
        String ingestUrl,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Build the one-time view. {@code ingestUrl} is the absolute URL a third party POSTs to, formed
     * from the request's own base URL plus {@code /ingest/{ingestKey}} (kept in sync with
     * {@code IngestController}'s route), so the caller can hand it straight to Stripe/GitHub.
     */
    public static CreatedSource from(Source source, String baseUrl) {
        return new CreatedSource(
                source.getId(),
                source.getName(),
                source.isActive(),
                source.getIngestKey(),
                baseUrl + "/ingest/" + source.getIngestKey(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
