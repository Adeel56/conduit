package dev.conduit.route;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable response envelope for {@code GET /routes}, mirroring the inspector's {@code EventPage}. We
 * do NOT serialize Spring's {@code Page} directly — its JSON shape is unstable across versions.
 */
public record RoutePage(
        List<RouteResponse> routes,
        int page,
        int size,
        long totalRoutes,
        int totalPages) {

    public static RoutePage from(Page<Route> page) {
        return new RoutePage(
                page.getContent().stream().map(RouteResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
