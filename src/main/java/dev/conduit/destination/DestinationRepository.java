package dev.conduit.destination;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DestinationRepository extends JpaRepository<Destination, UUID> {

    /**
     * Org-scoped, paginated list. The org filter is in the query, so a caller only ever sees its own
     * destinations. Sorting/paging come from the {@link Pageable}.
     */
    Page<Destination> findByOrgId(UUID orgId, Pageable pageable);

    /**
     * Tenant scope is enforced IN the query: an id belonging to another org returns empty exactly
     * like a non-existent id, so the controller returns an identical 404 (never 403, never a
     * find-then-check-in-Java that would load another org's row first).
     */
    Optional<Destination> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Used by route creation to confirm the referenced destination exists AND belongs to the caller's
     * org before wiring a route to it — same-org validation without an existence oracle.
     */
    boolean existsByIdAndOrgId(UUID id, UUID orgId);
}
