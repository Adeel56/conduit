package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.conduit.delivery.Attempt;
import dev.conduit.delivery.AttemptRepository;
import dev.conduit.delivery.Backoff;
import dev.conduit.delivery.Delivery;
import dev.conduit.delivery.DeliveryFanout;
import dev.conduit.delivery.DeliveryRepository;
import dev.conduit.delivery.DeliveryStatus;
import dev.conduit.delivery.DeliveryWorker;
import dev.conduit.destination.Destination;
import dev.conduit.destination.DestinationRepository;
import dev.conduit.event.Event;
import dev.conduit.event.EventRepository;
import dev.conduit.route.Route;
import dev.conduit.route.RouteRepository;
import dev.conduit.source.Source;
import dev.conduit.source.SourceRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The CON-13 centerpiece: end-to-end proof of the delivery engine against a REAL HTTP destination.
 *
 * <p>Uses the JDK's {@link HttpServer} (no extra dependency) bound to an ephemeral port as the stub
 * destination, so timeouts, retries and real status codes are genuinely exercised — not mocked. The
 * worker's scheduled auto-poll is OFF for the whole suite (see {@link AbstractPostgresIntegrationTest}),
 * so each test drives {@link DeliveryWorker#runOnce()} (or {@link DeliveryWorker#claim()}) explicitly.
 *
 * <p>Covers: happy path, retry-then-success, retry-then-dead-letter, fan-out (one event → two
 * deliveries), and the headline concurrency-claim proof (two threads, disjoint claims, no double-deliver).
 */
class DeliveryEngineIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    DeliveryWorker worker;
    @Autowired
    DeliveryFanout fanout;
    @Autowired
    DeliveryRepository deliveries;
    @Autowired
    AttemptRepository attempts;
    @Autowired
    EventRepository events;
    @Autowired
    SourceRepository sources;
    @Autowired
    DestinationRepository destinations;
    @Autowired
    RouteRepository routes;

    private HttpServer server;
    /** Total requests the stub has received (across all paths). */
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        // Port 0 → an OS-assigned ephemeral port. Default executor handles requests on a small pool.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Register a context at {@code path} that returns the given fixed status with an empty body. */
    private void respondAlways(String path, int status) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            reply(exchange, status);
        });
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1); // -1 → no response body
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** Create an active source for an org and return it. */
    private Source newSource(UUID orgId) {
        return sources.save(new Source(orgId, "src-" + UUID.randomUUID(),
                "ik-" + UUID.randomUUID().toString().replace("-", "")));
    }

    /** Create a destination for an org pointing at the stub path. */
    private Destination newDestination(UUID orgId, String path) {
        return destinations.save(new Destination(orgId, "dest-" + UUID.randomUUID(), url(path)));
    }

    /** Store an immutable event on a source. */
    private Event newEvent(UUID orgId, UUID sourceId) {
        return events.save(Event.received(orgId, sourceId,
                "{\"hello\":\"world\"}".getBytes(), "{}"));
    }

    /** Drive fan-out (which is @Async) and wait until its pending rows exist for the event. */
    private List<Delivery> fanOutAndAwait(UUID eventId, int expected) {
        fanout.onEventStored(eventId);
        await().atMost(Duration.ofSeconds(10)).until(
                () -> deliveries.findAll().stream()
                        .filter(d -> d.getEventId().equals(eventId)).count() == expected);
        List<Delivery> result = new ArrayList<>();
        for (Delivery d : deliveries.findAll()) {
            if (d.getEventId().equals(eventId)) {
                result.add(d);
            }
        }
        return result;
    }

    // --- tests ------------------------------------------------------------------------------------

    @Test
    void happyPath_singleRoute_delivers() {
        UUID org = newOrg("happy");
        String path = "/happy-" + UUID.randomUUID();
        respondAlways(path, 200);
        Source source = newSource(org);
        Destination dest = newDestination(org, path);
        routes.save(new Route(org, source.getId(), dest.getId()));
        Event event = newEvent(org, source.getId());

        List<Delivery> fanned = fanOutAndAwait(event.getId(), 1);
        assertThat(fanned).hasSize(1);

        int processed = worker.runOnce();
        assertThat(processed).isEqualTo(1);

        Delivery delivery = deliveries.findById(fanned.get(0).getId()).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(0); // success doesn't bump attempt_count

        List<Attempt> tries = attempts.findByDeliveryIdOrderByAttemptedAtAsc(delivery.getId());
        assertThat(tries).hasSize(1);
        assertThat(tries.get(0).getResponseStatus()).isEqualTo(200);
        assertThat(tries.get(0).getError()).isNull();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void fanOut_oneEventTwoDestinations_twoDeliveries() {
        UUID org = newOrg("fan-out");
        String pathA = "/fan-a-" + UUID.randomUUID();
        String pathB = "/fan-b-" + UUID.randomUUID();
        respondAlways(pathA, 200);
        respondAlways(pathB, 200);

        Source source = newSource(org);
        Destination destA = newDestination(org, pathA);
        Destination destB = newDestination(org, pathB);
        routes.save(new Route(org, source.getId(), destA.getId()));
        routes.save(new Route(org, source.getId(), destB.getId()));
        Event event = newEvent(org, source.getId());

        List<Delivery> fanned = fanOutAndAwait(event.getId(), 2);
        assertThat(fanned).hasSize(2);
        assertThat(fanned).extracting(Delivery::getDestinationId)
                .containsExactlyInAnyOrder(destA.getId(), destB.getId());

        int processed = worker.runOnce();
        assertThat(processed).isEqualTo(2);
        assertThat(deliveries.findAll().stream()
                .filter(d -> d.getEventId().equals(event.getId())))
                .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED));
    }

    /**
     * THE correctness test. Seed many pending deliveries, then run the exclusive claim from TWO real
     * threads released simultaneously by a latch. Assert (a) every claimed id is unique across both
     * threads (disjoint sets — {@code FOR UPDATE SKIP LOCKED} guarantees no row is claimed twice), and
     * (b) the union covers exactly the seeded set with no duplicates — proving no delivery is claimed,
     * and thus delivered, twice under concurrency.
     */
    @Test
    void concurrentClaim_isExclusive_noDoubleClaim() throws Exception {
        UUID org = newOrg("concurrency");
        String path = "/conc-" + UUID.randomUUID();
        respondAlways(path, 200);
        Source source = newSource(org);
        Destination dest = newDestination(org, path);
        routes.save(new Route(org, source.getId(), dest.getId()));

        // Seed many pending deliveries directly (deterministic — no fan-out timing in play). Use one
        // shared event so the FKs are satisfied; each delivery is its own row.
        int total = 200;
        Event event = newEvent(org, source.getId());
        Route route = routes.findBySourceIdAndActiveTrue(source.getId()).get(0);
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < total; i++) {
            Delivery d = deliveries.save(
                    Delivery.pending(org, event.getId(), route.getId(), dest.getId()));
            seeded.add(d.getId());
        }

        // Two threads each loop calling worker.claim() (batchSize=50) until nothing is left; collect the
        // ids each claims. A latch releases both at the same instant to maximise contention on the claim.
        ConcurrentLinkedQueue<UUID> claimedA = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<UUID> claimedB = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(claimLoop(start, claimedA));
            Future<?> b = pool.submit(claimLoop(start, claimedB));
            start.countDown(); // release both at once
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<UUID> all = new ArrayList<>(claimedA);
        all.addAll(claimedB);

        // (1) No id claimed by both threads — the two sets are disjoint.
        Set<UUID> setA = new HashSet<>(claimedA);
        Set<UUID> setB = new HashSet<>(claimedB);
        Set<UUID> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        assertThat(intersection).as("no delivery claimed by BOTH threads").isEmpty();

        // (2) No id claimed twice at all, and together they cover exactly the seeded set.
        assertThat(all).as("no duplicate claims").doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).as("every seeded delivery claimed exactly once")
                .isEqualTo(seeded);

        // (3) Every seeded delivery is now in_flight (claimed), none still pending.
        assertThat(deliveries.findAllById(seeded))
                .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(DeliveryStatus.IN_FLIGHT));
    }

    private Runnable claimLoop(CountDownLatch start, ConcurrentLinkedQueue<UUID> sink) {
        return () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Keep claiming until two consecutive empty claims (both threads have drained the queue).
            int emptyStreak = 0;
            while (emptyStreak < 2) {
                List<UUID> batch = worker.claim();
                if (batch.isEmpty()) {
                    emptyStreak++;
                    Thread.yield();
                } else {
                    emptyStreak = 0;
                    sink.addAll(batch);
                }
            }
        };
    }
}

