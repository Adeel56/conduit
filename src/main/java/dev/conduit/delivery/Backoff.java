package dev.conduit.delivery;

import java.time.Duration;
import java.util.Random;

/**
 * Pure, unit-testable exponential-backoff-with-full-jitter calculator (CON-13).
 *
 * <p>The uncapped delay for attempt {@code n} is {@code base * 2^(n-1)} (so attempt 1 → base,
 * attempt 2 → 2·base, attempt 3 → 4·base, …). That is clamped to {@code max} (the ceiling on a
 * single delay), then <b>full jitter</b> is applied: the returned delay is a uniformly random value
 * in {@code [0, cappedDelay]}. Full jitter (AWS's recommended form) spreads retries across the whole
 * window so a fleet of failed deliveries does not re-hit a struggling destination in lockstep
 * (thundering herd).
 *
 * <p>The {@link Random} is injected so a test can pass a seeded/fixed source and assert the result
 * lands within {@code [0, cappedDelay]} deterministically. No Spring, no I/O — just arithmetic.
 */
public final class Backoff {

    private final Duration base;
    private final Duration max;
    private final Random random;

    public Backoff(Duration base, Duration max, Random random) {
        this.base = base;
        this.max = max;
        this.random = random;
    }

    /**
     * The delay before {@code attemptNumber} (1-based: 1 is the first retry's wait). Computed as
     * {@code base * 2^(attemptNumber-1)}, capped at {@code max}, then full-jittered into
     * {@code [0, capped]}.
     */
    public Duration next(int attemptNumber) {
        int shift = Math.max(0, attemptNumber - 1);
        long baseMillis = base.toMillis();
        long maxMillis = max.toMillis();

        // base * 2^shift with overflow-safe saturation to maxMillis (large shifts would overflow a long).
        long uncapped;
        if (baseMillis == 0) {
            uncapped = 0;
        } else if (shift >= 62) {
            uncapped = maxMillis;
        } else {
            long scaled = baseMillis << shift;
            // If the shift overflowed (went non-positive) or ran past the cap, saturate to the cap.
            uncapped = (scaled < baseMillis || scaled > maxMillis) ? maxMillis : scaled;
        }

        long capped = Math.min(uncapped, maxMillis);
        if (capped <= 0) {
            return Duration.ZERO;
        }
        // Full jitter: uniform in [0, capped]. nextLong(bound) is [0, bound); +1 makes capped reachable.
        long jittered = random.nextLong(capped + 1);
        return Duration.ofMillis(jittered);
    }
}
