package dev.conduit.auth;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, as resolved from a valid API key. {@link #orgId} is the load-bearing
 * field: every tenant-scoped handler filters by it. Exposed to controllers via
 * {@code @AuthenticationPrincipal ApiKeyPrincipal}.
 */
public record ApiKeyPrincipal(UUID orgId, UUID apiKeyId, String name, Set<String> scopes) {
}
