package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.IndexVisitor;
import com.truesim.ui.controls.data.RowSource;
import java.util.Arrays;

/**
 * The records of a {@link LogRing} a log view shows: filtered by level ({@link #levels}), logger ({@link #logger}: the
 * logger and the loggers under it) and an incremental search ({@link #search}: the message contains the text,
 * ignoring case). The matches are a ring of sequence numbers as large as the log's; once a frame
 * ({@link #refresh}) records gone from the log leave the front and new matching records join the back, so a flood
 * costs its new records only. Narrowing a search filters the current matches; widening it or changing a filter scans
 * the log once. Allocation-free in steady state.
 *
 * <p>Versions are times of the ring's clock: a row's value changes in place only when a repeat is coalesced into the
 * latest record; everything else is structural.
 */
public final class LogRows implements RowSource<LogRecord> {

    private final LogRing ring;
    private final long[] seqs;
    private int head;
    private int count;
    private int levels = LogLevel.ALL;
    private String logger;
    private boolean[] loggerMatches = new boolean[64];
    private int loggersChecked;
    private final StringBuilder query = new StringBuilder();
    private final StringBuilder applied = new StringBuilder();
    private boolean dirty = true;
    private long seenNext;
    private int seenClears = -1;
    private long seenLast = -1;
    private long version;
    private long structureVersion;
    private long lastChanged;

    /** The rows of {@code ring}, every record shown. */
    @AllowAllocation("one per view")
    public LogRows(LogRing ring) {
        this.ring = ring;
        this.seqs = new long[ring.capacity()];
    }

    /** The log. */
    public LogRing ring() {
        return ring;
    }

    // ---------------------------------------------------------------- filters

    /** Shows records whose level's bit ({@link LogLevel#bit}) is in {@code mask}; returns this. */
    public LogRows levels(int mask) {
        if (mask != levels) {
            levels = mask;
            dirty = true;
        }
        return this;
    }

    /** The level mask shown. */
    public int levels() {
        return levels;
    }

    /** Shows only records of logger {@code name} and the loggers under it ({@code name.*}); null: every logger. */
    public LogRows logger(String name) {
        String value = name == null || name.isEmpty() ? null : name;
        if (value == null ? logger != null : !value.equals(logger)) {
            logger = value;
            loggersChecked = 0;
            dirty = true;
        }
        return this;
    }

    /** The logger filter, or null. */
    public String logger() {
        return logger;
    }

    /** Shows records whose message contains {@code text}, ignoring case (empty: all); returns this. */
    public LogRows search(CharSequence text) {
        if (!same(query, text)) {
            query.setLength(0);
            query.append(text);
        }
        return this;
    }

    /** The search text. */
    public CharSequence search() {
        return query;
    }

    private static boolean same(StringBuilder a, CharSequence b) {
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

    /** Whether {@code r} passes the filters. */
    public boolean matches(LogRecord r) {
        if ((levels & LogLevel.bit(r.level)) == 0) {
            return false;
        }
        if (logger != null && !loggerMatches(r.logger)) {
            return false;
        }
        return applied.isEmpty() || r.contains(applied);
    }

    private boolean loggerMatches(int index) {
        if (index >= loggersChecked) {
            checkLoggers();
        }
        return loggerMatches[index];
    }

    private void checkLoggers() {
        int n = ring.loggerCount();
        if (loggerMatches.length < n) {
            loggerMatches = grow(loggerMatches, n);
        }
        for (int i = loggersChecked; i < n; i++) {
            String name = ring.loggerName(i);
            loggerMatches[i] = name.equals(logger)
                || name.length() > logger.length() && name.startsWith(logger) && name.charAt(logger.length()) == '.';
        }
        loggersChecked = n;
    }

    @AllowAllocation("a new logger")
    private static boolean[] grow(boolean[] a, int n) {
        return Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    // ---------------------------------------------------------------- updating

    @Override
    public void refresh() {
        boolean structural = false;
        if (ring.clears() != seenClears) {
            seenClears = ring.clears();
            dirty = true;
        }
        if (!same(applied, query)) {
            boolean narrower = !dirty && startsWith(query, applied);
            applied.setLength(0);
            applied.append(query);
            if (narrower) {
                narrow();
                structural = true;
            } else {
                dirty = true;
            }
        }
        if (dirty) {
            dirty = false;
            rebuild();
            structural = true;
        } else {
            long oldest = ring.oldest();
            while (count > 0 && seqs[head] < oldest) {
                head = (head + 1) % seqs.length;
                count--;
                structural = true;
            }
            for (long s = Math.max(seenNext, oldest); s < ring.next(); s++) {
                LogRecord r = ring.record(s);
                if (matches(r)) {
                    push(s);
                    structural = true;
                }
            }
        }
        seenNext = ring.next();
        LogRecord last = ring.last();
        if (last != null && last.version != seenLast) {
            seenLast = last.version;
            lastChanged = last.version;
        }
        if (structural) {
            structureVersion = ring.tick(); // a filter change counts too
            version = structureVersion;
        } else if (lastChanged > version) {
            version = lastChanged;
        }
    }

    private static boolean startsWith(CharSequence s, CharSequence prefix) {
        if (s.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (s.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void push(long seq) {
        if (count == seqs.length) {
            head = (head + 1) % seqs.length;
            count--;
        }
        seqs[(head + count) % seqs.length] = seq;
        count++;
    }

    private void rebuild() {
        head = 0;
        count = 0;
        for (long s = ring.oldest(); s < ring.next(); s++) {
            if (matches(ring.record(s))) {
                push(s);
            }
        }
    }

    /** Keeps the current matches that still match (a longer search text). */
    private void narrow() {
        int kept = 0;
        long oldest = ring.oldest();
        for (int k = 0; k < count; k++) {
            long s = seqs[(head + k) % seqs.length];
            if (s >= oldest && matches(ring.record(s))) {
                seqs[(head + kept) % seqs.length] = s;
                kept++;
            }
        }
        count = kept;
    }

    // ---------------------------------------------------------------- the rows

    @Override
    public int size() {
        return count;
    }

    @Override
    public LogRecord get(int index) {
        return ring.record(seqs[(head + index) % seqs.length]);
    }

    /** The sequence number of row {@code index}. */
    public long seq(int index) {
        return seqs[(head + index) % seqs.length];
    }

    /** The row showing record {@code seq}, or -1. */
    public int indexOf(long seq) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long s = seq(mid);
            if (s < seq) {
                lo = mid + 1;
            } else if (s > seq) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    @Override
    public long key(int index) {
        return seq(index);
    }

    @Override
    public long version(int index) {
        return get(index).version;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public boolean changesSince(long since, IndexVisitor visitor) {
        if (structureVersion > since) {
            return false;
        }
        if (count > 0 && get(count - 1).version > since) {
            visitor.changed(count - 1);
        }
        return true;
    }
}
