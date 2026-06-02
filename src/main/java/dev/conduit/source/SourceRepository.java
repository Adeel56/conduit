package dev.conduit.source;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    /**
     * Resolve a source by its ingest key, but only if it is active. An unknown key and an inactive
     * key both yield an empty Optional, so the ingest endpoint returns an identical 404 for either
     * — it never reveals whether a key exists.
     */
    Optional<Source> findByIngestKeyAndActiveTrue(String ingestKey);
}
