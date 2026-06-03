package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
import dev.conduit.source.Source;
import dev.conduit.source.SourceService;
import java.util.List;
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
 * Route CRUD integration tests (CON-10): create a route wiring the caller's own source and
 * destination, list (optionally by source), get, deactivate, hard-delete, and the duplicate-route
 * rejection enforced by the {@code (source_id, destination_id)} unique constraint. Cross-tenant
 * rejection is in {@link DestinationRouteCrossTenantIntegrationTest}.
 */
class RouteCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ApiKeyService apiKeys;
    @Autowired
    SourceService sources;

    private HttpEntity<String> withKey(String fullKey, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(jsonBody, headers);
    }

    private String createDestination(String key, String name, String url) {
        return (String) rest.exchange("/destinations", HttpMethod.POST,
                withKey(key, "{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"), JSON_MAP)
                .getBody().get("id");
    }

    private String routeBody(UUID sourceId, String destinationId) {
        return "{\"sourceId\":\"" + sourceId + "\",\"destinationId\":\"" + destinationId + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routesOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("routes");
    }

    @Test
    void createListGetDeactivateDeleteHappyPath() {
        UUID org = newOrg("route-crud");
        String key = apiKeys.create(org, "k").plaintextKey();
        Source src = sources.create(org, "src");
        String dest = createDestination(key, "d", "https://hooks.example.com/in");

        // CREATE
        ResponseEntity<Map<String, Object>> created = rest.exchange("/routes", HttpMethod.POST,
                withKey(key, routeBody(src.getId(), dest)), JSON_MAP);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("sourceId")).isEqualTo(src.getId().toString());
        assertThat(created.getBody().get("destinationId")).isEqualTo(dest);
        assertThat(created.getBody().get("active")).isEqualTo(true);
        assertThat(created.getBody()).doesNotContainKey("orgId");
        String routeId = (String) created.getBody().get("id");

        // LIST (all)
        ResponseEntity<Map<String, Object>> listed =
                rest.exchange("/routes", HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(listed.getBody().get("totalRoutes")).isEqualTo(1);
        assertThat(routesOf(listed.getBody())).extracting(r -> r.get("id")).containsExactly(routeId);

        // LIST by the caller's own source returns it.
        ResponseEntity<Map<String, Object>> bySource =
                rest.exchange("/routes?sourceId=" + src.getId(), HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(bySource.getBody().get("totalRoutes")).isEqualTo(1);

        // LIST by a random (foreign/unknown) source id returns an EMPTY page — never an error/oracle.
        ResponseEntity<Map<String, Object>> byUnknownSource =
                rest.exchange("/routes?sourceId=" + UUID.randomUUID(), HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(byUnknownSource.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byUnknownSource.getBody().get("totalRoutes")).isEqualTo(0);

        // GET by id.
        assertThat(rest.exchange("/routes/" + routeId, HttpMethod.GET, withKey(key, null), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // DEACTIVATE (soft).
        ResponseEntity<Map<String, Object>> deactivated = rest.exchange("/routes/" + routeId + "/deactivate",
                HttpMethod.POST, withKey(key, null), JSON_MAP);
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivated.getBody().get("active")).isEqualTo(false);

        // DELETE (hard) → 204, then gone.
        assertThat(rest.exchange("/routes/" + routeId, HttpMethod.DELETE, withKey(key, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("/routes/" + routeId, HttpMethod.GET, withKey(key, null), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateRouteIsRejectedByTheUniqueConstraint() {
        UUID org = newOrg("route-dup");
        String key = apiKeys.create(org, "k").plaintextKey();
        Source src = sources.create(org, "src");
        String dest = createDestination(key, "d", "https://dup.example.com/in");

        ResponseEntity<Map<String, Object>> first = rest.exchange("/routes", HttpMethod.POST,
                withKey(key, routeBody(src.getId(), dest)), JSON_MAP);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same (source, destination) again → 409, not a duplicate row.
        ResponseEntity<Map<String, Object>> second = rest.exchange("/routes", HttpMethod.POST,
                withKey(key, routeBody(src.getId(), dest)), JSON_MAP);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("error")).isEqualTo("duplicate_route");

        // Exactly one route exists.
        assertThat(rest.exchange("/routes", HttpMethod.GET, withKey(key, null), JSON_MAP)
                .getBody().get("totalRoutes")).isEqualTo(1);
    }

    @Test
    void createWithUnknownSourceOrDestinationReturns404() {
        UUID org = newOrg("route-missing-ref");
        String key = apiKeys.create(org, "k").plaintextKey();
        Source src = sources.create(org, "src");
        String dest = createDestination(key, "d", "https://ref.example.com/in");

        // Unknown destination id (does not exist anywhere) → identical 404.
        ResponseEntity<String> unknownDest = rest.exchange("/routes", HttpMethod.POST,
                withKey(key, "{\"sourceId\":\"" + src.getId() + "\",\"destinationId\":\"" + UUID.randomUUID() + "\"}"),
                String.class);
        assertThat(unknownDest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownDest.getBody()).contains("not_found");

        // Unknown source id → identical 404.
        ResponseEntity<String> unknownSource = rest.exchange("/routes", HttpMethod.POST,
                withKey(key, "{\"sourceId\":\"" + UUID.randomUUID() + "\",\"destinationId\":\"" + dest + "\"}"),
                String.class);
        assertThat(unknownSource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownSource.getBody()).contains("not_found");
    }

    @Test
    void noApiKeyReturns401() {
        assertThat(rest.exchange("/routes", HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/routes", HttpMethod.POST, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
