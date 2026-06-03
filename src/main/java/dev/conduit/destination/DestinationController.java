package dev.conduit.destination;

import dev.conduit.auth.ApiKeyPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Destination management (CON-10): authenticated, strictly org-scoped CRUD for outbound targets.
 *
 * <ul>
 *   <li>{@code POST   /destinations} — create (URL validated; 400 if invalid).</li>
 *   <li>{@code GET    /destinations} — the caller's org's destinations, paginated.</li>
 *   <li>{@code GET    /destinations/{id}} — one, only if it's the caller's; else <b>404</b>.</li>
 *   <li>{@code PUT    /destinations/{id}} — update name/url; <b>404</b> if not the caller's.</li>
 *   <li>{@code DELETE /destinations/{id}} — soft-deactivate; <b>404</b> if not the caller's.</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> {@code org_id} is taken <em>only</em> from
 * {@code @AuthenticationPrincipal ApiKeyPrincipal} — there is deliberately no {@code orgId} request
 * field. Not-found and not-yours are answered with an identical 404 (never 403), so existence in
 * another org is never revealed. All routes sit under {@code anyRequest().authenticated()} (CON-8),
 * so no key → 401 before this controller runs.
 */
@RestController
public class DestinationController {

    private static final Logger log = LoggerFactory.getLogger(DestinationController.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final DestinationService destinations;
    private final MeterRegistry meters;

    public DestinationController(DestinationService destinations, MeterRegistry meters) {
        this.destinations = destinations;
        this.meters = meters;
    }

    @PostMapping("/destinations")
    public ResponseEntity<DestinationResponse> create(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                                      @RequestBody DestinationRequest request) {
        Destination created = destinations.create(principal.orgId(), request);
        meters.counter("conduit.destinations.create", "outcome", "ok").increment();
        log.debug("destinations.create org={} destination={}", principal.orgId(), created.getId());
        return ResponseEntity.created(URI.create("/destinations/" + created.getId()))
                .cacheControl(CacheControl.noStore())
                .body(DestinationResponse.from(created));
    }

    @GetMapping("/destinations")
    public ResponseEntity<DestinationPage> list(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = clampedPage(page, size);
        Page<Destination> result = destinations.list(principal.orgId(), pageable);
        meters.counter("conduit.destinations.list", "outcome", "ok").increment();
        log.debug("destinations.list org={} page={} size={} returned={}",
                principal.orgId(), pageable.getPageNumber(), pageable.getPageSize(),
                result.getNumberOfElements());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DestinationPage.from(result));
    }

    @GetMapping("/destinations/{id}")
    public ResponseEntity<Object> get(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                      @PathVariable UUID id) {
        return destinations.get(principal.orgId(), id)
                .<ResponseEntity<Object>>map(d -> {
                    meters.counter("conduit.destinations.get", "outcome", "ok").increment();
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(DestinationResponse.from(d));
                })
                .orElseGet(() -> notFound("destinations.get", principal.orgId(), id));
    }

    @PutMapping("/destinations/{id}")
    public ResponseEntity<Object> update(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @PathVariable UUID id,
                                         @RequestBody DestinationRequest request) {
        return destinations.update(principal.orgId(), id, request)
                .<ResponseEntity<Object>>map(d -> {
                    meters.counter("conduit.destinations.update", "outcome", "ok").increment();
                    log.debug("destinations.update org={} destination={}", principal.orgId(), id);
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(DestinationResponse.from(d));
                })
                .orElseGet(() -> notFound("destinations.update", principal.orgId(), id));
    }

    @DeleteMapping("/destinations/{id}")
    public ResponseEntity<Object> deactivate(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                             @PathVariable UUID id) {
        return destinations.deactivate(principal.orgId(), id)
                .<ResponseEntity<Object>>map(d -> {
                    meters.counter("conduit.destinations.deactivate", "outcome", "ok").increment();
                    log.debug("destinations.deactivate org={} destination={}", principal.orgId(), id);
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(DestinationResponse.from(d));
                })
                .orElseGet(() -> notFound("destinations.deactivate", principal.orgId(), id));
    }

    /** Invalid create/update payload → 400 with the (tenant-free) validation message. */
    @ExceptionHandler(InvalidDestinationException.class)
    public ResponseEntity<Object> onInvalid(InvalidDestinationException e) {
        meters.counter("conduit.destinations.invalid").increment();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_destination", "message", e.getMessage()));
    }

    private ResponseEntity<Object> notFound(String op, UUID orgId, UUID id) {
        // Not found OR not yours — identical 404, so existence in another org is never revealed.
        meters.counter(metricFor(op), "outcome", "not_found").increment();
        log.debug("{} org={} destination={} not_found", op, orgId, id);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "not_found"));
    }

    private static String metricFor(String op) {
        return "conduit." + op;
    }

    private static Pageable clampedPage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Newest-first, with an id tiebreaker for deterministic paging (mirrors the inspector).
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}
