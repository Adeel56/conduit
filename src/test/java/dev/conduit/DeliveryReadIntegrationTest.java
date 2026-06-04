package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
import dev.conduit.destination.Destination;
import dev.conduit.destination.DestinationRequest;
import dev.conduit.destination.DestinationService;
import dev.conduit.route.Route;
import dev.conduit.route.RouteRequest;
import dev.conduit.route.RouteService;
import dev.conduit.source.Source;
import dev.conduit.source.SourceService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deliveries read-API integration tests (CON-13). The worker/fan-out lives in a sibling change, so we
 * cannot produce deliveries by ingesting — instead we seed {@code deliveries}/{@code attempts} rows
 * directly via {@link JdbcTemplate} (controlled org_id/status/created_at), exactly as the CON-9
 * inspector test seeds events. The <b>cross-tenant isolation</b> test is the centerpiece: org A's key
 * sees only A's deliveries and gets a byte-identical 404 for B's delivery id.
 */
class DeliveryReadIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ApiKeyService apiKeys;
    @Autowired
    SourceService sources;
    @Autowired
    DestinationService destinations;
    @Autowired
    RouteService routes;
    @Autowired
    JdbcTemplate jdbc;

    private HttpEntity<Void> withKey(String fullKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        return new HttpEntity<>(headers);
    }

    /** The parent rows a delivery's FKs require: source, destination, route, event — all org-owned. */
    private record Wiring(UUID sourceId, UUID destinationId, UUID routeId, UUID eventId) {
    }

    /** Stand up a full org-owned wiring (source→destination route + one event) for the given org. */
    private Wiring seedWiring(UUID orgId, String slug) {
        Source source = sources.create(orgId, "src-" + slug);
        Destination destination = destinations.create(orgId,
                new DestinationRequest("dst-" + slug, "https://example.test/" + slug));
        Route route = routes.create(orgId, new RouteRequest(source.getId(), destination.getId()));
        UUID eventId = seedEvent(orgId, source.getId());
        return new Wiring(source.getId(), destination.getId(), route.getId(), eventId);
    }

    /** Seed an immutable event row directly (the inspector pattern) so deliveries have a real FK target. */
    private UUID seedEvent(UUID orgId, UUID sourceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id, org_id, source_id, payload, headers, received_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, now(), now())",
                id, orgId, sourceId, "payload".getBytes(StandardCharsets.UTF_8), "{\"X-Test\":\"1\"}");
        return id;
    }

    /** Insert a delivery row with fully controlled org/status/created_at so ordering is deterministic. */
    private UUID seedDelivery(UUID orgId, Wiring w, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO deliveries (id, org_id, event_id, route_id, destination_id, status, "
                        + "attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, orgId, w.eventId(), w.routeId(), w.destinationId(), status,
                0, Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    /** Append one attempt row to a delivery with a controlled attempted_at (drives detail ordering). */
    private UUID seedAttempt(UUID deliveryId, Integer responseStatus, String error, long durationMs,
                             Instant attemptedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO attempts (id, delivery_id, attempted_at, response_status, error, duration_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, deliveryId, Timestamp.from(attemptedAt), responseStatus, error, durationMs);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deliveriesOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("deliveries");
    }

    @Test
    void listReturnsOnlyTheCallersOrgDeliveriesNewestFirst() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        Wiring wa = seedWiring(orgA, "a");
        Wiring wb = seedWiring(orgB, "b");
        // 3 for A (created_at decreasing => insertion order is newest-first), 2 for B.
        List<UUID> aIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            aIds.add(seedDelivery(orgA, wa, "pending", BASE.minusSeconds(i)));
        }
        seedDelivery(orgB, wb, "delivered", BASE);
        seedDelivery(orgB, wb, "failed", BASE.minusSeconds(1));
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        ResponseEntity<Map<String, Object>> resp =
                rest.exchange("/deliveries", HttpMethod.GET, withKey(aKey), JSON_MAP);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("totalDeliveries")).isEqualTo(3); // only A's
        List<Map<String, Object>> rows = deliveriesOf(resp.getBody());
        assertThat(rows).hasSize(3);
        // Exactly A's ids, none of B's.
        assertThat(rows).extracting(d -> d.get("id")).containsExactlyInAnyOrderElementsOf(
                aIds.stream().map(UUID::toString).toList());
        // Newest-first: created_at strictly descending.
        List<String> created = rows.stream().map(d -> (String) d.get("createdAt")).toList();
        assertThat(created).isSortedAccordingTo((x, y) -> Instant.parse(y).compareTo(Instant.parse(x)));
        // Summary carries wiring + status, never attempts/payloads.
        assertThat(rows.get(0)).containsKeys("eventId", "routeId", "destinationId", "status")
                .doesNotContainKey("attempts");

        // A forged ?orgId=<orgB> is inert — org comes ONLY from the principal.
        ResponseEntity<Map<String, Object>> forged =
                rest.exchange("/deliveries?orgId=" + orgB, HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(forged.getBody().get("totalDeliveries")).isEqualTo(3);
    }

    @Test
    void statusFilterStaysOrgScoped() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        Wiring wa = seedWiring(orgA, "a");
        Wiring wb = seedWiring(orgB, "b");
        seedDelivery(orgA, wa, "delivered", BASE);
        seedDelivery(orgA, wa, "failed", BASE.minusSeconds(1));
        seedDelivery(orgB, wb, "delivered", BASE); // B's delivered must never appear for A
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        ResponseEntity<Map<String, Object>> delivered =
                rest.exchange("/deliveries?status=DELIVERED", HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delivered.getBody().get("totalDeliveries")).isEqualTo(1); // only A's delivered one
        assertThat(deliveriesOf(delivered.getBody())).allSatisfy(
                d -> assertThat(d.get("status")).isEqualTo("DELIVERED"));

        ResponseEntity<Map<String, Object>> failed =
                rest.exchange("/deliveries?status=FAILED", HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(failed.getBody().get("totalDeliveries")).isEqualTo(1);
    }

    @Test
    void eventIdFilterStaysOrgScoped() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        Wiring wa = seedWiring(orgA, "a");
        Wiring wb = seedWiring(orgB, "b");
        seedDelivery(orgA, wa, "pending", BASE);
        seedDelivery(orgB, wb, "pending", BASE);
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        // A filtering by its own event id sees its delivery.
        ResponseEntity<Map<String, Object>> own =
                rest.exchange("/deliveries?eventId=" + wa.eventId(), HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(own.getBody().get("totalDeliveries")).isEqualTo(1);

        // A passing B's event id while authed as A → EMPTY page, never B's rows and not an oracle.
        ResponseEntity<Map<String, Object>> foreign =
                rest.exchange("/deliveries?eventId=" + wb.eventId(), HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(foreign.getBody().get("totalDeliveries")).isEqualTo(0);
    }

    @Test
    void detailReturnsDeliveryWithAttemptsInAttemptedAtOrder() {
        UUID org = newOrg("org");
        Wiring w = seedWiring(org, "x");
        UUID deliveryId = seedDelivery(org, w, "failed", BASE);
        // Insert attempts out of chronological order; the API must return them attempted_at-ascending.
        seedAttempt(deliveryId, 500, "server_error", 120, BASE.plusSeconds(30));
        seedAttempt(deliveryId, null, "timeout", 5000, BASE.plusSeconds(10));
        seedAttempt(deliveryId, 502, "bad_gateway", 80, BASE.plusSeconds(20));
        String key = apiKeys.create(org, "key").plaintextKey();

        ResponseEntity<Map<String, Object>> resp =
                rest.exchange("/deliveries/" + deliveryId, HttpMethod.GET, withKey(key), JSON_MAP);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo(deliveryId.toString());
        assertThat(resp.getBody().get("eventId")).isEqualTo(w.eventId().toString());
        assertThat(resp.getBody().get("status")).isEqualTo("FAILED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) resp.getBody().get("attempts");
        assertThat(attempts).hasSize(3);
        // Oldest-first by attempted_at: +10s, +20s, +30s.
        List<String> times = attempts.stream().map(a -> (String) a.get("attemptedAt")).toList();
        assertThat(times).isSortedAccordingTo((x, y) -> Instant.parse(x).compareTo(Instant.parse(y)));
        assertThat(attempts.get(0).get("error")).isEqualTo("timeout");
        assertThat(attempts.get(0).get("responseStatus")).isNull(); // null is preserved
        assertThat(attempts.get(2).get("responseStatus")).isEqualTo(500);
    }

    @Test
    void crossTenantDetailReturns404IdenticalToANonexistentId() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        Wiring wa = seedWiring(orgA, "a");
        Wiring wb = seedWiring(orgB, "b");
        UUID aDelivery = seedDelivery(orgA, wa, "delivered", BASE);
        UUID bDelivery = seedDelivery(orgB, wb, "delivered", BASE);
        seedAttempt(bDelivery, 200, "ok-secret-marker", 42, BASE); // a marker that must never leak
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        // A can read its own delivery.
        ResponseEntity<Map<String, Object>> own =
                rest.exchange("/deliveries/" + aDelivery, HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().get("id")).isEqualTo(aDelivery.toString());

        // A asking for B's delivery id, and for a random non-existent id, get BYTE-IDENTICAL 404s.
        ResponseEntity<String> notYours =
                rest.exchange("/deliveries/" + bDelivery, HttpMethod.GET, withKey(aKey), String.class);
        ResponseEntity<String> notFound =
                rest.exchange("/deliveries/" + UUID.randomUUID(), HttpMethod.GET, withKey(aKey), String.class);
        assertThat(notYours.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notYours.getBody()).isEqualTo(notFound.getBody()); // existence not revealed
        // ...and the generic body leaks nothing about B's delivery (no attempt marker).
        assertThat(notYours.getBody()).contains("not_found").doesNotContain("ok-secret-marker");
    }

    @Test
    void listNeverIncludesAnotherOrgsRows() {
        UUID orgA = newOrg("org-a");
        UUID orgB = newOrg("org-b");
        Wiring wa = seedWiring(orgA, "a");
        Wiring wb = seedWiring(orgB, "b");
        seedDelivery(orgA, wa, "pending", BASE);
        UUID bDelivery = seedDelivery(orgB, wb, "pending", BASE);
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        ResponseEntity<Map<String, Object>> list =
                rest.exchange("/deliveries", HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(deliveriesOf(list.getBody())).extracting(d -> d.get("id"))
                .doesNotContain(bDelivery.toString());
    }

    @Test
    void noApiKeyReturns401OnBothRoutes() {
        assertThat(rest.exchange("/deliveries", HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/deliveries/" + UUID.randomUUID(), HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pageSizeIsClampedToTheHardMaxAndDefaultsTo20() {
        UUID org = newOrg("org");
        Wiring w = seedWiring(org, "x");
        for (int i = 0; i < 105; i++) {
            seedDelivery(org, w, "pending", BASE.minusSeconds(i));
        }
        String key = apiKeys.create(org, "key").plaintextKey();

        // size=1000000 must be clamped to 100 (resource-exhaustion guard).
        ResponseEntity<Map<String, Object>> huge =
                rest.exchange("/deliveries?size=1000000", HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat(huge.getBody().get("size")).isEqualTo(100);
        assertThat((List<?>) huge.getBody().get("deliveries")).hasSize(100);
        assertThat(huge.getBody().get("totalDeliveries")).isEqualTo(105);
        assertThat(huge.getBody().get("totalPages")).isEqualTo(2);

        // Default size is 20 when unspecified.
        ResponseEntity<Map<String, Object>> dflt =
                rest.exchange("/deliveries", HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat(dflt.getBody().get("size")).isEqualTo(20);
        assertThat((List<?>) dflt.getBody().get("deliveries")).hasSize(20);
    }

    @Test
    void malformedParamsReturn400WithAValidKey() {
        String key = apiKeys.create(newOrg("malformed-params-org"), "key").plaintextKey();
        // Binding/conversion failures are client errors (400), not a misleading 401, for an authed caller.
        assertThat(rest.exchange("/deliveries/not-a-uuid", HttpMethod.GET, withKey(key), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/deliveries?page=abc", HttpMethod.GET, withKey(key), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/deliveries?eventId=not-a-uuid", HttpMethod.GET, withKey(key), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/deliveries?status=bogus", HttpMethod.GET, withKey(key), JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
