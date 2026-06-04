-- V6 — delivery engine (CON-13): the relay tables. A `delivery` is the effort to get ONE stored
-- event to ONE destination (per the Route wiring); status + retry bookkeeping live here, NOT on the
-- immutable Event (ADR-0008). An `attempt` records a single try (timestamp, response, error, timing).
-- Shape per docs/data-model.md. Reverse: src/main/resources/db/undo/U6__deliveries_and_attempts.sql.
--
-- org_id is a real FK to organizations (CON-12); event_id/route_id/destination_id are real FKs too.

CREATE TABLE deliveries (
    id              UUID        PRIMARY KEY,
    org_id          UUID        NOT NULL REFERENCES organizations (id),
    event_id        UUID        NOT NULL REFERENCES events (id),
    route_id        UUID        NOT NULL REFERENCES routes (id),
    destination_id  UUID        NOT NULL REFERENCES destinations (id),
    -- pending: awaiting a (first or retry) attempt; in_flight: claimed by a worker right now;
    -- delivered: a 2xx was received; failed: dead-lettered after the attempt cap (replayable later).
    status          TEXT        NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'in_flight', 'delivered', 'failed')),
    attempt_count   INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The worker's claim query: "due pending deliveries, oldest first" (status, next_attempt_at). This is
-- the index the SELECT ... FOR UPDATE SKIP LOCKED scan rides.
CREATE INDEX ix_deliveries_claim ON deliveries (status, next_attempt_at);
-- Read API: an org's deliveries newest-first (id breaks created_at ties for stable paging).
CREATE INDEX ix_deliveries_org_created ON deliveries (org_id, created_at DESC, id DESC);
-- Read API: deliveries for one event (the fan-out view).
CREATE INDEX ix_deliveries_event ON deliveries (event_id);

CREATE TABLE attempts (
    id              UUID        PRIMARY KEY,
    delivery_id     UUID        NOT NULL REFERENCES deliveries (id),
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_status INT,                 -- nullable: null when the request never got a response (timeout/connect error)
    error           TEXT,                -- nullable: a short error class/message (never the payload)
    duration_ms     BIGINT      NOT NULL -- how long the try took, for observability
);

-- List a delivery's attempts in order (detail view).
CREATE INDEX ix_attempts_delivery ON attempts (delivery_id, attempted_at);
