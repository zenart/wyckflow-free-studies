package com.orderflow.cvd;

/**
 * SDK-free builder of the displayed CVD candle series and the true cumulative delta
 * (the divergence basis), from per-bar aggregates plus a reset-boundary key per bar.
 *
 * <p>This is the extracted heart of {@code CumulativeVolumeDeltaStudy.recompute}: the
 * cumulative chains within a reset period (each bar's open = the previous bar's close)
 * and drops to zero only when the boundary key changes. Both display modes are produced
 * from the SAME per-bar aggregation, differing only in how each candle is drawn:</p>
 * <ul>
 *   <li><b>Continuous</b> — open = running cumulative, close = open + bar delta.</li>
 *   <li><b>Per-bar</b> — open = 0, close = bar delta, high/low = the intra-bar
 *       running-delta extremes (NOT the cumulative).</li>
 * </ul>
 *
 * <p>{@link #cumTrue} is always the true reset-mode cumulative regardless of display
 * mode, so divergence detection is identical in both modes.</p>
 */
public final class CvdSeries {

    public final double[] open;     // displayed candle open
    public final double[] high;     // displayed candle high
    public final double[] low;      // displayed candle low
    public final double[] close;    // displayed candle close
    public final double[] cumTrue;  // true reset-mode cumulative (divergence basis)

    private CvdSeries(double[] o, double[] h, double[] l, double[] c, double[] cum) {
        this.open = o; this.high = h; this.low = l; this.close = c; this.cumTrue = cum;
    }

    /**
     * @param delta     per-bar net delta (ask − bid)
     * @param hiOff     per-bar running-delta high offset (>= 0)
     * @param loOff     per-bar running-delta low offset (<= 0)
     * @param boundary  reset-boundary key per bar; equal adjacent keys = same reset
     *                  period, a change = reset to zero. Use a single constant for
     *                  "Never" (continuous).
     * @param perBar    true = per-bar display mode, false = continuous
     */
    public static CvdSeries build(double[] delta, double[] hiOff, double[] loOff,
                                  long[] boundary, boolean perBar) {
        int n = delta.length;
        if (hiOff.length != n || loOff.length != n || boundary.length != n)
            throw new IllegalArgumentException("array length mismatch");
        double[] od = new double[n], hd = new double[n], ld = new double[n],
                 cd = new double[n], cum = new double[n];
        double cumS = 0.0;
        long lastB = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (lastB != Long.MIN_VALUE && boundary[i] != lastB) cumS = 0.0; // reset
            lastB = boundary[i];
            double o = cumS;             // continuous open = prior close (0 after a reset)
            cumS = o + delta[i];         // advance cumulative
            cum[i] = cumS;
            if (perBar) {
                od[i] = 0.0;
                hd[i] = hiOff[i];
                ld[i] = loOff[i];
                cd[i] = delta[i];
            } else {
                od[i] = o;
                hd[i] = o + hiOff[i];
                ld[i] = o + loOff[i];
                cd[i] = o + delta[i];
            }
        }
        return new CvdSeries(od, hd, ld, cd, cum);
    }
}
