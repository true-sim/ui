package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.IndexVisitor;
import com.truesim.common.state.VersionedList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * A sorted and filtered view of a {@link VersionedList} (design 5.6), updated incrementally from the list's change
 * log: the rows passing the filter, in the comparator's order (ties in source order, so the sort is stable), sorted
 * once and then fixed up per changed row in O(log n + B) ({@link OrderIndex}). A million rows with a hundred changes
 * a frame stay well under a millisecond. Lists, tables and combo boxes show it like any {@link RowSource}.
 *
 * <p>Versions: a row whose value changed but kept its place is an in-place change ({@link #changesSince} visits it);
 * a row that moved, came or went changes the {@link #structureVersion()}, so views reconcile their window by
 * {@link #key} (the source index). Inserting or removing rows of the source before its end, the log overflowing, or
 * a new filter or comparator rebuild the view (O(n log n)). A filter or comparator reading outside state calls
 * {@link #invalidate()} when that state changes.
 *
 * <pre>{@code
 * DataView<Unit> view = new DataView<>(clock, units).filter(u -> u.alive()).sort(Comparator.comparingInt(Unit::hp));
 * TableView<Unit> table = new TableView<>(ui, "Units", view);
 * }</pre>
 *
 * <p>Not thread-safe: the clock's thread. {@link #refresh()} runs once a frame (views call it).
 *
 * @param <T> the row type
 */
public final class DataView<T> implements RowSource<T> {

    private final ChangeClock clock;
    private final VersionedList<T> source;
    private final OrderIndex index = new OrderIndex();
    private final OrderIndex.Order order = this::compare;
    private final IndexVisitor collect = this::collect;
    private Predicate<? super T> filter;
    private Comparator<? super T> comparator;
    private boolean rebuild = true;
    private long seen = Long.MIN_VALUE;
    private int known;
    private long version;
    private long structureVersion;
    private int[] changed = new int[256];
    private int[] positions = new int[256];
    private int changedCount;
    private int[] scratch = new int[0];
    private int[] merge = new int[0];
    // in-place changes: (stamp, position), oldest first, valid since the last structural change
    private long[] logStamp = new long[256];
    private int[] logPosition = new int[256];
    private int logStart;
    private int logSize;
    private long logFrom;
    private int builds;

    /** A view of {@code source} (all rows, in source order until a filter or comparator is set), on {@code clock}. */
    @AllowAllocation("views are made when a UI is built")
    public DataView(ChangeClock clock, VersionedList<T> source) {
        this.clock = clock;
        this.source = source;
    }

    /** The viewed list. */
    public VersionedList<T> source() {
        return source;
    }

    /** Shows only rows {@code test} accepts (null: all); the view is rebuilt. Returns this. */
    public DataView<T> filter(Predicate<? super T> test) {
        filter = test;
        invalidate();
        return this;
    }

    /** Orders rows by {@code by} (null: source order); the view is rebuilt. Returns this. */
    public DataView<T> sort(Comparator<? super T> by) {
        comparator = by;
        invalidate();
        return this;
    }

    /** The filter, or null. */
    public Predicate<? super T> filter() {
        return filter;
    }

    /** The comparator, or null. */
    public Comparator<? super T> comparator() {
        return comparator;
    }

    /** Rebuilds the view at the next refresh: the filter's or comparator's own state changed. */
    public void invalidate() {
        rebuild = true;
    }

    // ---------------------------------------------------------------- RowSource

    @Override
    public void refresh() {
        long v = source.version();
        if (rebuild) {
            build();
        } else if (v != seen) {
            changedCount = 0;
            if (!source.changesSince(seen, collect)) {
                build();
            } else {
                apply();
            }
        }
        seen = v;
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public T get(int position) {
        return source.get(index.get(position));
    }

    /** The source index of row {@code position}. */
    public int sourceIndex(int position) {
        return index.get(position);
    }

    /** The row showing source index {@code sourceIndex}, or -1 when it is filtered out. */
    public int positionOf(int sourceIndex) {
        return index.contains(sourceIndex) ? index.positionOf(sourceIndex) : -1;
    }

    @Override
    public long key(int position) {
        return index.get(position);
    }

    @Override
    public long version(int position) {
        return source.version(index.get(position));
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public boolean changesSince(long since, IndexVisitor visitor) {
        if (since < logFrom) {
            return false;
        }
        for (int i = 0; i < logSize; i++) {
            int slot = (logStart + i) % logStamp.length;
            if (logStamp[slot] > since) {
                visitor.changed(logPosition[slot]);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- updating

    private int compare(int a, int b) {
        if (comparator != null) {
            int c = comparator.compare(source.get(a), source.get(b));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a, b);
    }

    private boolean passes(int sourceIndex) {
        return filter == null || filter.test(source.get(sourceIndex));
    }

    @AllowAllocation("a rebuild: a new filter, order or structure of the source")
    private void build() {
        rebuild = false;
        builds++;
        int n = source.size();
        if (scratch.length < n) {
            scratch = new int[n];
            merge = new int[n];
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (passes(i)) {
                scratch[count++] = i;
            }
        }
        if (comparator != null) {
            sort(scratch, merge, 0, count);
        }
        index.build(scratch, count, n);
        known = n;
        long stamp = clock.stamp();
        version = stamp;
        structureVersion = stamp;
        logStart = 0;
        logSize = 0;
        logFrom = stamp;
    }

    /** A stable merge sort of {@code a[from, to)} by {@link #compare}, using {@code tmp}. */
    private void sort(int[] a, int[] tmp, int from, int to) {
        if (to - from < 24) {
            for (int i = from + 1; i < to; i++) {
                int v = a[i];
                int j = i - 1;
                while (j >= from && compare(a[j], v) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = v;
            }
            return;
        }
        int mid = (from + to) >>> 1;
        sort(a, tmp, from, mid);
        sort(a, tmp, mid, to);
        if (compare(a[mid - 1], a[mid]) <= 0) {
            return;
        }
        System.arraycopy(a, from, tmp, from, to - from);
        int i = from;
        int j = mid;
        for (int k = from; k < to; k++) {
            if (j >= to || i < mid && compare(tmp[i], tmp[j]) <= 0) {
                a[k] = tmp[i++];
            } else {
                a[k] = tmp[j++];
            }
        }
    }

    private void collect(int sourceIndex) {
        if (changedCount == changed.length) {
            growChanged();
        }
        changed[changedCount++] = sourceIndex;
    }

    @AllowAllocation("grows to the most changes in one frame, once")
    private void growChanged() {
        changed = Arrays.copyOf(changed, changed.length * 2);
    }

    /**
     * Applies the collected changes: every changed row out first (the rest stays sorted by current values), then
     * each into its place. When every changed row stayed where it was, the changes were in place.
     */
    private void apply() {
        int n = source.size();
        index.capacity(n);
        if (positions.length < changedCount) {
            growPositions(changedCount);
        }
        boolean any = false;
        for (int c = 0; c < changedCount; c++) {
            int s = changed[c];
            positions[c] = index.contains(s) ? index.positionOf(s) : -1;
        }
        for (int c = 0; c < changedCount; c++) {
            int s = changed[c];
            if (positions[c] >= 0) {
                index.remove(s);
                any = true;
            }
        }
        for (int c = 0; c < changedCount; c++) {
            int s = changed[c];
            if (passes(s)) {
                index.insert(s, order);
                any = true;
            }
        }
        known = n;
        if (!any) {
            return;
        }
        boolean moved = false;
        for (int c = 0; c < changedCount && !moved; c++) {
            int s = changed[c];
            moved = positions[c] != (index.contains(s) ? index.positionOf(s) : -1);
        }
        long stamp = clock.stamp();
        version = stamp;
        if (moved) {
            structureVersion = stamp;
            logStart = 0;
            logSize = 0;
            logFrom = stamp;
        } else {
            for (int c = 0; c < changedCount; c++) {
                if (positions[c] >= 0) {
                    log(stamp, positions[c]); // filtered out before and after: nothing to show
                }
            }
        }
    }

    @AllowAllocation("grows to the most changes in one frame, once")
    private void growPositions(int n) {
        positions = new int[Math.max(n, positions.length * 2)];
    }

    private void log(long stamp, int position) {
        if (logSize == logStamp.length) {
            logFrom = logStamp[logStart]; // the oldest goes: older questions rebuild
            logStart = (logStart + 1) % logStamp.length;
            logSize--;
        }
        int slot = (logStart + logSize++) % logStamp.length;
        logStamp[slot] = stamp;
        logPosition[slot] = position;
    }

    /** How often the view was rebuilt (a derived view rebuilds with it). */
    int builds() {
        return builds;
    }

    /** Source rows the view knows (appended rows beyond are taken in at the next refresh). */
    int known() {
        return known;
    }
}
