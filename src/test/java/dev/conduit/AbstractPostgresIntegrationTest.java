package dev.conduit;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for integration tests that boot the full Spring context against a real Postgres
 * (Testcontainers, wired via {@link PostgresContainerConfig}). Subclasses share one cached context
 * and therefore one container — pay the container + context-boot cost once for the whole suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
abstract class AbstractPostgresIntegrationTest {
}
