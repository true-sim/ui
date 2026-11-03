package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/** An open-addressing map from non-negative longs to ints: lookups allocate nothing (no boxed keys). */
final class LongIntMap {

    private long[] keys = new long[128];
    private int[] values = new int[128];
    private int size;

    LongIntMap() {
        Arrays.fill(keys, -1);
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    /** The value of {@code key}, or -1. */
    int get(long key) {
        int mask = keys.length - 1;
        for (int at = hash(key) & mask; ; at = (at + 1) & mask) {
            long k = keys[at];
            if (k == key) {
                return values[at];
            }
            if (k < 0) {
                return -1;
            }
        }
    }

    /** Sets {@code key} (not yet present, non-negative) to {@code value}. */
    void put(long key, int value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        insert(keys, values, key, value);
        size++;
    }

    private static void insert(long[] keys, int[] values, long key, int value) {
        int mask = keys.length - 1;
        int at = hash(key) & mask;
        while (keys[at] >= 0 && keys[at] != key) {
            at = (at + 1) & mask;
        }
        keys[at] = key;
        values[at] = value;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        long[] k = new long[keys.length * 2];
        int[] v = new int[keys.length * 2];
        Arrays.fill(k, -1);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] >= 0) {
                insert(k, v, keys[i], values[i]);
            }
        }
        keys = k;
        values = v;
    }

    /** Removes every entry. */
    void clear() {
        Arrays.fill(keys, -1);
        size = 0;
    }

    /** How many entries there are. */
    int size() {
        return size;
    }
}
