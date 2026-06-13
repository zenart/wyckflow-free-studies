package com.orderflow.cvd;

import java.util.Arrays;

/**
 * SDK-free session-adaptivity helpers for the CVD study. The whole point is that the
 * user should NOT have to swap templates between the thin London session (~100 contracts
 * /min) and the heavy NY session (~1000+/min, an ~8× ratio measured on the real tape).
 * Thresholds are expressed relative to a trailing window of recent bars, so they scale
 * themselves to whatever session is currently active.
 */
public final class Adaptivity {

    /** Default trailing window (bars) for the rolling baselines. */
    public static final int DEFAULT_WINDOW = 90;
    /** Default multiplier for the immediate-divergence volume floor. A bar's window volume
     *  must reach {@code floorK × trailing-median} to be eligible — this is what stops the
     *  ● spam on thin low-volume bars (finding C11). */
    public static final double DEFAULT_FLOOR_K = 0.6;

    private Adaptivity() {}

    /** Trailing median of {@code vol[max(0,i-window+1) .. i]} (inclusive). Returns 0 for an
     *  empty/degenerate window. Median (not mean) so a single huge print doesn't inflate the
     *  baseline. */
    public static double medianTrailing(double[] vol, int i, int window) {
        if (vol == null || vol.length == 0 || i < 0) return 0.0;
        int hi = Math.min(i, vol.length - 1);
        int lo = Math.max(0, hi - Math.max(1, window) + 1);
        int n = hi - lo + 1;
        if (n <= 0) return 0.0;
        double[] tmp = new double[n];
        System.arraycopy(vol, lo, tmp, 0, n);
        Arrays.sort(tmp);
        return (n % 2 == 1) ? tmp[n / 2] : 0.5 * (tmp[n / 2 - 1] + tmp[n / 2]);
    }

    /** Volume floor for the immediate-divergence effort window ending at bar {@code i},
     *  spanning {@code effortWindow} bars: {@code floorK × effortWindow × trailing-median}.
     *  A window whose total volume is below this is too thin to be a meaningful signal. */
    public static double immediateVolumeFloor(double[] vol, int i, int effortWindow,
                                              int baselineWindow, double floorK) {
        // Baseline EXCLUDES the effort window under test (ends at i − effortWindow), so a high-volume
        // spike — the very thing the immediate signal should admit — can't inflate its own floor. (AR-6)
        double med = medianTrailing(vol, i - Math.max(1, effortWindow), baselineWindow);
        return floorK * Math.max(1, effortWindow) * med;
    }
}
