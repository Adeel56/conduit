package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conduit.event.Event;
import dev.conduit.event.EventRepository;
import dev.conduit.ingest.IngestProperties;
import dev.conduit.source.Source;
import dev.conduit.source.SourceRepository;
import dev.conduit.source.SourceService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * Integration tests for {@code POST /ingest/{ingestKey}} (CON-7), booting the full app against the
 * shared Testcontainers Postgres. Covers the 202 / 404 / 413 paths and the tenant fields.
 */
class IngestIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    SourceService sourceService;

    @Autowired
    SourceRepository sourceRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    IngestProperties ingestProperties;

    @Test
    void validRequestStoresEventAndReturns202() {
        UUID orgId = newOrg("acme");
        Source source = sourceService.create(orgId, "stripe-prod");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Webhook-Signature", "t=1,v1=deadbeef");
        String body = "{\"id\":\"evt_123\",\"type\":\"payment.succeeded\"}";

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/ingest/" + source.getIngestKey(), HttpMethod.POST,
                new HttpEntity<>(body, headers), JSON_MAP);

        // 202 + the new event id.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKey("eventId");
        UUID eventId = UUID.fromString((String) response.getBody().get("eventId"));

        // The event is stored with the tenant fields taken from the source, the raw body, and the headers.
        Event event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getOrgId()).isEqualTo(orgId);
        assertThat(event.getSourceId()).isEqualTo(source.getId());
        assertThat(new String(event.getPayload(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(event.getHeaders().toLowerCase()).contains("x-webhook-signature");
    }

    @Test
    void unknownKeyReturns404() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/ingest/this-key-does-not-exist", HttpMethod.POST,
                new HttpEntity<>("{}"), JSON_MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inactiveSourceReturns404LikeAnUnknownKey() {
        Source source = sourceService.create(newOrg("deactivated-org"), "deactivated");
        source.deactivate();
        sourceRepository.save(source);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/ingest/" + source.getIngestKey(), HttpMethod.POST,
                new HttpEntity<>("{}"), JSON_MAP);

        // Identical 404 to an unknown key — existence is not revealed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oversizeBodyReturns413() {
        Source source = sourceService.create(newOrg("big-payloads-org"), "big-payloads");
        byte[] tooBig = new byte[(int) ingestProperties.maxBodySize().toBytes() + 1];
        Arrays.fill(tooBig, (byte) 'x');

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/ingest/" + source.getIngestKey(), HttpMethod.POST,
                new HttpEntity<>(tooBig, headers), JSON_MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
