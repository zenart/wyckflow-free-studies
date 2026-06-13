package com.orderflow.trades;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.CRC32;

/**
 * On-disk archive of executed <b>trades</b> (time-and-sales), compact append-only
 * binary. One file per instrument per UTC day:
 * {@code <root>/<symbol>/<symbol>_<yyyyMMdd>.trd}.
 *
 * <h2>Why this exists</h2>
 * Unlike depth, exchanges <em>do</em> keep trade history — but a feed's tick history is
 * finite (a data subscription only goes back so far, and some instruments have none).
 * Recording trades forward to disk (exactly like {@code com.orderflow.depth} does for the
 * book) lets the Trade Bubbles study show <b>historical</b> bubbles across sessions and
 * beyond the feed's tick retention, and reload them instantly. This is the storage layer;
 * {@link TradeRecorder} drives it live and studies replay it.
 *
 * <h2>File format (version 1)</h2>
 * <pre>
 * Header (once):
 *   magic   : int   0x54524441 ("TRDA")
 *   version : byte  = 1
 *   flags   : byte  reserved (0)
 *   symbol  : UTF
 *   tickSize: double
 *   epochDay: int   (UTC day = time / 86_400_000)
 *   hdrCrc  : int   CRC32 of all header bytes above
 * Block (repeated) — a batch of trades, atomic for crash-safety:
 *   payLen  : int   payload length in bytes
 *   payload : byte[payLen]  = int count, then count × trade
 *   crc     : int   CRC32 of payload
 * Trade (17 bytes): long time, int priceTick, float volume, byte side (1 = ask/buy-aggressor)
 * </pre>
 * Trades are stored at <b>native tick resolution</b> ({@code round(price/tickSize)}) so they
 * can be re-aggregated later with any display settings. A truncated/corrupt tail only loses
 * the last partial block (each block is length-prefixed and CRC-checked).
 */
public final class TradeArchive {

    static final int  MAGIC   = 0x54524441;   // "TRDA"
    static final byte VERSION = 1;
    /** Max trades buffered before a block is flushed to disk. */
    public static final int BLOCK_MAX = 1024;
    private static final long MS_PER_DAY = 86_400_000L;

    private static final DateTimeFormatter DAY_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private TradeArchive() {}

    // ── Paths ─────────────────────────────────────────────────────────

    public static long epochDay(long timeMs) { return timeMs / MS_PER_DAY; }

