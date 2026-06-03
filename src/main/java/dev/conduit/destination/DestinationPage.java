package dev.conduit.destination;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable response envelope for {@code GET /destinations}, mirroring the inspector's {@code EventPage}.
 * We do NOT serialize Spring's {@code Page} directly — its JSON shape is explicitly unstable across
 * versions. This is our own contract.
 */
public record DestinationPage(
        List<DestinationResponse> destinations,
        int page,
        int size,
        long totalDestinations,
        int totalPages) {

    public static DestinationPage from(Page<Destination> page) {
        return new DestinationPage(
                page.getContent().stream().map(DestinationResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
