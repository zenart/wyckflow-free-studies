package com.orderflow.cvd;

import java.util.ArrayList;
import java.util.List;

/**
 * SDK-free CVD divergence detection — a faithful extraction of the logic currently inlined
 * in {@code CumulativeVolumeDeltaStudy.recompute}, so it can be golden-tested without the
 * MotiveWave SDK and the study can later delegate to it (one implementation, no live/historical
 * drift). All detection runs on the TRUE reset-mode cumulative ({@code cumTrue}) and never
 * compares pivots across a reset boundary.
 *
 * <p>Adds (approved redesign): an immediate-divergence <b>volume floor</b> (finding C11 — stops
 * the ● spam on thin low-volume bars) and a 0–100 <b>strength</b> score per mark (for the
 * Minimal / strength-filtered display variant).</p>
 *
 * <p>Directions (textbook, verified against the manual): regular bearish = price HH + CVD LH;
 * regular bullish = price LL + CVD HL; hidden bearish = price LH + CVD HH; hidden bullish =
 * price HL + CVD LL.</p>
 */
public final class DivergenceEngine {

    public enum Kind { REG_BEAR, REG_BULL, HID_BEAR, HID_BULL }

    /** A confirmed swing divergence between two pivots of the same type. */
    public static final class Swing {
        public final Kind kind;
        public final int prevIdx, curIdx;
        public final double strength;   // 0..100
        Swing(Kind k, int p, int c, double s) { kind = k; prevIdx = p; curIdx = c; strength = s; }
    }

    /** An immediate "effort vs result" divergence on a single bar/window. */
    public static final class Immediate {
        public final int idx;
        public final boolean bear;       // true = bearish absorption (buy effort, no rise)
        public final boolean confirmed;  // next-bar follow-through (when confirm requested)
        Immediate(int i, boolean bear, boolean confirmed) { this.idx = i; this.bear = bear; this.confirmed = confirmed; }
    }

    private DivergenceEngine() {}

    // ── Pivots (mirror of the study's isPivotHigh/Low) ──────────────────

    public static boolean isPivotHigh(double[] high, int i, int strength) {
        double v = high[i];
        for (int j = 1; j <= strength; j++) {
            if (i - j < 0 || i + j >= high.length) return false;
            if (high[i - j] >= v) return false;
            if (high[i + j] >= v) return false;
        }
        return true;
    }

    public static boolean isPivotLow(double[] low, int i, int strength) {
        double v = low[i];
        for (int j = 1; j <= strength; j++) {
            if (i - j < 0 || i + j >= low.length) return false;
            if (low[i - j] <= v) return false;
            if (low[i + j] <= v) return false;
        }
        return true;
    }

    // ── Swing (regular + hidden) — faithful mirror of the study ─────────

    /**
     * @param firstBar  first drawn bar (detection starts at {@code max(firstBar, pivotL)})
     * @param n         series length
     */
    public static List<Swing> swings(double[] high, double[] low, double[] cumTrue, long[] bnd,
                                     int firstBar, int n, int pivotL,
                                     boolean showRegular, boolean showHidden) {
        List<Swing> out = new ArrayList<>();
        int lo = Math.max(firstBar, pivotL);
        int hi = n - 1 - pivotL;
        // Strength is normalised against the DRAWN window only (not the whole history) — else a
        // huge historical cumulative (e.g. RESET_NONE over weeks) shrinks every visible swing's
        // strength toward 0 and the Minimal filter would hide them all. (AR-9)
        double cumRange = range(cumTrue, Math.max(0, firstBar), n);

        int prevHi = -1;
        for (int i = lo; i <= hi; i++) {
            if (!isPivotHigh(high, i, pivotL)) continue;
            if (prevHi >= 0 && bnd[i] == bnd[prevHi]) {
                boolean priceHH = high[i] > high[prevHi];
                if (showRegular && priceHH && cumTrue[i] < cumTrue[prevHi])
                    out.add(new Swing(Kind.REG_BEAR, prevHi, i, strength(cumTrue, prevHi, i, cumRange)));
                else if (showHidden && !priceHH && cumTrue[i] > cumTrue[prevHi])
                    out.add(new Swing(Kind.HID_BEAR, prevHi, i, strength(cumTrue, prevHi, i, cumRange)));
            }
            prevHi = i;
        }
        int prevLo = -1;
        for (int i = lo; i <= hi; i++) {
            if (!isPivotLow(low, i, pivotL)) continue;
            if (prevLo >= 0 && bnd[i] == bnd[prevLo]) {
                boolean priceLL = low[i] < low[prevLo];
                if (showRegular && priceLL && cumTrue[i] > cumTrue[prevLo])
                    out.add(new Swing(Kind.REG_BULL, prevLo, i, strength(cumTrue, prevLo, i, cumRange)));
                else if (showHidden && !priceLL && cumTrue[i] < cumTrue[prevLo])
                    out.add(new Swing(Kind.HID_BULL, prevLo, i, strength(cumTrue, prevLo, i, cumRange)));
            }
            prevLo = i;
        }
        return out;
    }

