package dev.conduit.route;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    /** Org-scoped, paginated list of all routes for the caller's org. */
    Page<Route> findByOrgId(UUID orgId, Pageable pageable);

    /**
     * Org-scoped, paginated list filtered to one source. The org filter stays in the query, so
     * passing another org's source id matches nothing (an empty page, not an oracle).
     */
    Page<Route> findByOrgIdAndSourceId(UUID orgId, UUID sourceId, Pageable pageable);

    /**
     * Tenant scope enforced IN the query: another org's route id returns empty exactly like a
     * non-existent id → identical 404, no existence leak.
     */
    Optional<Route> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Pre-check for the duplicate-wiring case so we can return a clean 409 instead of surfacing the
     * raw unique-constraint violation. The unique index on (source_id, destination_id) is the real
     * guarantee; this is the friendly check in front of it.
     */
    boolean existsBySourceIdAndDestinationId(UUID sourceId, UUID destinationId);
}
