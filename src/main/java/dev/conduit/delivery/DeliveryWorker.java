package dev.conduit.delivery;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The delivery engine's background worker (CON-13).
 *
 * <p><b>Two-phase, two-transaction design.</b> Phase 1 is the <em>claim</em> — a single short
 * transaction ({@link DeliveryClaimer#claimBatch(int)}) that {@code SELECT ... FOR UPDATE SKIP LOCKED}s
 * a batch of due, pending deliveries and flips them to {@code in_flight}, then commits. Committing the
 * status change is what makes the claim exclusive: once committed the rows are no longer {@code pending},
 * so no other worker (or instance) can re-claim them. Phase 2 <em>delivers</em> each claimed id in its
 * OWN separate transaction ({@link DeliveryProcessor#deliverOne(UUID)}) — the slow, blocking HTTP work is
 * deliberately kept out of the claim transaction so the claim lock is held for as little time as possible.
 *
 * <p>The scheduled {@link #poll()} only runs the cycle when {@code conduit.delivery.enabled} is true; in
 * tests that flag is false (see {@code AbstractPostgresIntegrationTest}) so the timer never fires and tests
 * drive {@link #runOnce()} (or {@link #claim()}) explicitly for determinism.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryClaimer claimer;
    private final DeliveryProcessor processor;
    private final DeliveryProperties properties;

    public DeliveryWorker(DeliveryClaimer claimer, DeliveryProcessor processor,
                          DeliveryProperties properties) {
        this.claimer = claimer;
        this.processor = processor;
        this.properties = properties;
    }

    /**
     * Scheduled poll. {@code fixedDelayString} waits this many ms AFTER the previous run finishes (so a
     * slow batch can't pile up overlapping runs). The interval comes from the {@code deliveryPollIntervalMillis}
     * bean ({@link DeliveryConfig}) — a plain {@code Long}, so resolving it can't create a circular
     * dependency on the worker the way a self-reference would.
     */
    @Scheduled(fixedDelayString = "#{@deliveryPollIntervalMillis}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            // Never let a poll throw — the scheduler would stop firing. Log the class only.
            log.warn("delivery poll failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * One full cycle: claim a batch, then deliver each claimed delivery in its own transaction.
     * Returns the number of deliveries processed (claimed and attempted). Tests call this directly.
     */
    public int runOnce() {
        List<UUID> claimedIds = claim();
        for (UUID id : claimedIds) {
            try {
                processor.deliverOne(id);
            } catch (Exception e) {
                // One bad delivery must not abort the batch; its row stays in_flight and a later
                // recovery sweep (a follow-up) can requeue it. Log the class only.
                log.warn("deliverOne failed: id={} error={}", id, e.getClass().getSimpleName());
            }
        }
        return claimedIds.size();
    }

    /**
     * The exclusive claim entry point (own transaction via {@link DeliveryClaimer}). Exposed so the
     * concurrency test can invoke it from two threads at once and assert the returned id sets are
     * disjoint (no delivery claimed twice).
     */
    public List<UUID> claim() {
        return claimer.claimBatch(properties.batchSize());
    }
}
