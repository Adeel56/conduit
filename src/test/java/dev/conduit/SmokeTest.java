package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial test whose only job is to prove the test harness compiles and runs (ticket AC).
 *
 * <p>It deliberately does <em>not</em> start the Spring context, so it needs no Docker and runs in
 * milliseconds — the fast, always-available signal we keep per CON-4's "keep at least one fast unit
 * test". Full boot-against-Postgres verification lives in {@link HealthIntegrationTest}, which boots
 * the real context against a Testcontainers Postgres.
 */
class SmokeTest {

    @Test
    void testHarnessRuns() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
