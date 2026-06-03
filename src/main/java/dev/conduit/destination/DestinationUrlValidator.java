package dev.conduit.destination;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Structural validation of a user-supplied destination URL (CON-10). A destination must be an
 * <b>absolute http/https URL with a host</b>; anything else is rejected as a client error.
 *
 * <p><b>Scope (deliberate):</b> this is structural validation only. SSRF hardening — resolve-then-
 * validate and blocking of private / link-local / loopback / cloud-metadata ranges to stop the
 * outbound request from reaching internal services — is a separate follow-up ticket (the CON-10
 * ticket explicitly defers it). We record that here so the gap is conscious, not forgotten: a URL
 * that passes this check may still point at an internal address; the delivery engine (CON-13) and
 * the SSRF ticket are where that is closed before any request actually goes out.
 */
public final class DestinationUrlValidator {

    private DestinationUrlValidator() {
    }

    /**
     * @return true if {@code url} is a syntactically valid absolute http/https URL with a host.
     */
    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https");
    }
}
