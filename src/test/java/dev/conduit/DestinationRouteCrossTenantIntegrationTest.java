package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
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
 * The CON-10 security centerpiece: cross-tenant isolation for destinations and routes. Org A must
 * never read, mutate, or wire onto org B's resources, and the rejection must be an <b>identical
 * 404</b> to a non-existent id — never a 403 and never a leak that the resource exists in another
 * tenant (no existence oracle). Building a route across two orgs would be the worst-case bug: a
 * data-isolation hole. Every assertion here pins that it cannot happen.
 */
class DestinationRouteCrossTenantIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ApiKeyService apiKeys;
    @Autowired
    SourceService sources;

    /** A small fixture for one org: its api key, a source, and a destination. */
    private record Tenant(UUID orgId, String key, Source source, String destinationId) {
    }

    private HttpEntity<String> with(String key, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(json, headers);
    }

    private Tenant seedTenant(String name) {
        UUID orgId = newOrg(name);
        String key = apiKeys.create(orgId, name + "-key").plaintextKey();
        Source source = sources.create(orgId, name + "-src");
        String destinationId = (String) rest.exchange("/destinations", HttpMethod.POST,
                        with(key, "{\"name\":\"" + name + "-dest\",\"url\":\"https://" + name + ".example.com/in\"}"),
                        JSON_MAP)
                .getBody().get("id");
        return new Tenant(orgId, key, source, destinationId);
    }

    @Test
    void orgACannotReadOrMutateOrgBsDestination() {
        Tenant a = seedTenant("dest-iso-a");
        Tenant b = seedTenant("dest-iso-b");

        // The control: A asking for a random non-existent id — the exact body we compare against.
        ResponseEntity<String> nonExistent = rest.exchange("/destinations/" + UUID.randomUUID(),
                HttpMethod.GET, with(a.key(), null), String.class);
        assertThat(nonExistent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // GET B's destination as A → byte-identical 404 (existence not revealed).
        ResponseEntity<String> getBs = rest.exchange("/destinations/" + b.destinationId(),
                HttpMethod.GET, with(a.key(), null), String.class);
        assertThat(getBs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getBs.getBody()).isEqualTo(nonExistent.getBody());
        assertThat(getBs.getBody()).doesNotContain("dest-iso-b"); // no leak of B's name/url

        // UPDATE B's destination as A → 404 (and it is NOT actually changed).
        ResponseEntity<String> updateBs = rest.exchange("/destinations/" + b.destinationId(),
                HttpMethod.PUT, with(a.key(), "{\"name\":\"hijacked\",\"url\":\"https://evil.example.com\"}"),
                String.class);
        assertThat(updateBs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // DELETE/deactivate B's destination as A → 404.
        ResponseEntity<String> deleteBs = rest.exchange("/destinations/" + b.destinationId(),
                HttpMethod.DELETE, with(a.key(), null), String.class);
        assertThat(deleteBs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // B confirms its destination is untouched: still active, original name.
        Map<String, Object> stillBs = rest.exchange("/destinations/" + b.destinationId(),
                HttpMethod.GET, with(b.key(), null), JSON_MAP).getBody();
        assertThat(stillBs.get("name")).isEqualTo("dest-iso-b-dest");
        assertThat(stillBs.get("active")).isEqualTo(true);

        // A's list never contains B's destination.
        Map<String, Object> aList = rest.exchange("/destinations", HttpMethod.GET, with(a.key(), null), JSON_MAP)
                .getBody();
        assertThat(aList.get("totalDestinations")).isEqualTo(1);
    }

    @Test
    void orgACannotCreateARouteOntoOrgBsSourceOrDestination() {
        Tenant a = seedTenant("route-iso-a");
        Tenant b = seedTenant("route-iso-b");

        // The control body for an unknown reference.
        String unknownRefBody = rest.exchange("/routes", HttpMethod.POST,
                with(a.key(), "{\"sourceId\":\"" + UUID.randomUUID() + "\",\"destinationId\":\"" + UUID.randomUUID() + "\"}"),
                String.class).getBody();

        // A's own source + B's destination → identical 404 (B's destination exists, but not for A).
        ResponseEntity<String> aSourceBDest = rest.exchange("/routes", HttpMethod.POST,
                with(a.key(), "{\"sourceId\":\"" + a.source().getId() + "\",\"destinationId\":\"" + b.destinationId() + "\"}"),
                String.class);
        assertThat(aSourceBDest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aSourceBDest.getBody()).isEqualTo(unknownRefBody); // no oracle: same as a totally unknown id

        // B's source + A's own destination → identical 404.
        ResponseEntity<String> bSourceADest = rest.exchange("/routes", HttpMethod.POST,
                with(a.key(), "{\"sourceId\":\"" + b.source().getId() + "\",\"destinationId\":\"" + a.destinationId() + "\"}"),
                String.class);
        assertThat(bSourceADest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bSourceADest.getBody()).isEqualTo(unknownRefBody);

        // Both of B's resources → identical 404.
        ResponseEntity<String> bothB = rest.exchange("/routes", HttpMethod.POST,
                with(a.key(), "{\"sourceId\":\"" + b.source().getId() + "\",\"destinationId\":\"" + b.destinationId() + "\"}"),
                String.class);
        assertThat(bothB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bothB.getBody()).isEqualTo(unknownRefBody);

        // No cross-tenant route was created for EITHER org.
        assertThat(rest.exchange("/routes", HttpMethod.GET, with(a.key(), null), JSON_MAP)
                .getBody().get("totalRoutes")).isEqualTo(0);
        assertThat(rest.exchange("/routes", HttpMethod.GET, with(b.key(), null), JSON_MAP)
                .getBody().get("totalRoutes")).isEqualTo(0);
    }

    @Test
    void orgACannotReadOrDeleteOrgBsRoute() {
        Tenant a = seedTenant("route-read-a");
        Tenant b = seedTenant("route-read-b");

        // B creates a legitimate, same-org route of its own.
        String bRouteId = (String) rest.exchange("/routes", HttpMethod.POST,
                        with(b.key(), "{\"sourceId\":\"" + b.source().getId() + "\",\"destinationId\":\"" + b.destinationId() + "\"}"),
                        JSON_MAP)
                .getBody().get("id");

        // Control body for an unknown route id, as A.
        String unknownRouteBody = rest.exchange("/routes/" + UUID.randomUUID(),
                HttpMethod.GET, with(a.key(), null), String.class).getBody();

        // A GET-ing B's route id → identical 404.
        ResponseEntity<String> getBRoute =
                rest.exchange("/routes/" + bRouteId, HttpMethod.GET, with(a.key(), null), String.class);
        assertThat(getBRoute.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getBRoute.getBody()).isEqualTo(unknownRouteBody);

        // A deactivating B's route → 404.
        assertThat(rest.exchange("/routes/" + bRouteId + "/deactivate", HttpMethod.POST, with(a.key(), null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A deleting B's route → 404.
        assertThat(rest.exchange("/routes/" + bRouteId, HttpMethod.DELETE, with(a.key(), null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // B's route survives, still active and visible only to B.
        Map<String, Object> bStill =
                rest.exchange("/routes/" + bRouteId, HttpMethod.GET, with(b.key(), null), JSON_MAP).getBody();
        assertThat(bStill.get("id")).isEqualTo(bRouteId);
        assertThat(bStill.get("active")).isEqualTo(true);
        assertThat(rest.exchange("/routes", HttpMethod.GET, with(a.key(), null), JSON_MAP)
                .getBody().get("totalRoutes")).isEqualTo(0); // A sees none of B's routes
    }
}
