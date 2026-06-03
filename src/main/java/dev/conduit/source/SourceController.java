package dev.conduit.source;

import dev.conduit.auth.ApiKeyPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Source management CRUD (CON-11): authenticated, strictly tenant-scoped.
 *
 * <ul>
 *   <li>{@code POST   /sources}                — create a source; returns the full ingest key/URL
 *       <b>once</b> (201).</li>
 *   <li>{@code GET    /sources}                — the caller's org's sources, newest-first,
 *       paginated summaries; <b>never</b> the full ingest key.</li>
 *   <li>{@code GET    /sources/{id}}           — one source (summary); <b>404</b> if not yours.</li>
 *   <li>{@code PATCH  /sources/{id}}           — rename; <b>404</b> if not yours.</li>
 *   <li>{@code POST   /sources/{id}/rotate-key}— issue a new ingest key (returned once); the old key
 *       stops resolving immediately. <b>404</b> if not yours.</li>
 *   <li>{@code POST   /sources/{id}/deactivate}— stop ingest (CON-7 then 404s) while preserving the
 *       row and its immutable events. <b>404</b> if not yours.</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> {@code org_id} is taken <em>only</em> from
 * {@code @AuthenticationPrincipal ApiKeyPrincipal} (CON-8) and threaded into every query — there is
 * no {@code orgId} request parameter, so a client cannot choose its tenant. A source that is missing
 * <em>or</em> belongs to another org yields an identical <b>404</b> (never 403), so existence is
 * never revealed. All routes sit under {@code anyRequest().authenticated()}: no key → 401 before
 * this controller runs.
 *
 * <p><b>Show-once:</b> the full ingest key (and absolute ingest URL) is returned only by create and
 * rotate-key, and is never logged. List/get expose only the non-secret {@code ingestKeyPrefix}.
 */
@RestController
public class SourceController {

    private static final Logger log = LoggerFactory.getLogger(SourceController.class);

    static final int MAX_PAGE_SIZE = 100;

    private static final ResponseEntity<Object> NOT_FOUND = ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .cacheControl(CacheControl.noStore())
            .body(Map.of("error", "not_found"));

    private final SourceService sources;
    private final MeterRegistry meters;

    public SourceController(SourceService sources, MeterRegistry meters) {
        this.sources = sources;
        this.meters = meters;
    }

    @PostMapping("/sources")
    public ResponseEntity<Object> create(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @RequestBody(required = false) SourceRequests.Create body) {
        String name = cleanName(body == null ? null : body.name());
        if (name == null) {
            return badRequest("name must not be blank");
        }

        Source source = sources.create(principal.orgId(), name);
        count("create", "ok");
        // ids + prefix only — never the full key (it can route ingest before HMAC exists).
        log.info("sources.create org={} source={} keyPrefix={}",
                principal.orgId(), source.getId(), source.ingestKeyPrefix());

        URI location = URI.create("/sources/" + source.getId());
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(CreatedSource.from(source, baseUrl()));
    }

    @GetMapping("/sources")
    public ResponseEntity<SourcePage> list(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        // Server-side clamps — never trust the client (resource-exhaustion guard).
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize); // unsorted: ORDER BY is in the query

        Page<Source> result = sources.list(principal.orgId(), pageable);
        count("list", "ok");
        log.debug("sources.list org={} page={} size={} returned={}",
                principal.orgId(), safePage, safeSize, result.getNumberOfElements());

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SourcePage.from(result));
    }

    @GetMapping("/sources/{id}")
    public ResponseEntity<Object> get(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                      @PathVariable UUID id) {
        return summaryOr404(sources.find(principal.orgId(), id), principal.orgId(), id, "get");
    }

    @PatchMapping("/sources/{id}")
    public ResponseEntity<Object> update(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                         @PathVariable UUID id,
                                         @RequestBody(required = false) SourceRequests.Update body) {
        String name = cleanName(body == null ? null : body.name());
        if (name == null) {
            return badRequest("name must not be blank");
        }
        return summaryOr404(sources.rename(principal.orgId(), id, name), principal.orgId(), id, "update");
    }

    @PostMapping("/sources/{id}/rotate-key")
    public ResponseEntity<Object> rotateKey(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                            @PathVariable UUID id) {
        Optional<Source> rotated = sources.rotateIngestKey(principal.orgId(), id);
        if (rotated.isEmpty()) {
            count("rotate", "not_found");
            log.debug("sources.rotate org={} source={} not_found", principal.orgId(), id);
            return NOT_FOUND;
        }
        Source source = rotated.get();
        count("rotate", "ok");
        // ids + NEW prefix only — never the full key. The old key is already invalid post-rotation.
        log.info("sources.rotate org={} source={} newKeyPrefix={}",
                principal.orgId(), source.getId(), source.ingestKeyPrefix());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CreatedSource.from(source, baseUrl()));
    }

    @PostMapping("/sources/{id}/deactivate")
    public ResponseEntity<Object> deactivate(@AuthenticationPrincipal ApiKeyPrincipal principal,
                                             @PathVariable UUID id) {
        return summaryOr404(sources.deactivate(principal.orgId(), id), principal.orgId(), id, "deactivate");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Map a present source to a 200 summary (no full key), or an identical 404 if absent/not yours. */
    private ResponseEntity<Object> summaryOr404(Optional<Source> maybe, UUID orgId, UUID id, String op) {
        return maybe
                .<ResponseEntity<Object>>map(source -> {
                    count(op, "ok");
                    log.debug("sources.{} org={} source={} ok", op, orgId, id);
                    return ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(SourceSummary.from(source));
                })
                .orElseGet(() -> {
                    // Not found OR not yours — identical 404, so existence is never revealed.
                    count(op, "not_found");
                    log.debug("sources.{} org={} source={} not_found", op, orgId, id);
                    return NOT_FOUND;
                });
    }

    /** Trim and reject blank/missing names; returns the cleaned value or {@code null} if invalid. */
    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "bad_request", "message", message));
    }

    /** The request's own scheme://host[:port] — used to build the absolute one-time ingest URL. */
    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private void count(String op, String outcome) {
        meters.counter("conduit.sources." + op, "outcome", outcome).increment();
    }
}
