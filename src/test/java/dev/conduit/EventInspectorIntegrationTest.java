package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.apikey.ApiKeyService;
import dev.conduit.source.Source;
import dev.conduit.source.SourceService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Inspector integration tests (CON-9). The <b>cross-tenant isolation</b> test is the heart of the
 * ticket: org A's key sees only A's events and gets an identical 404 for B's event by direct id.
 */
class EventInspectorIntegrationTest extends AbstractPostgresIntegrationTest {

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
    JdbcTemplate jdbc;

    private HttpEntity<Void> withKey(String fullKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(fullKey);
        return new HttpEntity<>(headers);
    }

    /** Seed an event directly (controlled received_at) so ordering/isolation are deterministic. */
    private UUID seedEvent(UUID orgId, UUID sourceId, byte[] payload, Instant receivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO events (id, org_id, source_id, payload, headers, received_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, now())",
                id, orgId, sourceId, payload, "{\"X-Test\":\"1\"}", Timestamp.from(receivedAt));
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("events");
    }

    @Test
    void listReturnsOnlyTheCallersOrgEventsNewestFirstWithSize() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        Source srcA = sources.create(orgA, "src-a");
        Source srcB = sources.create(orgB, "src-b");
        // 3 events for A (received_at decreasing => insertion order is newest-first), 2 for B.
        List<UUID> aIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            aIds.add(seedEvent(orgA, srcA.getId(), ("a-payload-" + i).getBytes(StandardCharsets.UTF_8), BASE.minusSeconds(i)));
        }
        seedEvent(orgB, srcB.getId(), "b-1".getBytes(StandardCharsets.UTF_8), BASE.minusSeconds(0));
        seedEvent(orgB, srcB.getId(), "b-2".getBytes(StandardCharsets.UTF_8), BASE.minusSeconds(1));
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        ResponseEntity<Map<String, Object>> resp =
                rest.exchange("/events", HttpMethod.GET, withKey(aKey), JSON_MAP);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("totalEvents")).isEqualTo(3); // only A's
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) resp.getBody().get("events");
        assertThat(events).hasSize(3);
        // All belong to A's source; none are B's ids.
        assertThat(events).allSatisfy(e -> assertThat(e.get("sourceId")).isEqualTo(srcA.getId().toString()));
        assertThat(events).extracting(e -> e.get("id")).containsExactlyInAnyOrderElementsOf(
                aIds.stream().map(UUID::toString).toList());
        // Newest-first: received_at strictly descending.
        List<String> received = events.stream().map(e -> (String) e.get("receivedAt")).toList();
        assertThat(received).isSortedAccordingTo((x, y) -> Instant.parse(y).compareTo(Instant.parse(x)));
        // Summary carries byte size, not payload.
        assertThat(events.get(0)).containsKey("payloadSize").doesNotContainKey("payload");

        // A forged ?orgId=<orgB> is inert — the org comes ONLY from the principal, never the request.
        ResponseEntity<Map<String, Object>> forged =
                rest.exchange("/events?orgId=" + orgB, HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(forged.getBody().get("totalEvents")).isEqualTo(3); // still only A's events
    }

    @Test
    void crossTenantDetailReturns404IdenticalToANonexistentId() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        Source srcA = sources.create(orgA, "src-a");
        Source srcB = sources.create(orgB, "src-b");
        UUID aEvent = seedEvent(orgA, srcA.getId(), "mine".getBytes(StandardCharsets.UTF_8), BASE);
        UUID bEvent = seedEvent(orgB, srcB.getId(), "theirs".getBytes(StandardCharsets.UTF_8), BASE);
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        // A can read its own event.
        ResponseEntity<Map<String, Object>> own =
                rest.exchange("/events/" + aEvent, HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().get("id")).isEqualTo(aEvent.toString());

        // A asking for B's event id, and for a random non-existent id, get BYTE-IDENTICAL 404s.
        ResponseEntity<String> notYours =
                rest.exchange("/events/" + bEvent, HttpMethod.GET, withKey(aKey), String.class);
        ResponseEntity<String> notFound =
                rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, withKey(aKey), String.class);
        assertThat(notYours.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notYours.getBody()).isEqualTo(notFound.getBody()); // existence not revealed
        // ...and the 404 is the generic body — it leaks nothing about B's event (payload "theirs").
        assertThat(notYours.getBody()).contains("not_found").doesNotContain("theirs");
    }

    @Test
    void sourceIdFilterStaysOrgScoped() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        Source srcA = sources.create(orgA, "src-a");
        Source srcB = sources.create(orgB, "src-b");
        seedEvent(orgA, srcA.getId(), "a".getBytes(StandardCharsets.UTF_8), BASE);
        seedEvent(orgB, srcB.getId(), "b".getBytes(StandardCharsets.UTF_8), BASE);
        String aKey = apiKeys.create(orgA, "a-key").plaintextKey();

        // Filtering by A's own source returns A's events.
        ResponseEntity<Map<String, Object>> ownSource =
                rest.exchange("/events?sourceId=" + srcA.getId(), HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(ownSource.getBody().get("totalEvents")).isEqualTo(1);

        // Passing B's source id while authenticated as A returns an EMPTY page — never B's events,
        // and not an error/oracle (the query is org-scoped, so a foreign source matches nothing).
        ResponseEntity<Map<String, Object>> foreignSource =
                rest.exchange("/events?sourceId=" + srcB.getId(), HttpMethod.GET, withKey(aKey), JSON_MAP);
        assertThat(foreignSource.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(foreignSource.getBody().get("totalEvents")).isEqualTo(0);
    }

    @Test
    void pageSizeIsClampedToTheHardMax() {
        UUID org = UUID.randomUUID();
        Source src = sources.create(org, "src");
        for (int i = 0; i < 105; i++) {
            seedEvent(org, src.getId(), "x".getBytes(StandardCharsets.UTF_8), BASE.minusSeconds(i));
        }
        String key = apiKeys.create(org, "key").plaintextKey();

        // size=1000000 must be clamped to 100 (resource-exhaustion guard).
        ResponseEntity<Map<String, Object>> huge =
                rest.exchange("/events?size=1000000", HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat(huge.getBody().get("size")).isEqualTo(100);
        assertThat((List<?>) huge.getBody().get("events")).hasSize(100);
        assertThat(huge.getBody().get("totalEvents")).isEqualTo(105);
        assertThat(huge.getBody().get("totalPages")).isEqualTo(2);

        // Explicit small size honored.
        ResponseEntity<Map<String, Object>> small =
                rest.exchange("/events?size=10", HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat((List<?>) small.getBody().get("events")).hasSize(10);

        // Default size is 20 when unspecified.
        ResponseEntity<Map<String, Object>> dflt =
                rest.exchange("/events", HttpMethod.GET, withKey(key), JSON_MAP);
        assertThat(dflt.getBody().get("size")).isEqualTo(20);
        assertThat((List<?>) dflt.getBody().get("events")).hasSize(20);
    }

    @Test
    void noApiKeyReturns401OnBothRoutes() {
        assertThat(rest.exchange("/events", HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, HttpEntity.EMPTY, JSON_MAP)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detailRendersTextAsUtf8AndBinaryAsBase64() {
        UUID org = UUID.randomUUID();
        Source src = sources.create(org, "src");
        byte[] text = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC0}; // not valid UTF-8
        UUID textId = seedEvent(org, src.getId(), text, BASE);
        UUID binId = seedEvent(org, src.getId(), binary, BASE.minusSeconds(1));
        String key = apiKeys.create(org, "key").plaintextKey();

        Map<String, Object> textBody =
                rest.exchange("/events/" + textId, HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(textBody.get("encoding")).isEqualTo("utf8");
        assertThat(textBody.get("payload")).isEqualTo("{\"hello\":\"world\"}");
        assertThat(textBody.get("payloadSize")).isEqualTo(text.length);
        // headers come back as a JSON OBJECT (not a re-escaped string).
        assertThat(textBody.get("headers")).isEqualTo(Map.of("X-Test", "1"));

        Map<String, Object> binBody =
                rest.exchange("/events/" + binId, HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(binBody.get("encoding")).isEqualTo("base64");
        assertThat(Base64.getDecoder().decode((String) binBody.get("payload"))).isEqualTo(binary);
    }

    @Test
    void pageParamNavigatesAndNegativePageClamps() {
        UUID org = UUID.randomUUID();
        Source src = sources.create(org, "src");
        for (int i = 0; i < 105; i++) {
            seedEvent(org, src.getId(), "x".getBytes(StandardCharsets.UTF_8), BASE.minusSeconds(i));
        }
        String key = apiKeys.create(org, "key").plaintextKey();

        List<Map<String, Object>> page0 = eventsOf(
                rest.exchange("/events?size=100&page=0", HttpMethod.GET, withKey(key), JSON_MAP).getBody());
        Map<String, Object> page1Body =
                rest.exchange("/events?size=100&page=1", HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        List<Map<String, Object>> page1 = eventsOf(page1Body);

        assertThat(page1Body.get("page")).isEqualTo(1);
        assertThat(page1).hasSize(5); // 105 - 100
        // Page 1 is disjoint from page 0 — navigation actually advances the window.
        List<Object> page0Ids = page0.stream().map(e -> e.get("id")).toList();
        assertThat(page1).extracting(e -> e.get("id")).doesNotContainAnyElementsOf(page0Ids);

        // A negative page is clamped to page 0 (server-side).
        Map<String, Object> negative =
                rest.exchange("/events?page=-1", HttpMethod.GET, withKey(key), JSON_MAP).getBody();
        assertThat(negative.get("page")).isEqualTo(0);
    }

    @Test
    void paginationIsStableWhenReceivedAtTies() {
        UUID org = UUID.randomUUID();
        Source src = sources.create(org, "src");
        Set<String> seeded = new HashSet<>();
        for (int i = 0; i < 5; i++) { // identical received_at: only the id tiebreaker makes the order total
            seeded.add(seedEvent(org, src.getId(), ("e" + i).getBytes(StandardCharsets.UTF_8), BASE).toString());
        }
        String key = apiKeys.create(org, "key").plaintextKey();

        Set<String> pagedIds = new HashSet<>();
        for (int page = 0; page < 3; page++) { // sizes 2,2,1 across 5 rows
            for (Map<String, Object> e : eventsOf(rest.exchange(
                    "/events?size=2&page=" + page, HttpMethod.GET, withKey(key), JSON_MAP).getBody())) {
                assertThat(pagedIds.add((String) e.get("id"))).isTrue(); // never a duplicate across pages
            }
        }
        // Every seeded id appears exactly once — no skips, no dups — despite the received_at tie.
        assertThat(pagedIds).isEqualTo(seeded);
    }

    @Test
    void malformedParamsReturn400WithAValidKey() {
        String key = apiKeys.create(UUID.randomUUID(), "key").plaintextKey();
        // Binding/conversion failures are client errors (400), not a misleading 401, for an authed caller.
        assertThat(rest.exchange("/events/not-a-uuid", HttpMethod.GET, withKey(key), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/events?page=abc", HttpMethod.GET, withKey(key), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/events?sourceId=not-a-uuid", HttpMethod.GET, withKey(key), JSON_MAP).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
