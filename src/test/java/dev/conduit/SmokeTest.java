package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial test whose only job is to prove the test harness compiles and runs in CI (ticket AC).
 *
 * <p>It deliberately does <em>not</em> start the Spring context: booting the app requires a live
 * Postgres (datasource + Flyway), which CI does not provision. Full boot-against-DB verification
 * is done locally via {@code docker compose up}; a Testcontainers-backed integration test is the
 * natural next ticket once there is real behaviour to assert.
 */
class SmokeTest {

    @Test
    void testHarnessRuns() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
