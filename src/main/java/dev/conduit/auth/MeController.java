package dev.conduit.auth;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal protected endpoint that echoes the authenticated caller's identity — proof that the API
 * key resolved to the right organization, and the first thing a client can call to verify its key.
 * It is the smallest real "protected route" the auth foundation needs; the Inspector (next ticket)
 * adds the real dashboard endpoints that filter by {@code principal.orgId()}.
 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal ApiKeyPrincipal principal) {
        return Map.of(
                "orgId", principal.orgId(),
                "apiKeyId", principal.apiKeyId(),
                "name", principal.name(),
                "scopes", principal.scopes());
    }
}
