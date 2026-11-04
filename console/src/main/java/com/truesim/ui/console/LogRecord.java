package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;

/**
 * One record of a {@link LogRing}: reused when the ring wraps, so read it on the UI thread while it is in the ring.
 * Its text views ({@link #message()}, {@link #source()}) are character sequences over the record's own buffers: no
 * copy, nothing allocated to show it.
 */
public final class LogRecord {

    long seq = -1;
    long version;
    int level;
    int logger;
    long time;
    int count;
    char[] message = new char[64];
    int messageLength;
    char[] source = new char[24];
    int sourceLength = -1;
    private final LogRing ring;
    private final Chars messageView = new Chars(true);
    private final Chars sourceView = new Chars(false);

    LogRecord(LogRing ring) {
        this.ring = ring;
    }

    /** The record's place in the ring's sequence of records (grows by one per record, never reused). */
    public long seq() {
        return seq;
    }

    /** When it last changed (it was written, or a repeat was coalesced into it): a time of its ring's clock. */
    public long version() {
        return version;
    }

    /** Its {@link LogLevel}. */
    public int level() {
        return level;
    }

    /** Its logger's index in the ring's logger table. */
    public int loggerIndex() {
        return logger;
    }

    /** Its logger's name. */
    public String logger() {
        return ring.loggerName(logger);
    }

    /** When it was logged (the first of coalesced repeats), in milliseconds since the epoch. */
    public long time() {
        return time;
    }

    /** How many identical records in a row it stands for (1: no repeats). */
    public int count() {
        return count;
    }

    /** The message (a view, valid while the record is not reused). */
    public CharSequence message() {
        return messageView;
    }

    /** Where it was logged ({@code File.java:42}), or null. */
    public CharSequence source() {
        return sourceLength < 0 ? null : sourceView;
    }

    boolean same(int lvl, int log, CharSequence msg, CharSequence src) {
        if (lvl != level || log != logger || !equal(message, messageLength, msg)) {
            return false;
        }
        return src == null ? sourceLength < 0 : sourceLength >= 0 && equal(source, sourceLength, src);
    }

    private static boolean equal(char[] a, int n, CharSequence b) {
        if (b.length() != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (a[i] != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the message contains {@code query}, ignoring case. */
    boolean contains(CharSequence query) {
        int n = query.length();
        outer:
        for (int i = 0; i + n <= messageLength; i++) {
            for (int k = 0; k < n; k++) {
                if (Character.toLowerCase(message[i + k]) != Character.toLowerCase(query.charAt(k))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    @AllowAllocation("debug text")
    public String toString() {
        return LogLevel.name(level) + " " + logger() + ": " + messageView + (count > 1 ? " (x" + count + ")" : "");
    }

    /** A view of the message or source buffer. */
    private final class Chars implements CharSequence {

        private final boolean ofMessage;

        Chars(boolean ofMessage) {
            this.ofMessage = ofMessage;
        }

        @Override
        public int length() {
            return ofMessage ? messageLength : Math.max(0, sourceLength);
        }

        @Override
        public char charAt(int index) {
            return ofMessage ? message[index] : source[index];
        }

        @Override
        @AllowAllocation("copies on request")
        public CharSequence subSequence(int start, int end) {
            return toString().substring(start, end);
        }

        @Override
        @AllowAllocation("copies on request")
        public String toString() {
            return ofMessage ? new String(message, 0, messageLength) : new String(source, 0, length());
        }
    }
}
