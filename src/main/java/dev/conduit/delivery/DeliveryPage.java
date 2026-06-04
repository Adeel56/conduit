package dev.conduit.delivery;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable response envelope for {@code GET /deliveries}, mirroring {@code EventPage} (CON-9). We do NOT
 * serialize Spring's {@code Page} directly — its JSON shape is explicitly unstable across versions.
 * This is our own contract; it is built from a {@code Page<Delivery>} by projecting each row to a
 * {@link DeliverySummary}.
 */
public record DeliveryPage(
        List<DeliverySummary> deliveries,
        int page,
        int size,
        long totalDeliveries,
        int totalPages) {

    public static DeliveryPage from(Page<Delivery> page) {
        return new DeliveryPage(
                page.getContent().stream().map(DeliverySummary::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
