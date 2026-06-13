package com.orderflow.cvd;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * SDK-free custom-session reset anchoring. Given a timestamp and a set of reset times-of-day in
 * a timezone, returns the most recent session-start instant (epoch ms) at or before the timestamp.
 * Timezone-aware via {@link java.time.ZonedDateTime}, so it is correct across DST transitions
 * (09:30 New York is 13:30 UTC in winter, 13:30… in summer the offset shifts by an hour and this
 * resolves it correctly). Extracted from the study so the DST / 24-7 / multi-time behaviour can be
 * golden-tested without the MotiveWave SDK.
 */
public final class SessionAnchor {

    private SessionAnchor() {}

    /**
     * Latest session start (epoch ms) at or before {@code t}.
     *
     * @param t            timestamp (epoch ms, UTC)
     * @param zone         the session's timezone
     * @param minutesOfDay reset times as minutes-of-day (e.g. 9*60+30 for 09:30); may hold several
     * @return the most recent anchor &le; {@code t}; falls back to the UTC-day index on any error
     */
    public static long anchor(long t, ZoneId zone, int[] minutesOfDay) {
        try {
            LocalDate date = Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
            long best = Long.MIN_VALUE;
            // Look back several days, so a timestamp before the day's first reset still anchors to
            // the previous session even across a weekend/holiday gap (sparse reset times).
            for (int dayBack = 0; dayBack <= 4; dayBack++) {
                LocalDate d = date.minusDays(dayBack);
                for (int m : minutesOfDay) {
                    long a = d.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli();
                    if (a <= t && a > best) best = a;
                }
            }
            // Fallback stays in the same epoch-ms key space as real anchors (start of day in the
            // session's zone) so a stray bar never gets a key inconsistent with its neighbours (BR-6).
            return best == Long.MIN_VALUE ? date.atStartOfDay(zone).toInstant().toEpochMilli() : best;
        } catch (Throwable ignored) {
            return t / 86_400_000L;
        }
    }
}
