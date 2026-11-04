package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.IndexVisitor;
import com.truesim.common.state.VersionedList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The rows of a {@link DataView} grouped by one or more keys (design 5.6 and 7: sorted, filtered and grouped views fed
 * from change logs): before each group's rows a header row, nested headers for nested levels, and collapsible groups.
 * The view's comparator starts with the group keys ({@link #order}), so each group's rows follow one another in the
 * view; this source inserts the headers and leaves out collapsed groups' rows.
 *
 * <p><b>Incremental.</b> Groups are kept per finest group (a leaf: one value per level), in order, each with its row
 * count (and, optionally, a sum), in Fenwick trees over the leaves: the rows each leaf shows (headers plus rows when
 * open) and the rows each holds, so a row index maps to a header or a view position in O(log G). Each frame the
 * source list's change log names the changed rows; each leaves its old group (known per source row) and joins the
 * group its keys find by binary search over the groups' representative rows, O(log G) comparisons, so a hundred
 * changes a frame among 100,000 rows cost a few microseconds. A group appearing or emptying, a rebuilt view, or a
 * representative row changed in place regroups from scratch (O(n)). Rows must not change their keys in place
 * without {@code touch} (immutable rows, replaced with {@code set}, are the model's way).
 *
 * <p>Header rows are not {@linkplain #selectable selectable}; {@link #get} gives a header's first row (its group's
 * values). Keys of headers are negative.
 *
 * @param <R> the row type
 */
public final class GroupedRows<R> implements RowSource<R> {

    private final ChangeClock clock;
    private final DataView<R> data;
    private final VersionedList<R> source;
    private final List<Comparator<? super R>> levels = new ArrayList<>();
    private int depth;
    private ToDoubleFunction<? super R> summed;
    private final List<R> collapsedRep = new ArrayList<>();
    private final List<Integer> collapsedLevel = new ArrayList<>();

    // leaves, in order
    private int groups;
    private int[] count = new int[16];
    private Object[] rep = new Object[16];
    private int[] firstLevel = new int[16];
    private int[] hiddenFrom = new int[16];
    private int[] weight = new int[16];
    private double[] sum = new double[16];
    private int[] endOf = new int[16]; // leaf * depth + level -> the leaf after the level's group
    private int[] fenWeight = new int[17];
    private int[] fenCount = new int[17];
    private double[] fenSum = new double[17];
    private int highBit = 1;
    // by source index
    private int[] groupOf = new int[0];
    private double[] contrib = new double[0];

    private int[] changed = new int[64];
    private int changedCount;
    private final IndexVisitor collect = this::collect;
    private final IndexVisitor forward = this::forward;
    private IndexVisitor target;

    private boolean rebuild = true;
    private long seenSource = Long.MIN_VALUE;
    private int seenBuilds = -1;
    private long seenDataStructure = Long.MIN_VALUE;
    private long version;
    private long structureVersion;
    private long headerVersion;
    private int[] keyLevel = new int[8];

    /** The rows of {@code data} grouped by nothing yet ({@link #levels}). */
    @AllowAllocation("views are made when a UI is built")
    public GroupedRows(ChangeClock clock, DataView<R> data) {
        this.clock = clock;
        this.data = data;
        this.source = data.source();
    }

    /** The grouped view. */
    public DataView<R> data() {
        return data;
    }

    /**
     * Groups by {@code keys}, outermost first (each a comparator: rows comparing equal are one group); regroups at the
     * next refresh. Set the view's order with {@link #order}.
     */
    @AllowAllocation("a user action: grouping changes")
    public GroupedRows<R> levels(List<? extends Comparator<? super R>> keys) {
        levels.clear();
        levels.addAll(keys);
        depth = levels.size();
        collapsedRep.clear();
        collapsedLevel.clear();
        rebuild = true;
        return this;
    }

    /** How many grouping levels. */
    public int depth() {
        return depth;
    }

    /** The comparator the view must sort by: the group keys, then {@code then} (null: source order). */
    @AllowAllocation("a user action: the comparator is composed anew")
    public Comparator<R> order(Comparator<? super R> then) {
        Comparator<R> c = null;
        for (Comparator<? super R> level : levels) {
            @SuppressWarnings("unchecked")
            Comparator<R> l = (Comparator<R>) level;
            c = c == null ? l : c.thenComparing(l);
        }
        if (then != null) {
            @SuppressWarnings("unchecked")
            Comparator<R> t = (Comparator<R>) then;
            c = c == null ? t : c.thenComparing(t);
        }
        return c;
    }

    /** Keeps a sum of {@code value} per group (shown by headers; null: none); regroups. Returns this. */
    public GroupedRows<R> sum(ToDoubleFunction<? super R> value) {
        summed = value;
        rebuild = true;
        return this;
    }

    // ---------------------------------------------------------------- headers

    /** Whether row {@code index} is a group header. */
    public boolean header(int index) {
        int g = leafAt(index);
        return index - prefixWeight(g) < headers(g);
    }

    /** The level (0: outermost) of header row {@code index}. */
    public int level(int index) {
        int g = leafAt(index);
        return firstLevel[g] + (index - prefixWeight(g));
    }

    /** How many rows the group of header row {@code index} holds (all its levels' rows). */
    public int count(int index) {
        int g = leafAt(index);
        int level = firstLevel[g] + (index - prefixWeight(g));
        int end = endOf[g * depth + level];
        return prefixCount(end) - prefixCount(g);
    }

    /** The sum of the group of header row {@code index} ({@link #sum}), or 0. */
    public double sum(int index) {
        int g = leafAt(index);
        int level = firstLevel[g] + (index - prefixWeight(g));
        int end = endOf[g * depth + level];
        return prefixSum(end) - prefixSum(g);
    }

    /** Whether the group of header row {@code index} is collapsed. */
    public boolean collapsed(int index) {
        int g = leafAt(index);
        int level = firstLevel[g] + (index - prefixWeight(g));
        return hiddenFrom[g] == level;
    }

    /** Collapses or opens the group of header row {@code index}. */
    @AllowAllocation("a user action")
    @SuppressWarnings("unchecked")
    public void collapse(int index, boolean on) {
        if (!header(index) || collapsed(index) == on) {
            return;
        }
        int g = leafAt(index);
        int level = firstLevel[g] + (index - prefixWeight(g));
        R r = (R) rep[g];
        if (on) {
            collapsedRep.add(r);
            collapsedLevel.add(level);
        } else {
            for (int i = collapsedRep.size() - 1; i >= 0; i--) {
                if (collapsedLevel.get(i) == level && differs(collapsedRep.get(i), r) > level) {
                    collapsedRep.remove(i);
                    collapsedLevel.remove(i);
                }
            }
        }
        hide();
        long stamp = clock.stamp();
        structureVersion = stamp;
        headerVersion = stamp;
        version = stamp;
    }

    /** How many finest groups there are. */
    public int groups() {
        return groups;
    }

    /** The view position of row {@code index} (not a header), for editing. */
    public int position(int index) {
        int g = leafAt(index);
        return prefixCount(g) + (index - prefixWeight(g) - headers(g));
    }

    // ---------------------------------------------------------------- RowSource

    @Override
    public int size() {
        return prefixWeight(groups);
    }

    @Override
    @SuppressWarnings("unchecked")
    public R get(int index) {
        int g = leafAt(index);
        int o = index - prefixWeight(g);
        int h = headers(g);
        return o < h ? (R) rep[g] : data.get(prefixCount(g) + o - h);
    }

    @Override
    public long key(int index) {
        int g = leafAt(index);
        int o = index - prefixWeight(g);
        int h = headers(g);
        return o < h ? -1 - ((long) g * 64 + firstLevel[g] + o) : data.key(prefixCount(g) + o - h);
    }

    @Override
    public long version(int index) {
        int g = leafAt(index);
        int o = index - prefixWeight(g);
        int h = headers(g);
        return o < h ? headerVersion : data.version(prefixCount(g) + o - h);
    }

    @Override
    public long version() {
        return Math.max(version, data.version());
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public boolean selectable(int index) {
        return !header(index);
    }

    @Override
    public boolean changesSince(long since, IndexVisitor visitor) {
        if (since < structureVersion || since < headerVersion) {
            return false;
        }
        target = visitor;
        try {
            return data.changesSince(since, forward);
        } finally {
            target = null;
        }
    }

    private void forward(int position) {
        // the leaf holding the view position: a descent of the counts' tree
        int at = 0;
        int rest = position;
        for (int step = highBit; step > 0; step >>= 1) {
            int next = at + step;
            if (next <= groups && fenCount[next] <= rest) {
                at = next;
                rest -= fenCount[next];
            }
        }
        if (at < groups && hiddenFrom[at] == depth) {
            target.changed(prefixWeight(at) + headers(at) + rest);
        }
    }

    // ---------------------------------------------------------------- updating

    @Override
    public void refresh() {
        long v = source.version();
        boolean known = v == seenSource || source.changesSince(seenSource, collect);
        data.refresh();
        if (rebuild || !known || data.builds() != seenBuilds || depth == 0) {
            build();
        } else if (changedCount > 0) {
            apply();
        }
        changedCount = 0;
        seenSource = v;
        if (data.structureVersion() != seenDataStructure) {
            seenDataStructure = data.structureVersion();
            structureVersion = Math.max(structureVersion, clock.stamp());
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

    /** The changed rows leave their groups and join the ones their keys find. */
    @SuppressWarnings("unchecked")
    private void apply() {
        int n = source.size();
        capacity(n);
        boolean regroup = false;
        for (int c = 0; c < changedCount && !regroup; c++) {
            int s = changed[c];
            int g = groupOf[s];
            if (g >= 0) {
                if (s < n && rep[g] == source.get(s)) {
                    regroup = true; // its representative changed in place: its keys cannot be trusted
                }
                addTo(g, -1, -contrib[s]);
                groupOf[s] = -1;
            }
        }
        for (int c = 0; c < changedCount && !regroup; c++) {
            int s = changed[c];
            if (s >= n || data.positionOf(s) < 0) {
                continue; // filtered out
            }
            R row = source.get(s);
            int g = find(row);
            if (g < 0) {
                regroup = true; // a new group
                break;
            }
            double v = summed == null ? 0 : summed.applyAsDouble(row);
            contrib[s] = v;
            groupOf[s] = g;
            addTo(g, 1, v);
        }
        for (int g = 0; g < groups && !regroup; g++) {
            regroup = count[g] == 0; // a group emptied
        }
        if (regroup) {
            build();
            return;
        }
        long stamp = clock.stamp();
        version = stamp;
        headerVersion = stamp;
        structureVersion = stamp; // counts moved rows below them
    }

    private void addTo(int g, int rows, double value) {
        count[g] += rows;
        sum[g] += value;
        for (int i = g + 1; i <= groups; i += i & -i) {
            fenCount[i] += rows;
            fenSum[i] += value;
        }
        if (hiddenFrom[g] == depth) {
            weight[g] += rows;
            for (int i = g + 1; i <= groups; i += i & -i) {
                fenWeight[i] += rows;
            }
        }
    }

    /** The leaf whose keys equal {@code row}'s, or -1. */
    @SuppressWarnings("unchecked")
    private int find(R row) {
        int low = 0;
        int high = groups - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int c = compare(row, (R) rep[mid]);
            if (c == 0) {
                return mid;
            }
            if (c < 0) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return -1;
    }

    private int compare(R a, R b) {
        for (int k = 0; k < depth; k++) {
            int c = levels.get(k).compare(a, b);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The first level at which {@code a} and {@code b} differ ({@link #depth} when none). */
    private int differs(R a, R b) {
        for (int k = 0; k < depth; k++) {
            if (levels.get(k).compare(a, b) != 0) {
                return k;
            }
        }
        return depth;
    }

    @AllowAllocation("grows with the source, amortized")
    private void capacity(int n) {
        if (groupOf.length < n) {
            int old = groupOf.length;
            groupOf = Arrays.copyOf(groupOf, Math.max(n, old * 2));
            Arrays.fill(groupOf, old, groupOf.length, -1);
            contrib = Arrays.copyOf(contrib, groupOf.length);
        }
    }

    /** Groups every row of the view again: O(n). */
    @AllowAllocation("a regrouping: grouping, the view or the set of groups changed")
    @SuppressWarnings("unchecked")
    private void build() {
        rebuild = false;
        seenBuilds = data.builds();
        capacity(source.size());
        Arrays.fill(groupOf, -1);
        int n = data.size();
        groups = 0;
        if (depth == 0) {
            ensureGroups(1);
            groups = n > 0 ? 1 : 0;
            if (groups == 1) {
                rep[0] = data.get(0);
                count[0] = n;
                firstLevel[0] = 0;
                sum[0] = 0;
            }
        } else {
            for (int p = 0; p < n; p++) {
                R row = data.get(p);
                int d = groups == 0 ? 0 : differs((R) rep[groups - 1], row);
                if (groups == 0 || d < depth) {
                    ensureGroups(groups + 1);
                    rep[groups] = row;
                    firstLevel[groups] = d;
                    count[groups] = 0;
                    sum[groups] = 0;
                    groups++;
                }
                int g = groups - 1;
                int s = data.sourceIndex(p);
                double v = summed == null ? 0 : summed.applyAsDouble(row);
                count[g]++;
                sum[g] += v;
                groupOf[s] = g;
                contrib[s] = v;
            }
        }
        for (int g = groups; g < rep.length; g++) {
            rep[g] = null;
        }
        // where each header's group ends: the next leaf starting a group at its level or above
        int levelsHere = Math.max(1, depth);
        if (endOf.length < groups * levelsHere) {
            endOf = new int[Math.max(groups * levelsHere, endOf.length * 2)];
        }
        if (keyLevel.length < levelsHere) {
            keyLevel = new int[levelsHere];
        }
        Arrays.fill(keyLevel, 0, levelsHere, groups);
        for (int g = groups - 1; g >= 0; g--) {
            for (int k = 0; k < depth; k++) {
                endOf[g * depth + k] = keyLevel[k];
            }
            for (int k = firstLevel[g]; k < depth; k++) {
                keyLevel[k] = g;
            }
        }
        hide();
        long stamp = clock.stamp();
        version = stamp;
        structureVersion = stamp;
        headerVersion = stamp;
    }

    @AllowAllocation("grows with the groups, amortized")
    private void ensureGroups(int n) {
        if (n > count.length) {
            int m = Math.max(n, count.length * 2);
            count = Arrays.copyOf(count, m);
            rep = Arrays.copyOf(rep, m);
            firstLevel = Arrays.copyOf(firstLevel, m);
            hiddenFrom = Arrays.copyOf(hiddenFrom, m);
            weight = Arrays.copyOf(weight, m);
            sum = Arrays.copyOf(sum, m);
        }
        if (fenWeight.length < n + 1) {
            int m = Math.max(n + 1, fenWeight.length * 2);
            fenWeight = new int[m];
            fenCount = new int[m];
            fenSum = new double[m];
        }
    }

    /** Which levels each leaf hides (collapsed groups), its weight, and the trees. */
    @SuppressWarnings("unchecked")
    private void hide() {
        ensureGroups(Math.max(1, groups));
        int collapsedFrom = depth; // the outermost collapsed level of the current chain
        for (int g = 0; g < groups; g++) {
            int f = firstLevel[g];
            if (collapsedFrom >= f) {
                collapsedFrom = depth; // the chain from f is new
                for (int k = f; k < depth && collapsedFrom == depth; k++) {
                    if (isCollapsed((R) rep[g], k)) {
                        collapsedFrom = k;
                    }
                }
            }
            hiddenFrom[g] = collapsedFrom;
            weight[g] = headers(g) + (collapsedFrom == depth ? count[g] : 0);
        }
        Arrays.fill(fenWeight, 0, groups + 1, 0);
        Arrays.fill(fenCount, 0, groups + 1, 0);
        Arrays.fill(fenSum, 0, groups + 1, 0);
        for (int g = 0; g < groups; g++) {
            int i = g + 1;
            fenWeight[i] += weight[g];
            fenCount[i] += count[g];
            fenSum[i] += sum[g];
            int parent = i + (i & -i);
            if (parent <= groups) {
                fenWeight[parent] += fenWeight[i];
                fenCount[parent] += fenCount[i];
                fenSum[parent] += fenSum[i];
            }
        }
        highBit = Integer.highestOneBit(Math.max(1, groups));
    }

    @SuppressWarnings("unchecked")
    private boolean isCollapsed(R row, int level) {
        for (int i = 0; i < collapsedRep.size(); i++) {
            if (collapsedLevel.get(i) == level && differs(collapsedRep.get(i), row) > level) {
                return true;
            }
        }
        return false;
    }

    /** How many header rows leaf {@code g} shows. */
    private int headers(int g) {
        if (depth == 0 || g >= groups) {
            return 0;
        }
        int f = firstLevel[g];
        int h = hiddenFrom[g];
        return h < f ? 0 : Math.min(depth - 1, h) - f + 1;
    }

    /** The leaf showing row {@code index}. */
    private int leafAt(int index) {
        int at = 0;
        int rest = index;
        for (int step = highBit; step > 0; step >>= 1) {
            int next = at + step;
            if (next <= groups && fenWeight[next] <= rest) {
                at = next;
                rest -= fenWeight[next];
            }
        }
        if (at >= groups) {
            throw new IndexOutOfBoundsException(index);
        }
        return at;
    }

    private int prefixWeight(int g) {
        int s = 0;
        for (int i = g; i > 0; i -= i & -i) {
            s += fenWeight[i];
        }
        return s;
    }

    private int prefixCount(int g) {
        int s = 0;
        for (int i = g; i > 0; i -= i & -i) {
            s += fenCount[i];
        }
        return s;
    }

    private double prefixSum(int g) {
        double s = 0;
        for (int i = g; i > 0; i -= i & -i) {
            s += fenSum[i];
        }
        return s;
    }
}
