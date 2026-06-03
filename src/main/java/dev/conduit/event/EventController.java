package dev.conduit.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.auth.ApiKeyPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Inspector (CON-9): read-only, authenticated, strictly tenant-scoped.
 *
 * <ul>
 *   <li>{@code GET /events} — the caller's org's events only, newest-first, paginated summaries
 *       (no payloads), optional {@code sourceId} filter (still org-scoped).</li>
 *   <li>{@code GET /events/{id}} — the full event, only if it belongs to the caller's org;
 *       otherwise <b>404</b> (identical to not-found — never 403, which would confirm existence).</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> {@code org_id} is taken <em>only</em> from
 * {@code @AuthenticationPrincipal ApiKeyPrincipal} (set by CON-8) and threaded into every query.
 * There is deliberately no {@code orgId} request parameter — it is structurally impossible for a
 * client to choose its tenant. Both routes sit under {@code anyRequest().authenticated()}, so no
 * key → 401 before this controller ever runs.
 */
@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final EventRepository events;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public EventController(EventRepository events, ObjectMapper objectMapper, MeterRegistry meters) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @GetMapping("/events")
    public ResponseEntity<EventPage> list(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) UUID sourceId) {
        // Server-side clamps — never trust the client (resource-exhaustion guard).
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize); // unsorted: ORDER BY is in the query

        UUID orgId = principal.orgId();
        Page<EventSummary> result = (sourceId == null)
                ? events.findSummariesByOrgId(orgId, pageable)
                : events.findSummariesByOrgIdAndSourceId(orgId, sourceId, pageable);

        meters.counter("conduit.events.list", "outcome", "ok").increment();
        log.debug("events.list org={} sourceId={} page={} size={} returned={}",
                orgId, sourceId, safePage, safeSize, result.getNumberOfElements());

        // Tenant-private data: never cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(EventPage.from(result));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<Object> detail(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @PathVariable UUID id) {
        return events.findByIdAndOrgId(id, principal.orgId())
                .<ResponseEntity<Object>>map(event -> {
                    meters.counter("conduit.events.detail", "outcome", "ok").increment();
                    log.debug("events.detail org={} event={} found", principal.orgId(), id);
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(EventDetail.from(event, objectMapper));
                })
                .orElseGet(() -> {
                    // Not found OR not yours — identical 404, so existence is never revealed.
                    meters.counter("conduit.events.detail", "outcome", "not_found").increment();
                    log.debug("events.detail org={} event={} not_found", principal.orgId(), id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                            .body(Map.of("error", "not_found"));
                });
    }
}
