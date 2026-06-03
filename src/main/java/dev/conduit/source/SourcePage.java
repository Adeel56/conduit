package dev.conduit.source;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable response envelope for {@code GET /sources} (mirrors {@code EventPage}). We do NOT serialize
 * Spring's {@code Page} directly — its JSON shape is explicitly unstable across versions. This is
 * our own contract. The rows are {@link SourceSummary}, which never carry the full ingest key.
 */
public record SourcePage(
        List<SourceSummary> sources,
        int page,
        int size,
        long totalSources,
        int totalPages) {

    public static SourcePage from(Page<Source> page) {
        return new SourcePage(
                page.getContent().stream().map(SourceSummary::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
