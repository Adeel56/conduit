package dev.conduit.source;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source-creation logic. Real production code (the future source-management CRUD ticket will expose
 * it over HTTP); for now it is the minimal, secure seam the ingest integration tests use to create
 * a {@link Source} with a freshly generated ingest key — so we ship NO unauthenticated
 * source-creation endpoint in this ticket.
 */
@Service
public class SourceService {

    private final SourceRepository sources;
    private final IngestKeyGenerator ingestKeys;

    public SourceService(SourceRepository sources, IngestKeyGenerator ingestKeys) {
        this.sources = sources;
        this.ingestKeys = ingestKeys;
    }

    @Transactional
    public Source create(UUID orgId, String name) {
        return sources.save(new Source(orgId, name, ingestKeys.generate()));
    }
}
