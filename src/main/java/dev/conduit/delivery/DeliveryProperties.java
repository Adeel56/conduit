package dev.conduit.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Delivery-engine configuration (CON-13), 12-factor / overridable via env (e.g.
 * {@code CONDUIT_DELIVERY_ENABLED=false}, {@code CONDUIT_DELIVERY_MAXATTEMPTS=8}).
 *
 * @param enabled        master switch for the background worker's scheduled poll — when {@code false}
 *                       the worker does not auto-deliver (fan-out still records pending rows; delivery
 *                       can be driven explicitly, which the tests rely on). Default {@code true}.
 * @param pollInterval   how often the worker polls for due deliveries. Default 1s.
 * @param batchSize      max deliveries claimed per poll (the {@code claimDue} limit). Default 50.
 * @param maxAttempts    attempt cap; past it a delivery is dead-lettered ({@code failed}). Default 5.
 * @param backoffBase    base of the exponential retry schedule (base, base*factor, ...). Default 1s.
 * @param backoffMax     ceiling on a single backoff delay (before jitter). Default 5m.
 * @param connectTimeout outbound HTTP connect timeout. Default 5s.
 * @param requestTimeout outbound HTTP overall request timeout (a hung destination must not hang a
 *                       worker forever). Default 10s.
 */
@ConfigurationProperties("conduit.delivery")
public record DeliveryProperties(
        Boolean enabled,
        Duration pollInterval,
        Integer batchSize,
        Integer maxAttempts,
        Duration backoffBase,
        Duration backoffMax,
        Duration connectTimeout,
        Duration requestTimeout) {

    public DeliveryProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(1);
        }
        if (batchSize == null) {
            batchSize = 50;
        }
        if (maxAttempts == null) {
            maxAttempts = 5;
        }
        if (backoffBase == null) {
            backoffBase = Duration.ofSeconds(1);
        }
        if (backoffMax == null) {
            backoffMax = Duration.ofMinutes(5);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
    }
}
