package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.IndexVisitor;
import com.truesim.common.state.NodeVisitor;
import com.truesim.common.state.VersionedTree;
import com.truesim.common.state.VersionedTree.Node;
import java.util.Arrays;

/**
 * The visible rows of a {@link VersionedTree}: its nodes in depth-first order, below expanded nodes only, the root left
 * out, and the view's own state per node: whether it is expanded, and lazy children ({@link ChildLoader}) loaded on the
 * first expand. Flattened again (O(visible rows)) when the tree's structure or the expansion changed; value changes
 * pass through as in-place changes of their rows. Keys are node ids, so a node keeps its row element while rows above
 * it open and close. Expansion and loading are stamped on the tree's clock, so they are versioned like the tree.
 *
 * <p>Expansion and loaders belong to the view, not the tree: two views of one tree open their nodes independently.
 * State is kept in arrays indexed by node id (ids are dense and never reused).
 *
 * @param <T> the value type
 */
public final class TreeRows<T> implements RowSource<Node<T>> {

    /** Fills a node's children when it is first expanded. */
    @FunctionalInterface
    public interface ChildLoader<T> {

        /**
         * Adds {@code node}'s children now, or marks it {@link TreeRows#loading loading} and adds them later followed
         * by {@link TreeRows#loaded}.
         */
        void load(Node<T> node);
    }

    private final VersionedTree<T> tree;
    private final ChangeClock clock;
    @SuppressWarnings("unchecked")
    private Node<T>[] flat = (Node<T>[]) new Node<?>[64];
    private int count;
    private boolean[] placeholder = new boolean[64]; // by row: a loading node's placeholder
    private long seenTree = Long.MIN_VALUE;
    private long seenOwn = Long.MIN_VALUE;
    private long structureVersion;
    private long ownVersion; // expansion and loading
    // per node id
    private int[] row = new int[64];
    private boolean[] expanded = new boolean[64];
    private boolean[] loading = new boolean[64];
    private Object[] loaders = new Object[64];
    private final NodeVisitor<T> forward = this::forward;
    private IndexVisitor target;

    /** The rows of {@code tree}, every node collapsed. */
    @AllowAllocation("views are made when a UI is built")
    public TreeRows(VersionedTree<T> tree) {
        this.tree = tree;
        this.clock = tree.clock();
        Arrays.fill(row, -1);
        ownVersion = clock.stamp();
    }

    /** The tree. */
    public VersionedTree<T> tree() {
        return tree;
    }

    // ---------------------------------------------------------------- expansion and loading

    private void ensure(int id) {
        if (id >= row.length) {
            grow(id);
        }
    }

    @AllowAllocation("grows with the tree's node ids, amortized")
    private void grow(int id) {
        int n = Math.max(id + 1, row.length * 2);
        int old = row.length;
        row = Arrays.copyOf(row, n);
        Arrays.fill(row, old, n, -1);
        expanded = Arrays.copyOf(expanded, n);
        loading = Arrays.copyOf(loading, n);
        loaders = Arrays.copyOf(loaders, n);
    }

    private void changed() {
        ownVersion = clock.stamp();
    }

    /** Whether {@code node} shows its children (the root always does). */
    public boolean expanded(Node<T> node) {
        int id = node.id();
        return node == tree.root() || id < expanded.length && expanded[id];
    }

    /** Expands or collapses {@code node}; expanding a lazy node the first time loads its children. */
    public void expanded(Node<T> node, boolean on) {
        if (on == expanded(node) || node == tree.root()) {
            return;
        }
        int id = node.id();
        ensure(id);
        expanded[id] = on;
        if (on && loaders[id] != null) {
            @SuppressWarnings("unchecked")
            ChildLoader<T> l = (ChildLoader<T>) loaders[id];
            loaders[id] = null;
            l.load(node);
        }
        changed();
    }

    /** Fills {@code node}'s children with {@code loader} when first expanded; until then it counts as a parent. */
    public void lazy(Node<T> node, ChildLoader<T> loader) {
        ensure(node.id());
        loaders[node.id()] = loader;
        changed();
    }

    /** Whether {@code node}'s children are still to load (a lazy node not yet expanded, or one loading). */
    public boolean pending(Node<T> node) {
        int id = node.id();
        return id < loaders.length && (loaders[id] != null || loading[id]);
    }

    /** Marks {@code node} as loading its children (asynchronously); {@link #loaded} ends it. */
    public void loading(Node<T> node) {
        ensure(node.id());
        loading[node.id()] = true;
        changed();
    }

    /** Whether {@code node}'s children are being loaded. */
    public boolean isLoading(Node<T> node) {
        int id = node.id();
        return id < loading.length && loading[id];
    }

    /** {@code node}'s children arrived. */
    public void loaded(Node<T> node) {
        ensure(node.id());
        loading[node.id()] = false;
        changed();
    }

    /** Whether {@code node} has no children and none to load. */
    public boolean leaf(Node<T> node) {
        return node.childCount() == 0 && !pending(node);
    }

    // ---------------------------------------------------------------- rows

    @Override
    public void refresh() {
        if (tree.structureVersion() != seenTree || ownVersion != seenOwn) {
            seenTree = tree.structureVersion();
            seenOwn = ownVersion;
            for (int i = 0; i < count; i++) {
                if (!placeholder[i]) {
                    row[flat[i].id()] = -1;
                }
                placeholder[i] = false;
                flat[i] = null;
            }
            count = 0;
            Node<T> root = tree.root();
            for (int i = 0; i < root.childCount(); i++) {
                visit(root.child(i));
            }
            structureVersion = Math.max(seenTree, seenOwn);
        }
    }

    private void visit(Node<T> node) {
        if (count == flat.length) {
            growFlat();
        }
        ensure(node.id());
        row[node.id()] = count;
        flat[count++] = node;
        if (expanded[node.id()]) {
            for (int i = 0; i < node.childCount(); i++) {
                visit(node.child(i));
            }
            if (loading[node.id()]) {
                // a row standing for the children still to come, after those already there
                if (count == flat.length) {
                    growFlat();
                }
                placeholder[count] = true;
                flat[count++] = node;
            }
        }
    }

    @AllowAllocation("grows to the most visible rows, amortized")
    private void growFlat() {
        flat = Arrays.copyOf(flat, flat.length * 2);
        placeholder = Arrays.copyOf(placeholder, flat.length);
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public Node<T> get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return flat[index];
    }

    /** The row of {@code node}, or -1 when it is not visible. */
    public int rowOf(Node<T> node) {
        int id = node.id();
        int r = id < row.length ? row[id] : -1;
        return r >= 0 && r < count && flat[r] == node ? r : -1;
    }

    @Override
    public long key(int index) {
        long id = flat[index].id();
        return placeholder[index] ? ~id : id;
    }

    /**
     * Whether row {@code index} is a loading placeholder: the row after an expanded node {@linkplain #loading loading}
     * its children (and those already there); {@link #get} gives the loading node.
     */
    public boolean placeholder(int index) {
        return index >= 0 && index < count && placeholder[index];
    }

    @Override
    public boolean selectable(int index) {
        return !placeholder(index);
    }

    @Override
    public long version(int index) {
        return flat[index].version();
    }

    @Override
    public long version() {
        return Math.max(tree.version(), ownVersion);
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public boolean changesSince(long since, IndexVisitor visitor) {
        target = visitor;
        try {
            return tree.changesSince(since, forward);
        } finally {
            target = null;
        }
    }

    private void forward(Node<T> node) {
        int r = rowOf(node);
        if (r >= 0) {
            target.changed(r);
        }
    }
}
