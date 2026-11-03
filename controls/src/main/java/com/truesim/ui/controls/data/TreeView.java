package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.DragSession;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.layout.Unit;

/**
 * A virtualized tree view (design 5.6) over a {@link TreeModel}: its visible rows ({@link TreeRows}) through a
 * {@link ValueRenderer} of the node values, lazy children loaded on expand, expand and collapse by the expander, a
 * double click or the keys (RIGHT expands or enters the first child, LEFT collapses or goes to the parent), and drag
 * and drop moving nodes between parents and reordering them. Selection, keys and virtualization are
 * {@link VirtualView}'s.
 *
 * <p><b>Elements and styles.</b> The root is a {@code tree-view} (role TREE); rows are {@code tree-item}s (role
 * TREE_ITEM, {@code :expanded} when open, {@code :selected}, {@code :focused}, {@code :drop-target} while a dragged
 * node would go into them) holding a {@code tree-indent} ({@code indent} times the depth wide), a
 * {@code tree-expander} ({@code :expanded}; {@code :disabled} on leaves, where themes hide it) and the renderer's view.
 * A {@code tree-drop-indicator} shows where a dragged node would go between rows.
 *
 * @param <T> the node value type
 */
public final class TreeView<T> extends VirtualView<TreeNode<T>> {

    /** The payload of a dragged tree node. */
    public record Drag(TreeView<?> tree, TreeNode<?> node) {
    }

    /** Drag and drop format of a dragged tree node. */
    public static final DataFormat<Drag> NODE = DataFormat.of("ui.tree.node", Drag.class);

    private final TreeModel<T> model;
    private final TreeRows<T> treeRows;
    private final ValueRenderer<? super T> renderer;
    private final ViewContext context;
    private final Element indicator;
    private Element preview;
    private boolean draggable;
    private TreeNode<T> dropParent;
    private int dropIndex = -1;
    private Element dropInto;
    private final TreeEvents treeEvents = new TreeEvents();

    /** A tree named {@code name} of {@code model}'s nodes shown through {@code renderer}. */
    @AllowAllocation("controls are made when a UI is built")
    public TreeView(Ui ui, String name, TreeModel<T> model, ValueRenderer<? super T> renderer) {
        this(ui, name, model, new TreeRows<>(model), renderer);
    }

    @AllowAllocation("controls are made when a UI is built")
    private TreeView(Ui ui, String name, TreeModel<T> model, TreeRows<T> rows, ValueRenderer<? super T> renderer) {
        super(ui, "tree-view", "tree-body", name, rows, Role.TREE_ITEM, Selection.Mode.SINGLE);
        this.model = model;
        this.treeRows = rows;
        this.renderer = renderer;
        this.context = new ViewContext(ui, "tree-view");
        root.semantics(Role.TREE, name);
        indicator = ui.element("tree-drop-indicator", null);
        body.add(indicator);
        ui.codeLayout(indicator, Ui.CODE_PLACEMENT);
        layout.anchors(indicator.slot(), 0, 0, 1, 0).visible(indicator.slot(), false);
        interaction.on(body, EventType.CLICK.bit() | EventType.DRAG_START.bit() | EventType.DROP_OVER.bit()
            | EventType.DROP.bit() | EventType.DROP_LEAVE.bit() | EventType.DRAG_END.bit(), treeEvents);
    }

    /** The model. */
    public TreeModel<T> model() {
        return model;
    }

    /** The visible rows. */
    public TreeRows<T> treeRows() {
        return treeRows;
    }

    /** Lets nodes be dragged into other nodes and between rows (mouse and pen); returns this. */
    public TreeView<T> draggable(boolean on) {
        draggable = on;
        return this;
    }

    /** Expands or collapses {@code node}; expanding loads lazy children. */
    public void expand(TreeNode<T> node, boolean on) {
        if (!node.leaf() || !on) {
            node.expanded(on);
        }
    }

    /** Where a dragged node would go: its new parent (null when no drag is over the tree). */
    public TreeNode<T> dropParent() {
        return dropParent;
    }

    /** Where a dragged node would go: its index under {@link #dropParent()}. */
    public int dropIndex() {
        return dropIndex;
    }

    @Override
    protected int kindOf(int index, TreeNode<T> row) {
        return renderer.kind(row.value());
    }

    @Override
    @AllowAllocation("rows are created while the visible window fills, then recycled")
    protected Element createRow(int kind) {
        Element row = ui.element("tree-item", null);
        Element indent = ui.element("tree-indent", null);
        ui.codeLayout(indent, Ui.CODE_WIDTH);
        Element expander = ui.element("tree-expander", null);
        interaction.pickable(expander, PointerEvents.ALL);
        row.add(indent).add(expander).add(renderer.create(kind, context));
        return row;
    }

    @Override
    protected boolean rebindOnStructure() {
        return true; // expansion and depth show on rows that did not move
    }

    @Override
    protected void prepare(int index, TreeNode<T> node, CellState state) {
        state.tree(node.depth(), node.expanded(), node.leaf());
    }

    @Override
    protected void bindRow(Element row, int kind, int index, TreeNode<T> node, CellState state) {
        UiStore store = ui.store();
        int indent = store.firstChild(row.slot());
        int expander = store.nextSibling(indent);
        int content = store.nextSibling(expander);
        float step = ui.styles().styleOf(row.slot()).getFloat(DataStyles.INDENT);
        layout.width(indent, state.depth() * step, Unit.PX);
        store.state(row.slot(), UiState.EXPANDED, node.expanded());
        store.state(expander, UiState.EXPANDED, node.expanded());
        store.state(expander, UiState.DISABLED, node.leaf());
        renderer.bind(store.element(content), node.value(), state);
    }

