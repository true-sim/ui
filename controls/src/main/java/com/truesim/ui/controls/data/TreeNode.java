package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * One node of a {@link TreeModel}: a value, children in order, whether it is expanded, and optionally a
 * {@link ChildLoader} that fills its children the first time it is expanded (lazy trees: a file system, a scene of
 * thousands of nodes). Every change is stamped on the model's clock. Not thread-safe: a loader on another thread
 * hands its results to the clock's thread, which adds the children and calls {@link #loaded()}.
 *
 * @param <T> the value type
 */
public final class TreeNode<T> {

    /** Fills a node's children when it is first expanded. */
    @FunctionalInterface
    public interface ChildLoader<T> {

        /**
         * Adds {@code node}'s children now, or marks it {@link TreeNode#loading()} and adds them later followed by
         * {@link TreeNode#loaded()}.
         */
        void load(TreeNode<T> node);
    }

    private final TreeModel<T> model;
    private final int id;
    private TreeNode<T> parent;
    private T value;
    @SuppressWarnings("unchecked")
    private TreeNode<T>[] children = (TreeNode<T>[]) new TreeNode<?>[0];
    private int childCount;
    boolean expanded;
    private long version;
    private ChildLoader<T> loader;
    private boolean loading;
    /** The node's row in the tree's visible rows, kept by {@link TreeRows}. */
    int row = -1;

    TreeNode(TreeModel<T> model, TreeNode<T> parent, T value) {
        this.model = model;
        this.parent = parent;
        this.value = value;
        this.id = model.nextId();
        this.version = model.clock().now();
    }

    /** The model. */
    public TreeModel<T> model() {
        return model;
    }

    /** A number unique in the model: the node's key. */
    public int id() {
        return id;
    }

    /** The value. */
    public T value() {
        return value;
    }

    /** Sets the value: a change when it differs ({@code equals}). */
    public void value(T newValue) {
        if (!Objects.equals(value, newValue)) {
            value = newValue;
            version = model.valueChanged(this);
        }
    }

    /** Stamps the value as changed (it changed in place). */
    public void touch() {
        version = model.valueChanged(this);
    }

    /** When the value last changed. */
    public long version() {
        return version;
    }

    /** The parent, or null for the root (and removed nodes). */
    public TreeNode<T> parent() {
        return parent;
    }

    /** How deep below the root: its children are at 0. */
    public int depth() {
        int d = -1;
        for (TreeNode<T> p = parent; p != null; p = p.parent) {
            d++;
        }
        return d;
    }

    /** How many children (loaded). */
    public int childCount() {
        return childCount;
    }

    /** Child {@code index}. */
    public TreeNode<T> child(int index) {
        Objects.checkIndex(index, childCount);
        return children[index];
    }

    /** The child's index under this node, or -1. */
    public int indexOf(TreeNode<T> child) {
        for (int i = 0; i < childCount; i++) {
            if (children[i] == child) {
                return i;
            }
        }
        return -1;
    }

    /** Adds a child holding {@code childValue} at the end; returns it. */
    public TreeNode<T> add(T childValue) {
        return add(childCount, childValue);
    }

    /** Adds a child holding {@code childValue} at {@code index}; returns it. */
    @AllowAllocation("a structural change of the model")
    public TreeNode<T> add(int index, T childValue) {
        TreeNode<T> child = new TreeNode<>(model, this, childValue);
        insert(index, child);
        model.structureChanged();
        return child;
    }

    @AllowAllocation("a structural change of the model")
    private void insert(int index, TreeNode<T> child) {
        Objects.checkIndex(index, childCount + 1);
        if (childCount == children.length) {
            children = Arrays.copyOf(children, Math.max(4, childCount * 2));
        }
        System.arraycopy(children, index, children, index + 1, childCount - index);
        children[index] = child;
        childCount++;
        child.parent = this;
    }

    private void detach() {
        int i = parent.indexOf(this);
        System.arraycopy(parent.children, i + 1, parent.children, i, parent.childCount - i - 1);
        parent.children[--parent.childCount] = null;
        parent = null;
    }

    /** Removes this node and its subtree from the tree. */
    public void remove() {
        if (parent == null) {
            throw new IllegalStateException("the root stays");
        }
        detach();
        model.structureChanged();
    }

    /**
     * Moves this node under {@code newParent} at {@code index} (counted after taking this node out); refuses to move
     * a node into its own subtree.
     */
    public void move(TreeNode<T> newParent, int index) {
        if (parent == null) {
            throw new IllegalStateException("the root stays");
        }
        if (isAncestorOf(newParent) || newParent == this) {
            throw new IllegalArgumentException("a node cannot move into its own subtree");
        }
        detach();
        newParent.insert(index, this);
        model.structureChanged();
    }

    /** Whether this node is {@code node}'s ancestor. */
    public boolean isAncestorOf(TreeNode<T> node) {
        for (TreeNode<T> p = node == null ? null : node.parent; p != null; p = p.parent) {
            if (p == this) {
                return true;
            }
        }
        return false;
    }

    /** Whether the node shows its children. */
    public boolean expanded() {
        return expanded;
    }

    /** Expands or collapses the node; expanding a lazy node the first time loads its children. */
    public void expanded(boolean on) {
        if (on == expanded) {
            return;
        }
        expanded = on;
        if (on && loader != null) {
            ChildLoader<T> l = loader;
            loader = null;
            l.load(this);
        }
        model.structureChanged();
    }

    /** Fills the children with {@code childLoader} when first expanded; until then the node counts as a parent. */
    public void lazy(ChildLoader<T> childLoader) {
        loader = childLoader;
    }

    /** Whether children are still to load (a lazy node not yet expanded, or one loading). */
    public boolean pending() {
        return loader != null || loading;
    }

    /** Marks the node as loading its children (asynchronously); {@link #loaded()} ends it. */
    public void loading() {
        loading = true;
        model.structureChanged();
    }

    /** Whether the node's children are being loaded. */
    public boolean isLoading() {
        return loading;
    }

    /** The children arrived. */
    public void loaded() {
        loading = false;
        model.structureChanged();
    }

    /** Whether the node has no children and none to load. */
    public boolean leaf() {
        return childCount == 0 && !pending();
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
