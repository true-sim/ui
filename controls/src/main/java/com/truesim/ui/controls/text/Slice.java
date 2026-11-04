package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;

/**
 * A reused view of part of a text, as a {@link CharSequence} a text block copies: a paragraph of an editor's text,
 * optionally masked (a password: each grapheme cluster shown as one mask, its other characters, such as the second
 * half of a surrogate pair, the rest of an emoji ZWJ sequence or a combining mark, as zero width spaces, so indices
 * stay those of the text).
 */
final class Slice implements CharSequence {

    private CharSequence text = "";
    private int from;
    private int to;
    private char mask;
    private boolean[] starts; // which characters start a cluster (null: each code point does)

    /** Shows {@code source[start, end)}, masked by {@code maskChar} (0 for none). */
    Slice set(CharSequence source, int start, int end, char maskChar) {
        text = source;
        from = start;
        to = end;
        mask = maskChar;
        starts = null;
        return this;
    }

    /** While masked: {@code clusterStarts[i]} says whether character {@code i} of the slice starts a cluster. */
    Slice clusters(boolean[] clusterStarts) {
        starts = clusterStarts;
        return this;
    }

    @Override
    public int length() {
        return to - from;
    }

    @Override
    public char charAt(int index) {
        char c = text.charAt(from + index);
        if (mask == 0) {
            return c;
        }
        if (starts != null) {
            return starts[index] ? mask : '​';
        }
        return Character.isLowSurrogate(c) ? '\u200B' : mask;
    }

    @Override
    @AllowAllocation("a copy, when asked for")
    public CharSequence subSequence(int start, int end) {
        StringBuilder b = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            b.append(charAt(i));
        }
        return b.toString();
    }

    @Override
    @AllowAllocation("a copy, when asked for")
    public String toString() {
        return subSequence(0, length()).toString();
    }
}