    public static String sanitize(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "UNKNOWN";
        return symbol.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static File symbolDir(File root, String symbol) {
        return new File(root, sanitize(symbol));
    }

    public static File dayFile(File root, String symbol, long epochDay) {
        String day = DAY_FMT.format(Instant.ofEpochMilli(epochDay * MS_PER_DAY));
        String s = sanitize(symbol);
        return new File(symbolDir(root, symbol), s + "_" + day + ".trd");
    }

    // ── Consumer ──────────────────────────────────────────────────────

    /** Receives reconstructed trades during {@link #forEach}. Avoids per-trade allocation. */
    public interface TradeConsumer {
        void accept(long time, int priceTick, float volume, boolean buy);
    }

    // ── Writer ────────────────────────────────────────────────────────

    /**
     * Append-only writer for a single day file. On open it scans any existing content for
     * the last recorded timestamp so re-records (e.g. after a settings reload) don't
     * duplicate trades. Not thread-safe; the caller serialises writes.
     */
    public static final class Writer implements AutoCloseable {
        private final File file;
        private final String symbol;
        private final double tickSize;
        private final long epochDay;
        private DataOutputStream out;
        private ByteArrayOutputStream blockBuf;
        private DataOutputStream block;
        private int blockCount;
        private long lastTime = Long.MIN_VALUE;

        Writer(File file, String symbol, double tickSize, long epochDay) throws IOException {
            this.file = file;
            this.symbol = symbol;
            this.tickSize = tickSize;
            this.epochDay = epochDay;
            open();
        }

        public long getLastTime() { return lastTime; }

        private void open() throws IOException {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            boolean fresh = !file.exists() || file.length() == 0;
            if (!fresh) {
                try { lastTime = scanLastTime(file); } catch (Throwable ignored) { /* keep MIN */ }
            }
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file, true)));
            if (fresh) writeHeader();
            blockBuf = new ByteArrayOutputStream(BLOCK_MAX * 17 + 8);
            block = new DataOutputStream(blockBuf);
            blockCount = 0;
        }

        private void writeHeader() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(bos);
            h.writeInt(MAGIC);
            h.writeByte(VERSION);
            h.writeByte(0); // flags
            h.writeUTF(sanitize(symbol));
            h.writeDouble(tickSize);
            h.writeInt((int) epochDay);
            byte[] hb = bos.toByteArray();
            CRC32 crc = new CRC32(); crc.update(hb);
            out.write(hb);
            out.writeInt((int) crc.getValue());
        }

        /** Append one trade. Monotonic: trades at or before the last written time are skipped. */
        public void write(long time, int priceTick, float volume, boolean buy) throws IOException {
            if (time <= lastTime) return;
            block.writeLong(time);
            block.writeInt(priceTick);
            block.writeFloat(volume);
            block.writeByte(buy ? 1 : 0);
            blockCount++;
            lastTime = time;
            if (blockCount >= BLOCK_MAX) flushBlock();
        }

        /** Write any buffered trades as a block and push to the OS (crash-safe boundary). */
        public void flush() throws IOException {
            flushBlock();
            if (out != null) out.flush();
        }

        private void flushBlock() throws IOException {
            if (blockCount == 0) return;
            byte[] trades = blockBuf.toByteArray();
            ByteArrayOutputStream pbos = new ByteArrayOutputStream(trades.length + 4);
            DataOutputStream p = new DataOutputStream(pbos);
            p.writeInt(blockCount);
            p.write(trades);
            byte[] payload = pbos.toByteArray();
            CRC32 crc = new CRC32(); crc.update(payload);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeInt((int) crc.getValue());
            out.flush();
            blockBuf.reset();
            blockCount = 0;
        }

        @Override public void close() {
            try { flushBlock(); } catch (IOException ignored) {}
            if (out != null) { try { out.flush(); out.close(); } catch (IOException ignored) {} out = null; }
        }
    }

    // ── Reader ────────────────────────────────────────────────────────

    /**
     * Stream all trades in {@code [from, to]} (epoch millis) for the symbol, across day
     * files, oldest first. Robust to truncated/corrupt blocks anywhere in the file —
     * resyncs to the next CRC-valid block (a recording interrupted by a MotiveWave
     * restart leaves a corrupt block mid-file; the rest of the history stays usable).
     */
    public static void forEach(File root, String symbol, long from, long to, TradeConsumer consumer) {
        long firstDay = epochDay(from);
        long lastDay = epochDay(to);
        File[] files = listDayFiles(root, symbol);
        if (files == null) return;
        java.util.TreeMap<Long, File> byDay = new java.util.TreeMap<>();
        for (File f : files) {
            Long day = parseDay(f.getName());
            if (day != null && day >= firstDay && day <= lastDay && f.length() > 0) byDay.put(day, f);
        }
        for (File f : byDay.values()) {
            try { readFile(f, from, to, consumer); }
            catch (Throwable ignored) { /* skip unreadable file */ }
        }
    }

    public static int fileCount(File root, String symbol) {
        File[] fs = listDayFiles(root, symbol);
        return fs == null ? 0 : fs.length;
    }

    // ── Retention ─────────────────────────────────────────────────────

    public static int purge(File root, String symbol, int keepDays, long nowMs) {
        if (keepDays <= 0) return 0;
        long cutoffDay = epochDay(nowMs) - keepDays;
        File[] fs = listDayFiles(root, symbol);
        if (fs == null) return 0;
        int removed = 0;
        for (File f : fs) {
            Long day = parseDay(f.getName());
            if (day != null && day < cutoffDay && f.delete()) removed++;
        }
        return removed;
    }

    // ── Internals ─────────────────────────────────────────────────────

    private static File[] listDayFiles(File root, String symbol) {
        File dir = symbolDir(root, symbol);
        String prefix = sanitize(symbol) + "_";
        return dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".trd"));
    }

    private static Long parseDay(String name) {
        try {
            int us = name.lastIndexOf('_');
            int dot = name.lastIndexOf(".trd");
            if (us < 0 || dot < 0 || dot <= us + 1) return null;
            String ymd = name.substring(us + 1, dot);
            int y = Integer.parseInt(ymd.substring(0, 4));
            int mo = Integer.parseInt(ymd.substring(4, 6));
            int d = Integer.parseInt(ymd.substring(6, 8));
            return Instant.from(java.time.LocalDate.of(y, mo, d).atStartOfDay(ZoneOffset.UTC))
                    .toEpochMilli() / MS_PER_DAY;
        } catch (Throwable t) { return null; }
    }

    // ── Reader internals (resync-tolerant) ─────────────────────────────
    //
    // A block is [int payLen][payload (= int count + count×17B trades)][int crc].
    // If MotiveWave is killed/quits/crashes (or the OS sleeps) mid-write, the file can
    // carry a truncated/partial block; on the next launch the recorder appends AFTER it,
    // so a corrupt block can sit in the MIDDLE of the file. Rather than STOP at the first
    // bad block (which hides all later, validly-recorded history), the reader RESYNCS:
    // it scans forward for the next CRC-valid block and resumes. Trades are independent,
    // so a gap just drops the unrecorded interval — the rest of the day stays usable.

    /** Whole file as bytes, or null if unreadable. Day files are read once on open. */
    private static byte[] readAll(File f) {
        try { return java.nio.file.Files.readAllBytes(f.toPath()); }
        catch (Throwable t) { return null; }
    }

    private static int rdInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
    private static long rdLong(byte[] b, int o) {
        return ((long) rdInt(b, o) << 32) | (rdInt(b, o + 4) & 0xffffffffL);
    }

    /** Validate the block whose length prefix is at {@code o}; returns the payload offset
     *  (start of {@code int count}) if valid, else -1. Cheap length/count checks run
     *  before the CRC so resync scanning stays fast. */
    private static int blockPayloadOffset(byte[] b, int o) {
        if (o + 8 > b.length) return -1;
        int payLen = rdInt(b, o);
        if (payLen < 8 || payLen > (1 << 26) || (long) o + 4 + payLen + 4 > b.length) return -1;
        int count = rdInt(b, o + 4);
        if (count <= 0 || (long) count * 17L + 4L > payLen) return -1;
        CRC32 crc = new CRC32();
        crc.update(b, o + 4, payLen);
        if ((int) crc.getValue() != rdInt(b, o + 4 + payLen)) return -1;
        return o + 4;   // points at int count
    }

    /** First byte offset >= {@code from} that begins a valid block, or -1. */
    private static int resync(byte[] b, int from) {
        for (int o = Math.max(0, from), max = b.length - 8; o <= max; o++)
            if (blockPayloadOffset(b, o) >= 0) return o;
        return -1;
    }

    /** Offset of the first block after the header, or -1 if the header is invalid. */
    private static int afterHeader(byte[] b) {
        try {
            if (b.length < 6 || rdInt(b, 0) != MAGIC || (b[4] & 0xff) != VERSION) return -1;
            int o = 6;                                          // magic(4) + version(1) + flags(1)
            int utf = ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); // symbol UTF length
            o += 2 + utf + 8 + 4 + 4;                           // symbol + tickSize + epochDay + hdrCrc
            return o <= b.length ? o : -1;
        } catch (Throwable t) { return -1; }
    }

    private static void readFile(File f, long from, long to, TradeConsumer consumer) {
        byte[] b = readAll(f);
        if (b == null) return;
        int o = afterHeader(b);
        if (o < 0) return;
        while (o + 8 <= b.length) {
            int pay = blockPayloadOffset(b, o);
            if (pay < 0) {                              // corrupt/partial → skip to next good block
                o = resync(b, o + 1);
                if (o < 0) break;
                continue;
            }
            int payLen = rdInt(b, o);
            int count = rdInt(b, pay);
            int q = pay + 4;
            for (int i = 0; i < count; i++) {
                long time = rdLong(b, q);
                int tick = rdInt(b, q + 8);
                float vol = Float.intBitsToFloat(rdInt(b, q + 12));
                boolean buy = b[q + 16] != 0;
                q += 17;
                if (time >= from && time <= to) consumer.accept(time, tick, vol, buy);
            }
            o += 4 + payLen + 4;
        }
    }

    /** Scan a file for the max trade timestamp (for dedupe on reopen). Resync-tolerant
     *  so a mid-file corruption doesn't make the recorder think the file ends early and
     *  re-append already-recorded trades after the gap. */
    private static long scanLastTime(File f) {
        byte[] b = readAll(f);
        if (b == null) return Long.MIN_VALUE;
        int o = afterHeader(b);
        if (o < 0) return Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        while (o + 8 <= b.length) {
            int pay = blockPayloadOffset(b, o);
            if (pay < 0) { o = resync(b, o + 1); if (o < 0) break; continue; }
            int payLen = rdInt(b, o);
            int count = rdInt(b, pay);
            long t = rdLong(b, pay + 4 + (count - 1) * 17);   // last trade's time (time-ordered)
            if (t > last) last = t;
            o += 4 + payLen + 4;
        }
        return last;
    }
}
