package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
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
 * Destination CRUD integration tests (CON-10). Covers the happy-path lifecycle, URL validation,
 * pagination bounds, and the auth gate. Cross-tenant isolation for destinations lives (together with
 * routes) in {@link DestinationRouteCrossTenantIntegrationTest} — the security centerpiece.
 */
class DestinationCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ApiKeyService apiKeys;

    private HttpEntity<String> withKey(String fullKey, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(jsonBody, headers);
    }

    private String keyFor(String orgName) {
        return apiKeys.create(newOrg(orgName), orgName + "-key").plaintextKey();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> destinationsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("destinations");
    }

    @Test
    void createListGetUpdateDeactivateHappyPath() {
        String key = keyFor("dest-crud");

        // CREATE
        ResponseEntity<Map<String, Object>> created = rest.exchange("/destinations", HttpMethod.POST,
                withKey(key, "{\"name\":\"Stripe relay\",\"url\":\"https://hooks.example.com/in\"}"), JSON_MAP);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("name")).isEqualTo("Stripe relay");
        assertThat(created.getBody().get("url")).isEqualTo("https://hooks.example.com/in");
        assertThat(created.getBody().get("active")).isEqualTo(true);
        // org_id is never echoed back to the client.
        assertThat(created.getBody()).doesNotContainKey("orgId");
        String id = (String) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation().toString()).isEqualTo("/destinations/" + id);

        // LIST shows it.
        ResponseEntity<Map<String, Object>> listed =
                rest.exchange("/destinations", HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(listed.getBody().get("totalDestinations")).isEqualTo(1);
        assertThat(destinationsOf(listed.getBody())).extracting(d -> d.get("id")).containsExactly(id);

        // GET by id.
        ResponseEntity<Map<String, Object>> got =
                rest.exchange("/destinations/" + id, HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody().get("id")).isEqualTo(id);

        // UPDATE name + url.
        ResponseEntity<Map<String, Object>> updated = rest.exchange("/destinations/" + id, HttpMethod.PUT,
                withKey(key, "{\"name\":\"Renamed\",\"url\":\"https://new.example.com/x\"}"), JSON_MAP);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("name")).isEqualTo("Renamed");
        assertThat(updated.getBody().get("url")).isEqualTo("https://new.example.com/x");

        // DEACTIVATE (soft delete).
        ResponseEntity<Map<String, Object>> deactivated =
                rest.exchange("/destinations/" + id, HttpMethod.DELETE, withKey(key, null), JSON_MAP);
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivated.getBody().get("active")).isEqualTo(false);

        // Still gettable (soft), and still listed — deactivation does not delete.
        ResponseEntity<Map<String, Object>> afterDelete =
                rest.exchange("/destinations/" + id, HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(afterDelete.getBody().get("active")).isEqualTo(false);
    }

    @Test
    void invalidUrlsAreRejectedWith400() {
        String key = keyFor("dest-url");
        for (String badUrl : List.of(
                "not-a-url", "ftp://files.example.com", "/relative/path", "example.com", "javascript:alert(1)")) {
            ResponseEntity<Map<String, Object>> resp = rest.exchange("/destinations", HttpMethod.POST,
                    withKey(key, "{\"name\":\"d\",\"url\":\"" + badUrl + "\"}"), JSON_MAP);
            assertThat(resp.getStatusCode())
                    .as("url=%s should be rejected", badUrl)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().get("error")).isEqualTo("invalid_destination");
        }
        // A blank name is also a 400.
        ResponseEntity<Map<String, Object>> blankName = rest.exchange("/destinations", HttpMethod.POST,
                withKey(key, "{\"name\":\"  \",\"url\":\"https://ok.example.com\"}"), JSON_MAP);
        assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validHttpAndHttpsUrlsAreAccepted() {
        String key = keyFor("dest-ok-url");
        for (String okUrl : List.of(
                "http://plain.example.com", "https://secure.example.com/path?q=1", "https://example.com:8443/in")) {
            ResponseEntity<Map<String, Object>> resp = rest.exchange("/destinations", HttpMethod.POST,
                    withKey(key, "{\"name\":\"d\",\"url\":\"" + okUrl + "\"}"), JSON_MAP);
            assertThat(resp.getStatusCode()).as("url=%s should be accepted", okUrl)
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void getUnknownIdReturns404() {
        String key = keyFor("dest-404");
        ResponseEntity<String> resp =
                rest.exchange("/destinations/" + UUID.randomUUID(), HttpMethod.GET, withKey(key, null), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("not_found");
    }

    @Test
    void pageSizeIsClampedToTheHardMax() {
        String key = keyFor("dest-page");
        for (int i = 0; i < 105; i++) {
            rest.exchange("/destinations", HttpMethod.POST,
                    withKey(key, "{\"name\":\"d" + i + "\",\"url\":\"https://h" + i + ".example.com\"}"), JSON_MAP);
        }
        // size over the cap clamps to 100.
        ResponseEntity<Map<String, Object>> huge =
                rest.exchange("/destinations?size=1000000", HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(huge.getBody().get("size")).isEqualTo(100);
        assertThat(destinationsOf(huge.getBody())).hasSize(100);
        assertThat(huge.getBody().get("totalDestinations")).isEqualTo(105);
        assertThat(huge.getBody().get("totalPages")).isEqualTo(2);

        // Default size is 20.
        ResponseEntity<Map<String, Object>> dflt =
                rest.exchange("/destinations", HttpMethod.GET, withKey(key, null), JSON_MAP);
        assertThat(dflt.getBody().get("size")).isEqualTo(20);
        assertThat(destinationsOf(dflt.getBody())).hasSize(20);

        // Page 1 advances the window and is disjoint from page 0.
        List<Object> page0 = destinationsOf(rest.exchange("/destinations?size=100&page=0",
                HttpMethod.GET, withKey(key, null), JSON_MAP).getBody()).stream().map(d -> d.get("id")).toList();
        Map<String, Object> page1Body =
                rest.exchange("/destinations?size=100&page=1", HttpMethod.GET, withKey(key, null), JSON_MAP).getBody();
        assertThat(page1Body.get("page")).isEqualTo(1);
        assertThat(destinationsOf(page1Body)).hasSize(5);
        assertThat(destinationsOf(page1Body)).extracting(d -> d.get("id")).doesNotContainAnyElementsOf(page0);
    }

    @Test
    void noApiKeyReturns401() {
        assertThat(rest.exchange("/destinations", HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/destinations", HttpMethod.POST, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
