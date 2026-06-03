package dev.conduit.event;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable response envelope for {@code GET /events}. We do NOT serialize Spring's {@code Page}
 * directly — its JSON shape is explicitly unstable across versions. This is our own contract.
 */
public record EventPage(
        List<EventSummary> events,
        int page,
        int size,
        long totalEvents,
        int totalPages) {

    public static EventPage from(Page<EventSummary> page) {
        return new EventPage(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
