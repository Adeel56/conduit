package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full end-to-end integration test: boots the entire Spring application against the shared
 * Testcontainers Postgres (see {@link AbstractPostgresIntegrationTest}) and makes a genuine HTTP
 * call to {@code GET /health}. Proves the whole thing wires together (CON-4).
 */
class HealthIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void healthEndpointReturnsUp() {
        // Actuator base-path is '/', so the public URL is GET /health (not /actuator/health).
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); // 200
        // show-details: never -> the public body carries status (plus probe groups). A 200 UP only
        // happens when the DB contributor is reachable, so this proves the app reached Postgres.
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayBaselineMigrationAppliedAgainstContainer() {
        // Flyway ran on startup against the container; assert the V1 baseline is recorded.
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);

        assertThat(applied).isEqualTo(1);
    }
}
