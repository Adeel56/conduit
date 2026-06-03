package dev.conduit.source;

/**
 * Request bodies for source management (CON-11). Validation is performed in the controller (the
 * codebase deliberately validates defensively in handlers rather than adding a validation starter):
 * a blank or missing {@code name} is a 400, and the value is trimmed before use.
 */
public final class SourceRequests {

    private SourceRequests() {
    }

    /** {@code POST /sources} body. */
    public record Create(String name) {
    }

    /** {@code PATCH /sources/{id}} body (rename). */
    public record Update(String name) {
    }
}
