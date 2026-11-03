package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/** A map from {@code long} keys to {@code int} values by open addressing: lookups allocate nothing, no boxing. */
final class LongIntMap {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int size;

    @AllowAllocation("made once per atlas")
    LongIntMap(int capacity) {
        int n = Integer.highestOneBit(Math.max(4, capacity) * 2 - 1) << 1;
        keys = new long[n];
        values = new int[n];
        Arrays.fill(keys, EMPTY);
    }

    /** The value of {@code key}, or -1. */
    int get(long key) {
        int mask = keys.length - 1;
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == EMPTY) {
                return -1;
            }
        }
    }

    /** Maps {@code key} (not {@link Long#MIN_VALUE}) to {@code value}. */
    void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) {
            rehash(keys.length * 2);
        }
        int mask = keys.length - 1;
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            if (keys[i] == EMPTY) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
        }
    }

    /** Removes every key. */
    void clear() {
        Arrays.fill(keys, EMPTY);
        size = 0;
    }

    int size() {
        return size;
    }

    @AllowAllocation("growing, rarely")
    private void rehash(int n) {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[n];
        values = new int[n];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32);
    }
}
