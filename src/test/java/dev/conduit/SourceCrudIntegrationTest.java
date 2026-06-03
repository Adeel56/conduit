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
 * Source management CRUD integration tests (CON-11). The load-bearing properties pinned here are
 * the security ones: the full ingest key is shown <b>once</b> (create/rotate) and never on
 * list/get; <b>cross-tenant</b> management is blocked with an identical 404; rotation invalidates
 * the old key immediately; and deactivate stops ingest while preserving the source row.
 */
class SourceCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ApiKeyService apiKeys;
    @Autowired
    SourceService sources;

    private String keyFor(UUID orgId) {
        return apiKeys.create(orgId, "key").plaintextKey();
    }

    private HttpHeaders bearer(String fullKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        return headers;
    }

    private HttpEntity<Void> withKey(String fullKey) {
        return new HttpEntity<>(bearer(fullKey));
    }

    private HttpEntity<String> withKeyAndJson(String fullKey, String json) {
        HttpHeaders headers = bearer(fullKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    /** POST /sources and return the parsed 201 body (the one-time view, with the full ingest key). */
    private Map<String, Object> createSource(String key, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/sources", HttpMethod.POST, withKeyAndJson(key, "{\"name\":\"" + name + "\"}"), JSON_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sourcesOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("sources");
    }

    // --- happy-path lifecycle --------------------------------------------------------------------

    @Test
    void createReturnsTheFullIngestKeyAndUrlExactlyOnce() {
        String key = keyFor(newOrg("create-org"));

        Map<String, Object> created = createSource(key, "stripe-prod");

        // The 201 carries the full ingest key + an absolute ingest URL ending in that key.
        String ingestKey = (String) created.get("ingestKey");
        assertThat(ingestKey).isNotBlank();
        assertThat(created.get("name")).isEqualTo("stripe-prod");
        assertThat(created.get("active")).isEqualTo(true);
        assertThat((String) created.get("ingestUrl")).endsWith("/ingest/" + ingestKey);
        UUID id = UUID.fromString((String) created.get("id"));

        // GET the same source: the full key is GONE — only a non-secret prefix remains.
        Map<String, Object> got = rest.exchange(
                "/sources/" + id, HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(got).doesNotContainKey("ingestKey").doesNotContainKey("ingestUrl");
        assertThat(got.get("ingestKeyPrefix")).isEqualTo(ingestKey.substring(0, 8));
        // The prefix is a strict, short slice — not the whole key.
        assertThat((String) got.get("ingestKeyPrefix")).hasSize(8).isNotEqualTo(ingestKey);

        // The freshly created key actually ingests (202) — proves the create response is usable.
        assertThat(rest.exchange("/ingest/" + ingestKey, HttpMethod.POST,
                new HttpEntity<>("{}"), JSON_MAP).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void listReturnsSummariesWithoutTheFullKeyAndIsOrgScoped() {
        UUID orgA = newOrg("list-a");
        UUID orgB = newOrg("list-b");
        String keyA = keyFor(orgA);
        createSource(keyA, "a-1");
        createSource(keyA, "a-2");
        sources.create(orgB, "b-1"); // B's source must never appear in A's list

        Map<String, Object> body = rest.exchange("/sources", HttpMethod.GET, withKey(keyA), JSON_MAP).getBody();

        assertThat(body.get("totalSources")).isEqualTo(2); // only A's
        List<Map<String, Object>> rows = sourcesOf(body);
        assertThat(rows).hasSize(2);
        // No row leaks the full ingest key; each carries the non-secret prefix instead.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r).doesNotContainKey("ingestKey").doesNotContainKey("ingestUrl");
            assertThat(r).containsKey("ingestKeyPrefix");
        });
        assertThat(rows).extracting(r -> r.get("name")).containsExactlyInAnyOrder("a-1", "a-2");
    }

    @Test
    void updateRenamesTheSourceOrgScoped() {
        String key = keyFor(newOrg("rename-org"));
        UUID id = UUID.fromString((String) createSource(key, "old-name").get("id"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/sources/" + id, HttpMethod.PATCH, withKeyAndJson(key, "{\"name\":\"new-name\"}"), JSON_MAP);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("name")).isEqualTo("new-name");
        // Persisted: a subsequent GET shows the new name.
        assertThat(rest.exchange("/sources/" + id, HttpMethod.GET, withKey(key), JSON_MAP)
                .getBody().get("name")).isEqualTo("new-name");
    }

    @Test
    void createWithBlankOrMissingNameReturns400() {
        String key = keyFor(newOrg("validation-org"));
        assertThat(rest.exchange("/sources", HttpMethod.POST, withKeyAndJson(key, "{\"name\":\"   \"}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/sources", HttpMethod.POST, withKeyAndJson(key, "{}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- rotate invalidates the old key ----------------------------------------------------------

    @Test
    void rotateIssuesANewKeyThatIngestsAndInvalidatesTheOldKey() {
        String key = keyFor(newOrg("rotate-org"));
        Map<String, Object> created = createSource(key, "to-rotate");
        UUID id = UUID.fromString((String) created.get("id"));
        String oldKey = (String) created.get("ingestKey");

        // Sanity: the old key ingests before rotation.
        assertThat(rest.exchange("/ingest/" + oldKey, HttpMethod.POST, new HttpEntity<>("{}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map<String, Object>> rotated = rest.exchange(
                "/sources/" + id + "/rotate-key", HttpMethod.POST, withKey(key), JSON_MAP);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newKey = (String) rotated.getBody().get("ingestKey");
        assertThat(newKey).isNotBlank().isNotEqualTo(oldKey);
        assertThat((String) rotated.getBody().get("ingestUrl")).endsWith("/ingest/" + newKey);

        // The OLD key no longer resolves — ingest on it now 404s (immediate invalidation).
        assertThat(rest.exchange("/ingest/" + oldKey, HttpMethod.POST, new HttpEntity<>("{}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The NEW key ingests — 202.
        assertThat(rest.exchange("/ingest/" + newKey, HttpMethod.POST, new HttpEntity<>("{}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // --- deactivate stops ingest, preserves the row ----------------------------------------------

    @Test
    void deactivateStopsIngestButKeepsTheSource() {
        String key = keyFor(newOrg("deactivate-org"));
        Map<String, Object> created = createSource(key, "to-deactivate");
        UUID id = UUID.fromString((String) created.get("id"));
        String ingestKey = (String) created.get("ingestKey");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                "/sources/" + id + "/deactivate", HttpMethod.POST, withKey(key), JSON_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("active")).isEqualTo(false);

        // Ingest to the (now inactive) source 404s — identical to an unknown key (CON-7 behaviour).
        assertThat(rest.exchange("/ingest/" + ingestKey, HttpMethod.POST, new HttpEntity<>("{}"), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // The source row is NOT deleted — it is still gettable (just inactive), preserving history.
        ResponseEntity<Map<String, Object>> got = rest.exchange(
                "/sources/" + id, HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody().get("active")).isEqualTo(false);
    }

    // --- cross-tenant isolation (the heart of the ticket) ----------------------------------------

    @Test
    void crossTenantManagementIsBlockedWithAnIdentical404() {
        UUID orgA = newOrg("xt-a");
        UUID orgB = newOrg("xt-b");
        String keyA = keyFor(orgA);
        // B owns a source; A must not be able to see or mutate it by id.
        Source bSource = sources.create(orgB, "b-private");
        UUID bId = bSource.getId();
        UUID randomId = UUID.randomUUID();

        // GET B's id and a random id both yield BYTE-IDENTICAL 404s (no existence oracle).
        ResponseEntity<String> notYours = rest.exchange(
                "/sources/" + bId, HttpMethod.GET, withKey(keyA), String.class);
        ResponseEntity<String> notFound = rest.exchange(
                "/sources/" + randomId, HttpMethod.GET, withKey(keyA), String.class);
        assertThat(notYours.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notYours.getBody()).isEqualTo(notFound.getBody());
        // ...and the 404 body leaks nothing about B's source (not its name).
        assertThat(notYours.getBody()).contains("not_found").doesNotContain("b-private");

        // Every mutating verb is equally blocked for A against B's source — all 404.
        assertThat(rest.exchange("/sources/" + bId, HttpMethod.PATCH,
                withKeyAndJson(keyA, "{\"name\":\"hijacked\"}"), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/sources/" + bId + "/rotate-key", HttpMethod.POST,
                withKey(keyA), JSON_MAP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/sources/" + bId + "/deactivate", HttpMethod.POST,
                withKey(keyA), JSON_MAP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // And B's source is untouched: still active, still named "b-private", same ingest key.
        Source after = sources.find(orgB, bId).orElseThrow();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getName()).isEqualTo("b-private");
        assertThat(after.getIngestKey()).isEqualTo(bSource.getIngestKey());
    }

    // --- pagination bounds -----------------------------------------------------------------------

    @Test
    void listPageSizeIsClampedToTheHardMax() {
        UUID org = newOrg("paging-org");
        for (int i = 0; i < 105; i++) {
            sources.create(org, "src-" + i);
        }
        String key = keyFor(org);

        Map<String, Object> huge = rest.exchange(
                "/sources?size=1000000", HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(huge.get("size")).isEqualTo(100); // clamped
        assertThat(sourcesOf(huge)).hasSize(100);
        assertThat(huge.get("totalSources")).isEqualTo(105);
        assertThat(huge.get("totalPages")).isEqualTo(2);

        // Default size 20 when unspecified; negative page clamps to 0.
        Map<String, Object> dflt = rest.exchange("/sources", HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(dflt.get("size")).isEqualTo(20);
        assertThat(sourcesOf(dflt)).hasSize(20);
        assertThat(rest.exchange("/sources?page=-1", HttpMethod.GET, withKey(key), JSON_MAP)
                .getBody().get("page")).isEqualTo(0);
    }

    // --- auth gate -------------------------------------------------------------------------------

    @Test
    void noApiKeyReturns401() {
        assertThat(rest.exchange("/sources", HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/sources", HttpMethod.POST, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/sources/" + UUID.randomUUID(), HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
