package dev.conduit.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source lifecycle logic (CON-7 create + CON-11 management). Every mutating/reading method here is
 * strictly tenant-scoped: the {@code orgId} is supplied by the caller (the controller, from the
 * authenticated principal — never from request input), and every lookup filters by it, so org A can
 * never touch org B's source. Lookups return an empty {@link Optional} for both "not found" and
 * "not yours", which the controller turns into an identical 404 (no existence oracle).
 *
 * <p>The original {@link #create(UUID, String)} seam is still used by the ingest integration tests.
 */
@Service
public class SourceService {

    private final SourceRepository sources;
    private final IngestKeyGenerator ingestKeys;

    public SourceService(SourceRepository sources, IngestKeyGenerator ingestKeys) {
        this.sources = sources;
        this.ingestKeys = ingestKeys;
    }

    /**
     * Create a source with a freshly generated, high-entropy ingest key. The returned entity still
     * holds the full key in memory so the controller can show it to the caller exactly once; it is
     * never returned again on list/get (only its non-secret prefix is).
     */
    @Transactional
    public Source create(UUID orgId, String name) {
        return sources.save(new Source(orgId, name, ingestKeys.generate()));
    }

    /** A single org-owned source, or empty if it does not exist or belongs to another org. */
    @Transactional(readOnly = true)
    public Optional<Source> find(UUID orgId, UUID id) {
        return sources.findByIdAndOrgId(id, orgId);
    }

    /** The org's sources, newest-first, paginated. */
    @Transactional(readOnly = true)
    public Page<Source> list(UUID orgId, Pageable pageable) {
        return sources.findByOrgIdOrderByCreatedAtDescIdDesc(orgId, pageable);
    }

    /**
     * Rename an org-owned source. Returns the updated entity, or empty if the source does not exist
     * or is not the caller's (→ 404). Renaming is metadata only; it does not affect ingest routing.
     */
    @Transactional
    public Optional<Source> rename(UUID orgId, UUID id, String name) {
        return sources.findByIdAndOrgId(id, orgId).map(source -> {
            source.rename(name);
            return source; // flushed on commit (dirty checking)
        });
    }

    /**
     * Rotate an org-owned source's ingest key: generate a new high-entropy key and overwrite the old
     * one in place. The old key stops resolving immediately (ingest on it then 404s). Returns the
     * updated entity (holding the new full key for the one-time response), or empty if not the
     * caller's source (→ 404).
     */
    @Transactional
    public Optional<Source> rotateIngestKey(UUID orgId, UUID id) {
        return sources.findByIdAndOrgId(id, orgId).map(source -> {
            source.rotateIngestKey(ingestKeys.generate());
            return source; // flushed on commit
        });
    }

    /**
     * Deactivate an org-owned source. Ingest to its key then 404s (CON-7), while the row and its
     * immutable events are preserved — we never hard-delete (that would orphan event history).
     * Returns the updated entity, or empty if not the caller's source (→ 404). Idempotent: calling
     * it on an already-inactive source leaves it inactive.
     */
    @Transactional
    public Optional<Source> deactivate(UUID orgId, UUID id) {
        return sources.findByIdAndOrgId(id, orgId).map(source -> {
            source.deactivate();
            return source; // flushed on commit
        });
    }
}
