package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
import dev.conduit.apikey.CreatedApiKey;
import dev.conduit.source.Source;
import dev.conduit.source.SourceService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests for API-key auth + tenant resolution (CON-8). Boots the full app (now with
 * Spring Security) against the shared Testcontainers Postgres. Covers the 401 paths, valid-key org
 * resolution, revocation, the cross-tenant scaffold, and — critically — that ingest and health stay
 * public with no key.
 */
class ApiKeyAuthIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApiKeyService apiKeys;

    @Autowired
    SourceService sources;

    private HttpEntity<Void> withKey(String fullKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<Map<String, Object>> getMe(HttpEntity<?> entity) {
        return rest.exchange("/api/me", HttpMethod.GET, entity, JSON_MAP);
    }

    @Test
    void validKeyAuthenticatesAndResolvesTheOwningOrg() {
        UUID orgId = newOrg("acme");
        CreatedApiKey created = apiKeys.create(orgId, "dashboard-key");

        ResponseEntity<Map<String, Object>> response = getMe(withKey(created.plaintextKey()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("orgId")).isEqualTo(orgId.toString());
        assertThat(response.getBody().get("apiKeyId")).isEqualTo(created.apiKey().getId().toString());
    }

    @Test
    void missingKeyOnAProtectedRouteReturns401() {
        ResponseEntity<Map<String, Object>> response = getMe(HttpEntity.EMPTY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedOrUnknownKeyReturns401() {
        // Well-formed shape but no such key:
        assertThat(getMe(withKey("cdt_unknownprefix.totallybogussecret")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Not even the right shape:
        assertThat(getMe(withKey("not-a-conduit-key")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokedKeyReturns401() {
        CreatedApiKey created = apiKeys.create(newOrg("revoke-org"), "to-be-revoked");
        apiKeys.revoke(created.apiKey().getId());

        ResponseEntity<Map<String, Object>> response = getMe(withKey(created.plaintextKey()));

        // Identical 401 to an unknown key — revocation is immediate and not distinguishable.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ingestStaysPublicWithNoApiKey() {
        Source source = sources.create(newOrg("ingest-org"), "stripe-prod");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Authorization header at all.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/ingest/" + source.getIngestKey(), HttpMethod.POST,
                new HttpEntity<>("{\"hello\":\"world\"}", headers), JSON_MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // 202 — auth did not lock it down
    }

    @Test
    void healthStaysPublicWithNoApiKey() {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aKeyResolvesItsOwnOrgNotAnother() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        CreatedApiKey keyA = apiKeys.create(orgA, "org-a-key");
        CreatedApiKey keyB = apiKeys.create(orgB, "org-b-key");

        Object resolvedForA = getMe(withKey(keyA.plaintextKey())).getBody().get("orgId");
        Object resolvedForB = getMe(withKey(keyB.plaintextKey())).getBody().get("orgId");

        // The scaffold tenant isolation builds on: each key resolves only its own org.
        assertThat(resolvedForA).isEqualTo(orgA.toString());
        assertThat(resolvedForB).isEqualTo(orgB.toString());
        assertThat(resolvedForA).isNotEqualTo(resolvedForB);
    }
}
