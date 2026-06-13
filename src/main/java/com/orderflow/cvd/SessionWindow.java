package com.orderflow.cvd;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * SDK-free tz-aware "is this timestamp inside a session window?" test, for the per-session
 * parameter override (London / NY / Asia). The window is given as minutes-of-day in the session's
 * own timezone; {@code endMin <= startMin} means it wraps past midnight (e.g. a Sydney evening
 * session). Extracted so the window logic (incl. DST and midnight wrap) is golden-testable without
 * the MotiveWave SDK.
 */
public final class SessionWindow {

    private SessionWindow() {}

    /** Minutes-of-day [0,1440) for {@code t} in {@code zone}, or -1 on error. */
    public static int minuteOfDay(long t, ZoneId zone) {
        try {
            LocalTime lt = Instant.ofEpochMilli(t).atZone(zone).toLocalTime();
            return lt.getHour() * 60 + lt.getMinute();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * True if {@code t} (epoch ms) falls within [startMin, endMin) in {@code zone}. A window with
     * {@code endMin <= startMin} wraps midnight (matches times at/after start OR before end).
     * Half-open at the end so adjacent windows don't both claim the boundary minute.
     */
    public static boolean contains(long t, ZoneId zone, int startMin, int endMin) {
        int m = minuteOfDay(t, zone);
        if (m < 0) return false;
        if (startMin == endMin) return false;            // empty window
        return (startMin < endMin) ? (m >= startMin && m < endMin)
                                   : (m >= startMin || m < endMin); // wraps midnight
    }
}
