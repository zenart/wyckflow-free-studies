package com.orderflow;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.PathInfo;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.ColorDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.StringDescriptor;
import com.motivewave.platform.sdk.common.desc.SettingGroup;
import com.motivewave.platform.sdk.common.desc.SettingTab;
import com.motivewave.platform.sdk.common.desc.SettingsDescriptor;
import com.motivewave.platform.sdk.common.desc.ValueDescriptor;
import com.motivewave.platform.sdk.draw.Box;
import com.motivewave.platform.sdk.draw.Label;
import com.motivewave.platform.sdk.draw.Line;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.study.RuntimeDescriptor;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import com.orderflow.cvd.Adaptivity;
import com.orderflow.cvd.BarDelta;
import com.orderflow.cvd.ClassificationQuality;
import com.orderflow.cvd.CvdSeries;
import com.orderflow.cvd.DivergenceEngine;
import com.orderflow.cvd.SessionAnchor;
import com.orderflow.cvd.SessionWindow;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Community-edition replacement for MotiveWave's premium
 * {@code com.motivewave.platform.premium_study.order_flow.CumulativeDelta}.
 *
 * <p>Plots cumulative volume delta as <b>OHLC candles</b> in a separate plot,
 * where each candle captures the open / intra-bar high / intra-bar low / close
 * of the running delta within the bar.</p>
 *
 * <p>Two <b>display modes</b> (General → Display → "Display mode"):</p>
 * <ul>
 *   <li><b>Continuous</b> — the classic cumulative CVD: each candle's open = the
 *       previous candle's close, so the series is unbroken within a reset period
 *       and drops to zero only at a real reset boundary. Because session-anchored
 *       cumulative delta drifts a long way over a trending day (e.g. 0 → 100k),
 *       the per-bar bodies are small against that range — good for reading the
 *       overall trend + divergence, less so for reading individual bars.</li>
 *   <li><b>Per-bar (non-cumulative)</b> — each candle is drawn from zero to that
 *       bar's delta (open = 0, close = delta, high/low = the intra-bar running
 *       delta extremes). Candles oscillate around zero at a legible size, so the
 *       sign and magnitude of every bar's delta is obvious — equivalent to what
 *       MotiveWave's native "Delta Volume" candle shows. Divergence is still
 *       detected against the true cumulative delta (see below).</li>
 * </ul>
 *
 * <p>Detects <b>CVD divergence</b> against price — always computed on the true
 * cumulative delta, independent of display mode: bearish when price makes a
 * higher swing high but CVD a lower high; bullish when price makes a lower swing
 * low but CVD a higher low.</p>
 */
@StudyHeader(
    namespace      = "com.orderflow",
    id             = "ORDERFLOW_CVD",
    name           = "Cumulative Volume Delta (CVD)",
    desc           = "Cumulative volume delta drawn as OHLC candles with auto-fit scaling and "
                    + "automatic price/CVD divergence detection. Community-compatible.",
    menu           = "WyckFlow",
    overlay        = false,
    requiresVolume = true,
    requiresBidAskHistory = true,
    supportsBarUpdates = true,
    requiresBarUpdates = true,
    barUpdatesByDefault = true
)
public class CumulativeVolumeDeltaStudy extends Study {

    // ── Setting keys ────────────────────────────────────────────────
    private static final String S_RESET_MODE    = "resetMode";
    private static final String S_MAX_BARS      = "maxBars";
    private static final String S_UP_COLOR      = "upColor";
    private static final String S_DOWN_COLOR    = "downColor";
    private static final String S_SHOW_DIV      = "showDivergence";
    private static final String S_SHOW_HIDDEN   = "showHidden";
    private static final String S_DIV_LOOKBACK  = "divLookback";
    private static final String S_DIV_LINE      = "divLine";
    private static final String S_LIVE_DIV      = "liveDiv";       // provisional (early) divergence
    private static final String S_SHOW_VALUE    = "showValueTag";  // live CVD value tag on right edge
    private static final String S_RECALC_CLOSE  = "recalcOnClose"; // re-scan closed bars from forEachTick (stable across reload)
    // Immediate "effort vs result" divergence (per-bar / short window, no swing lag)
    private static final String S_IMM_DIV       = "immDiv";
    private static final String S_IMM_WINDOW    = "immWindow";     // bars (1 = single candle)
    private static final String S_IMM_EFFORT    = "immEffort";     // min CVD effort, % of avg bar delta
    private static final String S_IMM_PRICETOL  = "immPriceTol";   // price tolerance in ticks
    private static final String S_IMM_CONFIRM   = "immConfirm";    // require next-bar follow-through
    private static final String S_BULL_COLOR    = "bullColor";
    private static final String S_BEAR_COLOR    = "bearColor";
    private static final String S_SCALE_SESSION = "scaleSession";
    private static final String S_DISPLAY_MODE  = "displayMode";
    private static final String S_WARN_UNREL    = "warnUnreliable"; // warn on collapsed (generated-tick) classification
    private static final String S_DIAG          = "diagLogging";    // JSONL diagnostics
    private static final String S_READ_TRD      = "readTrd";        // read recorded .trd trades (real ticks) for closed bars
    private static final String S_TRD_DIR       = "trdDir";         // .trd archive folder (blank = Big Trades default)
    /** Default .trd archive folder — the Big Trades (TradeBubbles) recorder writes here, one
     *  subfolder per instrument symbol. Reading it gives the real native-resolution tape, which
     *  the coarser {@code forEachTick} does not (verified: .trd cumΔ matches the footprint). */
    private static final String DEFAULT_TRD_DIR =
        System.getProperty("user.home") + "/MotiveWave Extensions/logs/TradeBubbles/archive";
    // Per-bar Totals table at the bottom of the CVD panel (like the footprint Totals), toggleable.
    private static final String S_TOT_SHOW   = "totShow";
    private static final String S_TOT_VOL    = "totVol";
    private static final String S_TOT_DELTA  = "totDelta";
    private static final String S_TOT_CUM    = "totCum";
    private static final String S_TOT_DPCT   = "totDpct";
    private static final String S_TOT_BA     = "totBa";
    private static final String S_TOT_HEAT   = "totHeat";
    private static final String S_TOT_FONT   = "totFont";
    private static final String S_CHART_STYLE   = "chartStyle";     // candles / line / histogram
    private static final String S_DIV_STYLE     = "divStyle";       // glyphs / minimal / off
    private static final String S_DIV_SOURCE    = "divSource";      // swing / immediate / both
    private static final String S_DIV_MINSTR    = "divMinStrength"; // minimal-mode strength threshold
    private static final String S_IMM_FLOOR     = "immVolFloor";    // immediate volume floor on/off (C11)
    private static final String S_IMM_FLOORK    = "immFloorK";      // floor = k% × window × rolling-median vol
    // Per-session override: time-of-day param sets so London/NY/Asia each get their own
    // floor%/effort%/pivot without swapping templates. Sessions are checked in priority order
    // (first match wins on overlap, e.g. the London/NY overlap uses NY).
    private static final String S_PS_ENABLE     = "psEnable";
    private static final String[] PS_IDS        = { "ny", "london", "asia" };
    private static String  psKey(String id, String f) { return "ps_" + id + "_" + f; }
    private static String  psLabel(String id) { return "asia".equals(id) ? "Asia" : "london".equals(id) ? "London" : "New York"; }
    private static String  psTz(String id)    { return "asia".equals(id) ? "Asia/Tokyo" : "london".equals(id) ? "Europe/London" : "America/New_York"; }
    private static String  psStart(String id) { return "asia".equals(id) ? "09:00" : "london".equals(id) ? "08:00" : "09:30"; }
    private static String  psEnd(String id)   { return "asia".equals(id) ? "15:00" : "london".equals(id) ? "16:30" : "16:00"; }
    private static boolean psDefaultOn(String id) { return true; }   // all three on by default
    /** Per-session immediate effort %% default, derived empirically from the recorded NQ tape
     *  (≈ the 80th percentile of per-bar Δ%% in each session, so only the strongly one-sided bars
     *  qualify): thin Asia/London have naturally higher Δ%% than the 8×-thicker NY. */
    private static int     psEffort(String id) { return "asia".equals(id) ? 35 : "london".equals(id) ? 30 : 16; }
    // Sessions tab
    private static final String S_SESS_PRESET   = "sessPreset";
    private static final String S_SESS_TZ       = "sessTz";
    private static final String S_SESS_TIMES    = "sessTimes";

    // Reset modes (when the cumulative resets to zero) — backed by the SDK's
    // session helpers so they match MotiveWave's native session boundaries.
    private static final String RESET_NONE     = "none";
    private static final String RESET_DAY_RTH  = "dayRTH";  // regular session start
    private static final String RESET_DAY_ETH  = "dayETH";  // incl. evening (extended) session
    private static final String RESET_WEEK     = "week";
    private static final String RESET_MONTH    = "month";
    private static final String RESET_CUSTOM   = "custom";  // custom session times (Sessions tab)

    // Session presets → (zone id, reset times in minutes-of-day). "custom" uses
    // the timezone + times typed on the Sessions tab.
    private static final String SESS_CUSTOM    = "custom";

    // Display modes (how each candle is drawn).
    private static final String DISP_CONTINUOUS = "continuous"; // cumulative CVD (classic)
    private static final String DISP_PERBAR     = "perbar";     // each candle 0 → its delta

    // Chart render style (approved redesign): the CVD itself as candles / line / histogram.
    private static final String STYLE_CANDLES = "candles";
    private static final String STYLE_LINE    = "line";
    private static final String STYLE_HIST    = "hist";
    // Divergence marker style + source (approved V3 switcher).
    private static final String DIV_GLYPHS  = "glyphs";   // clean glyphs, all qualifying
    private static final String DIV_MINIMAL = "minimal";  // only the strongest / confirmed
    private static final String DIV_OFF     = "off";
    private static final String SRC_SWING = "swing";
    private static final String SRC_IMM   = "immediate";
    private static final String SRC_BOTH  = "both";

