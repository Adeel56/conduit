package dev.conduit.delivery;

import dev.conduit.event.Event;
import dev.conduit.event.EventRepository;
import dev.conduit.route.Route;
import dev.conduit.route.RouteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fan-out implementation (CON-13): turns one stored {@link Event} into one {@code pending}
 * {@link Delivery} per active {@link Route} on the event's source.
 *
 * <p>{@code onEventStored} is {@code @Async @Transactional}, so it runs OFF the ingest request
 * thread in its own transaction. The event is already committed by the time {@code IngestController}
 * calls this (it 202s first), so there is no self-invocation / same-tx hazard — the async hop is the
 * commit boundary. A slow or large fan-out therefore can never add ingest latency (ADR-0003).
 *
 * <p>If the event has vanished (e.g. raced with a delete — none exists today, but defensive),
 * fan-out is a no-op. No payload or headers are touched here; only ids.
 */
@Service
public class DeliveryFanoutService implements DeliveryFanout {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFanoutService.class);

    private final EventRepository events;
    private final RouteRepository routes;
    private final DeliveryRepository deliveries;
    private final MeterRegistry meters;

    public DeliveryFanoutService(EventRepository events, RouteRepository routes,
                                 DeliveryRepository deliveries, MeterRegistry meters) {
        this.events = events;
        this.routes = routes;
        this.deliveries = deliveries;
        this.meters = meters;
    }

    @Override
    @Async
    @Transactional
    public void onEventStored(UUID eventId) {
        Event event = events.findById(eventId).orElse(null);
        if (event == null) {
            // The event is gone — nothing to fan out. (Defensive: events are immutable and not deleted.)
            log.warn("fan-out skipped: event not found event={}", eventId);
            return;
        }

        List<Route> activeRoutes = routes.findBySourceIdAndActiveTrue(event.getSourceId());
        int created = 0;
        for (Route route : activeRoutes) {
            deliveries.save(Delivery.pending(
                    event.getOrgId(), eventId, route.getId(), route.getDestinationId()));
            created++;
        }

        meters.counter("conduit.delivery.fannedOut").increment(created);
        log.info("fan-out: event={} org={} routes={} deliveries={}",
                eventId, event.getOrgId(), activeRoutes.size(), created);
    }
}
