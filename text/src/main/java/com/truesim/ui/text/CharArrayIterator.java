package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.text.CharacterIterator;

/** A {@link CharacterIterator} over part of a {@code char[]}, reused: break iterators read text without a copy. */
final class CharArrayIterator implements CharacterIterator {

    private char[] chars = new char[0];
    private int begin;
    private int end;
    private int pos;

    /** Iterates {@code chars[begin, end)}, from {@code begin}. */
    CharArrayIterator reset(char[] chars, int begin, int end) {
        this.chars = chars;
        this.begin = begin;
        this.end = end;
        this.pos = begin;
        return this;
    }

    @Override
    public char first() {
        pos = begin;
        return current();
    }

    @Override
    public char last() {
        pos = end > begin ? end - 1 : end;
        return current();
    }

    @Override
    public char current() {
        return pos >= begin && pos < end ? chars[pos] : DONE;
    }

    @Override
    public char next() {
        if (pos < end - 1) {
            pos++;
            return chars[pos];
        }
        pos = end;
        return DONE;
    }

    @Override
    public char previous() {
        if (pos <= begin) {
            return DONE;
        }
        pos--;
        return chars[pos];
    }

    @Override
    public char setIndex(int position) {
        if (position < begin || position > end) {
            throw new IllegalArgumentException("index " + position + " is not in " + begin + " to " + end);
        }
        pos = position;
        return current();
    }

    @Override
    public int getBeginIndex() {
        return begin;
    }

    @Override
    public int getEndIndex() {
        return end;
    }

    @Override
    public int getIndex() {
        return pos;
    }

    @Override
    @AllowAllocation("break iterators clone their text rarely")
    public Object clone() {
        return new CharArrayIterator().reset(chars, begin, end).withIndex(pos);
    }

    private CharArrayIterator withIndex(int index) {
        pos = index;
        return this;
    }
}
