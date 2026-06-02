package dev.conduit.auth;

import dev.conduit.apikey.ApiKey;
import dev.conduit.apikey.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves a caller from an {@code Authorization: Bearer <key>} header and, on success, puts an
 * {@link ApiKeyPrincipal} into the {@link SecurityContextHolder}.
 *
 * <p>Deliberately non-failing: if there is no key, or the key is malformed/invalid/revoked, the
 * filter simply sets no authentication and lets the request continue. The authorization rules then
 * decide — a public route (/health, /ingest/**) proceeds; a protected route returns 401 via the
 * entry point. This is also why a stray/invalid {@code Authorization} header never breaks ingest.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented = bearerToken(request);
            if (presented != null) {
                apiKeyService.authenticate(presented).ifPresent(key -> authenticate(key, request));
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(ApiKey key, HttpServletRequest request) {
        Set<String> scopes = parseScopes(key.getScopes());
        List<SimpleGrantedAuthority> authorities = scopes.stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .collect(Collectors.toList());
        ApiKeyPrincipal principal = new ApiKeyPrincipal(key.getOrgId(), key.getId(), key.getName(), scopes);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private static Set<String> parseScopes(String scopes) {
        return Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
