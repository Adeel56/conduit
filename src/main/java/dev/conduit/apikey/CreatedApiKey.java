package dev.conduit.apikey;

/**
 * Result of creating an API key. {@link #plaintextKey} is the full {@code cdt_<prefix>.<secret>}
 * value — it is shown to the caller exactly once here and is never recoverable afterwards (only its
 * hash is stored). Never log it.
 */
public record CreatedApiKey(ApiKey apiKey, String plaintextKey) {
}
