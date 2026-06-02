package dev.conduit.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Ingest configuration (12-factor: overridable via env/properties, e.g.
 * {@code CONDUIT_INGEST_MAXBODYSIZE=512KB}).
 *
 * @param maxBodySize hard cap on the request body; larger requests are rejected with 413 before
 *                    any storage work. Defaults to 1 MB.
 */
@ConfigurationProperties("conduit.ingest")
public record IngestProperties(DataSize maxBodySize) {

    public IngestProperties {
        if (maxBodySize == null) {
            maxBodySize = DataSize.ofMegabytes(1);
        }
    }
}
