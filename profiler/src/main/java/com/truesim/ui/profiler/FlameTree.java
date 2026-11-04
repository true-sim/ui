package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;

/**
 * The zones of one lane of a {@link Recording} merged by call path over a span of time, for a flame graph: node 0 is
 * the span itself, each other node a name under its parent's path with the time spent there (zones cut to the span)
 * and how many zones it merges. Each node has a horizontal place: {@link #offset} after its earlier siblings within its
 * parent, so a node's children lie side by side under it, in the order first met. Building allocates nothing once its
 * arrays have grown to the most nodes met (up to a cap; paths past it are left out and counted).
 */
public final class FlameTree {

    private int[] name = new int[256];
    private int[] parent = new int[256];
    private int[] depth = new int[256];
    private int[] firstChild = new int[256];
    private int[] nextSibling = new int[256];
    private int[] lastChild = new int[256];
    private long[] time = new long[256];
    private long[] calls = new long[256];
    private long[] offset = new long[256];
    private long[] used = new long[256]; // per node: how much of it its children took so far
    private int count;
    private int dropped;
    private int maxDepth;
    private int[] stack = new int[64];

    /**
     * Builds the tree of lane {@code lane}'s zones overlapping {@code from..to} (open zones run to {@code to}), with at
     * most {@code maxNodes} nodes. Returns this.
     */
    public FlameTree build(Recording recording, int lane, long from, long to, int maxNodes) {
        count = 0;
        dropped = 0;
        maxDepth = 0;
        node(-1, -1, 0);
        time[0] = Math.max(0, to - from);
        long first = recording.seek(lane, from);
        long end = recording.endZone(lane);
        for (long z = first; z < end; z++) {
            long s = recording.zoneStart(lane, z);
            if (s >= to) {
                break;
            }
            long e = recording.zoneEnd(lane, z);
            if (e == Recording.OPEN) {
                e = to;
            }
            if (e <= from) {
                continue;
            }
            int d = recording.zoneDepth(lane, z);
            if (d + 1 >= stack.length) {
                growStack(d + 2);
            }
            int p = d == 0 ? 0 : stack[d];
            if (p < 0) {
                stack[d + 1] = -1; // its parent was left out
                continue;
            }
            int n = recording.zoneName(lane, z);
            int child = find(p, n);
            if (child < 0) {
                if (count >= maxNodes) {
                    dropped++;
                    stack[d + 1] = -1;
                    continue;
                }
                child = node(p, n, depth[p] + 1);
            }
            time[child] += Math.min(e, to) - Math.max(s, from);
            calls[child]++;
            stack[d + 1] = child;
        }
        // places: each node after its earlier siblings, nodes in the order made (parents first)
        for (int i = 1; i < count; i++) {
            int p = parent[i];
            offset[i] = offset[p] + used[p];
            used[p] += time[i];
        }
        return this;
    }

    /** Empties the tree to a span of {@code nanos} without nodes (nothing to show). Returns this. */
    public FlameTree empty(long nanos) {
        count = 0;
        dropped = 0;
        maxDepth = 0;
        node(-1, -1, 0);
        time[0] = Math.max(0, nanos);
        return this;
    }

    private int find(int p, int n) {
        for (int c = firstChild[p]; c >= 0; c = nextSibling[c]) {
            if (name[c] == n) {
                return c;
            }
        }
        return -1;
    }

    private int node(int p, int n, int d) {
        if (count == name.length) {
            grow();
        }
        int i = count++;
        name[i] = n;
        parent[i] = p;
        depth[i] = d;
        firstChild[i] = -1;
        nextSibling[i] = -1;
        lastChild[i] = -1;
        time[i] = 0;
        calls[i] = 0;
        offset[i] = 0;
        used[i] = 0;
        if (p >= 0) {
            if (lastChild[p] < 0) {
                firstChild[p] = i;
            } else {
                nextSibling[lastChild[p]] = i;
            }
            lastChild[p] = i;
        }
        maxDepth = Math.max(maxDepth, d);
        return i;
    }

    /** How many nodes there are, the span's included. */
    public int count() {
        return count;
    }

    /** Zones left out because the tree had {@code maxNodes} already. */
    public int dropped() {
        return dropped;
    }

    /** The deepest node's depth (the span is 0). */
    public int maxDepth() {
        return maxDepth;
    }

    /** Node {@code i}'s name id (-1 for the span). */
    public int name(int i) {
        return name[i];
    }

    /** Node {@code i}'s parent (-1 for the span). */
    public int parent(int i) {
        return parent[i];
    }

    /** Node {@code i}'s depth: 0 the span, 1 the top-level zones. */
    public int depth(int i) {
        return depth[i];
    }

    /** Time spent in node {@code i}'s path, in nanoseconds (the span's: the span). */
    public long time(int i) {
        return time[i];
    }

    /** Zones merged into node {@code i}. */
    public long calls(int i) {
        return calls[i];
    }

    /** Where node {@code i} starts from its parent's start in the graph, in nanoseconds of the span. */
    public long offset(int i) {
        return offset[i];
    }

    /** Node {@code i}'s self time: its time less its children's. */
    public long self(int i) {
        long t = time[i];
        for (int c = firstChild[i]; c >= 0; c = nextSibling[c]) {
            t -= time[c];
        }
        return t;
    }

    @AllowAllocation("the tree grows to the most nodes met")
    private void grow() {
        int n = name.length * 2;
        name = java.util.Arrays.copyOf(name, n);
        parent = java.util.Arrays.copyOf(parent, n);
        depth = java.util.Arrays.copyOf(depth, n);
        firstChild = java.util.Arrays.copyOf(firstChild, n);
        nextSibling = java.util.Arrays.copyOf(nextSibling, n);
        lastChild = java.util.Arrays.copyOf(lastChild, n);
        time = java.util.Arrays.copyOf(time, n);
        calls = java.util.Arrays.copyOf(calls, n);
        offset = java.util.Arrays.copyOf(offset, n);
        used = java.util.Arrays.copyOf(used, n);
    }

    @AllowAllocation("the stack grows to the deepest zone met")
    private void growStack(int n) {
        stack = java.util.Arrays.copyOf(stack, Math.max(n, stack.length * 2));
    }
}
