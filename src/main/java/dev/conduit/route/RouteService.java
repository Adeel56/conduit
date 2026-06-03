package dev.conduit.route;

import dev.conduit.destination.DestinationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Route CRUD, strictly org-scoped (CON-10). The load-bearing rule lives here: a route may only wire
 * a source and a destination that BOTH belong to the caller's org. {@code orgId} always comes from
 * the authenticated principal — never the client.
 *
 * <p><b>Same-org enforcement (the centerpiece):</b> on create we confirm the source and the
 * destination each exist <em>and</em> belong to {@code orgId}. If either fails — whether it doesn't
 * exist or belongs to another tenant — we throw the SAME {@link RouteReferenceNotFoundException},
 * which the controller renders as an identical 404. So a caller can never use route creation as an
 * oracle to discover that another org owns a given source/destination id (a cross-tenant leak).
 */
@Service
public class RouteService {

    private final RouteRepository routes;
    private final RouteSourceLookup sources;
    private final DestinationRepository destinations;

    public RouteService(RouteRepository routes, RouteSourceLookup sources,
                        DestinationRepository destinations) {
        this.routes = routes;
        this.sources = sources;
        this.destinations = destinations;
    }

    /**
     * Wire {@code sourceId} → {@code destinationId} for the caller's org.
     *
     * @throws RouteReferenceNotFoundException if the source or destination is missing OR not the
     *                                         caller's org (identical 404 — no existence oracle)
     * @throws DuplicateRouteException         if this exact wiring already exists (409)
     */
    @Transactional
    public Route create(UUID orgId, RouteRequest request) {
        UUID sourceId = request.sourceId();
        UUID destinationId = request.destinationId();
        if (sourceId == null || destinationId == null) {
            // A missing id can never reference a same-org row → same not-found answer (no 400 oracle).
            throw new RouteReferenceNotFoundException();
        }

        // SAME-ORG GATE: both the source and the destination must be the caller's. A foreign-org or
        // unknown id is indistinguishable here — both raise the identical not-found.
        boolean sourceIsOurs = sources.existsByIdAndOrgId(sourceId, orgId);
        boolean destinationIsOurs = destinations.existsByIdAndOrgId(destinationId, orgId);
        if (!sourceIsOurs || !destinationIsOurs) {
            throw new RouteReferenceNotFoundException();
        }

        // Friendly duplicate check in front of the unique constraint. Both ids are confirmed
        // same-org above, so reporting the duplicate leaks nothing across tenants.
        if (routes.existsBySourceIdAndDestinationId(sourceId, destinationId)) {
            throw new DuplicateRouteException();
        }

        try {
            // saveAndFlush, NOT save: Route's id is application-assigned (GenerationType.UUID), so a
            // plain save() defers the INSERT to commit — after this method returns, outside this
            // catch — and a concurrent-duplicate unique violation would escape as a 500. Flushing
            // here forces the INSERT now, so the race is caught and mapped to the same clean 409.
            return routes.saveAndFlush(new Route(orgId, sourceId, destinationId));
        } catch (DataIntegrityViolationException race) {
            // Backstop for the race where two concurrent creates both pass the pre-check above: the
            // unique index on (source_id, destination_id) rejects the second insert. Same clean 409.
            throw new DuplicateRouteException();
        }
    }

    /** Org-scoped, paginated list, optionally filtered to one source (still org-scoped). */
    @Transactional(readOnly = true)
    public Page<Route> list(UUID orgId, UUID sourceId, Pageable pageable) {
        return (sourceId == null)
                ? routes.findByOrgId(orgId, pageable)
                : routes.findByOrgIdAndSourceId(orgId, sourceId, pageable);
    }

    /** One route by id, only if it is the caller's; otherwise empty (→ 404). */
    @Transactional(readOnly = true)
    public Optional<Route> get(UUID orgId, UUID id) {
        return routes.findByIdAndOrgId(id, orgId);
    }

    /** Soft-deactivate a route the caller owns. Empty if not theirs/not found (→ 404). */
    @Transactional
    public Optional<Route> deactivate(UUID orgId, UUID id) {
        return routes.findByIdAndOrgId(id, orgId).map(route -> {
            route.deactivate();
            return route; // dirty-checked within the transaction
        });
    }

    /** Hard-delete a route the caller owns. Returns false if not theirs/not found (→ 404). */
    @Transactional
    public boolean delete(UUID orgId, UUID id) {
        return routes.findByIdAndOrgId(id, orgId).map(route -> {
            routes.delete(route);
            return true;
        }).orElse(false);
    }
}
