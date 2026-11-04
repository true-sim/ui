package com.truesim.ui.controls.debug;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.FrameStats;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.imm.Imm;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.Picking;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Paint;
import java.util.List;

/**
 * The UI debugger (design stage 15), written with the {@link Imm} façade: an inspector of a {@link Ui}'s element tree
 * and of one element, the frame's timings, and overlays drawn over everything.
 *
 * <ul>
 *   <li><b>Inspector</b> (window "UI inspector"): the tree of every layer's elements (expand to walk it; the
 *       debugger's own layers left out); <b>Pick</b> selects the element under the pointer (hovered ones are outlined;
 *       the press that picks goes to the debugger, not the UI). For the selected element: its id path, style name,
 *       state bits, facts, layout rectangle, and every resolved style value with the rule and {@code file:line} it
 *       came from ({@link StyleResolver#explain}).</li>
 *   <li><b>Timings</b> (window "UI frame"): the last frame's input, frame code, styles, layout and record times, the
 *       layout passes, the elements recorded and the bytes the renderer uploads ({@link FrameStats}, turned on while
 *       the debugger shows).</li>
 *   <li><b>Overlays</b> ({@link #overlay}): {@link #BOUNDS} (every element's layout box), {@link #CLIPS} (clipping
 *       elements), {@link #PICKING} (the picking grids' occupied cells), {@link #DIRTY} (the elements the last frame
 *       recorded: the dirty ranges' owners), on the {@link Layers#DEBUG} layer. Their looks are style parts of the
 *       overlay element: {@code debug-bounds}, {@code debug-clip}, {@code debug-grid}, {@code debug-dirty},
 *       {@code debug-hover}, {@code debug-selected} (border and background).</li>
 * </ul>
 *
 * Toggled by {@link #show} (or a console command: ui-console's {@code DebugCommands}). It runs as a frame listener
 * registered before its façade's, so its calls see this frame's input and show in this frame. Hidden, it costs one
 * comparison a frame.
 */
public final class UiDebugger implements Ui.FrameListener {

    /** Overlay bit: every element's layout box. */
    public static final int BOUNDS = 1;
    /** Overlay bit: clipping elements' clips. */
    public static final int CLIPS = 2;
    /** Overlay bit: the picking grids' occupied cells. */
    public static final int PICKING = 4;
    /** Overlay bit: the elements the last frame recorded. */
    public static final int DIRTY = 8;
    /** The layer the debugger's windows are on. */
    public static final String LAYER = "inspector";
    /** Its z: over everything, the debug layer's overlays too. */
    public static final int LAYER_Z = 1100;

    private static final long TREE_IDS = 1L << 40;

    private final Ui ui;
    private final UiStore store;
    private final Layout layout;
    private final Imm imm;
    private final Layer layer;
    private final Element overlay;
    private final Element bounds;
    private final Element clips;
    private final Element grid;
    private final Element dirty;
    private final Element hover;
    private final Element selectedPart;
    private final Element blocker;
    private boolean shown;
    private int overlays;
    private boolean picking;
    private Element selected;
    private Element hovered;
    private List<StyleResolver.Origin> origins = List.of();
    private Element explained;
    private long explainedVersion = -1;
    private final StringBuilder line = new StringBuilder(128);
    private final StringBuilder value = new StringBuilder(64);
    private final float[] gridInfo = new float[5];
    private int depthLimit = 64;

