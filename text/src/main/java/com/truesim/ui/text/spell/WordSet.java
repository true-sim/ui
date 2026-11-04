package com.truesim.ui.text.spell;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A set of words in one character pool with an open-addressing hash, looked up by any {@link CharSequence} range
 * without making a string: what spell checking asks per word, many times a frame while text is rechecked. Words keep
 * the order they were added in and are numbered from 0 ({@link #word}), so an index (a BK-tree) refers to them by
 * number.
 */
public final class WordSet {

    private char[] pool = new char[1024];
    private int poolSize;
    private int[] start = new int[64];
    private int[] length = new int[64];
    private int count;
    private int[] table = new int[128]; // word number + 1, 0 empty
    private long version;

    /** An empty set. */
    @AllowAllocation("one per dictionary")
    public WordSet() {
        // empty
    }

    /** How many words. */
    public int size() {
        return count;
    }

    /** A number that changes on every addition and removal. */
    public long version() {
        return version;
    }

    /** Adds {@code word} (a range of {@code text}); true if it was not there. */
    @AllowAllocation("growing while words are added")
    public boolean add(CharSequence text, int from, int to) {
        if (to <= from || find(text, from, to) >= 0) {
            return false;
        }
        int n = to - from;
        if (poolSize + n > pool.length) {
            pool = Arrays.copyOf(pool, Math.max(pool.length * 2, poolSize + n));
        }
        for (int i = 0; i < n; i++) {
            pool[poolSize + i] = text.charAt(from + i);
        }
        if (count == start.length) {
            start = Arrays.copyOf(start, count * 2);
            length = Arrays.copyOf(length, count * 2);
        }
        start[count] = poolSize;
        length[count] = n;
        poolSize += n;
        count++;
        if (count * 2 > table.length) {
            rehash(table.length * 2);
        } else {
            insert(count - 1);
        }
        version++;
        return true;
    }

    /** Adds {@code word}; true if it was not there. */
    @AllowAllocation("growing while words are added")
    public boolean add(CharSequence word) {
        return add(word, 0, word.length());
    }

    /** Whether the range {@code [from, to)} of {@code text} is a word of the set. Allocates nothing. */
    public boolean contains(CharSequence text, int from, int to) {
        return find(text, from, to) >= 0;
    }

    /** Whether {@code word} is in the set. */
    public boolean contains(CharSequence word) {
        return find(word, 0, word.length()) >= 0;
    }

    /** The number of the word {@code [from, to)} of {@code text}, or -1. */
    public int find(CharSequence text, int from, int to) {
        int mask = table.length - 1;
        int h = hash(text, from, to) & mask;
        int n = to - from;
        while (true) {
            int w = table[h] - 1;
            if (w < 0) {
                return -1;
            }
            if (length[w] == n && equal(w, text, from)) {
                return w;
            }
            h = (h + 1) & mask;
        }
    }

    /**
     * Removes {@code word} (the user dictionary's "remove"); true if it was there. The word's number goes to the last
     * word: numbers are not stable across removals.
     */
    @AllowAllocation("removing is a user action")
    public boolean remove(CharSequence word) {
        int w = find(word, 0, word.length());
        if (w < 0) {
            return false;
        }
        int last = count - 1;
        start[w] = start[last];
        length[w] = length[last];
        count--;
        rehash(table.length); // the pool keeps the removed characters until it is rebuilt: removals are rare
        version++;
        return true;
    }

    /** Removes every word. */
    public void clear() {
        count = 0;
        poolSize = 0;
        Arrays.fill(table, 0);
        version++;
    }

    /** Word {@code index} as a string. */
    @AllowAllocation("words are read on user action")
    public String word(int index) {
        return new String(pool, start[index], length[index]);
    }

    /** The length of word {@code index}. */
    public int length(int index) {
        return length[index];
    }

    /** Character {@code at} of word {@code index}. */
    public char charAt(int index, int at) {
        return pool[start[index] + at];
    }

    private boolean equal(int w, CharSequence text, int from) {
        int s = start[w];
        for (int i = 0, n = length[w]; i < n; i++) {
            if (pool[s + i] != text.charAt(from + i)) {
                return false;
            }
        }
        return true;
    }

    private static int hash(CharSequence text, int from, int to) {
        int h = 0x811C9DC5;
        for (int i = from; i < to; i++) {
            h = (h ^ text.charAt(i)) * 0x01000193;
        }
        return h ^ (h >>> 15);
    }

    private int hashOf(int w) {
        int h = 0x811C9DC5;
        int s = start[w];
        for (int i = 0, n = length[w]; i < n; i++) {
            h = (h ^ pool[s + i]) * 0x01000193;
        }
        return h ^ (h >>> 15);
    }

    private void insert(int w) {
        int mask = table.length - 1;
        int h = hashOf(w) & mask;
        while (table[h] != 0) {
            h = (h + 1) & mask;
        }
        table[h] = w + 1;
    }

    @AllowAllocation("growing while words are added")
    private void rehash(int size) {
        table = new int[size];
        for (int w = 0; w < count; w++) {
            insert(w);
        }
    }
}