    // ── Data series keys (exported for legend / axis range) ──────────
    enum Values { DELTA, CUM, HI, LO }

    // ── Live aggregation state ──────────────────────────────────────
    /** Cache of closed-bar aggregates: bar start time → [delta, hiOff, loOff, totalVol]
     *  (offsets relative to the bar open; totalVol = sum of all trade volume). Closed
     *  bars never change, so they are scanned from tick history exactly once.
     *  <b>ConcurrentHashMap</b> because it is written on the feed thread ({@code onTick})
     *  and read on the calculate path ({@code recompute}); pruned to the drawn window in
     *  {@code recompute} so it can't grow without bound over a long session (AR-1). */
    private final Map<Long, double[]> barCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Start time of the bar currently being formed by live ticks. Volatile: published by the
     *  feed thread, read on the calculate path (AR-2). */
    private volatile long formingStart = Long.MIN_VALUE;
    /** Running delta / intra-bar high / low / total volume of the forming bar (volatile, AR-2). */
    private volatile double fRun, fHi, fLo, fVol;

    /** Resolved once per rebuild: whether to prefer the recorded .trd tape, and its root folder. */
    private volatile boolean rReadTrd = true;
    private volatile File rTrdRoot = new File(DEFAULT_TRD_DIR);

    private boolean liveReady = false;
    /** Min ms between live full redraws so fast feeds don't thrash. Each recompute is an O(n)
     *  full rebuild (arrays + per-bar figures), so ~4 fps is plenty for a delta panel and keeps
     *  the feed thread responsive — the Liquidity Heatmap throttles similarly (AR-8). */
    private static final long MIN_RENDER_INTERVAL_MS = 250;
    private volatile long lastRenderMs = 0;   // touched from feed + calculate threads (L2)

    /**
     * Returns [delta, hiOffset, loOffset, totalVol] of bar {@code i} (offsets relative
     * to the bar's open; totalVol = sum of all trade volume, used for the Δ% absorption
     * gate). Forming bar → live accumulator; closed bar → cache, scanning tick history
     * only on the first miss.
     */
    private double[] barAggregate(Instrument inst, int i, int last, long barStart, long barEnd) {
        if (i == last && formingStart == barStart) {
            return new double[]{fRun, fHi, fLo, fVol};
        }
        double[] cached = barCache.get(barStart);
        if (cached != null) return cached;

        // PREFER the recorded .trd tape (real native-resolution trades, classified by the live
        // isAskTick at record time) over forEachTick — which on historical data returns coarser,
        // more one-sided "generated"-style classification (verified: forEachTick gave ask/bid 58/42
        // and cumΔ ~47K where the real .trd tape is 51/49 and cumΔ ~5K, matching the footprint).
        // This is exactly the footprint's preference order. Aggregation is delegated to the
        // SDK-free, unit-tested BarDelta either way (no drift, AR-11).
        double[] st = null;
        if (rReadTrd && rTrdRoot != null && inst != null) {
            final BarDelta tbd = new BarDelta();
            final boolean[] any = { false };
            try {
                com.orderflow.trades.TradeArchive.forEach(rTrdRoot, inst.getSymbol(), barStart, barEnd - 1,
                    (time, priceTick, volume, buy) -> { tbd.add(volume, buy); any[0] = true; });
            } catch (Throwable ignored) { /* archive unavailable for this bar → fall through */ }
            if (any[0]) st = tbd.toArray();
        }
        if (st == null) {                                  // no .trd for this bar → forEachTick
            final BarDelta fbd = new BarDelta();
            try {
                inst.forEachTick(barStart, barEnd, t -> {
                    if (t != null) fbd.add(t.getVolumeAsFloat(), t.isAskTick());
                });
            } catch (Throwable ignored) { /* no tick history → flat */ }
            st = fbd.toArray();
        }
        if (i != last) barCache.put(barStart, st); // only cache closed bars
        return st;
    }

    /**
     * Per-tick hook (called for EVERY live tick per the MotiveWave SDK). This is
     * the reliable real-time entry point — onBarUpdate only fires when "live bar
     * updates" are enabled, so we drive the live candle from here directly.
     */
    @Override
    public void onTick(DataContext ctx, Tick tick) {
        if (ctx == null || tick == null) return;
        DataSeries series = ctx.getDataSeries();
        if (series == null) return;
        int last = series.size() - 1;
        if (last < 0) return;
        long barStart = series.getStartTime(last);
        boolean rollover = barStart != formingStart;
        if (rollover) {
            // Bar rolled over. By default DON'T freeze the live onTick accumulation:
            // MotiveWave's real-time tick stream differs slightly from its historical
            // forEachTick record (late prints / re-classification / consolidation), so a
            // frozen live value would NOT match what a chart reload/restart shows. Leaving
            // the just-closed bar uncached makes the next (forced) recompute re-scan it from
            // forEachTick — the same source used on reload — so live and post-restart agree.
            // Default (S_RECALC_CLOSE = false) FREEZES the live capture (more detailed; the
            // historical forEachTick can be coarser/generated). Turn it ON to instead re-scan
            // from forEachTick so closed-bar values stay identical after a reload/restart.
            if (formingStart != Long.MIN_VALUE && !getSettings().getBoolean(S_RECALC_CLOSE, false)) {
                barCache.put(formingStart, new double[]{fRun, fHi, fLo, fVol});
            }
            formingStart = barStart;
            fRun = 0.0; fHi = 0.0; fLo = 0.0; fVol = 0.0;
        }
        double tv = tick.getVolumeAsFloat();
        fRun += tv * (tick.isAskTick() ? 1.0 : -1.0);
        if (fRun > fHi) fHi = fRun;
        if (fRun < fLo) fLo = fRun;
        fVol += tv;

        // Redraw live via a throttled full recompute — the proven path shared with
        // the Liquidity Heatmap and Volume Imprint. Forced on rollover / first paint.
        liveRecompute(ctx, rollover || !liveReady);
    }

    @Override
    public void clearState() {
        barCache.clear();
        formingStart = Long.MIN_VALUE;
        fRun = fHi = fLo = fVol = 0.0;
        liveReady = false;
    }

