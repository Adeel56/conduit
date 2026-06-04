package dev.conduit.delivery;

/**
 * Lifecycle of a single {@link Delivery} (CON-13). Stored lowercase in the DB via
 * {@link DeliveryStatusConverter} (matching the {@code status} CHECK in V6).
 *
 * <pre>
 *   PENDING  ──claim──▶ IN_FLIGHT ──2xx──────────▶ DELIVERED   (terminal: success)
 *      ▲                    │
 *      └──retry (backoff)───┤  (non-2xx / error, attempts left)
 *                           └──cap reached───────▶ FAILED      (terminal: dead-letter, replayable)
 * </pre>
 */
public enum DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED
}
