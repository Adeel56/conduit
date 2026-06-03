package dev.conduit.route;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Route management (CON-10): authenticated, strictly org-scoped wiring of a source to a destination.
 *
 * <ul>
 *   <li>{@code POST   /routes} — create a route. Both ids must be the caller's org; if a referenced
 *       source/destination is missing or another org's, the response is an identical <b>404</b>
 *       (never reveal it exists elsewhere). A duplicate wiring → <b>409</b>.</li>
 *   <li>{@code GET    /routes} — the caller's routes, paginated; optional {@code sourceId} filter
 *       (still org-scoped — a foreign source id matches nothing, not an error).</li>
 *   <li>{@code GET    /routes/{id}} — one, only if it's the caller's; else <b>404</b>.</li>
 *   <li>{@code POST   /routes/{id}/deactivate} — soft-disable; <b>404</b> if not the caller's.</li>
 *   <li>{@code DELETE /routes/{id}} — hard-delete the join row; <b>404</b> if not the caller's.</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> {@code org_id} only ever from {@code @AuthenticationPrincipal
 * ApiKeyPrincipal}. All routes sit under {@code anyRequest().authenticated()} (CON-8) → no key = 401.
 */
@RestController
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);

    static final int MAX_PAGE_SIZE = 100;

    private final RouteService routes;
    private final MeterRegistry meters;

    public RouteController(RouteService routes, MeterRegistry meters) {
        this.routes = routes;
        this.meters = meters;
    }

    @PostMapping("/routes")
    public ResponseEntity<RouteResponse> create(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                                @RequestBody RouteRequest request) {
        Route created = routes.create(principal.orgId(), request);
        meters.counter("conduit.routes.create", "outcome", "ok").increment();
        log.debug("routes.create org={} route={} source={} destination={}",
                principal.orgId(), created.getId(), created.getSourceId(), created.getDestinationId());
        return ResponseEntity.created(URI.create("/routes/" + created.getId()))
                .cacheControl(CacheControl.noStore())
                .body(RouteResponse.from(created));
    }

    @GetMapping("/routes")
    public ResponseEntity<RoutePage> list(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) UUID sourceId) {
        Pageable pageable = clampedPage(page, size);
        Page<Route> result = routes.list(principal.orgId(), sourceId, pageable);
        meters.counter("conduit.routes.list", "outcome", "ok").increment();
        log.debug("routes.list org={} sourceId={} page={} size={} returned={}",
                principal.orgId(), sourceId, pageable.getPageNumber(), pageable.getPageSize(),
                result.getNumberOfElements());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RoutePage.from(result));
    }

    @GetMapping("/routes/{id}")
    public ResponseEntity<Object> get(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                      @PathVariable UUID id) {
        return routes.get(principal.orgId(), id)
                .<ResponseEntity<Object>>map(r -> {
                    meters.counter("conduit.routes.get", "outcome", "ok").increment();
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(RouteResponse.from(r));
                })
                .orElseGet(() -> notFound("routes.get", principal.orgId(), id));
    }

    @PostMapping("/routes/{id}/deactivate")
    public ResponseEntity<Object> deactivate(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                             @PathVariable UUID id) {
        return routes.deactivate(principal.orgId(), id)
                .<ResponseEntity<Object>>map(r -> {
                    meters.counter("conduit.routes.deactivate", "outcome", "ok").increment();
                    log.debug("routes.deactivate org={} route={}", principal.orgId(), id);
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(RouteResponse.from(r));
                })
                .orElseGet(() -> notFound("routes.deactivate", principal.orgId(), id));
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Object> delete(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @PathVariable UUID id) {
        if (routes.delete(principal.orgId(), id)) {
            meters.counter("conduit.routes.delete", "outcome", "ok").increment();
            log.debug("routes.delete org={} route={}", principal.orgId(), id);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return notFound("routes.delete", principal.orgId(), id);
    }

    /** Source/destination missing OR not the caller's org → identical 404, no existence oracle. */
    @ExceptionHandler(RouteReferenceNotFoundException.class)
    public ResponseEntity<Object> onReferenceNotFound(RouteReferenceNotFoundException e) {
        meters.counter("conduit.routes.create", "outcome", "ref_not_found").increment();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "not_found"));
    }

    /** Duplicate (source, destination) wiring → 409 (both ids are the caller's, so this is safe). */
    @ExceptionHandler(DuplicateRouteException.class)
    public ResponseEntity<Object> onDuplicate(DuplicateRouteException e) {
        meters.counter("conduit.routes.create", "outcome", "duplicate").increment();
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "duplicate_route", "message", e.getMessage()));
    }

    private ResponseEntity<Object> notFound(String op, UUID orgId, UUID id) {
        meters.counter("conduit." + op, "outcome", "not_found").increment();
        log.debug("{} org={} route={} not_found", op, orgId, id);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "not_found"));
    }

    private static Pageable clampedPage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}
