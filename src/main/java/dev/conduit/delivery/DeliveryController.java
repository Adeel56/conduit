package dev.conduit.delivery;

import dev.conduit.auth.ApiKeyPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deliveries read API (CON-13): read-only, authenticated, strictly tenant-scoped — the same
 * shape as the CON-9 event inspector ({@code EventController}), reused deliberately.
 *
 * <ul>
 *   <li>{@code GET /deliveries} — the caller's org's deliveries only, newest-first, paginated
 *       summaries (no attempts), optional {@code eventId} and {@code status} filters (still
 *       org-scoped).</li>
 *   <li>{@code GET /deliveries/{id}} — one delivery with its full attempt history, only if it
 *       belongs to the caller's org; otherwise <b>404</b> identical to not-found — never 403, which
 *       would confirm existence (no cross-tenant oracle).</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> {@code org_id} is taken <em>only</em> from
 * {@code @AuthenticationPrincipal ApiKeyPrincipal} and threaded into every query. There is
 * deliberately no {@code orgId} request parameter — a client cannot choose its tenant. Both routes
 * sit under {@code anyRequest().authenticated()}, so no key → 401 before this controller runs.
 *
 * <p>Malformed query/path params (a non-UUID {@code id}/{@code eventId}, an unknown {@code status},
 * {@code page=abc}) bind-fail to a 400 via Spring's {@code MethodArgumentTypeMismatch} — there is no
 * catch-all here that would mask them as a misleading 401.
 */
@RestController
public class DeliveryController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryController.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveries;
    private final AttemptRepository attempts;
    private final MeterRegistry meters;

    public DeliveryController(DeliveryRepository deliveries, AttemptRepository attempts,
                             MeterRegistry meters) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.meters = meters;
    }

    @GetMapping("/deliveries")
    public ResponseEntity<DeliveryPage> list(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) UUID eventId,
                                             @RequestParam(required = false) DeliveryStatus status) {
        // Server-side clamps — never trust the client (resource-exhaustion guard).
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Newest-first with the id tiebreaker so paging is a total order even when created_at ties.
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

        UUID orgId = principal.orgId();
        // Pick the org-scoped finder by which optional filters are present — every branch keeps org_id.
        Page<Delivery> result;
        if (eventId == null && status == null) {
            result = deliveries.findByOrgId(orgId, pageable);
        } else if (status == null) {
            result = deliveries.findByOrgIdAndEventId(orgId, eventId, pageable);
        } else if (eventId == null) {
            result = deliveries.findByOrgIdAndStatus(orgId, status, pageable);
        } else {
            result = deliveries.findByOrgIdAndEventIdAndStatus(orgId, eventId, status, pageable);
        }

        meters.counter("conduit.deliveries.list", "outcome", "ok").increment();
        log.debug("deliveries.list org={} eventId={} status={} page={} size={} returned={}",
                orgId, eventId, status, safePage, safeSize, result.getNumberOfElements());

        // Tenant-private data: never cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DeliveryPage.from(result));
    }

    @GetMapping("/deliveries/{id}")
    public ResponseEntity<Object> detail(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @PathVariable UUID id) {
        return deliveries.findByIdAndOrgId(id, principal.orgId())
                .<ResponseEntity<Object>>map(delivery -> {
                    // Attempts are fetched only after the delivery is confirmed to be the caller's.
                    List<Attempt> history = attempts.findByDeliveryIdOrderByAttemptedAtAsc(delivery.getId());
                    meters.counter("conduit.deliveries.detail", "outcome", "ok").increment();
                    log.debug("deliveries.detail org={} delivery={} found attempts={}",
                            principal.orgId(), id, history.size());
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(DeliveryDetail.from(delivery, history));
                })
                .orElseGet(() -> {
                    // Not found OR not yours — identical 404, so existence is never revealed.
                    meters.counter("conduit.deliveries.detail", "outcome", "not_found").increment();
                    log.debug("deliveries.detail org={} delivery={} not_found", principal.orgId(), id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                            .body(Map.of("error", "not_found"));
                });
    }
}