    @Override
    protected void unbindRow(Element row, int kind) {
        UiStore store = ui.store();
        int content = store.lastChild(row.slot());
        renderer.unbind(store.element(content));
    }

    @Override
    protected void rowCommand(UiEvent e) {
        int i = current();
        if (i < 0 || i >= rows().size()) {
            return;
        }
        TreeNode<T> node = rows().get(i);
        switch (e.command()) {
            case RIGHT -> {
                if (!node.leaf() && !node.expanded()) {
                    expand(node, true);
                } else if (node.expanded() && node.childCount() > 0) {
                    moveTo(i + 1, false, false);
                }
                e.consume();
            }
            case LEFT -> {
                if (node.expanded() && !node.leaf()) {
                    expand(node, false);
                } else {
                    TreeNode<T> parent = node.parent();
                    if (parent != null && parent != model.root()) {
                        moveTo(treeRows.rowOf(parent), false, false);
                    }
                }
                e.consume();
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- drag and drop

    /** Works out where a node dragged over UI pixel {@code y} would go: into a row's middle, else between rows. */
    private boolean target(TreeNode<?> dragged, float y) {
        clearTarget();
        int n = rows().size();
        double at = (y - layout.y(viewport.slot())) / scale() + vertical().position();
        if (n == 0) {
            dropParent = model.root();
            dropIndex = 0;
            return true;
        }
        int i = extents().indexAt(at);
        double top = extents().offset(i);
        double size = extents().size(i);
        double part = (at - top) / Math.max(1, size);
        TreeNode<T> node = rows().get(i);
        if (at >= top + size) {
            // below the last row: the end of the top level
            dropParent = model.root();
            dropIndex = model.root().childCount();
        } else if (part > 0.25 && part < 0.75) {
            dropParent = node;
            dropIndex = node.childCount();
            dropInto = rowElement(i);
        } else {
            dropParent = node.parent();
            int k = dropParent.indexOf(node);
            dropIndex = part >= 0.75 ? k + 1 : k;
        }
        if (dragged == dropParent || dragged.model() == model && isAncestor(dragged, dropParent)) {
            clearTarget();
            return false;
        }
        if (dropInto != null) {
            dropInto.state(UiState.DROP_TARGET, true);
        } else {
            int r = i + (part >= 0.75 ? 1 : 0);
            double base = layout.scrollY(body.slot()) - vertical().position();
            float off = (float) (extents().offset(Math.min(r, n)) + base);
            layout.offsets(indicator.slot(), 0, off, 0, off).visible(indicator.slot(), true);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean isAncestor(TreeNode<?> node, TreeNode<?> of) {
        return ((TreeNode<Object>) node).isAncestorOf((TreeNode<Object>) of);
    }

    private void clearTarget() {
        if (dropInto != null && dropInto.alive()) {
            dropInto.state(UiState.DROP_TARGET, false);
        }
        dropInto = null;
        dropParent = null;
        dropIndex = -1;
        layout.visible(indicator.slot(), false);
    }

    /** Clicks on expanders and drag and drop of nodes. */
    private final class TreeEvents implements UiListener {

        @Override
        @AllowAllocation("a drag starts: its payload is made once")
        @SuppressWarnings("unchecked")
        public void handle(UiEvent e) {
            switch (e.type()) {
                case CLICK -> {
                    int index = indexOf(ui.store().element(e.targetSlot()));
                    if (index < 0) {
                        return;
                    }
                    TreeNode<T> node = rows().get(index);
                    boolean onExpander = ui.store().type(e.targetSlot()).equals("tree-expander");
                    if (onExpander || e.clickCount() == 2) {
                        expand(node, !node.expanded());
                        e.consume();
                    }
                }
                case DRAG_START -> {
                    if (!draggable || e.pointerKind() == Pointer.Kind.TOUCH) {
                        return;
                    }
                    int index = indexOf(ui.store().element(e.targetSlot()));
                    if (index < 0) {
                        return;
                    }
                    TreeNode<T> node = rows().get(index);
                    Element row = rowElement(index);
                    if (preview == null) {
                        preview = ui.label(null, "");
                        preview.style("tree-drag-preview");
                    }
                    ui.text(preview, String.valueOf(node.value()));
                    DragSession session = e.startDrag(DragOperation.MOVE);
                    session.put(NODE, new Drag(TreeView.this, node)).preview(preview,
                        row == null ? 0 : e.x() - layout.x(row.slot()), row == null ? 0 : e.y() - layout.y(row.slot()));
                }
                case DROP_OVER -> {
                    Drag drag = e.drag() == null ? null : e.drag().get(NODE);
                    if (drag != null && drag.node().model() == model && target(drag.node(), e.y())) {
                        e.acceptDrop(DragOperation.MOVE);
                    }
                }
                case DROP -> {
                    Drag drag = e.drag().get(NODE);
                    TreeNode<T> parent = dropParent;
                    int index = dropIndex;
                    clearTarget();
                    if (drag != null && parent != null && drag.node().model() == model) {
                        TreeNode<T> node = (TreeNode<T>) drag.node();
                        if (node.parent() == parent && parent.indexOf(node) < index) {
                            index--; // counted after taking the node out
                        }
                        node.move(parent, index);
                        if (parent != model.root() && !parent.expanded()) {
                            parent.expanded(true);
                        }
                    }
                }
                case DROP_LEAVE, DRAG_END -> clearTarget();
                default -> {
                }
            }
        }
    }
}
