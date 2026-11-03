package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.IndexVisitor;
import java.util.Arrays;

/**
 * The visible rows of a {@link TreeModel}: its nodes in depth-first order, below expanded nodes only, the root left
 * out. Flattened again (O(visible rows)) when the tree's structure or expansion changed; value changes pass through
 * as in-place changes of their rows. Keys are node ids, so a node keeps its row element while rows above it open
 * and close.
 *
 * @param <T> the value type
 */
public final class TreeRows<T> implements RowSource<TreeNode<T>> {

    private final TreeModel<T> model;
    @SuppressWarnings("unchecked")
    private TreeNode<T>[] flat = (TreeNode<T>[]) new TreeNode<?>[64];
    private int count;
    private long seenStructure = Long.MIN_VALUE;
    private long structureVersion;
    private final TreeModel.NodeVisitor<T> forward = this::forward;
    private IndexVisitor target;

    /** The rows of {@code model}. */
    @AllowAllocation("views are made when a UI is built")
    public TreeRows(TreeModel<T> model) {
        this.model = model;
    }

    /** The model. */
    public TreeModel<T> model() {
        return model;
    }

    @Override
    public void refresh() {
        if (model.structureVersion() != seenStructure) {
            seenStructure = model.structureVersion();
            for (int i = 0; i < count; i++) {
                flat[i].row = -1;
                flat[i] = null;
            }
            count = 0;
            TreeNode<T> root = model.root();
            for (int i = 0; i < root.childCount(); i++) {
                visit(root.child(i));
            }
            structureVersion = seenStructure;
        }
    }

    private void visit(TreeNode<T> node) {
        if (count == flat.length) {
            grow();
        }
        node.row = count;
        flat[count++] = node;
        if (node.expanded()) {
            for (int i = 0; i < node.childCount(); i++) {
                visit(node.child(i));
            }
        }
    }

    @AllowAllocation("grows to the most visible rows, amortized")
    private void grow() {
        flat = Arrays.copyOf(flat, flat.length * 2);
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public TreeNode<T> get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return flat[index];
    }

    /** The row of {@code node}, or -1 when it is not visible. */
    public int rowOf(TreeNode<T> node) {
        int r = node.row;
        return r >= 0 && r < count && flat[r] == node ? r : -1;
    }

    @Override
    public long key(int index) {
        return flat[index].id();
    }

    @Override
    public long version(int index) {
        return flat[index].version();
    }

    @Override
    public long version() {
        return model.version();
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public boolean changesSince(long since, IndexVisitor visitor) {
        target = visitor;
        try {
            return model.changesSince(since, forward);
        } finally {
            target = null;
        }
    }

    private void forward(TreeNode<T> node) {
        int r = rowOf(node);
        if (r >= 0) {
            target.changed(r);
        }
    }
}
