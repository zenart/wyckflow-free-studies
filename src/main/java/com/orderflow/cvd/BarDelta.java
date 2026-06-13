package com.orderflow.cvd;

/**
 * SDK-free per-bar CVD aggregator. Accumulates the running delta of one bar from a
 * sequence of classified trade ticks, tracking the intra-bar running-delta extremes
 * and the volume split. This is the single source of truth for the per-bar math that
 * {@code CumulativeVolumeDeltaStudy} feeds from both its live ({@code onTick}) and
 * historical ({@code forEachTick}) paths — extracted here so it can be unit-tested
 * without the MotiveWave SDK and so the two study paths cannot drift.
 *
 * <p>"Delta" everywhere is {@code askVolume − bidVolume}: a buyer-aggressor (ask) tick
 * adds {@code +volume}, a seller-aggressor (bid) tick adds {@code −volume}. The aggressor
 * side is the caller's classification (the study uses {@code Tick.isAskTick()}); this
 * class is agnostic to how that bit was derived.</p>
 */
public final class BarDelta {

    private double delta;     // running ask − bid (net) at the current point in the bar
    private double hiOff;     // max running delta reached in the bar (>= 0 by construction)
    private double loOff;     // min running delta reached in the bar (<= 0 by construction)
    private double totalVol;  // sum of all trade volume in the bar (ask + bid + unknown)
    private double askVol;    // volume classified buyer-aggressor
    private double bidVol;    // volume classified seller-aggressor

    /** Add one classified trade. {@code isAsk} = buyer-aggressor (ask tick). */
    public void add(double volume, boolean isAsk) {
        if (volume < 0) volume = 0;            // defensive: never let a bad tick subtract volume
        delta += isAsk ? volume : -volume;
        if (delta > hiOff) hiOff = delta;
        if (delta < loOff) loOff = delta;
        totalVol += volume;
        if (isAsk) askVol += volume; else bidVol += volume;
    }

    /** Net delta (ask − bid) at the end of the bar. */
    public double delta()    { return delta; }
    /** Highest running delta reached in the bar (offset relative to bar open; >= 0). */
    public double hiOffset() { return hiOff; }
    /** Lowest running delta reached in the bar (offset relative to bar open; <= 0). */
    public double loOffset() { return loOff; }
    /** Total traded volume in the bar. */
    public double totalVol() { return totalVol; }
    public double askVol()   { return askVol; }
    public double bidVol()   { return bidVol; }

    /**
     * One-sidedness of the bar in [0,1]: {@code |delta| / totalVol}. 0 = perfectly
     * balanced ask/bid, 1 = every contract on one side. Real exchange flow rarely
     * exceeds ~0.7 even on strong bars; a sustained ~1.0 is the fingerprint of
     * synthetic "Generated Ticks" (whose aggressor side is just price direction).
     * Returns 0 for an empty bar.
     */
    public double oneSidedness() {
        return totalVol > 0 ? Math.abs(delta) / totalVol : 0.0;
    }

    /** Immutable snapshot {@code [delta, hiOff, loOff, totalVol]} — the array shape the
     *  study caches per closed bar. */
    public double[] toArray() { return new double[]{ delta, hiOff, loOff, totalVol }; }
}