    // ── Immediate (effort vs result) — mirror + volume floor (C11) ──────

    /**
     * @param volFloorK  volume-floor multiplier (0 disables the floor); window volume must reach
     *                   {@code volFloorK × window × trailing-median(volume)} or the bar is skipped
     * @param baselineWindow trailing window (bars) for the median baseline
     */
    public static List<Immediate> immediate(double[] close, double[] open, double[] cumTrue,
                                            double[] vol, long[] bnd, int firstBar, int last,
                                            int window, double deltaPct, double tol, boolean confirm,
                                            double volFloorK, int baselineWindow) {
        double[] dp = new double[close.length], fk = new double[close.length];
        java.util.Arrays.fill(dp, deltaPct);
        java.util.Arrays.fill(fk, volFloorK);
        return immediate(close, open, cumTrue, vol, bnd, firstBar, last, window, dp, tol, confirm, fk, baselineWindow);
    }

    /**
     * Per-bar variant: {@code deltaPct[i]} and {@code volFloorK[i]} are the effort gate and
     * volume-floor multiplier for bar {@code i}. This lets the per-session override apply EACH
     * bar's own session tuning (a London bar uses London params, an NY bar uses NY) instead of
     * retroactively re-tuning historical bars to the live session (BR-2).
     */
    public static List<Immediate> immediate(double[] close, double[] open, double[] cumTrue,
                                            double[] vol, long[] bnd, int firstBar, int last,
                                            int window, double[] deltaPct, double tol, boolean confirm,
                                            double[] volFloorK, int baselineWindow) {
        List<Immediate> out = new ArrayList<>();
        for (int i = Math.max(firstBar + window, window); i <= last; i++) {
            if (bnd[i] != bnd[i - window]) continue;                 // same reset period
            double cvdChange = cumTrue[i] - cumTrue[i - window];
            double winVol = 0.0;
            for (int k = i - window + 1; k <= i; k++) winVol += vol[k];
            if (winVol <= 0.0) continue;
            if (volFloorK[i] > 0) {                                  // C11: skip thin low-volume bars
                double floor = Adaptivity.immediateVolumeFloor(vol, i, window, baselineWindow, volFloorK[i]);
                if (winVol < floor) continue;
            }
            if (Math.abs(cvdChange) < deltaPct[i] * winVol) continue;  // not one-sided enough
            double body = close[i] - open[i - window + 1];
            boolean absBear = cvdChange > 0 && body <= tol;          // buy effort, price didn't rise
            boolean absBull = cvdChange < 0 && body >= -tol;         // sell effort, price didn't fall
            if (!absBear && !absBull) continue;
            boolean confirmed;
            if (!confirm)        confirmed = true;
            else if (i >= last)  confirmed = false;                  // forming/last bar: pending
            // Follow-through = the bar AFTER the signal moves in the absorption direction by MORE
            // than the same price tolerance used for the body gate, so sub-tolerance noise can't
            // flip confirmation (M2: tol now consistent across both gates; tol=0 keeps the strict
            // default). Confirmation is intentionally the single next bar regardless of the effort
            // window — an early, responsive confirm, not one delayed by the window length (M1).
            else confirmed = absBear ? close[i + 1] < close[i] - tol
                                     : close[i + 1] > close[i] + tol;
            out.add(new Immediate(i, absBear, confirmed));
        }
        return out;
    }

    // ── strength scoring (0..100) for the Minimal / filtered variant ────

    private static double strength(double[] cum, int prev, int cur, double cumRange) {
        if (cumRange <= 0) return 0.0;
        double mag = Math.abs(cum[cur] - cum[prev]) / cumRange;   // fraction of the visible cum range
        return Math.max(0.0, Math.min(100.0, mag * 100.0));
    }

    /** Max − min of {@code a[from..to)} (clamped to the array). Returns 0 for an empty span. */
    private static double range(double[] a, int from, int to) {
        from = Math.max(0, from); to = Math.min(a.length, to);
        if (to <= from) return 0.0;
        double mn = a[from], mx = a[from];
        for (int i = from + 1; i < to; i++) { if (a[i] < mn) mn = a[i]; if (a[i] > mx) mx = a[i]; }
        return mx - mn;
    }
}
