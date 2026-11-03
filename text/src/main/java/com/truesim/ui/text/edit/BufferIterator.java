package com.truesim.ui.text.edit;

import java.text.CharacterIterator;

/** A {@link CharacterIterator} over a range of a {@link CharSequence} (a gap buffer), reused. */
final class BufferIterator implements CharacterIterator {

    private CharSequence text = "";
    private int begin;
    private int end;
    private int index;

    BufferIterator reset(CharSequence chars, int from, int to) {
        text = chars;
        begin = from;
        end = to;
        index = from;
        return this;
    }

    @Override
    public char first() {
        index = begin;
        return current();
    }

    @Override
    public char last() {
        index = end > begin ? end - 1 : end;
        return current();
    }

    @Override
    public char current() {
        return index >= begin && index < end ? text.charAt(index) : DONE;
    }

    @Override
    public char next() {
        if (index < end) {
            index++;
        }
        return current();
    }

    @Override
    public char previous() {
        if (index <= begin) {
            return DONE;
        }
        index--;
        return current();
    }

    @Override
    public char setIndex(int position) {
        if (position < begin || position > end) {
            throw new IllegalArgumentException("index " + position + " outside " + begin + ".." + end);
        }
        index = position;
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
        return index;
    }

    @Override
    @SuppressWarnings("java:S2975")
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
