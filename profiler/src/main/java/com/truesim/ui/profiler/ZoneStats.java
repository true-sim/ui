package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;

/**
 * Zones of a {@link Recording} summed up by name over a span of time: per name the calls, the total, mean, 99th
 * percentile and longest duration, and the self time (less the zones directly inside). Zones count when they end
 * within the span ({@link #compute}); open zones do not. Rows sort by any column. Computing allocates nothing once its
 * scratch has grown to the most zones met.
 */
public final class ZoneStats {

    /** A column rows sort by. */
    public enum Column {
        /** The name, alphabetically. */
        NAME,
        /** Calls. */
        CALLS,
        /** Total time. */
        TOTAL,
        /** Self time (the total less the zones directly inside). */
        SELF,
        /** Mean time. */
        AVG,
        /** The 99th percentile. */
        P99,
        /** The longest. */
        MAX
    }

    private static final int NAME_SHIFT = 40;
    private static final long DURATION_MASK = (1L << NAME_SHIFT) - 1;

    private long[] packed = new long[4096]; // name << 40 | duration, sorted to group by name, then duration
    private long[] childTime = new long[4096]; // per name id: time spent in zones directly inside its zones
    private int count;
    // rows
    private int[] rowName = new int[256];
    private long[] calls = new long[256];
    private long[] total = new long[256];
    private long[] self = new long[256];
    private long[] p99 = new long[256];
    private long[] max = new long[256];
    private int[] order = new int[256];
    private double[] key = new double[256];
    private int rows;
    private Column sortedBy = Column.TOTAL;
    private boolean descending = true;
    // the open zone per depth while scanning a lane: its name and start
    private int[] stackName = new int[64];
    private long[] stackStart = new long[64];
    private long[] stackEnd = new long[64];

