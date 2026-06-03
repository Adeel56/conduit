package dev.conduit.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Full single-event view for {@code GET /events/{id}}.
 *
 * <p><b>Payload rendering:</b> the payload is raw bytes (could be non-UTF-8 — gzip, binary, …). We
 * try a <em>strict</em> UTF-8 decode (reports malformed input rather than silently substituting
 * U+FFFD); on success the body is returned as text with {@code encoding="utf8"}, otherwise as
 * Base64 with {@code encoding="base64"}. The explicit {@code encoding} field means the client never
 * has to guess. We never echo the stored request {@code Content-Type} as the response content type
 * (that would invite stored-content XSS) — the body is always a string field in a JSON envelope.
 *
 * <p>{@code headers} is returned as the JSON object it was stored as (not a re-escaped string).
 */
public record EventDetail(
        UUID id,
        UUID sourceId,
        Instant receivedAt,
        Instant createdAt,
        int payloadSize,
        String encoding,
        String payload,
        JsonNode headers) {

    public static EventDetail from(Event event, ObjectMapper objectMapper) {
        byte[] payload = event.getPayload();
        String encoding;
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
            encoding = "utf8";
        } catch (CharacterCodingException notText) {
            body = Base64.getEncoder().encodeToString(payload);
            encoding = "base64";
        }

        JsonNode headers;
        try {
            headers = objectMapper.readTree(event.getHeaders());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // headers is always written as valid JSON at ingest (CON-7); this is defensive only.
            throw new UncheckedIOException(e);
        }

        return new EventDetail(event.getId(), event.getSourceId(), event.getReceivedAt(),
                event.getCreatedAt(), payload.length, encoding, body, headers);
    }
}
