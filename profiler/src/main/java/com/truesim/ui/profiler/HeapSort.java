package com.truesim.ui.profiler;

/** In-place heap sort of a {@code long[]} prefix: no allocation, whatever the input (the JDK's sorts may take a buffer). */
final class HeapSort {

    private HeapSort() {
    }

    /** Sorts {@code a[0..n)} ascending. */
    static void sort(long[] a, int n) {
        for (int i = n / 2 - 1; i >= 0; i--) {
            down(a, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            long t = a[0];
            a[0] = a[end];
            a[end] = t;
            down(a, 0, end);
        }
    }

    private static void down(long[] a, int i, int n) {
        long v = a[i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) {
                break;
            }
            if (c + 1 < n && a[c + 1] > a[c]) {
                c++;
            }
            if (a[c] <= v) {
                break;
            }
            a[i] = a[c];
            i = c;
        }
        a[i] = v;
    }
}
