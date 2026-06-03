package dev.conduit.route;

import dev.conduit.source.Source;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read-only lookup of {@code sources} for route creation, owned by this (route) package so we do not
 * edit the source-management package (CON-11's lane, ADR-0009). It reads the existing {@code Source}
 * entity purely to answer one question: does this source exist AND belong to the caller's org?
 *
 * <p>Used to enforce same-org route creation: a route may only join a source that is the caller's,
 * and the answer is given as a boolean so it never leaks the existence of another org's source.
 */
public interface RouteSourceLookup extends JpaRepository<Source, UUID> {

    boolean existsByIdAndOrgId(UUID id, UUID orgId);
}
