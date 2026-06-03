package dev.conduit.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    // --- Inspector list (CON-9) ---------------------------------------------------------------
    // Org-scoped, newest-first, paginated SUMMARIES. The explicit constructor expression selects
    // only id/source_id/received_at and computes the byte size with octet_length(payload) — the
    // (large) payload and headers columns are never read for the list. ORDER BY is fixed in the
    // query (received_at DESC + id tiebreaker for deterministic paging), so callers pass an
    // UNSORTED Pageable. The countQuery repeats the org filter so the total stays org-scoped.
    // Uses the V2 (org_id, source_id, received_at) index: org-only -> index range scan on org_id
    // + bounded top-N sort; org+source -> index scan backward, no sort.

    // Byte size via byte_length(payload) -> octet_length(payload) (see BinaryLengthFunctionContributor;
    // Hibernate's built-in octet_length() rejects byte[]). Entity attributes keep their correct Java
    // types (e.g. receivedAt -> Instant), so no native-query type fragility. The payload/headers
    // columns are never selected here, only their byte size.
    @Query(value = """
            SELECT new dev.conduit.event.EventSummary(
                e.id, e.sourceId, byte_length(e.payload), e.receivedAt)
            FROM Event e
            WHERE e.orgId = :orgId
            ORDER BY e.receivedAt DESC, e.id DESC
            """,
            countQuery = "SELECT count(e) FROM Event e WHERE e.orgId = :orgId")
    Page<EventSummary> findSummariesByOrgId(@Param("orgId") UUID orgId, Pageable pageable);

    @Query(value = """
            SELECT new dev.conduit.event.EventSummary(
                e.id, e.sourceId, byte_length(e.payload), e.receivedAt)
            FROM Event e
            WHERE e.orgId = :orgId AND e.sourceId = :sourceId
            ORDER BY e.receivedAt DESC, e.id DESC
            """,
            countQuery = "SELECT count(e) FROM Event e WHERE e.orgId = :orgId AND e.sourceId = :sourceId")
    Page<EventSummary> findSummariesByOrgIdAndSourceId(@Param("orgId") UUID orgId,
                                                       @Param("sourceId") UUID sourceId,
                                                       Pageable pageable);

    // --- Inspector detail (CON-9) -------------------------------------------------------------
    // Tenant scope is enforced IN the query: an id belonging to another org returns empty exactly
    // like a non-existent id, so the controller returns an identical 404 (never 403, never a
    // find-then-check-in-Java that would load another org's payload).
    Optional<Event> findByIdAndOrgId(UUID id, UUID orgId);
}
