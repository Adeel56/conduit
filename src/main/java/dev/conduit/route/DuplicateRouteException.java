package dev.conduit.route;

/**
 * Thrown when a route already wires this (source, destination) pair — the unique constraint
 * {@code ux_routes_source_destination} would otherwise reject the insert. Both ids are the caller's
 * own (same-org is validated first), so reporting the conflict leaks nothing across tenants → 409.
 */
public class DuplicateRouteException extends RuntimeException {

    public DuplicateRouteException() {
        super("a route already exists for this source and destination");
    }
}
