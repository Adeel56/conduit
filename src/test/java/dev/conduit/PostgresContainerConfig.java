package dev.conduit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Single shared Postgres container for all integration tests. Registered as a {@code @Bean}, so
 * Spring Boot starts it when the (cached) context starts and {@code @ServiceConnection} wires the
 * datasource to it — and because every integration test shares the same context configuration, the
 * context (and this container) is created once for the whole suite, not once per test class.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresContainerConfig {

    // Same digest-pinned image docker-compose.yml runs (container-baseline digest discipline).
    // Keep in sync with docker-compose.yml. Digest-only form: @ServiceConnection rejects tag+digest.
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres@sha256:4b7183ac05f8ef417db21fd72d71047a4238340c261d3cc3ddb6d579ab5071ae")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
