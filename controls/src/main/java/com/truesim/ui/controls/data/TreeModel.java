package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.Versioned;

/**
 * A versioned tree (design 4.2's {@code VersionedTree}, kept here until common-state has one): {@link TreeNode}s with
 * values, children, expansion and lazily loaded children, every change stamped on a {@link ChangeClock}. Value changes
 * are stamped per node and logged, so a tree view rebinds only the changed rows it shows; adding, removing, moving,
 * expanding and collapsing change the {@link #structureVersion()} (the visible rows change).
 *
 * <pre>{@code
 * TreeModel<String> tree = new TreeModel<>(clock, "root");
 * TreeNode<String> assets = tree.root().add("assets");
 * assets.lazy(node -> files.list(node.value()).forEach(node::add)); // children on first expand
 * }</pre>
 *
 * <p>Not thread-safe: the clock's thread.
 *
 * @param <T> the value type
 */
public final class TreeModel<T> implements Versioned {

    /** Value changes kept for views lagging behind; older ones make them reconcile. */
    static final int LOG = 1024;

    private final ChangeClock clock;
    private final TreeNode<T> root;
    private long version;
    private long structureVersion;
    private int nextId;
    private final TreeNode<?>[] logNode = new TreeNode<?>[LOG];
    private final long[] logStamp = new long[LOG];
    private int logStart;
    private int logSize;
    private long logFrom;

    /** A tree on {@code clock} whose (hidden) root holds {@code rootValue}. */
    @AllowAllocation("models are made when a UI is built")
    public TreeModel(ChangeClock clock, T rootValue) {
        this.clock = clock;
        this.root = new TreeNode<>(this, null, rootValue);
        root.expanded = true;
        structureVersion = version = clock.stamp();
        logFrom = version;
    }

    /** The root: its children are the top-level rows of a tree view. */
    public TreeNode<T> root() {
        return root;
    }

    /** The clock stamping changes. */
    public ChangeClock clock() {
        return clock;
    }

    @Override
    public long version() {
        return version;
    }

    /** When nodes were last added, removed, moved, expanded or collapsed. */
    public long structureVersion() {
        return structureVersion;
    }

    int nextId() {
        return nextId++;
    }

    long structureChanged() {
        long stamp = clock.stamp();
        version = stamp;
        structureVersion = stamp;
        logStart = 0;
        logSize = 0;
        java.util.Arrays.fill(logNode, null);
        logFrom = stamp;
        return stamp;
    }

    long valueChanged(TreeNode<T> node) {
        long stamp = clock.stamp();
        version = stamp;
        if (logSize == LOG) {
            logFrom = logStamp[logStart];
            logNode[logStart] = null;
            logStart = (logStart + 1) % LOG;
            logSize--;
        }
        int slot = (logStart + logSize++) % LOG;
        logNode[slot] = node;
        logStamp[slot] = stamp;
        return stamp;
    }

    /** Visits the nodes whose values changed after {@code since}; false when that cannot be told. */
    @SuppressWarnings("unchecked")
    boolean changesSince(long since, NodeVisitor<T> visitor) {
        if (since < logFrom) {
            return false;
        }
        for (int i = 0; i < logSize; i++) {
            int slot = (logStart + i) % LOG;
            TreeNode<T> node = (TreeNode<T>) logNode[slot];
            if (logStamp[slot] > since && node.version() == logStamp[slot]) {
                visitor.changed(node);
            }
        }
        return true;
    }

    /** Receives changed nodes. */
    @FunctionalInterface
    interface NodeVisitor<T> {
        void changed(TreeNode<T> node);
    }
}