/**
 * Retry behaviour (CON-13), split into its own top-level class so it can run with a tiny backoff and a
 * small attempt cap via {@link TestPropertySource} — tiny {@code backoff-base}/{@code backoff-max} make
 * every retry immediately due, so the worker can be driven to a terminal state in-test without real
 * waits. (A separate property set means a separate Spring context from the main delivery test, which is
 * fine; the worker auto-poll stays off, inherited from {@link AbstractPostgresIntegrationTest}.)
 *
 * <p>Two cases: a destination that 500s twice then 200s ends {@code DELIVERED} (retry-then-success), and
 * one that always 500s dead-letters to {@code FAILED} at the attempt cap (retry-then-dead-letter).
 */
@TestPropertySource(properties = {
        "conduit.delivery.backoff-base=1ms",
        "conduit.delivery.backoff-max=5ms",
        "conduit.delivery.max-attempts=5"
})
class DeliveryRetryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    DeliveryWorker worker;
    @Autowired
    DeliveryFanout fanout;
    @Autowired
    DeliveryRepository deliveries;
    @Autowired
    AttemptRepository attempts;
    @Autowired
    EventRepository events;
    @Autowired
    SourceRepository sources;
    @Autowired
    DestinationRepository destinations;
    @Autowired
    RouteRepository routes;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Status follows {@code statuses} call-by-call; once exhausted the LAST status repeats. */
    private void respondInSequence(String path, List<Integer> statuses) {
        AtomicInteger call = new AtomicInteger();
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            int i = Math.min(call.getAndIncrement(), statuses.size() - 1);
            exchange.sendResponseHeaders(statuses.get(i), -1);
            exchange.close();
        });
    }

    private Delivery seedSingleDelivery(UUID org, String path, List<Integer> statuses) {
        respondInSequence(path, statuses);
        Source source = sources.save(new Source(org, "src-" + UUID.randomUUID(),
                "ik-" + UUID.randomUUID().toString().replace("-", "")));
        Destination dest = destinations.save(
                new Destination(org, "dest-" + UUID.randomUUID(), url(path)));
        routes.save(new Route(org, source.getId(), dest.getId()));
        Event event = events.save(Event.received(org, source.getId(), "{\"x\":1}".getBytes(), "{}"));
        fanout.onEventStored(event.getId());
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveries.findAll().stream()
                .anyMatch(d -> d.getEventId().equals(event.getId())));
        return deliveries.findAll().stream()
                .filter(d -> d.getEventId().equals(event.getId())).findFirst().orElseThrow();
    }

    /** Run the worker repeatedly until the delivery leaves pending (retries are immediately due). */
    private Delivery drainToTerminal(UUID deliveryId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            worker.runOnce();
            Delivery d = deliveries.findById(deliveryId).orElseThrow();
            return d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.FAILED;
        });
        return deliveries.findById(deliveryId).orElseThrow();
    }

    @Test
    void retryThenSuccess() {
        UUID org = newOrg("retry-ok");
        String path = "/retry-ok-" + UUID.randomUUID();
        // 500, 500, then 200 → three attempts, ends DELIVERED with attempt_count == 2 (two failures).
        Delivery seeded = seedSingleDelivery(org, path, List.of(500, 500, 200));

        Delivery delivery = drainToTerminal(seeded.getId());
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);

        List<Attempt> tries = attempts.findByDeliveryIdOrderByAttemptedAtAsc(delivery.getId());
        assertThat(tries).hasSize(3);
        assertThat(tries.get(0).getResponseStatus()).isEqualTo(500);
        assertThat(tries.get(1).getResponseStatus()).isEqualTo(500);
        assertThat(tries.get(2).getResponseStatus()).isEqualTo(200);
    }

    @Test
    void retryThenDeadLetter() {
        UUID org = newOrg("dead-letter");
        String path = "/dead-" + UUID.randomUUID();
        // Always 500 → after max-attempts (5) it dead-letters: FAILED, attempt_count == cap.
        Delivery seeded = seedSingleDelivery(org, path, List.of(500));

        Delivery delivery = drainToTerminal(seeded.getId());
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(5); // == conduit.delivery.max-attempts

        List<Attempt> tries = attempts.findByDeliveryIdOrderByAttemptedAtAsc(delivery.getId());
        assertThat(tries).hasSize(5);
        assertThat(tries).allSatisfy(a -> assertThat(a.getResponseStatus()).isEqualTo(500));
    }
}