    /** A debugger of {@code ui}, hidden; {@link #show} shows it. */
    @AllowAllocation("one per UI")
    public UiDebugger(Ui ui) {
        this.ui = ui;
        this.store = ui.store();
        this.layout = ui.layout();
        ui.addFrameListener(this); // before the fa\u00E7ade's: its calls are collected after them
        Layer own = ui.layerStack().find(LAYER);
        layer = own != null ? own : ui.layerStack().add(LAYER, LAYER_Z);
        blocker = ui.element("debug-blocker", null);
        ui.codeLayout(blocker, Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        layout.width(blocker.slot(), 100, com.truesim.ui.layout.Unit.PERCENT)
            .height(blocker.slot(), 100, com.truesim.ui.layout.Unit.PERCENT);
        ui.interaction().pickable(blocker, com.truesim.ui.core.event.PointerEvents.ALL);
        layer.root().add(blocker);
        layout.visible(blocker.slot(), false);
        blocker.semantics(com.truesim.ui.core.Role.GROUP, null);
        Element windows = ui.element("debug-windows", null);
        layer.root().add(windows);
        imm = new Imm(ui, windows);
        overlay = ui.element("debug-overlay", null);
        bounds = ui.part(overlay, "debug-bounds", null);
        clips = ui.part(overlay, "debug-clip", null);
        grid = ui.part(overlay, "debug-grid", null);
        dirty = ui.part(overlay, "debug-dirty", null);
        hover = ui.part(overlay, "debug-hover", null);
        selectedPart = ui.part(overlay, "debug-selected", null);
        ui.paint(overlay, new OverlayPainter());
        Layer debug = ui.layer(Layers.DEBUG);
        debugRoot = debug.rootSlot();
        debug.root().add(overlay);
        layout.visible(overlay.slot(), false);
    }

    private int debugRoot;

    /** Shows or hides the debugger's windows (overlays show while any is on, the windows or not). */
    public void show(boolean on) {
        shown = on;
        ui.stats().enabled(on || overlays != 0);
        if (!on) {
            pick(false);
        }
    }

    /** Whether the windows show. */
    public boolean shown() {
        return shown;
    }

    /** Shows the windows when hidden, hides them when shown. */
    public void toggle() {
        show(!shown);
    }

    /** Turns overlay {@code bit} ({@link #BOUNDS}, {@link #CLIPS}, {@link #PICKING}, {@link #DIRTY}) on or off. */
    public void overlay(int bit, boolean on) {
        overlays = on ? overlays | bit : overlays & ~bit;
        ui.stats().enabled(shown || overlays != 0);
    }

    /** The overlays on. */
    public int overlays() {
        return overlays;
    }

    /** Starts or stops picking: the next press selects the element under it. */
    public void pick(boolean on) {
        picking = on;
        layout.visible(blocker.slot(), on);
        if (!on) {
            hovered = null;
        }
    }

    /** Whether it is picking. */
    public boolean picking() {
        return picking;
    }

    /** Selects {@code element} (or nothing) for the inspector. */
    public void select(Element element) {
        selected = element;
    }

    /** The selected element, or null. */
    public Element selected() {
        return selected;
    }

    /** The element the pointer is over while picking, or null. */
    public Element hovered() {
        return hovered;
    }

    /** The façade the debugger's windows are written with. */
    public Imm imm() {
        return imm;
    }

    /** How deep the tree view walks (expanded) elements at most. */
    public UiDebugger depthLimit(int value) {
        depthLimit = value;
        return this;
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void frame(long nanos) {
        boolean anything = shown || overlays != 0;
        if (layout.shown(overlay.slot()) != anything) {
            layout.visible(overlay.slot(), anything);
        }
        if (anything) {
            ui.repaint(overlay);
        }
        if (!shown) {
            return;
        }
        if (picking) {
            pickUnderPointer();
        }
        inspector();
        timings();
    }

    /** While picking: the element under the pointer (the debugger's own left out); a press selects it. */
    private void pickUnderPointer() {
        UiInput input = ui.input();
        if (input == null || input.pointerCount() == 0) {
            return;
        }
        Pointer p = input.pointerAt(0);
        hovered = deepest(p.x(), p.y());
        if ((p.buttons() & Pointer.PRIMARY) != 0 && hovered != null) {
            selected = hovered;
            pick(false);
        }
    }

    /** The deepest shown element under {@code x, y} outside the debugger's layers (labels and parts included). */
    private Element deepest(float x, float y) {
        Layers layers = ui.layerStack();
        for (int li = layers.count() - 1; li >= 0; li--) {
            int root = layers.at(li).rootSlot();
            if (own(root)) {
                continue;
            }
            int found = deepest(root, x, y);
            if (found != UiStore.NONE && found != root) {
                return store.element(found);
            }
        }
        return null;
    }

    private int deepest(int slot, float x, float y) {
        if (!layout.shown(slot) || ui.isPart(store.element(slot))) {
            return UiStore.NONE;
        }
        for (int c = store.lastChild(slot); c != UiStore.NONE; c = store.previousSibling(c)) {
            int found = deepest(c, x, y);
            if (found != UiStore.NONE) {
                return found;
            }
        }
        float ex = layout.x(slot);
        float ey = layout.y(slot);
        return x >= ex && y >= ey && x < ex + layout.width(slot) && y < ey + layout.height(slot) ? slot
            : UiStore.NONE;
    }

    /** Whether {@code slot} is in the debugger's layers. */
    private boolean own(int slot) {
        int root = store.rootOf(slot);
        return root == layer.rootSlot() || root == debugRoot;
    }

    private void inspector() {
        imm.begin("UI inspector", 16, 16);
        boolean pickNow = imm.checkbox("Pick", picking);
        if (pickNow != picking) {
            pick(pickNow);
        }
        imm.sameLine();
        overlayBox("Bounds", BOUNDS);
        imm.sameLine();
        overlayBox("Clips", CLIPS);
        imm.sameLine();
        overlayBox("Picking", PICKING);
        imm.sameLine();
        overlayBox("Dirty", DIRTY);
        imm.beginChild("tree", 220);
        Layers layers = ui.layerStack();
        for (int li = 0; li < layers.count(); li++) {
            int root = layers.at(li).rootSlot();
            if (!own(root)) {
                node(root, 0);
            }
        }
        imm.endChild();
        imm.separator();
        details();
        imm.end();
    }

    private void overlayBox(String label, int bit) {
        boolean on = (overlays & bit) != 0;
        boolean now = imm.checkbox(label, on);
        if (now != on) {
            overlay(bit, now);
        }
    }

    /** One element of the tree: a tree node (or a selectable leaf) named by its type, id and style name. */
    private void node(int slot, int depth) {
        Element e = store.element(slot);
        line.setLength(0);
        describe(line, slot);
        long id = TREE_IDS + ((long) store.generation(slot) << 24) + slot;
        boolean leaf = true;
        for (int c = store.firstChild(slot); c != UiStore.NONE && leaf; c = store.nextSibling(c)) {
            leaf = ui.isPart(store.element(c));
        }
        if (leaf || depth >= depthLimit) {
            if (imm.selectable(id, line, e == selected)) {
                selected = e;
            }
            return;
        }
        boolean open = imm.treeNode(id, line, depth == 0 || selected != null && contains(slot, selected.slot()));
        if (open) {
            if (imm.selectable(id | 1L << 62, "(select)", e == selected)) {
                selected = e;
            }
            for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
                if (!ui.isPart(store.element(c))) {
                    node(c, depth + 1);
                }
            }
            imm.treePop();
        }
    }

    /** Whether {@code slot} is {@code descendant} or an ancestor of it: the tree opens on the selection's path. */
    private boolean contains(int slot, int descendant) {
        for (int s = descendant; s != UiStore.NONE; s = store.parent(s)) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    /** {@code type#id.style}, and the label's text in quotes. */
    private void describe(StringBuilder out, int slot) {
        out.append(store.type(slot));
        String id = store.id(slot);
        if (id != null) {
            out.append('#').append(id);
        }
        String style = store.style(slot);
        if (style != null) {
            out.append('.').append(style);
        }
        Element e = store.element(slot);
        if ("label".equals(store.type(slot))) {
            CharSequence text = ui.text(e);
            out.append(" \"");
            int n = Math.min(text.length(), 24);
            for (int i = 0; i < n; i++) {
                out.append(text.charAt(i));
            }
            out.append(n < text.length() ? "\u2026\"" : "\"");
        }
    }

    private void details() {
        if (selected == null || !selected.alive()) {
            selected = null;
            imm.text("Select an element in the tree, or pick one.", "imm-label");
            return;
        }
        int slot = selected.slot();
        line.setLength(0);
        path(line, slot);
        imm.labelText("Path", line);
        line.setLength(0);
        String style = store.style(slot);
        line.append(style == null ? "(none)" : style);
        imm.labelText("Style name", line);
        line.setLength(0);
        int state = store.state(slot);
        for (int bit = 1; bit < 1 << UiState.count(); bit <<= 1) {
            if ((state & bit) != 0) {
                line.append(':').append(UiState.name(bit)).append(' ');
            }
        }
        if (line.isEmpty()) {
            line.append("(none)");
        }
        imm.labelText("State", line);
        line.setLength(0);
        for (com.truesim.ui.style.Selector.Fact f : ui.styles().factsOf(slot).facts()) {
            line.append('[').append(f.name()).append('=').append(f.value()).append(']');
        }
        if (line.isEmpty()) {
            line.append("(none)");
        }
        imm.labelText("Facts", line);
        line.setLength(0);
        float s = layout.scale() > 0 ? layout.scale() : 1;
        Imm.number(line, layout.x(slot) / s, 1);
        line.append(", ");
        Imm.number(line, layout.y(slot) / s, 1);
        line.append("  ");
        Imm.number(line, layout.width(slot) / s, 1);
        line.append(" x ");
        Imm.number(line, layout.height(slot) / s, 1);
        imm.labelText("Rect", line);
        explain(slot);
        imm.beginChild("styles", 200);
        imm.beginTable("resolved", 3);
        imm.tableNextColumn();
        imm.text("Key", "imm-label");
        imm.tableNextColumn();
        imm.text("Value", "imm-label");
        imm.tableNextColumn();
        imm.text("From", "imm-label");
        for (int i = 0; i < origins.size(); i++) {
            StyleResolver.Origin o = origins.get(i);
            imm.tableNextRow();
            imm.tableNextColumn();
            imm.text(o.key().name());
            imm.tableNextColumn();
            imm.text(texts[i * 2]);
            imm.tableNextColumn();
            imm.text(texts[i * 2 + 1]);
        }
        imm.endTable();
        imm.endChild();
    }

    private String[] texts = new String[0];

    /** The selected element's resolved values and where they come from, again when its style changed. */
    @AllowAllocation("on a new selection or a restyle of the selected element")
    private void explain(int slot) {
        ResolvedStyle style = ui.styles().styleOf(slot);
        if (selected == explained && style.version() == explainedVersion) {
            return;
        }
        explained = selected;
        explainedVersion = style.version();
        origins = ui.styles().explain(slot);
        texts = new String[origins.size() * 2];
        for (int i = 0; i < origins.size(); i++) {
            StyleResolver.Origin o = origins.get(i);
            texts[i * 2] = String.valueOf(o.value());
            String source = o.source();
            texts[i * 2 + 1] = o.selector() + "  " + source.substring(source.lastIndexOf('/') + 1);
        }
    }

    /** {@code root > panel.hud > button#ok}: the element's ancestors and itself. */
    private void path(StringBuilder out, int slot) {
        int parent = store.parent(slot);
        if (parent != UiStore.NONE) {
            path(out, parent);
            out.append(" > ");
        }
        out.append(store.type(slot));
        String id = store.id(slot);
        if (id != null) {
            out.append('#').append(id);
        }
        String style = store.style(slot);
        if (style != null) {
            out.append('.').append(style);
        }
    }

    private void timings() {
        FrameStats stats = ui.stats();
        imm.begin("UI frame", 980, 600);
        ms("Input", stats.inputNanos());
        ms("Frame code", stats.listenerNanos());
        ms("Styles", stats.styleNanos());
        ms("Layout", stats.layoutNanos());
        ms("Record", stats.recordNanos());
        ms("Update", stats.totalNanos());
        value.setLength(0);
        value.append(stats.passes()).append(" passes, ").append(stats.recorded()).append(" recorded");
        imm.labelText("Layout passes", value);
        value.setLength(0);
        value.append(stats.uploadBytes()).append(" bytes");
        imm.labelText("Upload", value);
        value.setLength(0);
        value.append(store.count()).append(" elements");
        imm.labelText("Elements", value);
        imm.end();
    }

    private void ms(String label, long nanos) {
        value.setLength(0);
        Imm.number(value, nanos / 1e6, 3);
        value.append(" ms");
        imm.labelText(label, value);
    }

    // ---------------------------------------------------------------- overlays

    /** Draws the overlays over the whole screen. */
    private final class OverlayPainter implements Painter {

        @Override
        public long measure(int slot, float width, int widthMode, float height, int heightMode) {
            return Measure.size(0, 0);
        }

        @Override
        public int spare(int recorded) {
            return recorded + 512;
        }

        @Override
        public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            Layers layers = ui.layerStack();
            if ((overlays & (BOUNDS | CLIPS)) != 0) {
                for (int li = 0; li < layers.count(); li++) {
                    int root = layers.at(li).rootSlot();
                    if (!own(root)) {
                        boxes(list, root);
                    }
                }
            }
            if ((overlays & PICKING) != 0) {
                Picking picking = ui.interaction().picking();
                for (int li = 0; li < layers.count(); li++) {
                    if (own(layers.at(li).rootSlot()) || picking.grid(li, gridInfo) == 0) {
                        continue;
                    }
                    float cell = gridInfo[2];
                    for (int r = 0; r < (int) gridInfo[4]; r++) {
                        for (int c = 0; c < (int) gridInfo[3]; c++) {
                            if (picking.cellCount(li, c, r) > 0) {
                                float gx = gridInfo[0] + c * cell;
                                float gy = gridInfo[1] + r * cell;
                                outline(list, grid, gx, gy, gx + cell, gy + cell);
                            }
                        }
                    }
                }
            }
            if ((overlays & DIRTY) != 0) {
                FrameStats stats = ui.stats();
                for (int i = 0; i < stats.recordedCount(); i++) {
                    int s = stats.recordedSlot(i);
                    if (store.alive(s) && layout.shown(s) && !own(s)) {
                        outline(list, dirty, s);
                    }
                }
            }
            if (hovered != null && hovered.alive()) {
                outline(list, hover, hovered.slot());
            }
            if (shown && selected != null && selected.alive()) {
                outline(list, selectedPart, selected.slot());
            }
        }

        private void boxes(DrawList list, int slot) {
            if (!layout.shown(slot) || ui.isPart(store.element(slot))) {
                return;
            }
            if ((overlays & BOUNDS) != 0) {
                outline(list, bounds, slot);
            }
            if ((overlays & CLIPS) != 0 && ui.styles().styleOf(slot).get(Styles.CLIP)) {
                outline(list, clips, slot);
            }
            for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
                boxes(list, c);
            }
        }

        private void outline(DrawList list, Element part, int slot) {
            float x = layout.x(slot);
            float y = layout.y(slot);
            outline(list, part, x, y, x + layout.width(slot), y + layout.height(slot));
        }

        private void outline(DrawList list, Element part, float ax, float ay, float bx, float by) {
            ResolvedStyle s = ui.styles().styleOf(part.slot());
            Border border = s.get(Styles.BORDER);
            Paint fill = s.get(Styles.BACKGROUND);
            long fillColor = fill instanceof Paint.Box box ? box.color().rgba() : Rgba.TRANSPARENT;
            list.box(ax, ay, bx, by, fillColor, 0, 0, 0, 0, border.width() * layout.scale(), border.color().rgba());
        }
    }
}
