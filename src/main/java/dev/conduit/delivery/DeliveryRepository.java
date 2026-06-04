package dev.conduit.delivery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Delivery}. Holds the worker's atomic claim query (write side) and the
 * org-scoped finders (read API). This interface is the shared contract for CON-13 — complete as-is;
 * the engine and read-API code build on it without modifying it.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /**
     * <b>The concurrency crux.</b> Atomically claim up to {@code limit} due, pending deliveries:
     * {@code FOR UPDATE} row-locks the selected rows and {@code SKIP LOCKED} makes a concurrent worker
     * step over rows another worker has already locked (instead of blocking). So two workers running
     * this at the same instant get <em>disjoint</em> sets — no row is claimed twice. Must be called
     * inside a transaction; the caller flips each returned row to {@code in_flight} and commits, after
     * which the lock releases and the now-non-pending row is invisible to the next claim.
     */
    @Query(value = """
            SELECT * FROM deliveries
            WHERE status = 'pending' AND next_attempt_at <= now()
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Delivery> claimDue(@Param("limit") int limit);

    /** Tenant scope IN the query: another org's id returns empty exactly like a missing id (→ 404). */
    Optional<Delivery> findByIdAndOrgId(UUID id, UUID orgId);

    Page<Delivery> findByOrgId(UUID orgId, Pageable pageable);

    Page<Delivery> findByOrgIdAndStatus(UUID orgId, DeliveryStatus status, Pageable pageable);

    Page<Delivery> findByOrgIdAndEventId(UUID orgId, UUID eventId, Pageable pageable);

    Page<Delivery> findByOrgIdAndEventIdAndStatus(UUID orgId, UUID eventId, DeliveryStatus status,
                                                  Pageable pageable);
}
