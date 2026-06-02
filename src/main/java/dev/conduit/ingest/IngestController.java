package dev.conduit.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.event.Event;
import dev.conduit.event.EventRepository;
import dev.conduit.source.Source;
import dev.conduit.source.SourceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conduit's front door (CON-7): {@code POST /ingest/{ingestKey}} receives a webhook, stores it as
 * an immutable {@link Event}, and acknowledges with {@code 202 Accepted}. Receive + store + ack
 * only — no HMAC verification, no delivery, no idempotency (each a later ticket).
 *
 * <p>Control flow, ordered so the size cap runs before any expensive work:
 * <ol>
 *   <li>resolve the source by ingest key (active only) → <b>404</b> if unknown/inactive (identical
 *       response either way, so existence isn't revealed);</li>
 *   <li>reject oversize bodies → <b>413</b> (fast Content-Length check, then a bounded read that
 *       also catches a missing/lying Content-Length) — caps memory use;</li>
 *   <li>store the event (org_id copied from the source) and return <b>202</b> with its id.</li>
 * </ol>
 * The event is committed (durably stored) before we ack — the store-then-ack reliability contract.
 */
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final SourceRepository sources;
    private final EventRepository events;
    private final IngestProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public IngestController(SourceRepository sources, EventRepository events,
                            IngestProperties properties, ObjectMapper objectMapper,
                            MeterRegistry meters) {
        this.sources = sources;
        this.events = events;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @PostMapping("/ingest/{ingestKey}")
    public ResponseEntity<Map<String, Object>> ingest(@PathVariable String ingestKey,
                                                       HttpServletRequest request) throws IOException {
        long maxBytes = properties.maxBodySize().toBytes();

        // 1. Resolve the source. Unknown or inactive → 404, the same response for both.
        Optional<Source> maybeSource = sources.findByIngestKeyAndActiveTrue(ingestKey);
        if (maybeSource.isEmpty()) {
            count("not_found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        Source source = maybeSource.get();

        // 2a. Fast reject on a declared Content-Length over the cap (no body read).
        if (request.getContentLengthLong() > maxBytes) {
            count("too_large");
            return tooLarge(maxBytes);
        }

        // 2b. Bounded read: also catches a missing/chunked/lying Content-Length. Returns null if the
        // body exceeds the cap, so we never buffer more than maxBytes (+ one chunk) into memory.
        byte[] payload = readBounded(request.getInputStream(), maxBytes);
        if (payload == null) {
            count("too_large");
            return tooLarge(maxBytes);
        }

        // 3. Store the event with org_id taken from the source (events are never orphaned), then ack.
        Event event = Event.received(source.getOrgId(), source.getId(), payload, headersAsJson(request));
        events.save(event);

        count("accepted");
        // Log ids + size only — never the payload or headers (they can carry secrets/PII).
        log.info("ingest accepted: source={} org={} event={} bytes={}",
                source.getId(), source.getOrgId(), event.getId(), payload.length);
        return ResponseEntity.accepted().body(Map.of("eventId", event.getId()));
    }

    private ResponseEntity<Map<String, Object>> tooLarge(long maxBytes) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "payload_too_large", "maxBytes", maxBytes));
    }

    private void count(String outcome) {
        meters.counter("conduit.ingest.requests", "outcome", outcome).increment();
    }

    /**
     * Read the stream into a byte[], stopping as soon as more than {@code maxBytes} have been read.
     * Returns {@code null} to signal "over the cap" so the caller can return 413 without ever
     * holding an unbounded body in memory.
     */
    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Capture request headers as a JSON object (name -> comma-joined values), stored faithfully. */
    private String headersAsJson(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, String.join(", ", Collections.list(request.getHeaders(name))));
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
