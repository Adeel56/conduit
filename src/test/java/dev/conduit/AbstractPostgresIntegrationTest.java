package dev.conduit;

import dev.conduit.organization.OrganizationService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for integration tests that boot the full Spring context against a real Postgres
 * (Testcontainers, wired via {@link PostgresContainerConfig}). Subclasses share one cached context
 * and therefore one container — pay the container + context-boot cost once for the whole suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig.class)
// Delivery worker's scheduled auto-poll OFF for the whole suite so tests are deterministic: delivery
// tests create pending rows (fan-out still runs) and drive the worker explicitly, not via a timer.
@TestPropertySource(properties = "conduit.delivery.enabled=false")
abstract class AbstractPostgresIntegrationTest {

    @Autowired
    private OrganizationService organizations;

    /**
     * Seed a real organization and return its id. Since CON-12 made {@code org_id} a foreign key,
     * tenant rows (sources, api keys, events) must reference an organization that actually exists —
     * so tests create one here instead of inventing a bare {@code UUID.randomUUID()}.
     */
    protected UUID newOrg(String name) {
        return organizations.create(name).getId();
    }
}