/**
 * Pure unit test for {@link Backoff} (no Spring, no DB) — proves the exponential schedule and the
 * full-jitter bounds deterministically by injecting a fixed {@link java.util.Random}.
 */
class BackoffTest {

    /** A Random whose nextLong(bound) returns a configured fraction of the bound, for exact assertions. */
    private static java.util.Random fixedFraction(double fraction) {
        return new java.util.Random() {
            @Override
            public long nextLong(long bound) {
                long v = (long) (fraction * (bound - 1));
                return Math.max(0, Math.min(v, bound - 1));
            }
        };
    }

    @Test
    void fullJitterAtMax_returnsTheCappedExponentialDelay() {
        // base=1s, max=1m. Uncapped for attempt n is 1s*2^(n-1): 1s, 2s, 4s, 8s, 16s, 32s, 64s→cap 60s.
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(1), fixedFraction(1.0));
        // fraction 1.0 → jitter returns the full capped delay (the upper bound of [0, capped]).
        assertThat(backoff.next(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.next(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.next(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.next(4)).isEqualTo(Duration.ofSeconds(8));
        // attempt 7 would be 64s uncapped → clamped to the 60s ceiling.
        assertThat(backoff.next(7)).isEqualTo(Duration.ofSeconds(60));
        // far-out attempts saturate at the cap, never overflow.
        assertThat(backoff.next(100)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void fullJitterAtZero_returnsZero() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(1), fixedFraction(0.0));
        assertThat(backoff.next(3)).isEqualTo(Duration.ZERO);
    }

    @Test
    void jitterAlwaysWithinZeroToCappedDelay() {
        // With a real Random, every sample must land in [0, cappedDelay] for the given attempt.
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(1), new java.util.Random(42));
        long capMillis = Duration.ofSeconds(4).toMillis(); // attempt 3 → 4s cap
        for (int i = 0; i < 1000; i++) {
            long d = backoff.next(3).toMillis();
            assertThat(d).isBetween(0L, capMillis);
        }
    }
}
