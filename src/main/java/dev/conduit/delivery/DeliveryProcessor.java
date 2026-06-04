package dev.conduit.delivery;

import dev.conduit.destination.Destination;
import dev.conduit.destination.DestinationRepository;
import dev.conduit.event.Event;
import dev.conduit.event.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers ONE claimed {@link Delivery} (CON-13). Split out from {@link DeliveryWorker} so that each
 * call runs in its <b>own</b> transaction via the Spring proxy ({@code REQUIRES_NEW}) — a per-delivery
 * boundary, distinct from the worker's claim transaction. (Putting it in a separate bean avoids the
 * self-invocation pitfall where calling a {@code @Transactional} method on {@code this} bypasses the
 * proxy and the new transaction never starts.)
 *
 * <p>One attempt = one outbound POST of the event payload to the destination URL, bounded by the
 * configured request timeout, recorded as an {@link Attempt}, then exactly one terminal/retry
 * transition on the {@link Delivery}:
 * <ul>
 *   <li>2xx → {@link Delivery#markDelivered()};</li>
 *   <li>otherwise, if this was the last allowed attempt → {@link Delivery#failPermanently()} (dead-letter);</li>
 *   <li>otherwise → {@link Delivery#failAndRetryAt(Instant)} with the next backoff.</li>
 * </ul>
 *
 * <p><b>Security:</b> only ids, status, response code, attempt number, and a short error class are
 * logged or stored — never the payload, the request/response headers, or the destination URL (which
 * could carry a token). Redirects are never followed (set on the shared {@link HttpClient}).
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final DestinationRepository destinations;
    private final AttemptRepository attempts;
    private final HttpClient httpClient;
    private final DeliveryProperties properties;
    private final MeterRegistry meters;
    private final Backoff backoff;

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryProcessor(DeliveryRepository deliveries, EventRepository events,
                             DestinationRepository destinations, AttemptRepository attempts,
                             HttpClient deliveryHttpClient, DeliveryProperties properties,
                             MeterRegistry meters) {
        this(deliveries, events, destinations, attempts, deliveryHttpClient, properties, meters,
                ThreadLocalRandom.current());
    }

    /** Test seam: inject a deterministic {@link Random} for the backoff jitter. */
    DeliveryProcessor(DeliveryRepository deliveries, EventRepository events,
                      DestinationRepository destinations, AttemptRepository attempts,
                      HttpClient deliveryHttpClient, DeliveryProperties properties,
                      MeterRegistry meters, Random random) {
        this.deliveries = deliveries;
        this.events = events;
        this.destinations = destinations;
        this.attempts = attempts;
        this.httpClient = deliveryHttpClient;
        this.properties = properties;
        this.meters = meters;
        this.backoff = new Backoff(properties.backoffBase(), properties.backoffMax(), random);
    }

    /**
     * Deliver one claimed delivery in its own transaction. Reloads the row (it was claimed/committed by
     * the worker), the event, and the destination; POSTs; records an Attempt; transitions; saves.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverOne(UUID deliveryId) {
        Delivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.IN_FLIGHT) {
            // Already handled by another path, or vanished — nothing to do.
            return;
        }

        Event event = events.findById(delivery.getEventId()).orElse(null);
        Destination destination = destinations.findById(delivery.getDestinationId()).orElse(null);
        if (event == null || destination == null) {
            // Referenced rows missing (shouldn't happen given FKs) — record an attempt and dead-letter.
            attempts.save(Attempt.record(deliveryId, null, "missing_event_or_destination", 0));
            transition(delivery, false);
            return;
        }

        Integer status = null;
        String error = null;
        long started = System.nanoTime();
        try {
            HttpResponse<Void> response = send(destination.getUrl(), event);
            status = response.statusCode();
        } catch (Exception e) {
            // Short error CLASS only (e.g. "HttpConnectTimeoutException") — never the message/payload/URL.
            error = e.getClass().getSimpleName();
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        attempts.save(Attempt.record(deliveryId, status, error, durationMs));

        boolean success = status != null && status >= 200 && status < 300;
        transition(delivery, success);
        deliveries.save(delivery);

        log.info("delivery {}: id={} status={} responseCode={} attempt={}",
                delivery.getStatus(), deliveryId,
                success ? "ok" : "fail", status, delivery.getAttemptCount());
    }

    /** Exactly one of delivered / retry / dead-letter, plus the matching Micrometer counter. */
    private void transition(Delivery delivery, boolean success) {
        if (success) {
            delivery.markDelivered();
            meters.counter("conduit.delivery.delivered").increment();
            return;
        }
        // attemptCount is the number of tries BEFORE this one; +1 counts the try we just made.
        int triesAfterThis = delivery.getAttemptCount() + 1;
        if (triesAfterThis >= properties.maxAttempts()) {
            delivery.failPermanently();
            meters.counter("conduit.delivery.failed").increment();
        } else {
            delivery.failAndRetryAt(Instant.now().plus(backoff.next(triesAfterThis)));
            meters.counter("conduit.delivery.retried").increment();
        }
    }

    /** POST the raw payload with a bounded request timeout; discard the body (we only need the status). */
    private HttpResponse<Void> send(String url, Event event) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "Conduit-Delivery")
                .POST(HttpRequest.BodyPublishers.ofByteArray(event.getPayload()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
