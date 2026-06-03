package dev.conduit.route;

/**
 * Thrown when a route create references a source or destination that does not exist <em>or</em> is
 * not the caller's org. Both cases are deliberately collapsed into ONE exception so the controller
 * returns a single, identical 404 — it never reveals whether the id exists in another tenant (no
 * cross-tenant existence oracle). The message is generic and carries no tenant data.
 */
public class RouteReferenceNotFoundException extends RuntimeException {

    public RouteReferenceNotFoundException() {
        super("source or destination not found");
    }
}
