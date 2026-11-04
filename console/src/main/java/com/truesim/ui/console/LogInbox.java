package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A {@link LogSink} any thread may write to: records wait in a fixed number of slots (their text copied into buffers
 * grown to the longest line seen) until the UI thread {@linkplain #drainTo drains} them into the console's
 * {@link LogRing}, once a frame. A full inbox drops its oldest waiting record (counted in {@link #dropped()}): the ring
 * keeps only its latest records anyway. Appending takes a short lock and allocates nothing in steady state.
 */
public final class LogInbox implements LogSink {

    private final int[] levels;
    private final long[] times;
    private final char[][] loggers;
    private final int[] loggerLengths;
    private final char[][] messages;
    private final int[] messageLengths;
    private final char[][] sources;
    private final int[] sourceLengths;
    private int head;
    private int count;
    private long dropped;
    private final View logger = new View();
    private final View message = new View();
    private final View source = new View();

    /** An inbox of {@link ConsoleTunables#LOG_CAPACITY} slots. */
    public LogInbox() {
        this((int) ConsoleTunables.LOG_CAPACITY.get());
    }

    /** An inbox of {@code capacity} slots. */
    @AllowAllocation("one per console")
    public LogInbox(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        levels = new int[capacity];
        times = new long[capacity];
        loggers = new char[capacity][32];
        loggerLengths = new int[capacity];
        messages = new char[capacity][64];
        messageLengths = new int[capacity];
        sources = new char[capacity][16];
        sourceLengths = new int[capacity];
    }

    @Override
    public synchronized void log(int level, CharSequence loggerName, CharSequence text, CharSequence where,
        long epochMillis) {
        int n = levels.length;
        if (count == n) {
            head = (head + 1) % n; // the oldest waiting record goes
            count--;
            dropped++;
        }
        int at = (head + count) % n;
        count++;
        levels[at] = level;
        times[at] = epochMillis;
        loggerLengths[at] = copy(loggers, at, loggerName == null ? "" : loggerName);
        messageLengths[at] = copy(messages, at, text == null ? "" : text);
        sourceLengths[at] = where == null ? -1 : copy(sources, at, where);
    }

    private static int copy(char[][] buffers, int at, CharSequence text) {
        int n = Math.min(text.length(), (int) ConsoleTunables.MAX_LINE.get());
        if (buffers[at].length < n) {
            buffers[at] = grow(buffers[at], n);
        }
        char[] b = buffers[at];
        for (int i = 0; i < n; i++) {
            b[i] = text.charAt(i);
        }
        return n;
    }

    @AllowAllocation("a slot's buffer grows to the longest line it held")
    private static char[] grow(char[] buffer, int n) {
        return Arrays.copyOf(buffer, Math.max(n, buffer.length * 2));
    }

    /**
     * Hands every waiting record to {@code sink} (the console's ring), oldest first; returns how many waited. A ring
     * gets only the records it can hold: a burst larger than it would push the older ones out at once anyway.
     */
    public synchronized int drainTo(LogSink sink) {
        int n = count;
        int skip = sink instanceof LogRing ring ? Math.max(0, n - ring.capacity()) : 0;
        for (int k = skip; k < n; k++) {
            int at = (head + k) % levels.length;
            logger.set(loggers[at], loggerLengths[at]);
            message.set(messages[at], messageLengths[at]);
            source.set(sources[at], sourceLengths[at]);
            sink.log(levels[at], logger, message, sourceLengths[at] < 0 ? null : source, times[at]);
        }
        head = (head + n) % levels.length;
        count = 0;
        return n;
    }

    /** How many records wait. */
    public synchronized int waiting() {
        return count;
    }

    /** How many records were dropped because the inbox was full. */
    public synchronized long dropped() {
        return dropped;
    }

    /** A view of one slot's buffer, valid during a drain. */
    private static final class View implements CharSequence {

        private char[] chars;
        private int length;

        void set(char[] value, int n) {
            chars = value;
            length = Math.max(0, n);
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            return chars[index];
        }

        @Override
        @AllowAllocation("copies on request")
        public CharSequence subSequence(int start, int end) {
            return new String(chars, start, end - start);
        }

        @Override
        @AllowAllocation("copies on request")
        public String toString() {
            return new String(chars, 0, length);
        }
    }
}
