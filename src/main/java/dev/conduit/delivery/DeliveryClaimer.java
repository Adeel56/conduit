package dev.conduit.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic claim step (CON-13) — the concurrency crux — isolated in its own bean so its
 * {@code @Transactional} boundary is honoured by the Spring proxy (a self-invocation from the worker
 * would bypass the proxy and the claim would not be a real, committed transaction).
 *
 * <p>{@link #claimBatch(int)} runs {@link DeliveryRepository#claimDue(int)} inside one transaction:
 * the {@code SELECT ... FOR UPDATE SKIP LOCKED} row-locks the due, pending rows it returns and makes a
 * concurrent claimer step over rows another claimer already locked (rather than block). Each returned
 * row is flipped to {@code in_flight} and saved; when this transaction commits, the locks release and
 * the now-non-pending rows are invisible to the next claim. The net effect: two claimers running at the
 * same instant get <b>disjoint</b> id sets — no delivery is ever claimed twice. The ids are returned so
 * the caller can deliver each one in its own separate transaction.
 */
@Service
public class DeliveryClaimer {

    private final DeliveryRepository deliveries;

    public DeliveryClaimer(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    /** Claim up to {@code batchSize} due deliveries; mark them in-flight; return their ids. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch(int batchSize) {
        List<Delivery> claimed = deliveries.claimDue(batchSize);
        List<UUID> ids = new ArrayList<>(claimed.size());
        for (Delivery delivery : claimed) {
            delivery.markInFlight();
            deliveries.save(delivery);
            ids.add(delivery.getId());
        }
        return ids;
    }
}
