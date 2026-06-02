package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full end-to-end integration test: boots the <em>entire</em> Spring application (web + Actuator
 * + JPA + Flyway) against a real PostgreSQL that Testcontainers spins up in a throwaway Docker
 * container, then makes a genuine HTTP call to {@code GET /health}. This moves the "the whole
 * thing actually wires together" proof out of manual local testing and into CI (CON-4).
 *
 * <p>How the container lifecycle ties to the test lifecycle:
 * <ul>
 *   <li>{@code @Testcontainers} installs the JUnit 5 extension that drives container start/stop.</li>
 *   <li>The {@code static} {@code @Container} field is started <b>once before all tests</b> in this
 *       class and stopped after the last (and Testcontainers' Ryuk reaper guarantees teardown even
 *       if the JVM is killed). It must be static because Spring builds the application context once
 *       for the class, and the container's connection details must already exist at that point.</li>
 *   <li>{@code @ServiceConnection} derives the JDBC url/username/password from the running container
 *       and feeds them into Boot's auto-configuration — so the {@code DataSource} wires straight to
 *       this container with no {@code @DynamicPropertySource} boilerplate. This is also what lets the
 *       context start at all: {@code application.yml} reads {@code ${SPRING_DATASOURCE_URL}} and
 *       defines no defaults, so {@code @ServiceConnection} is what supplies them here.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIntegrationTest {

    // Pinned by digest to the SAME image docker-compose.yml runs (its `postgres:16@sha256:4b7183…`
    // — identical digest), so the test exercises the exact Postgres engine local/prod use and we
    // honour the digest-pinning gate in docs/security/container-baseline.md. Keep this digest in
    // sync with docker-compose.yml.
    // Digest-only form (no `:16` tag): @ServiceConnection's name deduction rejects a combined
    // tag+digest reference, and the digest alone already pins the image uniquely.
    // asCompatibleSubstituteFor("postgres") tells PostgreSQLContainer this digest-pinned image is a
    // Postgres, since the inference can't read it from a bare digest reference.
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres@sha256:4b7183ac05f8ef417db21fd72d71047a4238340c261d3cc3ddb6d579ab5071ae")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void healthEndpointReturnsUp() {
        // Actuator's base-path is '/', so the public URL is GET /health (not /actuator/health).
        // TestRestTemplate resolves this relative path against the random server port.
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); // 200
        // show-details: never -> the public body carries status (plus the probe groups), never the
        // DB component detail. The aggregate is UP only when the DB contributor is reachable, so a
        // 200 UP here genuinely proves the app reached the Testcontainers Postgres. Use contains()
        // because the body also includes "groups":["liveness","readiness"].
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayBaselineMigrationAppliedAgainstContainer() {
        // Flyway ran on context startup against the container. The V1 baseline is intentionally
        // schema-free, so flyway_schema_history is the durable evidence the migration pipeline
        // executed against the real engine — assert the V1 row is present and succeeded.
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);

        assertThat(applied).isEqualTo(1);
    }
}
