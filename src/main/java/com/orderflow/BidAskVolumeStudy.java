package com.orderflow;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.common.desc.SettingGroup;
import com.motivewave.platform.sdk.common.desc.SettingTab;
import com.motivewave.platform.sdk.common.desc.SettingsDescriptor;
import com.motivewave.platform.sdk.common.desc.ValueDescriptor;
import com.motivewave.platform.sdk.study.RuntimeDescriptor;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

/**
 * Community-edition replacement for MotiveWave's premium
 * {@code com.motivewave.platform.premium_study.order_flow.BidAskVolume}.
 *
 * <p>Plots two paths in a separate plot:</p>
 * <ul>
 *   <li><b>Ask volume</b> — cumulative volume from ask-aggressor trades in the bar</li>
 *   <li><b>Bid volume</b> — cumulative volume from bid-aggressor trades in the bar</li>
 * </ul>
 *
 * <p>The two are useful side-by-side to spot crossovers where one starts
 * dominating the other (intra-bar shift in pressure).</p>
 */
@StudyHeader(
    namespace      = "com.orderflow",
    id             = "ORDERFLOW_BID_ASK_VOLUME",
    name           = "Bid/Ask Volume",
    desc           = "Plots bid-aggressor volume and ask-aggressor volume as two separate paths "
                    + "in a sub-plot. Community-compatible.",
    menu           = "WyckFlow.com",
    overlay        = false,
    requiresVolume = true,
    supportsBarUpdates = true,
    requiresBarUpdates = true,
    barUpdatesByDefault = true
)
public class BidAskVolumeStudy extends Study {

    private static final String S_RTH_ONLY  = "rthOnly";
    private static final String S_ASK_PATH  = "askPath";
    private static final String S_BID_PATH  = "bidPath";

    enum Values { ASK_VOL, BID_VOL }

    private int    currentBarIndex = -1;
    private double currentAsk = 0.0;
    private double currentBid = 0.0;

    @Override
    public void initialize(Defaults defaults) {
        SettingsDescriptor sd = new SettingsDescriptor();
        setSettingsDescriptor(sd);

        SettingTab tab = new SettingTab("General");
        sd.addTab(tab);

        SettingGroup display = new SettingGroup("Display");
        tab.addGroup(display);
        display.addRow(new PathDescriptor(S_ASK_PATH,
            "Ask volume (buyers aggressive)", defaults.getGreen(), 1.5f, null, true, true, true));
        display.addRow(new PathDescriptor(S_BID_PATH,
            "Bid volume (sellers aggressive)", defaults.getRed(), 1.5f, null, true, true, true));

        SettingGroup behavior = new SettingGroup("Behavior");
        tab.addGroup(behavior);
        behavior.addRow(new BooleanDescriptor(S_RTH_ONLY,
            "Only count ticks during regular trading hours", false));

        RuntimeDescriptor desc = new RuntimeDescriptor();
        setRuntimeDescriptor(desc);
        desc.exportValue(new ValueDescriptor(Values.ASK_VOL, "Ask Volume", new String[]{}));
        desc.exportValue(new ValueDescriptor(Values.BID_VOL, "Bid Volume", new String[]{}));
        desc.declarePath(Values.ASK_VOL, S_ASK_PATH);
        desc.declarePath(Values.BID_VOL, S_BID_PATH);
        desc.setRangeKeys(Values.ASK_VOL, Values.BID_VOL);
        desc.setLabelSettings();
    }

    @Override
    public void onLoad(Defaults defaults) { resetState(); }

    @Override
    public void clearState() { resetState(); }

    private void resetState() {
        currentBarIndex = -1;
        currentAsk = 0.0;
        currentBid = 0.0;
    }

    @Override
    public void onTick(DataContext ctx, Tick tick) {
        if (ctx == null || tick == null) return;
        DataSeries series = ctx.getDataSeries();
        if (series == null) return;
        int last = series.size() - 1;
        if (last < 0) return;

        if (getSettings().getBoolean(S_RTH_ONLY, false)) {
            try { if (!ctx.isRTH()) return; } catch (Throwable ignored) {}
        }

        if (last != currentBarIndex) {
            if (currentBarIndex >= 0) {
                series.setDouble(currentBarIndex, Values.ASK_VOL, currentAsk);
                series.setDouble(currentBarIndex, Values.BID_VOL, currentBid);
            }
            currentBarIndex = last;
            currentAsk = 0.0;
            currentBid = 0.0;
        }

        float vol = tick.getVolumeAsFloat();
        if (tick.isAskTick()) currentAsk += vol;
        else                  currentBid += vol;

        series.setDouble(last, Values.ASK_VOL, currentAsk);
        series.setDouble(last, Values.BID_VOL, currentBid);
    }

    @Override
    public void onBarClose(DataContext ctx) {
        if (ctx == null) return;
        DataSeries series = ctx.getDataSeries();
        if (series == null) return;
        int last = series.size() - 1;
        if (last < 0) return;
        series.setDouble(last, Values.ASK_VOL, currentAsk);
        series.setDouble(last, Values.BID_VOL, currentBid);
        series.setComplete(last);
    }

    @Override
    protected void calculate(int index, DataContext ctx) {
        if (index != 0 || ctx == null) return;
        DataSeries series = ctx.getDataSeries();
        if (series == null || series.size() == 0) return;
        resetState();
        try {
            for (int i = 0; i < series.size(); i++) {
                long barStart = series.getStartTime(i);
                long barEnd   = series.getEndTime(i);
                double[] ab = { 0.0, 0.0 }; // [ask, bid]
                ctx.getInstrument().forEachTick(barStart, barEnd, t -> {
                    if (t == null) return;
                    float v = t.getVolumeAsFloat();
                    if (t.isAskTick()) ab[0] += v; else ab[1] += v;
                });
                series.setDouble(i, Values.ASK_VOL, ab[0]);
                series.setDouble(i, Values.BID_VOL, ab[1]);
            }
        } catch (Throwable err) {
            // No historical tick data — live path will populate going forward.
        }
    }
}
