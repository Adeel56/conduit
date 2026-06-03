package dev.conduit.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    /**
     * Resolve a source by its ingest key, but only if it is active. An unknown key and an inactive
     * key both yield an empty Optional, so the ingest endpoint returns an identical 404 for either
     * — it never reveals whether a key exists.
     */
    Optional<Source> findByIngestKeyAndActiveTrue(String ingestKey);

    // --- Source management (CON-11), all strictly org-scoped --------------------------------------

    /**
     * Resolve a source by id, but only within the caller's org. A source belonging to another org
     * returns empty exactly like a non-existent id, so the controller emits an identical 404 (never
     * 403) — cross-tenant access is indistinguishable from not-found, leaking no existence oracle.
     */
    Optional<Source> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The caller's org's sources, newest-first, paginated. Returns full entities (a source row is
     * small — unlike an event payload — so no projection is needed); the controller maps each to a
     * summary that omits the full ingest key. ORDER BY is fixed here for deterministic paging (the
     * id tiebreaker makes the order total when created_at ties), so callers pass an UNSORTED
     * Pageable. Uses the V2 ix_sources_org_id index for the org filter.
     */
    Page<Source> findByOrgIdOrderByCreatedAtDescIdDesc(UUID orgId, Pageable pageable);
}
