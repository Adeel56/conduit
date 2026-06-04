package dev.conduit.delivery;

import java.util.UUID;

/**
 * Creates the {@code pending} {@link Delivery} rows for a freshly-stored event — one per active
 * {@code Route} on the event's source (CON-13 fan-out).
 *
 * <p>It is an interface on purpose: {@code IngestController} depends on it <em>optionally</em>
 * (via {@code ObjectProvider}), so ingest works whether or not the delivery engine is present, and
 * the engine implementation is free to run the fan-out strictly OFF the ingest request path
 * (asynchronously, after the event is committed) — a slow fan-out must never add ingest latency.
 */
public interface DeliveryFanout {

    /** Fan out the stored event {@code eventId} into pending deliveries for its source's active routes. */
    void onEventStored(UUID eventId);
}