    /**
     * Sums up {@code recording}'s zones ending within {@code from..to} on lane {@code lane}, or on every thread lane
     * when {@code lane} is negative; the rows keep their sort. Returns this.
     */
    public ZoneStats compute(Recording recording, int lane, long from, long to) {
        count = 0;
        int names = recording.names();
        if (childTime.length < names) {
            growNames(names);
        }
        java.util.Arrays.fill(childTime, 0, names, 0);
        for (int l = 0; l < recording.lanes(); l++) {
            if (lane >= 0 ? l != lane : recording.laneKind(l) != LaneKind.THREAD) {
                continue;
            }
            scan(recording, l, from, to);
        }
        HeapSort.sort(packed, count);
        rows = 0;
        int i = 0;
        while (i < count) {
            int name = (int) (packed[i] >>> NAME_SHIFT);
            int j = i;
            long sum = 0;
            while (j < count && (int) (packed[j] >>> NAME_SHIFT) == name) {
                sum += packed[j] & DURATION_MASK;
                j++;
            }
            int n = j - i;
            row(name, n, sum, packed[i + Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1)] & DURATION_MASK,
                packed[j - 1] & DURATION_MASK);
            i = j;
        }
        for (int r = 0; r < rows; r++) {
            self[r] = total[r] - childTime[rowName[r]];
        }
        sort(sortedBy, descending);
        return this;
    }

    private void scan(Recording recording, int lane, long from, long to) {
        long first = recording.seek(lane, from);
        long end = recording.endZone(lane);
        int depthTop = -1;
        for (long z = first; z < end; z++) {
            long s = recording.zoneStart(lane, z);
            if (s > to) {
                break;
            }
            long e = recording.zoneEnd(lane, z);
            int d = recording.zoneDepth(lane, z);
            if (d >= stackName.length) {
                growStack(d + 1);
            }
            int name = recording.zoneName(lane, z);
            stackName[d] = name;
            stackStart[d] = s;
            stackEnd[d] = e;
            depthTop = d;
            if (e == Recording.OPEN || e < from || e > to) {
                continue;
            }
            long duration = Math.min(DURATION_MASK, Math.max(0, e - s));
            if (count == packed.length) {
                growZones();
            }
            packed[count++] = (long) name << NAME_SHIFT | duration;
            if (d > 0 && depthTop >= 0 && stackStart[d - 1] <= s && stackEnd[d - 1] >= e) {
                childTime[stackName[d - 1]] += duration;
            }
        }
    }

    private void row(int name, long n, long sum, long percentile, long longest) {
        if (rows == rowName.length) {
            growRows();
        }
        rowName[rows] = name;
        calls[rows] = n;
        total[rows] = sum;
        p99[rows] = percentile;
        max[rows] = longest;
        order[rows] = rows;
        rows++;
    }

    /**
     * Orders the rows by {@code column}, {@code down} (largest first; names: Z first) or up. Allocates nothing.
     */
    public void sort(Column column, boolean down) {
        sortedBy = column;
        descending = down;
        for (int r = 0; r < rows; r++) {
            order[r] = r;
            key[r] = switch (column) {
                case NAME -> r; // by name below
                case CALLS -> calls[r];
                case TOTAL -> total[r];
                case SELF -> self[r];
                case AVG -> calls[r] == 0 ? 0 : (double) total[r] / calls[r];
                case P99 -> p99[r];
                case MAX -> max[r];
            };
        }
        // insertion sort by key (rows are names: few hundred at most), stable
        for (int i = 1; i < rows; i++) {
            int o = order[i];
            int j = i - 1;
            while (j >= 0 && before(o, order[j])) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = o;
        }
    }

    private Recording names; // for NAME sorting, set by the view

    /** The recording whose names a sort by {@link Column#NAME} compares. */
    public ZoneStats names(Recording recording) {
        names = recording;
        return this;
    }

    private boolean before(int a, int b) {
        int c;
        if (sortedBy == Column.NAME) {
            c = names == null ? Integer.compare(rowName[a], rowName[b])
                : names.nameOf(rowName[a]).compareTo(names.nameOf(rowName[b]));
        } else {
            c = Double.compare(key[a], key[b]);
        }
        return descending ? c > 0 : c < 0;
    }

    /** The column the rows are sorted by. */
    public Column sortedBy() {
        return sortedBy;
    }

    /** Whether the sort is largest first. */
    public boolean descending() {
        return descending;
    }

    /** How many rows (names) there are. */
    public int rows() {
        return rows;
    }

    /** How many zones were counted. */
    public int zones() {
        return count;
    }

    /** The name id of the {@code i}-th row in sort order. */
    public int name(int i) {
        return rowName[order[i]];
    }

    /** The {@code i}-th row's calls. */
    public long calls(int i) {
        return calls[order[i]];
    }

    /** The {@code i}-th row's total, in nanoseconds. */
    public long total(int i) {
        return total[order[i]];
    }

    /** The {@code i}-th row's self time. */
    public long self(int i) {
        return self[order[i]];
    }

    /** The {@code i}-th row's mean. */
    public long avg(int i) {
        int r = order[i];
        return calls[r] == 0 ? 0 : total[r] / calls[r];
    }

    /** The {@code i}-th row's 99th percentile. */
    public long p99(int i) {
        return p99[order[i]];
    }

    /** The {@code i}-th row's longest. */
    public long max(int i) {
        return max[order[i]];
    }

    /** The row of name {@code name} in sort order, or -1. */
    public int rowOf(int name) {
        for (int i = 0; i < rows; i++) {
            if (rowName[order[i]] == name) {
                return i;
            }
        }
        return -1;
    }

    @AllowAllocation("scratch grows to the most zones met")
    private void growZones() {
        packed = java.util.Arrays.copyOf(packed, packed.length * 2);
    }

    @AllowAllocation("scratch grows to the most names met")
    private void growNames(int names) {
        childTime = new long[Math.max(names, childTime.length * 2)];
    }

    @AllowAllocation("scratch grows to the deepest zone met")
    private void growStack(int depth) {
        int n = Math.max(depth, stackName.length * 2);
        stackName = java.util.Arrays.copyOf(stackName, n);
        stackStart = java.util.Arrays.copyOf(stackStart, n);
        stackEnd = java.util.Arrays.copyOf(stackEnd, n);
    }

    @AllowAllocation("rows grow to the most names met")
    private void growRows() {
        int n = rowName.length * 2;
        rowName = java.util.Arrays.copyOf(rowName, n);
        calls = java.util.Arrays.copyOf(calls, n);
        total = java.util.Arrays.copyOf(total, n);
        self = java.util.Arrays.copyOf(self, n);
        p99 = java.util.Arrays.copyOf(p99, n);
        max = java.util.Arrays.copyOf(max, n);
        order = java.util.Arrays.copyOf(order, n);
        key = java.util.Arrays.copyOf(key, n);
    }
}
