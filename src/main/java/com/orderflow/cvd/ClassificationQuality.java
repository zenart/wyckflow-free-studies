package com.orderflow.cvd;

/**
 * SDK-free detector for <b>unreliable trade-aggressor classification</b> — the root
 * cause of the "CVD only rises" symptom.
 *
 * <p>MotiveWave delivers two kinds of tick history. Real exchange ticks carry a genuine
 * aggressor flag ({@code Tick.isAskTick()}), and aggregate over a day to a roughly
 * balanced ask/bid split with a delta that oscillates and frequently disagrees with the
 * bar's price direction. But for bars older than the feed's true-tick retention (≈10
 * days on CQG/CME) MotiveWave synthesises "Generated Ticks" (O/H/L/C per minute) whose
 * aggressor side is simply the price direction. On such bars every up-bar is ~100%
 * ask, every down-bar ~100% bid, so the cumulative delta becomes a meaningless monotonic
 * copy of price — it "only rises" in an uptrend. The sibling footprint study already
 * guards this per price-level ({@code VolumeImprintStudy.isUnreliableSplit}); this is the
 * equivalent guard at CVD's per-bar granularity (no per-level data needed — one-sidedness
 * is derivable as {@code |delta| / volume}).</p>
 *
 * <p>The detector is intentionally conservative: it fires only when, over a window of
 * bars, an overwhelming share are BOTH near-perfectly one-sided AND have their delta sign
 * equal to their price-change sign — the precise, jointly-unlikely fingerprint of
 * price-derived synthetic ticks. Genuine one-sided pushes (absorption, sweeps) are
 * neither perfectly one-sided nor perfectly sign-aligned across a whole window.</p>
 */
public final class ClassificationQuality {

    /** A bar at/above this one-sidedness ({@code |delta|/vol}) is "near-pure" one-sided. */
    public static final double PURE_ONE_SIDED = 0.95;
    /** Minimum bars in the window before a verdict is trustworthy. */
    public static final int    MIN_BARS       = 8;
    /** Window unreliable when at least this share of non-empty bars are near-pure
     *  one-sided AND sign(delta)==sign(priceChange). */
    public static final double UNRELIABLE_SHARE = 0.90;

    /** Result of {@link #assess}. */
    public static final class Result {
        public final boolean unreliable;
        public final int     barsConsidered;   // non-empty bars in the window
        public final double  pureOneSidedShare;// share that are near-pure one-sided
        public final double  signMatchShare;   // share where sign(delta)==sign(priceChange)
        public final double  fingerprintShare; // share that are BOTH (the joint test)
        Result(boolean u, int n, double p, double s, double f) {
            unreliable = u; barsConsidered = n;
            pureOneSidedShare = p; signMatchShare = s; fingerprintShare = f;
        }
    }

    private ClassificationQuality() {}

    /**
     * Assess the most recent {@code window} bars (or all if fewer).
     *
     * @param delta        per-bar net delta (ask − bid)
     * @param vol          per-bar total volume
     * @param priceChange  per-bar price change (e.g. close − open); only its sign is used
     * @param fromInclusive first bar index to consider
     * @param toInclusive   last bar index to consider (inclusive)
     */
    public static Result assess(double[] delta, double[] vol, double[] priceChange,
                                int fromInclusive, int toInclusive) {
        int considered = 0, pure = 0, signMatch = 0, both = 0;
        for (int i = Math.max(0, fromInclusive); i <= toInclusive && i < delta.length; i++) {
            if (vol[i] <= 0) continue;            // empty bar — no opinion
            considered++;
            double oneSided = Math.abs(delta[i]) / vol[i];
            boolean isPure = oneSided >= PURE_ONE_SIDED;
            boolean sMatch = sameSign(delta[i], priceChange[i]);
            if (isPure)  pure++;
            if (sMatch)  signMatch++;
            if (isPure && sMatch) both++;
        }
        if (considered < MIN_BARS) {
            return new Result(false, considered, frac(pure, considered),
                              frac(signMatch, considered), frac(both, considered));
        }
        double fingerprint = frac(both, considered);
        boolean unreliable = fingerprint >= UNRELIABLE_SHARE;
        return new Result(unreliable, considered, frac(pure, considered),
                          frac(signMatch, considered), fingerprint);
    }

    /** True when both have the same strict sign (both > 0 or both < 0). A zero on
     *  either side does NOT count as a match (synthetic ticks never produce a flat
     *  price-change with non-zero pure delta). */
    private static boolean sameSign(double a, double b) {
        return (a > 0 && b > 0) || (a < 0 && b < 0);
    }

    private static double frac(int num, int den) { return den > 0 ? (double) num / den : 0.0; }
}
