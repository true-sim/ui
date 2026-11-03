package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;

/**
 * Text as a gap buffer: one array with a movable hole at the last edit, so typing at the caret costs a few writes
 * whatever the text's length (a megabyte of text moves only when the caret jumps far between edits). A
 * {@link CharSequence}: reading allocates nothing.
 */
public final class GapBuffer implements CharSequence {

    private char[] chars;
    private int gapStart;
    private int gapEnd;

    /** An empty buffer with room for {@code capacity} characters. */
    @AllowAllocation("one per text element")
    public GapBuffer(int capacity) {
        chars = new char[Math.max(16, capacity)];
        gapStart = 0;
        gapEnd = chars.length;
    }

    @Override
    public int length() {
        return chars.length - (gapEnd - gapStart);
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException(index);
        }
        return index < gapStart ? chars[index] : chars[index + gapEnd - gapStart];
    }

    /** Copies characters {@code [start, end)} into {@code dst} from {@code at}. */
    public void getChars(int start, int end, char[] dst, int at) {
        if (start < 0 || end > length() || start > end) {
            throw new IndexOutOfBoundsException(start + ".." + end);
        }
        int n = end - start;
        if (end <= gapStart) {
            System.arraycopy(chars, start, dst, at, n);
        } else if (start >= gapStart) {
            System.arraycopy(chars, start + gapEnd - gapStart, dst, at, n);
        } else {
            int first = gapStart - start;
            System.arraycopy(chars, start, dst, at, first);
            System.arraycopy(chars, gapEnd, dst, at + first, n - first);
        }
    }

    /** Inserts {@code text} at {@code index}. */
    public void insert(int index, CharSequence text) {
        insert(index, text, 0, text.length());
    }

    /** Inserts {@code text[from, to)} at {@code index}. */
    public void insert(int index, CharSequence text, int from, int to) {
        if (index < 0 || index > length()) {
            throw new IndexOutOfBoundsException(index);
        }
        int n = to - from;
        if (n == 0) {
            return;
        }
        moveGap(index);
        if (gapEnd - gapStart < n) {
            grow(n);
        }
        for (int i = from; i < to; i++) {
            chars[gapStart++] = text.charAt(i);
        }
    }

    /** Removes characters {@code [start, end)}. */
    public void delete(int start, int end) {
        if (start < 0 || end > length() || start > end) {
            throw new IndexOutOfBoundsException(start + ".." + end);
        }
        if (start == end) {
            return;
        }
        moveGap(start);
        gapEnd += end - start;
    }

    /** Replaces everything with {@code text}. */
    public void set(CharSequence text) {
        gapStart = 0;
        gapEnd = chars.length;
        insert(0, text);
    }

    private void moveGap(int index) {
        if (index < gapStart) {
            int n = gapStart - index;
            System.arraycopy(chars, index, chars, gapEnd - n, n);
            gapStart = index;
            gapEnd -= n;
        } else if (index > gapStart) {
            int n = index - gapStart;
            System.arraycopy(chars, gapEnd, chars, gapStart, n);
            gapStart += n;
            gapEnd += n;
        }
    }

    @AllowAllocation("growing, rarely")
    private void grow(int needed) {
        int size = length();
        int capacity = Math.max(chars.length * 2, size + needed + 16);
        char[] next = new char[capacity];
        int tail = chars.length - gapEnd;
        System.arraycopy(chars, 0, next, 0, gapStart);
        System.arraycopy(chars, gapEnd, next, capacity - tail, tail);
        gapEnd = capacity - tail;
        chars = next;
    }

    @Override
    @AllowAllocation("a copy, when asked for")
    public CharSequence subSequence(int start, int end) {
        char[] out = new char[end - start];
        getChars(start, end, out, 0);
        return new String(out);
    }

    @Override
    @AllowAllocation("a copy, when asked for")
    public String toString() {
        return subSequence(0, length()).toString();
    }

    /** Whether the text equals {@code other}'s characters. */
    public boolean contentEquals(CharSequence other) {
        if (other.length() != length()) {
            return false;
        }
        for (int i = 0; i < other.length(); i++) {
            if (charAt(i) != other.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The capacity, for tests. */
    int capacity() {
        return chars.length;
    }
}