    @Override
    public void initialize(Defaults defaults) {
        SettingsDescriptor sd = new SettingsDescriptor();
        setSettingsDescriptor(sd);

        SettingTab general = new SettingTab("General");
        sd.addTab(general);

        SettingGroup candles = new SettingGroup("Candles");
        general.addGroup(candles);
        candles.addRow(new ColorDescriptor(S_UP_COLOR,
            "Up candle (close ≥ open)", defaults.getGreen()));
        candles.addRow(new ColorDescriptor(S_DOWN_COLOR,
            "Down candle (close < open)", defaults.getRed()));

        SettingGroup reset = new SettingGroup("Reset");
        general.addGroup(reset);
        List<NVP> resetModes = new ArrayList<>();
        resetModes.add(new NVP("Session (regular / RTH)", RESET_DAY_RTH));
        resetModes.add(new NVP("Session incl. evening (ETH)", RESET_DAY_ETH));
        resetModes.add(new NVP("Weekly", RESET_WEEK));
        resetModes.add(new NVP("Monthly", RESET_MONTH));
        resetModes.add(new NVP("Custom session times (see Sessions tab)", RESET_CUSTOM));
        resetModes.add(new NVP("Never (continuous)", RESET_NONE));
        reset.addRow(new DiscreteDescriptor(S_RESET_MODE, "Reset cumulative", RESET_DAY_RTH, resetModes));

        SettingGroup scale = new SettingGroup("Display");
        general.addGroup(scale);
        List<NVP> dispModes = new ArrayList<>();
        dispModes.add(new NVP("Continuous (cumulative CVD)", DISP_CONTINUOUS));
        dispModes.add(new NVP("Per-bar delta (each candle 0 → its delta)", DISP_PERBAR));
        scale.addRow(new DiscreteDescriptor(S_DISPLAY_MODE, "Display mode", DISP_CONTINUOUS, dispModes));
        List<NVP> chartStyles = new ArrayList<>();
        chartStyles.add(new NVP("Candles", STYLE_CANDLES));
        chartStyles.add(new NVP("Line", STYLE_LINE));
        chartStyles.add(new NVP("Histogram (per-bar delta)", STYLE_HIST));
        scale.addRow(new DiscreteDescriptor(S_CHART_STYLE, "Chart style", STYLE_CANDLES, chartStyles)
            .setDescription("How the CVD is drawn: Candles (OHLC of running delta), Line (cumulative curve — "
                + "cleanest for divergence), or Histogram (per-bar delta bars from zero)."));
        scale.addRow(new BooleanDescriptor(S_SCALE_SESSION,
            "Scale axis to current session", true)
            .setDescription("Continuous mode only — fits the axis to the current session, ignoring earlier ones."));
        scale.addRow(new BooleanDescriptor(S_SHOW_VALUE,
            "Live CVD value tag (right edge)", true)
            .setDescription("Shows the current CVD at the right edge, updated per tick and coloured by sign."));
        scale.addRow(new BooleanDescriptor(S_RECALC_CLOSE,
            "Recompute closed bars from tick history", false)
            .setDescription("ON = closed bars match a reload/restart exactly. OFF/default = keep the richer "
                + "LIVE capture, which can differ slightly from the historical record."));
        scale.addRow(new IntegerDescriptor(S_MAX_BARS,
            "Render at most N recent candles", 300, 10, 5_000, 1));
        scale.addRow(new BooleanDescriptor(S_WARN_UNREL,
            "Warn on unreliable bid/ask split (generated ticks)", true)
            .setDescription("MotiveWave synthesises ticks from minute bars when true tick data is "
                + "unavailable (e.g. CQG keeps only ~10 days); their aggressor side is just the price "
                + "direction, so the cumulative delta degenerates into a monotonic copy of price. When "
                + "the recent window looks like that, a warning is shown so you don't trade a meaningless line."));

        SettingGroup div = new SettingGroup("Divergence");
        general.addGroup(div);
        List<NVP> divStyles = new ArrayList<>();
        divStyles.add(new NVP("Glyphs (clean markers)", DIV_GLYPHS));
        divStyles.add(new NVP("Minimal (only the strongest)", DIV_MINIMAL));
        divStyles.add(new NVP("Off", DIV_OFF));
        div.addRow(new DiscreteDescriptor(S_DIV_STYLE, "Divergence style", DIV_GLYPHS, divStyles)
            .setDescription("Glyphs = small clean triangles/dots (no frames). Minimal = only strong/confirmed "
                + "markers (least clutter). Off = no markers."));
        List<NVP> divSources = new ArrayList<>();
        divSources.add(new NVP("Swing + Immediate", SRC_BOTH));
        divSources.add(new NVP("Swing only", SRC_SWING));
        divSources.add(new NVP("Immediate only", SRC_IMM));
        div.addRow(new DiscreteDescriptor(S_DIV_SOURCE, "Divergence source", SRC_BOTH, divSources));
        div.addRow(new IntegerDescriptor(S_DIV_MINSTR,
            "  ↳ Minimal: min strength (0–100)", 35, 0, 100, 1)
            .setDescription("In Minimal style, hide swing divergences weaker than this and immediate dots that "
                + "haven't been confirmed."));
        div.addRow(new BooleanDescriptor(S_SHOW_DIV, "Detect regular divergence (exhaustion)", true));
        div.addRow(new BooleanDescriptor(S_SHOW_HIDDEN, "Detect hidden divergence (absorption)", false));
        div.addRow(new IntegerDescriptor(S_DIV_LOOKBACK,
            "Pivot strength (bars each side)", 5, 2, 50, 1));
        div.addRow(new BooleanDescriptor(S_DIV_LINE, "Draw divergence connector line", true));
        div.addRow(new BooleanDescriptor(S_LIVE_DIV,
            "Live (provisional) divergence — flag early on the forming swing, before the "
            + "pivot confirms (drawn faded; may vanish if price extends)", true));
        div.addRow(new BooleanDescriptor(S_IMM_DIV,
            "Immediate divergence (effort vs result) — CVD pushes one way but price doesn't "
            + "follow; shown on the candle where it arises (●, no swing lag)", true));
        div.addRow(new IntegerDescriptor(S_IMM_WINDOW,
            "  ↳ Effort window (bars; 1 = single candle)", 1, 1, 20, 1));
        div.addRow(new IntegerDescriptor(S_IMM_EFFORT,
            "  ↳ Min delta (% of bar volume — like the footprint Δ% row)", 10, 0, 100, 1));
        div.addRow(new IntegerDescriptor(S_IMM_PRICETOL,
            "  ↳ Price tolerance (ticks — how far the candle body may still move with the effort)", 0, 0, 100, 1));
        div.addRow(new BooleanDescriptor(S_IMM_CONFIRM,
            "  ↳ Confirm on next bar (solid when price follows through; faded until then)", true));
        div.addRow(new BooleanDescriptor(S_IMM_FLOOR,
            "  ↳ Auto volume floor (hide on thin low-volume bars)", true)
            .setDescription("Auto-adapts to the session: an immediate marker only fires when the bar's volume "
                + "reaches a fraction of the recent rolling median, so the thin London session stops spamming "
                + "markers while the heavy NY session still flags them. No template switching needed."));
        div.addRow(new IntegerDescriptor(S_IMM_FLOORK,
            "      floor = k% × rolling-median volume", 60, 0, 300, 5));
        div.addRow(new ColorDescriptor(S_BULL_COLOR, "Bullish divergence colour", defaults.getGreen()));
        div.addRow(new ColorDescriptor(S_BEAR_COLOR, "Bearish divergence colour", defaults.getRed()));

        SettingGroup data = new SettingGroup("Tick data");
        general.addGroup(data);
        data.addRow(new BooleanDescriptor(S_READ_TRD,
            "Use recorded trades (.trd) for closed bars", true)
            .setDescription("Reads the real native-resolution tape recorded by Big Trades, instead of "
                + "MotiveWave's coarser historical forEachTick (which classifies more one-sided on old "
                + "bars). This makes the delta match the footprint exactly. Bars seen live keep their "
                + "live capture; bars with no .trd fall back to forEachTick. Needs Big Trades recording on."));
        data.addRow(new StringDescriptor(S_TRD_DIR,
            "   .trd archive folder (blank = Big Trades default)", ""));

        SettingGroup diag = new SettingGroup("Diagnostics");
        general.addGroup(diag);
        diag.addRow(new BooleanDescriptor(S_DIAG,
            "Diagnostics logging (JSONL)", false)
            .setDescription("Writes a machine-readable event log (classification quality, ask/bid volume) "
                + "to logs/CumulativeVolumeDelta/ so feed/classification issues can be diagnosed. Off by default."));

        // ── Sessions tab: custom reset times (active when Reset = "Custom") ──
        SettingTab sessions = new SettingTab("Sessions");
        sd.addTab(sessions);
        SettingGroup sess = new SettingGroup("Custom session reset");
        sessions.addGroup(sess);
        List<NVP> presets = new ArrayList<>();
        presets.add(new NVP("New York RTH — 09:30 (America/New_York)", "nyRTH"));
        presets.add(new NVP("New York pre-market — 08:00 (America/New_York)", "nyPre"));
        presets.add(new NVP("CME Globex open — 17:00 (America/Chicago)", "cme"));
        presets.add(new NVP("London — 08:00 (Europe/London)", "london"));
        presets.add(new NVP("Frankfurt — 09:00 (Europe/Berlin)", "frankfurt"));
        presets.add(new NVP("Tokyo — 09:00 (Asia/Tokyo)", "tokyo"));
        presets.add(new NVP("Sydney — 10:00 (Australia/Sydney)", "sydney"));
        presets.add(new NVP("Custom (timezone + times below)", SESS_CUSTOM));
        sess.addRow(new DiscreteDescriptor(S_SESS_PRESET, "Session preset", "nyRTH", presets));

        List<NVP> zones = new ArrayList<>();
        zones.add(new NVP("America/New_York", "America/New_York"));
        zones.add(new NVP("America/Chicago", "America/Chicago"));
        zones.add(new NVP("Europe/London", "Europe/London"));
        zones.add(new NVP("Europe/Berlin", "Europe/Berlin"));
        zones.add(new NVP("Europe/Prague", "Europe/Prague"));
        zones.add(new NVP("Asia/Tokyo", "Asia/Tokyo"));
        zones.add(new NVP("Asia/Shanghai", "Asia/Shanghai"));
        zones.add(new NVP("Australia/Sydney", "Australia/Sydney"));
        zones.add(new NVP("UTC", "UTC"));
        sess.addRow(new DiscreteDescriptor(S_SESS_TZ, "Custom timezone", "America/New_York", zones));
        sess.addRow(new StringDescriptor(S_SESS_TIMES,
            "Custom reset times (HH:MM, comma-separated)", "09:30"));

        // ── Per-session tab: auto-switch divergence params by time-of-day ──
        SettingTab psTab = new SettingTab("Per-session");
        sd.addTab(psTab);
        SettingGroup psMaster = new SettingGroup("Per-session override");
        psTab.addGroup(psMaster);
        psMaster.addRow(new BooleanDescriptor(S_PS_ENABLE,
            "Enable per-session override", true)
            .setDescription("When on, the divergence parameters below auto-switch by the current "
                + "time-of-day (tz-aware), so London / NY / Asia each keep their own tuning without "
                + "swapping templates. Off = the single global auto-adaptation is used everywhere. "
                + "On overlap (e.g. London+NY) the first matching session wins: NY, then London, then Asia."));
        for (String id : PS_IDS) {
            SettingGroup g = new SettingGroup(psLabel(id) + " session");
            psTab.addGroup(g);
            g.addRow(new BooleanDescriptor(psKey(id, "on"), "Use this session", psDefaultOn(id)));
            List<NVP> z = new ArrayList<>();
            for (String tz : new String[]{"America/New_York", "America/Chicago", "Europe/London",
                    "Europe/Berlin", "Europe/Prague", "Asia/Tokyo", "Asia/Shanghai", "Australia/Sydney", "UTC"})
                z.add(new NVP(tz, tz));
            g.addRow(new DiscreteDescriptor(psKey(id, "tz"), "Timezone", psTz(id), z));
            g.addRow(new StringDescriptor(psKey(id, "start"), "Start (HH:MM)", psStart(id)));
            g.addRow(new StringDescriptor(psKey(id, "end"),   "End (HH:MM)",   psEnd(id)));
            g.addRow(new IntegerDescriptor(psKey(id, "floork"), "Immediate volume floor (k%)", 60, 0, 300, 5)
                .setDescription("Only applies when the global 'Auto volume floor' (Divergence tab) is on."));
            g.addRow(new IntegerDescriptor(psKey(id, "effort"), "Immediate min delta (%)", psEffort(id), 0, 100, 1));
            // Pivot strength is a structural/timeframe setting (global, Divergence tab) — it is NOT
            // overridden per session, so swing detection stays stable as the live session changes.
        }

        // ── Totals tab: per-bar summary table at the bottom of the CVD panel ──
        SettingTab totals = new SettingTab("Totals");
        sd.addTab(totals);
        SettingGroup totRows = new SettingGroup("Totals table");
        totals.addGroup(totRows);
        totRows.addRow(new BooleanDescriptor(S_TOT_SHOW, "Show totals table", true)
            .setDescription("A per-bar summary band at the bottom of the CVD panel (like the footprint "
                + "Totals table) — one column per candle, so you can read the exact delta numbers and "
                + "compare them with the footprint."));
        totRows.addRow(new BooleanDescriptor(S_TOT_VOL,   "Volume (total per bar)", false));
        totRows.addRow(new BooleanDescriptor(S_TOT_DELTA, "Δ — bar delta (ask − bid)", true));
        totRows.addRow(new BooleanDescriptor(S_TOT_CUM,   "CumΔ — cumulative delta (reset-mode)", true));
        totRows.addRow(new BooleanDescriptor(S_TOT_DPCT,  "Δ% — delta / volume", true));
        totRows.addRow(new BooleanDescriptor(S_TOT_BA,    "B/A% — bid/ask split", false));
        totRows.addRow(new BooleanDescriptor(S_TOT_HEAT,  "Colour cells by magnitude (heat)", true));
        totRows.addRow(new IntegerDescriptor(S_TOT_FONT,  "Totals font size", 10, 6, 24, 1));

        RuntimeDescriptor desc = new RuntimeDescriptor();
        setRuntimeDescriptor(desc);

        desc.exportValue(new ValueDescriptor(Values.CUM,   "CVD (displayed)", new String[]{}));
        desc.exportValue(new ValueDescriptor(Values.DELTA, "Bar Delta", new String[]{}));
        desc.exportValue(new ValueDescriptor(Values.HI,    "CVD High", new String[]{}));
        desc.exportValue(new ValueDescriptor(Values.LO,    "CVD Low", new String[]{}));

        // Drive the plot's value axis from the displayed candle high/low range.
        desc.setRangeKeys(Values.HI, Values.LO);
        desc.setMinTick(1.0); // axis in whole contracts
        desc.setLabelSettings();

        // Zero baseline for readability.
        desc.addHorizontalLine(new com.motivewave.platform.sdk.common.LineInfo(
            0.0, null, 1.0f, new float[]{3, 3}));
    }

