package com.truesim.ui.core.draw;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;

/**
 * The changed parts of a table (a draw list's primitives, its transform table) since it was last uploaded, as a few
 * sorted, disjoint ranges of records: two changes far apart upload two short ranges instead of everything between
 * them. Ranges closer than {@link #MERGE_GAP} records merge (one copy is cheaper than two for a short gap); past
 * {@link #MAX_RANGES} ranges the two closest merge, so a frame that changes everything uploads a few ranges, not
 * thousands. Adding allocates nothing once the arrays have grown.
 */
public final class DirtyRanges {

    /** Records between two changed ranges below which they upload as one (the gap is uploaded too). */
    public static final Tunable MERGE_GAP = Tunable.of("ui.draw.dirtyMergeGap", 16, 0, 4096,
        "Changed parts of a UI draw list closer than this many primitives upload as one range");
    /** The most ranges a table keeps a frame: past it the closest two merge. */
    public static final Tunable MAX_RANGES = Tunable.of("ui.draw.dirtyRanges", 16, 1, 256,
        "The most separate changed ranges of a UI draw list uploaded a frame");

    private int[] from = new int[8];
    private int[] to = new int[8];
    private int count;
    private int last = -1; // the range the last add touched: sequential writes extend it without a search

    /** No ranges. */
    @AllowAllocation("one per table")
    public DirtyRanges() {
    }

    /** The tunables of dirty ranges, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(MERGE_GAP, MAX_RANGES);
    }

    /** Marks records {@code [a, b)} changed. */
    public void add(int a, int b) {
        if (b <= a) {
            return;
        }
        int gap = (int) MERGE_GAP.get();
        if (last >= 0 && a <= to[last] + gap && b >= from[last] - gap) { // the common case: next to the last change
            from[last] = Math.min(from[last], a);
            to[last] = Math.max(to[last], b);
            joinNeighbours(last, gap);
            return;
        }
        int i = 0;
        while (i < count && to[i] + gap < a) {
            i++;
        }
        if (i < count && from[i] - gap <= b) { // overlaps or nearly touches range i
            from[i] = Math.min(from[i], a);
            to[i] = Math.max(to[i], b);
            last = joinNeighbours(i, gap);
            return;
        }
        if (count == from.length) {
            grow();
        }
        System.arraycopy(from, i, from, i + 1, count - i);
        System.arraycopy(to, i, to, i + 1, count - i);
        from[i] = a;
        to[i] = b;
        count++;
        last = i;
        int max = Math.max(1, (int) MAX_RANGES.get());
        while (count > max) {
            mergeClosest();
        }
    }

    /** Merges range {@code i} with the ranges after and before it it now reaches: the index it ends at. */
    private int joinNeighbours(int i, int gap) {
        while (i + 1 < count && from[i + 1] - gap <= to[i]) {
            to[i] = Math.max(to[i], to[i + 1]);
            remove(i + 1);
        }
        while (i > 0 && to[i - 1] + gap >= from[i]) {
            from[i - 1] = Math.min(from[i - 1], from[i]);
            to[i - 1] = Math.max(to[i - 1], to[i]);
            remove(i);
            i--;
        }
        last = i;
        return i;
    }

    private void mergeClosest() {
        int best = 0;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < count; i++) {
            int g = from[i + 1] - to[i];
            if (g < bestGap) {
                bestGap = g;
                best = i;
            }
        }
        to[best] = to[best + 1];
        remove(best + 1);
        last = best;
    }

    private void remove(int i) {
        System.arraycopy(from, i + 1, from, i, count - i - 1);
        System.arraycopy(to, i + 1, to, i, count - i - 1);
        count--;
    }

    @AllowAllocation("more ranges than ever before, rarely")
    private void grow() {
        from = java.util.Arrays.copyOf(from, from.length * 2);
        to = java.util.Arrays.copyOf(to, to.length * 2);
    }

    /** Forgets every range (all uploaded). */
    public void clear() {
        count = 0;
        last = -1;
    }

    /** Ranges, sorted and disjoint. */
    public int count() {
        return count;
    }

    /** Whether nothing changed. */
    public boolean isEmpty() {
        return count == 0;
    }

    /** The first record of range {@code i}. */
    public int from(int i) {
        return from[i];
    }

    /** The record after the last of range {@code i}. */
    public int to(int i) {
        return to[i];
    }

    /** The first changed record, or 0 if none. */
    public int first() {
        return count == 0 ? 0 : from[0];
    }

    /** The record after the last changed one, or 0 if none: with {@link #first()}, the span of every range. */
    public int end() {
        return count == 0 ? 0 : to[count - 1];
    }

    /** The records the ranges cover. */
    public int records() {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            sum += to[i] - from[i];
        }
        return sum;
    }
}
