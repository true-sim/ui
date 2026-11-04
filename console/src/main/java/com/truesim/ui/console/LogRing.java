package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The console's log (design 5.10, UI28): a ring of a fixed number of {@link LogRecord}s, made once, that keeps the
 * latest records. Appending copies the preformatted text into the record's own buffers (grown to the longest line
 * seen, capped at {@link ConsoleTunables#MAX_LINE} characters) and allocates nothing in steady state; a record equal
 * to the last one (level, logger, message and source) is coalesced into it ({@link LogRecord#count()} grows). Logger
 * names are interned once per logger.
 *
 * <p>For the UI thread only: records from other threads go through a {@link LogInbox}, drained once a frame. Every
 * change ticks the ring's clock: {@link #version()} (anything), {@link #structureVersion()} (records came or went),
 * and each record's {@link LogRecord#version()}, so views ({@link LogRows}) update only what changed.
 */
public final class LogRing implements LogSink {

    private final LogRecord[] records;
    private long next;
    private long clock;
    private long version;
    private long structureVersion;
    private int clears;
    private String[] loggers = new String[64];
    private int loggerCount;
    private int[] table = new int[128];

    /** A ring of {@link ConsoleTunables#LOG_CAPACITY} records. */
    public LogRing() {
        this((int) ConsoleTunables.LOG_CAPACITY.get());
    }

    /** A ring of {@code capacity} records, all made now. */
    @AllowAllocation("one per console")
    public LogRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        records = new LogRecord[capacity];
        for (int i = 0; i < capacity; i++) {
            records[i] = new LogRecord(this);
        }
        Arrays.fill(table, -1);
    }

    @Override
    public void log(int level, CharSequence logger, CharSequence message, CharSequence source, long epochMillis) {
        int log = logger(logger == null ? "" : logger);
        CharSequence text = message == null ? "" : message;
        if (next > first) {
            LogRecord last = records[(int) ((next - 1) % records.length)];
            if (last.same(level, log, text, source)) {
                last.count++;
                last.version = tick();
                version = last.version;
                return;
            }
        }
        LogRecord r = records[(int) (next % records.length)];
        r.seq = next++;
        r.level = level;
        r.logger = log;
        r.time = epochMillis;
        r.count = 1;
        r.messageLength = copy(r, text, true);
        r.sourceLength = source == null ? -1 : copy(r, source, false);
        r.version = tick();
        version = r.version;
        structureVersion = r.version;
    }

    long tick() {
        return ++clock;
    }

    /** Copies {@code text} into the record's message or source, capped at {@link ConsoleTunables#MAX_LINE}. */
    private static int copy(LogRecord r, CharSequence text, boolean message) {
        int max = (int) ConsoleTunables.MAX_LINE.get();
        int n = Math.min(text.length(), max);
        char[] buffer = message ? r.message : r.source;
        if (buffer.length < n) {
            buffer = grow(buffer, n);
            if (message) {
                r.message = buffer;
            } else {
                r.source = buffer;
            }
        }
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            buffer[i] = c == '\n' || c == '\r' || c == '\t' ? ' ' : c;
        }
        if (n < text.length() && n > 0) {
            buffer[n - 1] = '\u2026'; // cut: an ellipsis says so
        }
        return n;
    }

    @AllowAllocation("a record's buffer grows to the longest line it held")
    private static char[] grow(char[] buffer, int n) {
        return Arrays.copyOf(buffer, Math.max(n, buffer.length * 2));
    }

    // ---------------------------------------------------------------- loggers

    /** The index of the logger named {@code name}, interned on first sight. */
    public int logger(CharSequence name) {
        int h = hash(name);
        int mask = table.length - 1;
        for (int at = h & mask; ; at = (at + 1) & mask) {
            int i = table[at];
            if (i < 0) {
                return add(at, name);
            }
            if (sameName(loggers[i], name)) {
                return i;
            }
        }
    }

    private static int hash(CharSequence s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h ^ (h >>> 16);
    }

    private static boolean sameName(String a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @AllowAllocation("a logger seen for the first time")
    private int add(int at, CharSequence name) {
        if (loggerCount == loggers.length) {
            loggers = Arrays.copyOf(loggers, loggerCount * 2);
        }
        int i = loggerCount++;
        loggers[i] = name.toString();
        table[at] = i;
        if (loggerCount * 2 > table.length) {
            table = new int[table.length * 2];
            Arrays.fill(table, -1);
            int mask = table.length - 1;
            for (int k = 0; k < loggerCount; k++) {
                int p = hash(loggers[k]) & mask;
                while (table[p] >= 0) {
                    p = (p + 1) & mask;
                }
                table[p] = k;
            }
        }
        return i;
    }

    /** The name of logger {@code index}. */
    public String loggerName(int index) {
        return loggers[index];
    }

    /** How many loggers were seen. */
    public int loggerCount() {
        return loggerCount;
    }

    // ---------------------------------------------------------------- reading

    /** How many records it can hold. */
    public int capacity() {
        return records.length;
    }

    /** How many records it holds. */
    public int size() {
        return (int) Math.min(next - first, records.length);
    }

    private long first; // the first sequence number after the last clear

    /** The sequence number the next record gets. */
    public long next() {
        return next;
    }

    /** The oldest sequence number still held. */
    public long oldest() {
        return Math.max(first, next - records.length);
    }

    /** The record of sequence number {@code seq}, or null when it is not held (gone or not yet logged). */
    public LogRecord record(long seq) {
        if (seq < oldest() || seq >= next) {
            return null;
        }
        return records[(int) (seq % records.length)];
    }

    /** The {@code i}-th record held, oldest first. */
    public LogRecord get(int i) {
        return records[(int) ((oldest() + i) % records.length)];
    }

    /** The latest record, or null. */
    public LogRecord last() {
        return next == first ? null : records[(int) ((next - 1) % records.length)];
    }

    /** When anything last changed. */
    public long version() {
        return version;
    }

    /** When records last came or went. */
    public long structureVersion() {
        return structureVersion;
    }

    /** Forgets every record (the loggers stay interned; sequence numbers go on). */
    public void clear() {
        first = next;
        clears++;
        version = tick();
        structureVersion = version;
    }

    /** How many times it was {@linkplain #clear cleared}. */
    public int clears() {
        return clears;
    }
}