    // Repaint figures whenever we rebuild them live (matches LiquidityHeatmap).
    @Override
    public boolean isRepaintAllOnUpdate() { return true; }

    // Whole-series computation hook: called for the full series on load / settings
    // change. The per-bar tick cache keeps it cheap.
    @Override
    protected void calculateValues(DataContext ctx) {
        recompute(ctx);
    }

    // Live hooks — redraw intra-bar, not only on bar close. onTick covers the
    // per-tick case; these add coverage when only bar-updates are delivered.
    @Override public void onBarOpen(DataContext ctx)   { liveRecompute(ctx, true);  }
    @Override public void onBarUpdate(DataContext ctx) { liveRecompute(ctx, false); }
    @Override public void onBarClose(DataContext ctx)  { liveRecompute(ctx, true);  }

    /** Throttled full redraw; {@code force} bypasses the throttle (open/close/rollover). */
    private void liveRecompute(DataContext ctx, boolean force) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastRenderMs < MIN_RENDER_INTERVAL_MS) return;
        lastRenderMs = now;
        recompute(ctx);
    }

    /** Guard wrapper: a study must NEVER throw into MotiveWave's calculate/render path (a
     *  partially-initialised settings read, a feed hiccup, or a bad figure could otherwise
     *  break the chart). Any failure is logged and swallowed (AR-10). */
    private void recompute(DataContext ctx) {
        try {
            recomputeImpl(ctx);
        } catch (Throwable t) {
            try { error("CVD recompute failed: " + t); } catch (Throwable ignored) { /* never throw */ }
        }
    }

    private void recomputeImpl(DataContext ctx) {
        if (ctx == null) return;
        DataSeries series = ctx.getDataSeries();
        if (series == null || series.size() == 0) return;
        Instrument inst = ctx.getInstrument();
        if (inst == null) return;

        clearFigures();

        Color up   = orDefault(getSettings().getColor(S_UP_COLOR),   ctx.getDefaults().getGreen());
        Color down = orDefault(getSettings().getColor(S_DOWN_COLOR), ctx.getDefaults().getRed());
        Color bull = orDefault(getSettings().getColor(S_BULL_COLOR), ctx.getDefaults().getGreen());
        Color bear = orDefault(getSettings().getColor(S_BEAR_COLOR), ctx.getDefaults().getRed());
        String resetMode = getSettings().getString(S_RESET_MODE, RESET_DAY_RTH);
        boolean showDiv = getSettings().getBoolean(S_SHOW_DIV, true);
        boolean showHidden = getSettings().getBoolean(S_SHOW_HIDDEN, false);
        boolean divLine = getSettings().getBoolean(S_DIV_LINE, true);
        boolean liveDiv = getSettings().getBoolean(S_LIVE_DIV, true);
        boolean showValue = getSettings().getBoolean(S_SHOW_VALUE, true);
        boolean scaleSession = getSettings().getBoolean(S_SCALE_SESSION, true);
        boolean perBar = DISP_PERBAR.equals(getSettings().getString(S_DISPLAY_MODE, DISP_CONTINUOUS));
        int maxBars     = Math.max(10, getSettings().getInteger(S_MAX_BARS, 300));
        int pivotL      = Math.max(2,  getSettings().getInteger(S_DIV_LOOKBACK, 5));
        boolean immDiv     = getSettings().getBoolean(S_IMM_DIV, true);
        int immWindow      = Math.max(1, getSettings().getInteger(S_IMM_WINDOW, 1));
        double immDeltaPct = Math.max(0, getSettings().getInteger(S_IMM_EFFORT, 10)) / 100.0; // min |Δ| as fraction of bar volume
        boolean immConfirm = getSettings().getBoolean(S_IMM_CONFIRM, true);
        double tickSize    = inst.getTickSize() > 0 ? inst.getTickSize() : 0.25;
        double immTol      = Math.max(0, getSettings().getInteger(S_IMM_PRICETOL, 0)) * tickSize;
        String chartStyle  = getSettings().getString(S_CHART_STYLE, STYLE_CANDLES);
        String divStyle    = getSettings().getString(S_DIV_STYLE, DIV_GLYPHS);
        String divSource   = getSettings().getString(S_DIV_SOURCE, SRC_BOTH);
        int divMinStr      = Math.max(0, getSettings().getInteger(S_DIV_MINSTR, 35));
        boolean immFloorOn = getSettings().getBoolean(S_IMM_FLOOR, true);
        double immFloorK   = immFloorOn ? Math.max(0, getSettings().getInteger(S_IMM_FLOORK, 60)) / 100.0 : 0.0;
        // Resolve the .trd tape source once (used per closed bar in barAggregate).
        rReadTrd = getSettings().getBoolean(S_READ_TRD, true);
        String trdDir = getSettings().getString(S_TRD_DIR, "");
        rTrdRoot = new File((trdDir != null && !trdDir.trim().isEmpty()) ? trdDir.trim() : DEFAULT_TRD_DIR);

        // Resolve custom-session config once (zone + reset minutes-of-day).
        ZoneId sessZone = null;
        int[] sessMins  = null;
        if (RESET_CUSTOM.equals(resetMode)) {
            Object[] cfg = resolveSessionConfig();
            if (cfg != null) { sessZone = (ZoneId) cfg[0]; sessMins = (int[]) cfg[1]; }
        }

        int n = series.size();
        int last = n - 1;

        // Per-session override resolves the immediate-divergence params (effort %, volume floor)
        // PER BAR — each bar uses its OWN session's tuning (a London bar uses London params, an NY
        // bar uses NY), so historical markers don't flip when the live session changes (BR-2). The
        // arrays are built once below and passed to the engine; null = the single global tuning.
        double[][] psImm = perBarImmParams(series, n, immDeltaPct, immFloorK);

        // Displayed CVD candles. The reset-mode CUMULATIVE (cumTrue) is always
        // computed — it is the basis for divergence detection and for the
        // Continuous display. Each candle's open = the previous candle's close,
        // so the series is unbroken within a reset period, dropping to zero only
        // at a real reset boundary (session / week / month / custom times / never).
        //   • Continuous mode → drawn OHLC follows that cumulative.
        //   • Per-bar mode    → each candle is drawn from zero to its own delta
        //     (open = 0, close = delta, high/low = intra-bar running-delta
        //     extremes) so the per-bar sign/size is legible around zero.
        double[] delta = new double[n], hiOff = new double[n], loOff = new double[n];
        double[] vol = new double[n];      // total bar volume (for the Δ% absorption gate)
        long[] bnd = new long[n];          // reset-boundary key per bar (for session scaling)
        for (int i = 0; i < n; i++) {
            long barStart = series.getStartTime(i);
            // [delta, hiOffset, loOffset, totalVol] relative to the bar open. Closed bars are
            // cached (scanned once); the forming bar uses the live onTick accumulator.
            double[] agg = barAggregate(inst, i, last, barStart, series.getEndTime(i));
            delta[i] = agg[0]; hiOff[i] = agg[1]; loOff[i] = agg[2];
            vol[i]   = agg.length > 3 ? agg[3] : 0.0;
            bnd[i]   = (sessMins != null) ? SessionAnchor.anchor(barStart, sessZone, sessMins)
                                          : resetBoundary(inst, barStart, resetMode);
        }
        // Cumulative chaining + per-bar/continuous OHLC delegated to the SDK-free, unit-tested
        // CvdSeries so the running code IS the golden-tested code (no live/historical drift, AR-11).
        CvdSeries cs = CvdSeries.build(delta, hiOff, loOff, bnd, perBar);
        double[] od = cs.open, hd = cs.high, ld = cs.low, cd = cs.close, cumTrue = cs.cumTrue;

        // Bound the closed-bar cache to the loaded series so it can't grow without limit over a
        // long session (AR-1). Keys for bars MotiveWave has dropped from the series never recur.
        if (n > 0) { long oldest = series.getStartTime(0); barCache.keySet().removeIf(k -> k < oldest); }

        int firstBar = Math.max(0, n - maxBars);
        // Scale to the current session: start drawing/scaling at the most recent
        // reset boundary so the axis fits this session, not the previous day's.
        // Only meaningful in Continuous mode — per-bar candles already sit around
        // zero, so they keep the full maxBars window for spotting bar-to-bar shifts.
        if (scaleSession && !perBar && !RESET_NONE.equals(resetMode)) {
            int sessStart = last;
            while (sessStart > 0 && bnd[sessStart - 1] == bnd[last]) sessStart--;
            firstBar = Math.max(firstBar, sessStart);
        }

        // Export values for the drawn window — drives the plot's value axis.
        for (int i = 0; i < n; i++) {
            if (i < firstBar) {
                series.setDouble(i, Values.HI, null);
                series.setDouble(i, Values.LO, null);
                series.setDouble(i, Values.CUM, null);
                series.setDouble(i, Values.DELTA, null);
            } else {
                // Axis range follows the active chart style so Line / Histogram fit too.
                double axHi, axLo;
                if (STYLE_HIST.equals(chartStyle)) {
                    double d = cd[i] - od[i];                          // per-bar delta bar from zero
                    axHi = Math.max(0.0, d); axLo = Math.min(0.0, d);
                } else {
                    // Candles AND Line: drive the axis by the running-delta high/low. The line (cd)
                    // always sits within [ld, hd], so the axis is never zero-height even when the
                    // cumulative is momentarily flat (AR-4: HI==LO would collapse the Line axis).
                    axHi = hd[i]; axLo = ld[i];
                }
                series.setDouble(i, Values.HI,    axHi);
                series.setDouble(i, Values.LO,    axLo);
                series.setDouble(i, Values.CUM,   cd[i]);
                series.setDouble(i, Values.DELTA, cd[i] - od[i]);
            }
        }

        liveReady = true;

        // ── Draw candles + divergence (batched; repainted via isRepaintAllOnUpdate) ──
        beginFigureUpdate();
        try {
            // ── Divergence is computed FIRST so the divergence candles can be RECOLOURED ─
            // Detection delegated to the SDK-free, unit-tested DivergenceEngine (no drift).
            // ALWAYS computed on the true reset cumulative (cumTrue). We collect: marker glyphs,
            // connector lines, and a per-bar recolour map; then draw the (recoloured) candles,
            // then the connectors + glyphs on top. Marker placement (user spec): bearish ABOVE
            // the candle high, bullish BELOW the candle low.
            boolean divOff  = DIV_OFF.equals(divStyle);
            boolean minimal = DIV_MINIMAL.equals(divStyle);
            boolean wantSwing = !divOff && !SRC_IMM.equals(divSource);
            boolean wantImm   = !divOff && !SRC_SWING.equals(divSource);
            boolean candleStyle = STYLE_CANDLES.equals(chartStyle);
            boolean histStyle   = STYLE_HIST.equals(chartStyle);
            java.util.List<DivMark> divMarks = new ArrayList<>();
            java.util.List<Line> connectors = new ArrayList<>();
            java.util.Map<Integer, Color> divColors = new java.util.HashMap<>();   // bar index → recolour
            // Style-aware anchor: Candles/Line use the displayed close (cd); Histogram uses the
            // per-bar delta (cd − od) so markers/lines stay inside the active axis range.
            double[] dispY = new double[n];
            for (int i = 0; i < n; i++) dispY[i] = histStyle ? (cd[i] - od[i]) : cd[i];

            if (wantSwing && (showDiv || showHidden)) {
                double[] hiA = new double[n], loA = new double[n];
                for (int i = 0; i < n; i++) { hiA[i] = series.getHigh(i); loA[i] = series.getLow(i); }
                for (DivergenceEngine.Swing s :
                        DivergenceEngine.swings(hiA, loA, cumTrue, bnd, firstBar, n, pivotL, showDiv, showHidden)) {
                    if (minimal && s.strength < divMinStr) continue;       // Minimal: hide weak swings
                    boolean bearish = s.kind == DivergenceEngine.Kind.REG_BEAR || s.kind == DivergenceEngine.Kind.HID_BEAR;
                    boolean hidden  = s.kind == DivergenceEngine.Kind.HID_BEAR || s.kind == DivergenceEngine.Kind.HID_BULL;
                    Color col = bearish ? bear : bull;
                    double markY = candleStyle ? (bearish ? hd[s.curIdx] : ld[s.curIdx]) : dispY[s.curIdx];
                    if (divLine)
                        connectors.add(new Line(series.getStartTime(s.prevIdx), dispY[s.prevIdx],
                                                series.getStartTime(s.curIdx), dispY[s.curIdx],
                                                new PathInfo(col, 1.4f, new float[]{5, 4}, true, false, true, 0, null)));
                    divMarks.add(new DivMark(series.getStartTime(s.curIdx), markY,
                            bearish ? Glyph.TRI_DOWN : Glyph.TRI_UP, hidden, col, false, bearish));
                    divColors.put(s.curIdx, col);                          // recolour the divergence candle
                }
                // Provisional (live) swing — last confirmed pivot vs a forming-tail extreme.
                if (liveDiv && !minimal) {                                 // suppress provisional in Minimal
                    int hi = n - 1 - pivotL;
                    long curBnd = bnd[n - 1];                              // active reset period
                    int prevHi = lastPivot(hiA, bnd, curBnd, firstBar, pivotL, n, true);
                    int prevLo = lastPivot(loA, bnd, curBnd, firstBar, pivotL, n, false);
                    int p = provisionalPivotHigh(series, n, firstBar, pivotL);
                    if (p > hi && prevHi >= 0 && bnd[p] == bnd[prevHi]) {
                        boolean priceHH = series.getHigh(p) > series.getHigh(prevHi);
                        double my = candleStyle ? hd[p] : dispY[p];
                        if (showDiv && priceHH && cumTrue[p] < cumTrue[prevHi])
                            divMarks.add(new DivMark(series.getStartTime(p), my, Glyph.TRI_DOWN, false, bear, true, true));
                        else if (showHidden && !priceHH && cumTrue[p] > cumTrue[prevHi])
                            divMarks.add(new DivMark(series.getStartTime(p), my, Glyph.TRI_DOWN, true, bear, true, true));
                    }
                    int q = provisionalPivotLow(series, n, firstBar, pivotL);
                    if (q > hi && prevLo >= 0 && bnd[q] == bnd[prevLo]) {
                        boolean priceLL = series.getLow(q) < series.getLow(prevLo);
                        double my = candleStyle ? ld[q] : dispY[q];
                        if (showDiv && priceLL && cumTrue[q] > cumTrue[prevLo])
                            divMarks.add(new DivMark(series.getStartTime(q), my, Glyph.TRI_UP, false, bull, true, false));
                        else if (showHidden && !priceLL && cumTrue[q] < cumTrue[prevLo])
                            divMarks.add(new DivMark(series.getStartTime(q), my, Glyph.TRI_UP, true, bull, true, false));
                    }
                }
            }

            // Immediate "effort vs result" divergence (no swing lag) + AUTO VOLUME FLOOR (C11):
            // suppressed on thin low-volume bars (quiet London) so it stops spamming, still
            // fires on heavy NY — no template switching. Dot above the high (bear) / below the
            // low (bull); confirmed dots recolour their candle.
            if (wantImm && immDiv) {
                double[] clA = new double[n], opA = new double[n];
                for (int i = 0; i < n; i++) { clA[i] = series.getClose(i); opA[i] = series.getOpen(i); }
                java.util.List<DivergenceEngine.Immediate> imms = (psImm != null)
                    ? DivergenceEngine.immediate(clA, opA, cumTrue, vol, bnd, firstBar, last, immWindow,
                            psImm[0], immTol, immConfirm, psImm[1], Adaptivity.DEFAULT_WINDOW)
                    : DivergenceEngine.immediate(clA, opA, cumTrue, vol, bnd, firstBar, last, immWindow,
                            immDeltaPct, immTol, immConfirm, immFloorK, Adaptivity.DEFAULT_WINDOW);
                for (DivergenceEngine.Immediate im : imms) {
                    if (minimal && !im.confirmed) continue;                // Minimal: only confirmed dots
                    Color col = im.bear ? bear : bull;
                    double dotY = candleStyle ? (im.bear ? hd[im.idx] : ld[im.idx]) : dispY[im.idx];
                    divMarks.add(new DivMark(series.getStartTime(im.idx), dotY, Glyph.DOT, false, col, !im.confirmed, im.bear));
                    if (im.confirmed) divColors.put(im.idx, col);          // recolour confirmed immediate candle
                }
            }

            // ── CVD render: Candles / Line / Histogram (divergence bars RECOLOURED) ──
            if (STYLE_LINE.equals(chartStyle)) {
                for (int i = Math.max(firstBar + 1, 1); i < n; i++) {
                    if (bnd[i] != bnd[i - 1]) continue;                    // don't connect across a reset
                    Color seg = cd[i] >= cd[i - 1] ? up : down;
                    addFigure(new Line(series.getStartTime(i - 1), cd[i - 1],
                                       series.getStartTime(i), cd[i], solid(seg, 1.5f)));
                }
            } else if (histStyle) {
                for (int i = firstBar; i < n; i++) {
                    Color ov = divColors.get(i);
                    drawHistBar(series.getStartTime(i), series.getEndTime(i), cd[i] - od[i],
                                ov != null ? ov : up, ov != null ? ov : down);
                }
            } else {
                for (int i = firstBar; i < n; i++) {
                    Color ov = divColors.get(i);
                    drawCandle(null, series.getStartTime(i), series.getEndTime(i), od[i], hd[i], ld[i], cd[i],
                               ov != null ? ov : up, ov != null ? ov : down);
                }
            }

            for (Line c : connectors) addFigure(c);                        // connectors on top of candles
            if (!divMarks.isEmpty()) addFigure(new DivergenceLayer(divMarks));

            // ── Live CVD value tag (right edge, updates per tick) ────────────
            if (showValue && last >= firstBar) {
                double v = cd[last];
                Color tagCol = v >= 0 ? up : down;
                Font tagFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);
                Label tag = new Label((v >= 0 ? "+" : "") + Math.round(v),
                                      tagFont, tagCol, new Color(0, 0, 0, 175));
                tag.setLocation(series.getEndTime(last), v);
                addFigure(tag);
            }

            // ── Per-bar Totals table (bottom band, like the footprint Totals) ─
            // One column per drawn candle: Δ / CumΔ / Δ% / Vol / B-A%, so you can read the
            // exact numbers and compare 1:1 with the footprint. Pixel-space fixed Figure
            // pinned to the bottom via getBounds() (the SDK-correct pattern, same as footprint).
            if (getSettings().getBoolean(S_TOT_SHOW, true) && n > firstBar) {
                int cols = n - firstBar;
                long[] tStart = new long[cols], tEnd = new long[cols];
                final double[] dRow = new double[cols], cRow = new double[cols],
                               pRow = new double[cols], vRow = new double[cols];
                final int[] baBid = new int[cols];
                for (int i = firstBar; i < n; i++) {
                    int k = i - firstBar;
                    tStart[k] = series.getStartTime(i); tEnd[k] = series.getEndTime(i);
                    double d = cd[i] - od[i], vv = vol[i];
                    dRow[k] = d; cRow[k] = cumTrue[i]; vRow[k] = vv;
                    pRow[k]  = vv > 0 ? d / vv * 100.0 : 0.0;
                    baBid[k] = vv > 0 ? (int) Math.round((vv - d) / 2.0 / vv * 100.0) : 50;
                }
                java.util.List<TotalsRow> trows = new ArrayList<>();
                if (getSettings().getBoolean(S_TOT_VOL, false))
                    trows.add(totRow("Vol", cols, k -> fmtNum(vRow[k], false), k -> (byte) 0, k -> vRow[k], false));
                if (getSettings().getBoolean(S_TOT_DELTA, true))
                    trows.add(totRow("Δ", cols, k -> fmtNum(dRow[k], true), k -> sgn(dRow[k]), k -> Math.abs(dRow[k]), true));
                if (getSettings().getBoolean(S_TOT_CUM, true))
                    trows.add(totRow("CumΔ", cols, k -> fmtNum(cRow[k], true), k -> sgn(cRow[k]), k -> Math.abs(cRow[k]), true));
                if (getSettings().getBoolean(S_TOT_DPCT, true))
                    trows.add(totRow("Δ%", cols, k -> Math.round(pRow[k]) + "%", k -> sgn(dRow[k]), k -> Math.abs(pRow[k]), true));
                if (getSettings().getBoolean(S_TOT_BA, false))
                    trows.add(totRow("B/A%", cols, k -> baBid[k] + "/" + (100 - baBid[k]), k -> (byte) 0, k -> (double) Math.abs(baBid[k] - 50), false));
                if (!trows.isEmpty()) {
                    int fsize = Math.max(6, getSettings().getInteger(S_TOT_FONT, 10));
                    addFigure(new TotalsTable(tStart, tEnd, trows,
                            new Font(Font.SANS_SERIF, Font.PLAIN, fsize), up, down,
                            ctx.getDefaults().getTextColor(), new Color(16, 16, 22, 205),
                            getSettings().getBoolean(S_TOT_HEAT, true)));
                }
            }

            // ── Classification-quality safeguard (P0.6 / C2) ─────────────────
            // MotiveWave synthesises "Generated Ticks" from minute bars when true
            // tick data is unavailable (CQG retains only ~10 days); their aggressor
            // side is just the price direction, so the cumulative delta degenerates
            // into a monotonic copy of price — the reported "CVD only rises" symptom.
            // Detect that fingerprint over the drawn window and warn, rather than
            // silently drawing a meaningless line. Per-bar delta = cd − od in BOTH
            // display modes (per-bar: od=0; continuous: cd−od = bar delta).
            if (last >= firstBar) {
                int wlen = last - firstBar + 1;
                double[] wd = new double[wlen], wv = new double[wlen], wp = new double[wlen];
                for (int i = firstBar; i <= last; i++) {
                    int k = i - firstBar;
                    wd[k] = cd[i] - od[i];
                    wv[k] = vol[i];
                    wp[k] = series.getClose(i) - series.getOpen(i);
                }
                ClassificationQuality.Result q = ClassificationQuality.assess(wd, wv, wp, 0, wlen - 1);
                if (q.unreliable && getSettings().getBoolean(S_WARN_UNREL, true)) {
                    addFigure(new WarnBadge(
                        "CVD: unreliable bid/ask split (generated ticks) — delta is tracking price, not order flow"));
                }
                if (getSettings().getBoolean(S_DIAG, false)) logDiag(ctx, inst, wd, wv, q);
            }
        } finally {
            endFigureUpdate();
        }
        notifyRedraw();
    }

    // ── Diagnostics (JSONL) — opt-in; never throws into the study ───────────
    private String runId = null;
    private boolean runLogged = false;
    private String lastDiagSig = "";

    private void logDiag(DataContext ctx, Instrument inst, double[] wd, double[] wv,
                         ClassificationQuality.Result q) {
        try {
            if (runId == null) runId = "cvd-" + Long.toHexString(System.currentTimeMillis());
            double askVol = 0, bidVol = 0;
            for (int k = 0; k < wd.length; k++) { askVol += (wv[k] + wd[k]) / 2.0; bidVol += (wv[k] - wd[k]) / 2.0; }
            String instr = "?"; try { instr = inst.getSymbol(); } catch (Throwable ignored) {}
            String mode = "LIVE"; try { if (ctx.isReplayMode()) mode = "REPLAY"; } catch (Throwable ignored) {}
            File dir = new File(new File(System.getProperty("user.home"),
                    "MotiveWave Extensions/logs"), "CumulativeVolumeDelta");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "CumulativeVolumeDelta_events.jsonl");
            long ts = System.currentTimeMillis();
            if (!runLogged) {
                append(f, "{\"ts\":" + ts + ",\"runId\":\"" + runId + "\",\"mode\":\"" + mode
                    + "\",\"study\":\"CumulativeVolumeDelta\",\"event\":\"RUN_START\",\"instr\":\"" + instr
                    + "\",\"resetMode\":\"" + getSettings().getString(S_RESET_MODE, RESET_DAY_RTH) + "\"}");
                runLogged = true;
            }
            String sig = q.unreliable + ":" + (int) (q.fingerprintShare * 20) + ":" + wd.length;
            if (sig.equals(lastDiagSig)) return;   // throttle: emit only when the signature changes
            lastDiagSig = sig;
            append(f, "{\"ts\":" + ts + ",\"runId\":\"" + runId + "\",\"mode\":\"" + mode
                + "\",\"study\":\"CumulativeVolumeDelta\",\"event\":\"DIAG\",\"instr\":\"" + instr
                + "\",\"bars\":" + wd.length + ",\"askVol\":" + Math.round(askVol) + ",\"bidVol\":" + Math.round(bidVol)
                + ",\"unreliable\":" + q.unreliable + ",\"fingerprint\":" + fmt(q.fingerprintShare)
                + ",\"pureOneSided\":" + fmt(q.pureOneSidedShare) + ",\"signMatch\":" + fmt(q.signMatchShare) + "}");
        } catch (Throwable ignored) { /* logging must never throw into the study */ }
    }

    private static void append(File f, String line) {
        try (FileWriter w = new FileWriter(f, true)) { w.write(line); w.write("\n"); }
        catch (Throwable ignored) {}
    }

    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.3f", v); }

    /** Most-recent provisional pivot HIGH in the unconfirmed tail: a bar strictly
     *  higher than the {@code pivotL} bars to its LEFT and not exceeded by any bar
     *  to its RIGHT (so the forming bar qualifies). Returns -1 if none. */
    private static int provisionalPivotHigh(DataSeries series, int n, int firstBar, int pivotL) {
        for (int p = n - 1; p >= Math.max(firstBar, pivotL); p--) {   // left-window needs p≥pivotL, draw-window p≥firstBar
            double v = series.getHigh(p);
            boolean leftOk = true;
            for (int k = 1; k <= pivotL && leftOk; k++)
                if (series.getHigh(p - k) >= v) leftOk = false;
            if (!leftOk) continue;
            boolean rightOk = true;
            for (int k = 1; p + k <= n - 1 && rightOk; k++)
                if (series.getHigh(p + k) >= v) rightOk = false;
            if (rightOk) return p;
        }
        return -1;
    }

    /** Most-recent provisional pivot LOW — mirror of {@link #provisionalPivotHigh}. */
    private static int provisionalPivotLow(DataSeries series, int n, int firstBar, int pivotL) {
        for (int p = n - 1; p >= Math.max(firstBar, pivotL); p--) {   // left-window needs p≥pivotL, draw-window p≥firstBar
            double v = series.getLow(p);
            boolean leftOk = true;
            for (int k = 1; k <= pivotL && leftOk; k++)
                if (series.getLow(p - k) <= v) leftOk = false;
            if (!leftOk) continue;
            boolean rightOk = true;
            for (int k = 1; p + k <= n - 1 && rightOk; k++)
                if (series.getLow(p + k) <= v) rightOk = false;
            if (rightOk) return p;
        }
        return -1;
    }

    /** Reset boundary key for a bar start time, using the SDK's native session
     *  helpers so resets line up with MotiveWave's own session boundaries.
     *  A constant means "never reset". Falls back to UTC-day on any error. */
    private static long resetBoundary(Instrument inst, long t, String mode) {
        try {
            switch (mode) {
                case RESET_NONE:    return 0L;
                case RESET_DAY_ETH: return inst.getStartOfDay(t, false);
                case RESET_WEEK:    return inst.getStartOfWeek(t, true);
                case RESET_MONTH:   return inst.getStartOfMonth(t, true);
                case RESET_DAY_RTH:
                default:            return inst.getStartOfDay(t, true);
            }
        } catch (Throwable ignored) {
            return RESET_NONE.equals(mode) ? 0L : t / 86_400_000L; // UTC-day fallback
        }
    }

    /** Resolves the Sessions-tab config into [ZoneId, int[] minutesOfDay], or
     *  null if it can't be parsed (caller then falls back to the general mode). */
    private Object[] resolveSessionConfig() {
        String preset = getSettings().getString(S_SESS_PRESET, "nyRTH");
        String tz; int[] mins;
        switch (preset) {
            case "nyRTH":     tz = "America/New_York"; mins = new int[]{9 * 60 + 30}; break;
            case "nyPre":     tz = "America/New_York"; mins = new int[]{8 * 60};      break;
            case "cme":       tz = "America/Chicago";  mins = new int[]{17 * 60};     break;
            case "london":    tz = "Europe/London";    mins = new int[]{8 * 60};      break;
            case "frankfurt": tz = "Europe/Berlin";    mins = new int[]{9 * 60};      break;
            case "tokyo":     tz = "Asia/Tokyo";       mins = new int[]{9 * 60};      break;
            case "sydney":    tz = "Australia/Sydney"; mins = new int[]{10 * 60};     break;
            case SESS_CUSTOM:
            default:
                tz = getSettings().getString(S_SESS_TZ, "America/New_York");
                mins = parseTimes(getSettings().getString(S_SESS_TIMES, "09:30"));
                break;
        }
        if (mins == null || mins.length == 0) return null;
        try {
            return new Object[]{ ZoneId.of(tz), mins };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Parses "HH:MM,HH:MM" into sorted minutes-of-day; null/empty on failure. */
    private static int[] parseTimes(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] hm = p.split(":");
            try {
                int h = Integer.parseInt(hm[0].trim());
                int m = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
                if (h >= 0 && h < 24 && m >= 0 && m < 60) out.add(h * 60 + m);
            } catch (NumberFormatException ignored) { /* skip bad token */ }
        }
        if (out.isEmpty()) return null;
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        java.util.Arrays.sort(a);   // honour the "sorted minutes-of-day" contract (BR-1)
        return a;
    }

    /** Per-bar immediate-divergence params {@code [effortFrac[], floorK[]]} when the per-session
     *  override is on, else {@code null} (use the single global tuning). Each bar's params come
     *  from the session containing its start time (priority NY → London → Asia); bars in no
     *  session keep the global values. Session windows are resolved ONCE (not per bar) to bound
     *  per-rebuild allocation (BR-4); when the global floor is off, per-session floor stays 0 (BR-3). */
    private double[][] perBarImmParams(DataSeries series, int n, double globalEffort, double globalFloorK) {
        if (n <= 0 || !getSettings().getBoolean(S_PS_ENABLE, false)) return null;
        List<Object[]> sess = new ArrayList<>();   // [ZoneId, startMin, endMin, effortFrac, floorK]
        for (String id : PS_IDS) {
            if (!getSettings().getBoolean(psKey(id, "on"), psDefaultOn(id))) continue;
            ZoneId z;
            try { z = ZoneId.of(getSettings().getString(psKey(id, "tz"), psTz(id))); }
            catch (Throwable ignored) { continue; }
            int[] st = parseTimes(getSettings().getString(psKey(id, "start"), psStart(id)));
            int[] en = parseTimes(getSettings().getString(psKey(id, "end"), psEnd(id)));
            if (st == null || en == null) continue;
            double eff = Math.max(0, getSettings().getInteger(psKey(id, "effort"), psEffort(id))) / 100.0;
            double fk  = (globalFloorK > 0) ? Math.max(0, getSettings().getInteger(psKey(id, "floork"), 60)) / 100.0 : 0.0;
            sess.add(new Object[]{ z, st[0], en[0], eff, fk });
        }
        if (sess.isEmpty()) return null;
        double[] dp = new double[n], fk = new double[n];
        for (int i = 0; i < n; i++) {
            long t = series.getStartTime(i);
            dp[i] = globalEffort; fk[i] = globalFloorK;            // default for bars in no session
            for (Object[] s : sess) {
                if (SessionWindow.contains(t, (ZoneId) s[0], (Integer) s[1], (Integer) s[2])) {
                    dp[i] = (Double) s[3]; fk[i] = (Double) s[4]; break;
                }
            }
        }
        return new double[][]{ dp, fk };
    }


    /** Draws one filled candle (wick + solid body), <b>centred on the bar's START
     *  time</b>. MotiveWave anchors a chart candle on {@code getStartTime(i)} (it
     *  maps a Box/Line's time coords through {@code translate(time)}, where the
     *  bar centre = {@code translate(getStartTime(i))}), NOT on the
     *  {@code (start+end)/2} midpoint. Drawing at the midpoint shifts the candle
     *  half a bar to the right (into the gap before the next candle), so the
     *  body/wick are centred on {@code barStart} and span ±½ bar around it to sit
     *  exactly under the price candle. A null key uses the default figure group;
     *  a non-null key groups it so it can be cleared in isolation. */
    private void drawCandle(String key, long barStart, long barEnd,
                            double o, double h, double l, double c, Color up, Color down) {
        long width = Math.max(1L, barEnd - barStart);
        long half  = width / 2L;
        long pad   = Math.min(half, Math.max(1L, width / 6L));   // never let pad exceed half (L1)
        long left  = barStart - half + pad;   // body centred on barStart, ≈2/3 bar wide
        long right = barStart + half - pad;
        Color col = c >= o ? up : down;
        addFig(key, new Line(barStart, h, barStart, l, solid(col, 1f)));  // wick on the anchor
        double top = Math.max(o, c), bot = Math.min(o, c);
        if (top == bot) {
            addFig(key, new Line(left, top, right, top, solid(col, 1.5f)));
        } else {
            Box body = new Box(left, top, right, bot);
            body.setFillColor(col);
            body.setLineColor(col);
            addFig(key, body);
        }
    }

    private void addFig(String key, com.motivewave.platform.sdk.draw.Figure f) {
        if (key == null) addFigure(f); else addFigure(key, f);
    }

    /** Histogram bar (per-bar delta) from zero to {@code delta}, centred on the bar start
     *  (same anchoring as the candle body), coloured by sign. */
    private void drawHistBar(long barStart, long barEnd, double delta, Color up, Color down) {
        long width = Math.max(1L, barEnd - barStart);
        long half  = width / 2L;
        long pad   = Math.min(half, Math.max(1L, width / 6L));   // never let pad exceed half (L1)
        long left  = barStart - half + pad;
        long right = barStart + half - pad;
        Color col  = delta >= 0 ? up : down;
        double top = Math.max(0.0, delta), bot = Math.min(0.0, delta);
        if (top == bot) { addFigure(new Line(left, 0.0, right, 0.0, solid(col, 1.5f))); return; }
        Box body = new Box(left, top, right, bot);
        body.setFillColor(col);
        body.setLineColor(col);
        addFigure(body);
    }

    /** Last confirmed pivot high ({@code high=true}) / low in {@code [max(firstBar,pivotL) ..
     *  n-1-pivotL]} that lies in the SAME reset period as {@code targetBnd}. Restricting to the
     *  active period (not just the globally-latest pivot) means a valid same-session pivot isn't
     *  skipped just because a later pivot sits in the previous session (AR-7). */
    private static int lastPivot(double[] hl, long[] bnd, long targetBnd, int firstBar, int pivotL, int n, boolean high) {
        int lo = Math.max(firstBar, pivotL), hi = n - 1 - pivotL, found = -1;
        for (int i = lo; i <= hi; i++) {
            if (bnd[i] != targetBnd) continue;
            if (high ? DivergenceEngine.isPivotHigh(hl, i, pivotL)
                     : DivergenceEngine.isPivotLow(hl, i, pivotL)) found = i;
        }
        return found;
    }

    private static PathInfo solid(Color c, float width) {
        return new PathInfo(c, width, null, true, false, true, 0, null);
    }

    private static Color orDefault(Color c, Color fallback) {
        return c != null ? c : fallback;
    }

    // ── Per-bar Totals table (same shape as the footprint Totals) ───────────
    private static byte sgn(double v) { return (byte) (v > 0 ? 1 : v < 0 ? -1 : 0); }

    /** Compact number: ≥10000 → one-decimal "K" (e.g. +47.7K); else a plain integer.
     *  {@code signed} prefixes "+" for non-negative values (matches the footprint Totals). */
    private static String fmtNum(double v, boolean signed) {
        String s;
        if (Math.abs(v) >= 10000) s = String.format(java.util.Locale.US, "%.1fK", v / 1000.0);
        else s = String.valueOf(Math.round(v));
        return (signed && v >= 0) ? "+" + s : s;
    }

    private interface CellFn { String apply(int k); }
    private interface SignFn { byte apply(int k); }
    private interface MagFn  { double apply(int k); }

    private static TotalsRow totRow(String label, int count, CellFn cell, SignFn sgn, MagFn mag, boolean signed) {
        String[] txt = new String[count]; byte[] sg = new byte[count]; double[] mg = new double[count];
        for (int k = 0; k < count; k++) { txt[k] = cell.apply(k); sg[k] = sgn.apply(k); mg[k] = mag.apply(k); }
        return new TotalsRow(label, txt, sg, mg, signed);
    }

    private static final class TotalsRow {
        final String label; final String[] cells; final byte[] sign; final double[] mag;
        final boolean signed; final double maxMag;
        TotalsRow(String label, String[] cells, byte[] sign, double[] mag, boolean signed) {
            this.label = label; this.cells = cells; this.sign = sign; this.mag = mag; this.signed = signed;
            double m = 0.0; for (double x : mag) if (x > m) m = x; this.maxMag = m;
        }
    }

    /** Per-bar summary band pinned to the bottom of the CVD panel via {@link DrawContext#getBounds()},
     *  one column per drawn candle (anchored on the bar start, like every other figure here). Click-
     *  transparent, never uses drawImage. A left legend column carries the row labels. */
    private static final class TotalsTable extends Figure {
        private final long[] starts, ends;
        private final java.util.List<TotalsRow> rows;
        private final Font font; private final Color pos, neg, txt, bg; private final boolean heat;

        TotalsTable(long[] starts, long[] ends, java.util.List<TotalsRow> rows, Font font,
                    Color pos, Color neg, Color txt, Color bg, boolean heat) {
            this.starts = starts; this.ends = ends; this.rows = rows; this.font = font;
            this.pos = pos; this.neg = neg; this.txt = txt; this.bg = bg; this.heat = heat;
        }

        @Override public boolean isVisible(DrawContext ctx) { return starts.length > 0 && !rows.isEmpty(); }
        @Override public boolean contains(double x, double y, DrawContext ctx) { return false; }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            Rectangle b = ctx.getBounds();
            if (b == null || starts.length == 0 || rows.isEmpty()) return;
            Object oldAA = gc.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gc.setFont(font);
            FontMetrics fm = gc.getFontMetrics();
            int rowH = fm.getHeight() + 2;
            int nRows = rows.size();
            int tableH = nRows * rowH;
            int top = b.y + b.height - tableH;                 // pinned to the bottom of the panel
            if (bg != null && bg.getAlpha() > 0) { gc.setColor(bg); gc.fillRect(b.x, top, b.width, tableH); }
            Color grid = new Color(255, 255, 255, 30);
            gc.setColor(grid);
            for (int r = 0; r <= nRows; r++) gc.drawLine(b.x, top + r * rowH, b.x + b.width, top + r * rowH);
            // legend width
            int legW = 0; for (TotalsRow tr : rows) legW = Math.max(legW, fm.stringWidth(tr.label)); legW += 8;

            for (int c = 0; c < starts.length; c++) {
                Point2D ps = ctx.translate(starts[c], 0.0);
                Point2D pe = ctx.translate(ends[c], 0.0);
                if (ps == null || pe == null) continue;
                double cx = ps.getX();
                double w = Math.max(2.0, pe.getX() - ps.getX());
                if (cx < b.x - w || cx > b.x + b.width + w) continue;
                int cellX = (int) Math.round(cx - w / 2.0) + 1;
                int cellW = Math.max(1, (int) Math.round(w) - 1);
                for (int r = 0; r < nRows; r++) {
                    TotalsRow tr = rows.get(r);
                    String s = tr.cells[c];
                    if (s == null || s.isEmpty()) continue;
                    int y = top + r * rowH;
                    byte sg = tr.sign[c];
                    if (heat && tr.maxMag > 0.0 && tr.mag[c] > 0.0) {
                        double t = Math.min(1.0, tr.mag[c] / tr.maxMag);
                        int alpha = 30 + (int) Math.round(Math.pow(t, 0.7) * 195.0);
                        Color base = tr.signed ? (sg > 0 ? pos : neg) : new Color(70, 130, 235);
                        gc.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
                        gc.fillRect(cellX, y + 1, cellW, rowH - 1);
                    } else if (!heat && sg != 0) {
                        Color base = sg > 0 ? pos : neg;
                        gc.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 38));
                        gc.fillRect(cellX, y + 1, cellW, rowH - 1);
                    }
                    int sw = fm.stringWidth(s);
                    gc.setColor(heat ? txt : (sg > 0 ? pos : sg < 0 ? neg : txt));
                    gc.drawString(s, (int) Math.round(cx - sw / 2.0), y + fm.getAscent());
                }
            }
            // legend column on its own background (left)
            gc.setColor(bg != null ? bg : new Color(16, 16, 22, 220));
            gc.fillRect(b.x, top, legW, tableH);
            gc.setColor(grid);
            gc.drawLine(b.x + legW, top, b.x + legW, top + tableH);
            gc.setColor(txt);
            for (int r = 0; r < nRows; r++) gc.drawString(rows.get(r).label, b.x + 4, top + r * rowH + fm.getAscent());

            setBounds(new Rectangle2D.Double(b.x, top, b.width, tableH));
            gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                oldAA != null ? oldAA : RenderingHints.VALUE_ANTIALIAS_DEFAULT);
        }
    }

    /** Fixed-corner warning badge (top-left), shown when the bid/ask classification
     *  looks unreliable (MotiveWave "Generated Ticks"). Drawn in pixel space via
     *  {@link DrawContext#getBounds()} so it stays pinned to the panel corner and does
     *  not scroll with the bars (the documented fixed-corner HUD pattern). */
    private static final class WarnBadge extends Figure {
        private final String text;
        WarnBadge(String text) { this.text = text; }

        @Override public boolean isVisible(DrawContext ctx) { return true; }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            Rectangle b = ctx.getBounds();
            if (b == null) return;
            gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Font f = gc.getFont().deriveFont(Font.BOLD, 11f);
            gc.setFont(f);
            int th = gc.getFontMetrics().getHeight();
            int tw = gc.getFontMetrics().stringWidth(text);
            int pad = 6, glyph = 16;
            int w = tw + glyph + 2 * pad, h = th + 4;
            int x = b.x + 8, y = b.y + 8;
            gc.setColor(new Color(45, 22, 0, 210));
            gc.fillRoundRect(x, y, w, h, 6, 6);
            gc.setColor(new Color(255, 176, 0));
            gc.setStroke(new BasicStroke(1f));
            gc.drawRoundRect(x, y, w, h, 6, 6);
            gc.drawString("⚠", x + pad, y + th - 3);           // warning triangle
            gc.drawString(text, x + pad + glyph, y + th - 3);
            setBounds(new Rectangle2D.Double(x, y, w, h));
        }
    }

    // ── Clean divergence markers (pixel-space, no frames — replaces SDK Label boxes) ──

    private enum Glyph { TRI_DOWN, TRI_UP, DOT }

    /** One divergence marker anchored at (time, value). {@code hollow} = hidden divergence
     *  (outline triangle); {@code faded} = provisional / unconfirmed (drawn translucent);
     *  {@code above} = nudge the glyph ABOVE the anchor (bearish) vs below (bullish) — so a
     *  bare dot clears the candle wick instead of blending into it. */
    private static final class DivMark {
        final long time; final double value; final Glyph glyph;
        final boolean hollow; final Color color; final boolean faded; final boolean above;
        DivMark(long time, double value, Glyph glyph, boolean hollow, Color color, boolean faded, boolean above) {
            this.time = time; this.value = value; this.glyph = glyph;
            this.hollow = hollow; this.color = color; this.faded = faded; this.above = above;
        }
    }

    /** Single draw-all figure for every divergence marker, rendered in pixel space as small
     *  clean glyphs (filled/hollow triangles, dots) with NO box/frame — the SDK {@code Label}
     *  always paints a framed background, which the redesign removes. Triangles sit just
     *  outside the anchor (bearish above, bullish below); size scales with panel height;
     *  off-screen marks are culled. Never uses drawImage. */
    private static final class DivergenceLayer extends Figure {
        private final java.util.List<DivMark> marks;
        DivergenceLayer(java.util.List<DivMark> marks) { this.marks = marks; }

        @Override public boolean isVisible(DrawContext ctx) { return true; }

        @Override
        public void draw(Graphics2D gc, DrawContext ctx) {
            Rectangle b = ctx.getBounds();
            if (b == null || marks.isEmpty()) return;
            Object oldAA = gc.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            java.awt.Stroke oldStroke = gc.getStroke();
            gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = (int) Math.max(3, Math.min(7, Math.round(b.height * 0.012))); // half-size scales w/ panel
            for (DivMark m : marks) {
                if (!Double.isFinite(m.value)) continue;          // never draw NaN/∞
                java.awt.geom.Point2D p = ctx.translate(m.time, m.value);
                if (p == null || !Double.isFinite(p.getX()) || !Double.isFinite(p.getY())) continue;
                int x = (int) Math.round(p.getX()), y = (int) Math.round(p.getY());
                if (x < b.x - 20 || x > b.x + b.width + 20 || y < b.y - 20 || y > b.y + b.height + 20) continue;
                Color c = m.faded ? new Color(m.color.getRed(), m.color.getGreen(), m.color.getBlue(), 120) : m.color;
                gc.setColor(c);
                switch (m.glyph) {
                    case DOT: {
                        int r = Math.max(2, s - 1);
                        int cy = m.above ? y - (r + s + 3) : y + (r + s + 3);  // clear the candle wick
                        gc.fillOval(x - r, cy - r, 2 * r, 2 * r);
                        break;
                    }
                    case TRI_DOWN: {                                  // bearish — above the anchor, points down
                        int cy = y - s - 3;
                        int[] xs = { x - s, x + s, x };
                        int[] ys = { cy - s, cy - s, cy + s };
                        if (m.hollow) { gc.setStroke(new BasicStroke(1.4f)); gc.drawPolygon(xs, ys, 3); }
                        else gc.fillPolygon(xs, ys, 3);
                        break;
                    }
                    case TRI_UP: {                                    // bullish — below the anchor, points up
                        int cy = y + s + 3;
                        int[] xs = { x - s, x + s, x };
                        int[] ys = { cy + s, cy + s, cy - s };
                        if (m.hollow) { gc.setStroke(new BasicStroke(1.4f)); gc.drawPolygon(xs, ys, 3); }
                        else gc.fillPolygon(xs, ys, 3);
                        break;
                    }
                }
            }
            gc.setStroke(oldStroke);
            gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                oldAA != null ? oldAA : RenderingHints.VALUE_ANTIALIAS_DEFAULT);
        }
    }
}
