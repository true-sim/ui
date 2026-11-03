package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.HostBox;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.OverlayInput;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.DragSession;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Direction;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * A dock space: panels ({@link DockPanel}) docked in panes (tab groups) arranged by splits (rows and columns with
 * draggable splitters), nested to any depth, filling the {@code dock-space} element ({@link #root()}, put anywhere: the
 * HUD, a window, a world panel). Tabs are dragged to reorder them within their strip, to move them into another
 * pane's tabs, to a pane's side (it splits there) or to the dock space's outer edge; dropped anywhere else they undock
 * into a floating window ({@link DockHost}), whose title bar dragged over the space docks the panel again. While
 * dragging, a {@code dock-drop-preview} shows where it would dock and {@code dock-zones} guides (five
 * {@code dock-zone}s, the active one {@code :selected}) sit over the pane under the pointer. Shift held while dragging
 * ({@code ui.docking.dockModifier}) docks nothing. The arrangement is data ({@link #layout()}, {@link #restore}, text
 * through {@link DockLayout}), remembered through a {@link LayoutStore}.
 *
 * <p>Elements, for styles: {@code dock-space} ({@code dock-zone}: how deep the side zones reach; {@code dock-min-pane}:
 * the smallest pane a splitter leaves), {@code dock-split} and {@code dock-splitter} (fact {@code orientation}:
 * {@code row} or {@code column}), {@code dock-tabs} (a pane), {@code dock-tab-strip}, {@code dock-tab}
 * ({@code :selected} for the shown one), {@code dock-tab-close}, {@code dock-pane-body}, {@code dock-panel}.
 *
 * <p>The tab strip and splitter here are docking's own parts: generic tabbed panes and split panes are separate
 * controls; these can be replaced by them when both exist, as nothing outside this class touches them.
 */
public final class DockSpace implements Ui.FrameListener, WindowDropTarget {

    /** The drag payload of a dragged tab. */
    public static final DataFormat<DockPanel> PANEL = DataFormat.of("ui.dock.panel", DockPanel.class);

    private static final Facts ROW = Facts.of("orientation", "row");
    private static final Facts COLUMN = Facts.of("orientation", "column");
    private static final DockPosition[] POSITIONS = {DockPosition.LEFT, DockPosition.RIGHT, DockPosition.TOP,
        DockPosition.BOTTOM, DockPosition.CENTER};

    /** A node of the tree: a split or a pane. */
    abstract static class Node {
        Split parent;
        Element element;
        String style;
    }

    /** A row or column of nodes with size ratios and splitters between them. */
    static final class Split extends Node {
        boolean row;
        final List<Node> children = new ArrayList<>();
        float[] ratios = new float[0];
        Element[] splitters = new Element[0];
    }

    /** A pane: a tab group of panels. */
    static final class Tabs extends Node {
        final List<DockPanel> panels = new ArrayList<>();
        int active;
        Element strip;
        Element body;

        DockPanel active() {
            return panels.isEmpty() ? null : panels.get(Math.min(active, panels.size() - 1));
        }
    }

    private final Ui ui;
    private final Interaction interaction;
    private final Layout layout;
    private final UiStore store;
    private final Element root;
    private final List<DockPanel> panels = new ArrayList<>();
    private Node tree;
    private Split[] splits = new Split[0];
    private Tabs[] panes = new Tabs[0];
    private DockHost host;
    private LayoutStore persistence;
    private String persistenceKey;
    private String closeName = "Close";

    private final Overlay preview;
    private final Element previewElement;
    private final Overlay zones;
    private final Element zonesElement;
    private final Element[] zone = new Element[5];
    private final Element dragTab;
    private final Element dragLabel;

    // the drop target found last
    private Tabs hitTabs;
    private DockPosition hitPosition;
    private int hitIndex;
    private boolean hit;
    private final float[] hitRect = new float[4];
    private final float[] point = new float[2];
    private final float[] box = new float[4];

    // splitter drags
    private Split dragSplit;
    private int dragIndex;
    private float dragA;
    private float dragB;
    private float dragRatio;

    private final UiListener rootListener = new RootListener();
    private final UiListener tabListener = new TabListener();
    private final UiListener splitterListener = new SplitterListener();

    /**
     * A dock space of the UI of {@code overlays}; undocked panels float in windows of {@code windows} (null: panels
     * cannot float). Add {@link #root()} where it should show.
     */
    @AllowAllocation("one per dock space")
    public DockSpace(Overlays overlays, WindowManager windows) {
        this.ui = overlays.ui();
        this.interaction = ui.interaction();
        this.layout = ui.layout();
        this.store = ui.store();
        this.root = ui.element("dock-space", null);
        root.semantics(Role.GROUP, null);
        interaction.on(root, EventType.DROP_OVER.bit() | EventType.DROP.bit() | EventType.DROP_LEAVE.bit(),
            rootListener);
        previewElement = ui.element("dock-drop-preview", null);
        preview = overlays.create(previewElement, Layers.DRAG).input(OverlayInput.PASS_THROUGH);
        zonesElement = ui.element("dock-zones", null);
        for (int i = 0; i < 5; i++) {
            zone[i] = ui.element("dock-zone", null).id(POSITIONS[i].key());
            zonesElement.add(zone[i]);
        }
        zones = overlays.create(zonesElement, Layers.DRAG).input(OverlayInput.PASS_THROUGH);
        dragTab = ui.element("dock-tab", "drag-preview");
        dragLabel = ui.textSystem() == null ? null : ui.label(null, "");
        if (dragLabel != null) {
            dragTab.add(dragLabel);
        }
        if (windows != null) {
            host = new WindowDockHost(windows);
            windows.addDropTarget(this);
        }
        ui.addFrameListener(this);
    }

    /** The dock space's element: add it where the space should show (it fills what its parent gives it). */
    public Element root() {
        return root;
    }

    /** Where undocked panels float, or null. */
    public DockHost host() {
        return host;
    }

    /** Sets where undocked panels float (an engine's OS windows; null: they cannot float). */
    public void host(DockHost value) {
        host = value;
    }

    /** Sets the accessible name of the tabs' close buttons (localised), for panels made afterwards. */
    public void closeName(String value) {
        closeName = value;
    }

    // ================================================================ panels

    /** A new panel with id {@code id} (no white space) and title {@code title}, not docked yet. */
    @AllowAllocation("panels are made when a UI is built")
    public DockPanel panel(String id, String title) {
        if (find(id) != null) {
            throw new IllegalArgumentException("a panel " + id + " exists");
        }
        Element tab = ui.element("dock-tab", null);
        Element label = ui.textSystem() == null ? ui.element("label", null) : ui.label(null, title);
        Element close = ui.element("dock-tab-close", null);
        Element content = ui.element("dock-panel", null);
        tab.add(label).add(close);
        DockPanel p = new DockPanel(this, id, title, tab, label, close, content);
        p.name(closeName);
        interaction.focusable(tab, true);
        interaction.on(tab, EventType.PRESS.bit() | EventType.DRAG_START.bit() | EventType.DRAG_END.bit()
            | EventType.COMMAND.bit(), tabListener);
        interaction.on(close, EventType.CLICK, tabListener);
        panels.add(p);
        return p;
    }

    /** The panel with id {@code id}, or null. */
    public DockPanel find(String id) {
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).id().equals(id)) {
                return panels.get(i);
            }
        }
        return null;
    }

    /** How many panels exist. */
    public int panelCount() {
        return panels.size();
    }

    /** How many panes (tab groups) show. */
    public int paneCount() {
        return panes.length;
    }

    /** The panels of pane {@code index} (tree order), first to last tab. */
    @AllowAllocation("a query for tests and tools")
    public List<DockPanel> pane(int index) {
        return List.copyOf(panes[index].panels);
    }

    /** The pane element ({@code dock-tabs}) {@code panel} is docked in, or null. */
    public Element paneOf(DockPanel panel) {
        return panel.tabs == null ? null : panel.tabs.element;
    }

    /** The tab strip ({@code dock-tab-strip}) of the pane {@code panel} is docked in, or null. */
    public Element stripOf(DockPanel panel) {
        return panel.tabs == null ? null : panel.tabs.strip;
    }

    /** The splitters of the split holding {@code panel}'s pane, or an empty array. */
    public Element[] splittersBeside(DockPanel panel) {
        Split s = panel.tabs == null ? null : panel.tabs.parent;
        return s == null ? new Element[0] : s.splitters.clone();
    }

    /** The size ratios of the split holding {@code panel}'s pane, or an empty array. */
    public float[] ratiosBeside(DockPanel panel) {
        Split s = panel.tabs == null ? null : panel.tabs.parent;
        return s == null ? new float[0] : s.ratios.clone();
    }

    // ================================================================ docking

    /** Docks {@code panel} at {@code where} of the whole space (a side: a quarter of it; centre: the first pane). */
    @AllowAllocation("docking is a user action")
    public void dock(DockPanel panel, DockPosition where) {
        detachPanel(panel);
        dockOuter(panel, where);
        rebuild();
        activate(panel);
    }

    /** Docks {@code panel} at {@code where} of the pane {@code beside} is in (centre: into its tabs). */
    @AllowAllocation("docking is a user action")
    public void dock(DockPanel panel, DockPanel beside, DockPosition where) {
        if (beside.tabs == null) {
            throw new IllegalArgumentException(beside + " is not docked");
        }
        dockAt(panel, beside.tabs, where, -1);
    }

    /** Docks {@code panel} into the tabs of {@code beside}'s pane at tab position {@code index}. */
    @AllowAllocation("docking is a user action")
    public void dockTab(DockPanel panel, DockPanel beside, int index) {
        if (beside.tabs == null) {
            throw new IllegalArgumentException(beside + " is not docked");
        }
        dockAt(panel, beside.tabs, DockPosition.CENTER, index);
    }

    @AllowAllocation("docking is a user action")
    private void dockAt(DockPanel panel, Tabs target, DockPosition where, int index) {
        if (panel.tabs == target && target.panels.size() == 1) {
            activate(panel);
            return; // beside or into itself: nothing changes
        }
        if (panel.tabs == target && where == DockPosition.CENTER) {
            int from = target.panels.indexOf(panel);
            target.panels.remove(from);
            int at = index < 0 ? target.panels.size() : Math.min(from < index ? index - 1 : index, target.panels.size());
            target.panels.add(at, panel);
            rebuild();
            activate(panel);
            return;
        }
        detachPanel(panel);
        if (where == DockPosition.CENTER) {
            int at = index < 0 || index > target.panels.size() ? target.panels.size() : index;
            target.panels.add(at, panel);
            panel.tabs = target;
        } else {
            Tabs fresh = new Tabs();
            fresh.panels.add(panel);
            panel.tabs = fresh;
            Split parent = target.parent;
            if (parent != null && parent.row == where.row()) {
                int i = parent.children.indexOf(target);
                float half = parent.ratios[i] * 0.5f;
                parent.ratios[i] = half;
                insert(parent, where.first() ? i : i + 1, fresh, half);
            } else {
                Split split = new Split();
                split.row = where.row();
                replace(target, split);
                split.children.add(where.first() ? fresh : target);
                split.children.add(where.first() ? target : fresh);
                split.ratios = new float[] {0.5f, 0.5f};
                target.parent = split;
                fresh.parent = split;
            }
        }
        rebuild();
        activate(panel);
    }

    @AllowAllocation("docking is a user action")
    private void dockOuter(DockPanel panel, DockPosition where) {
        Tabs fresh = new Tabs();
        fresh.panels.add(panel);
        panel.tabs = fresh;
        if (tree == null) {
            tree = fresh;
            return;
        }
        if (where == DockPosition.CENTER) {
            Tabs first = firstPane(tree);
            fresh.panels.clear();
            first.panels.add(panel);
            panel.tabs = first;
            return;
        }
        float share = 0.25f;
        if (tree instanceof Split s && s.row == where.row()) {
            for (int i = 0; i < s.ratios.length; i++) {
                s.ratios[i] *= 1 - share;
            }
            insert(s, where.first() ? 0 : s.children.size(), fresh, share);
            return;
        }
        Split split = new Split();
        split.row = where.row();
        Node old = tree;
        split.children.add(where.first() ? fresh : old);
        split.children.add(where.first() ? old : fresh);
        split.ratios = where.first() ? new float[] {share, 1 - share} : new float[] {1 - share, share};
        old.parent = split;
        fresh.parent = split;
        tree = split;
    }

    private static Tabs firstPane(Node node) {
        Node n = node;
        while (n instanceof Split s) {
            n = s.children.get(0);
        }
        return (Tabs) n;
    }

    @AllowAllocation("docking is a user action")
    private static void insert(Split split, int at, Node node, float ratio) {
        split.children.add(at, node);
        float[] r = new float[split.ratios.length + 1];
        System.arraycopy(split.ratios, 0, r, 0, at);
        r[at] = ratio;
        System.arraycopy(split.ratios, at, r, at + 1, split.ratios.length - at);
        split.ratios = r;
        node.parent = split;
    }

    /** Puts {@code with} where {@code node} is in the tree. */
    private void replace(Node node, Node with) {
        Split parent = node.parent;
        with.parent = parent;
        if (parent == null) {
            tree = with;
        } else {
            parent.children.set(parent.children.indexOf(node), with);
        }
    }

    /** Takes {@code panel} out of its pane (collapsing an emptied pane and one-child splits) or its floating host. */
    @AllowAllocation("docking is a user action")
    private void detachPanel(DockPanel panel) {
        if (panel.floating) {
            panel.floating = false;
            if (host != null) {
                host.release(panel);
            }
        }
        Tabs tabs = panel.tabs;
        if (tabs == null) {
            return;
        }
        panel.tabs = null;
        int i = tabs.panels.indexOf(panel);
        tabs.panels.remove(i);
        if (tabs.active > i || tabs.active >= tabs.panels.size()) {
            tabs.active = Math.max(0, tabs.active - 1);
        }
        if (!tabs.panels.isEmpty()) {
            return;
        }
        Split parent = tabs.parent;
        if (parent == null) {
            tree = null;
            return;
        }
        int at = parent.children.indexOf(tabs);
        float freed = parent.ratios[at];
        parent.children.remove(at);
        float[] r = new float[parent.ratios.length - 1];
        System.arraycopy(parent.ratios, 0, r, 0, at);
        System.arraycopy(parent.ratios, at + 1, r, at, r.length - at);
        r[Math.min(at, r.length - 1)] += freed; // the neighbour takes the room
        parent.ratios = r;
        if (parent.children.size() == 1) {
            Node only = parent.children.get(0);
            replace(parent, only);
            if (only instanceof Split inner && inner.parent != null && inner.parent.row == inner.row) {
                flatten(inner);
            }
        }
    }

    /** Merges {@code inner} into its parent split of the same orientation. */
    @AllowAllocation("docking is a user action")
    private static void flatten(Split inner) {
        Split outer = inner.parent;
        int at = outer.children.indexOf(inner);
        float share = outer.ratios[at];
        outer.children.remove(at);
        float[] r = new float[outer.ratios.length - 1 + inner.children.size()];
        System.arraycopy(outer.ratios, 0, r, 0, at);
        for (int k = 0; k < inner.children.size(); k++) {
            Node c = inner.children.get(k);
            outer.children.add(at + k, c);
            c.parent = outer;
            r[at + k] = inner.ratios[k] * share;
        }
        System.arraycopy(outer.ratios, at + 1, r, at + inner.children.size(), outer.ratios.length - at - 1);
        outer.ratios = r;
    }

    /** Undocks {@code panel} into a floating window at its pane's place (or centred). */
    @AllowAllocation("undocking is a user action")
    public void undock(DockPanel panel) {
        float scale = HostBox.scale(ui);
        float w = 320;
        float h = 240;
        float x = 40;
        float y = 40;
        if (panel.tabs != null && layout.shown(panel.tabs.element.slot())) {
            int s = panel.tabs.element.slot();
            w = layout.width(s) / scale;
            h = layout.height(s) / scale;
            if (host != null) {
                host.toHost(layout.x(s), layout.y(s), point);
                x = point[0];
                y = point[1];
            }
        }
        undock(panel, x, y, w, h);
    }

    /** Undocks {@code panel} to float at {@code x, y}, {@code width x height} (panel units of the host). */
    @AllowAllocation("undocking is a user action")
    public void undock(DockPanel panel, float x, float y, float width, float height) {
        if (host == null) {
            throw new IllegalStateException("this dock space has no host for floating panels");
        }
        detachPanel(panel);
        rebuild();
        panel.floating = true;
        host.undock(panel, x, y, width, height);
        save();
    }

    /** Closes {@code panel} (no asking): it leaves its pane or window. */
    @AllowAllocation("closing is a user action")
    public void close(DockPanel panel) {
        if (!panel.isOpen()) {
            return;
        }
        detachPanel(panel);
        rebuild();
        panel.listener.closed(panel);
        save();
    }

    /** The floating host closed {@code panel}'s window: the panel closed. */
    @AllowAllocation("closing is a user action")
    void closedFloating(DockPanel panel) {
        panel.floating = false;
        panel.listener.closed(panel);
        save();
    }

    /** Shows {@code panel}: its pane's active tab, or its floating window raised. */
    public void activate(DockPanel panel) {
        if (panel.tabs != null) {
            panel.tabs.active = panel.tabs.panels.indexOf(panel);
            showActive(panel.tabs);
        } else if (panel.floating && host != null) {
            host.raise(panel);
        }
    }

    @AllowAllocation("titles change on user action")
    void retitle(DockPanel panel) {
        if (panel.tabLabel != null && ui.textSystem() != null) {
            ui.text(panel.tabLabel, panel.title());
        }
        panel.name(closeName);
        if (panel.tabs != null) {
            showActive(panel.tabs);
        }
    }

    private void showActive(Tabs tabs) {
        DockPanel shown = tabs.active();
        for (int i = 0; i < tabs.panels.size(); i++) {
            DockPanel p = tabs.panels.get(i);
            p.tab.state(UiState.SELECTED, p == shown);
            layout.visible(p.content.slot(), p == shown);
        }
        if (shown != null && tabs.body != null && store.label(tabs.body.slot()) != shown.title()) {
            tabs.body.semantics(Role.TAB_PANEL, shown.title());
        }
    }

    // ================================================================ elements

    /** Makes the elements of the tree anew: after a structural change, never per frame. */
    @AllowAllocation("the tree's elements are made when docking changes")
    private void rebuild() {
        for (DockPanel p : panels) {
            if (p.tab.parent() != null) {
                p.tab.detach();
            }
            if (p.content.parent() != null && !p.floating) {
                p.content.detach();
            }
        }
        for (Split s : splits) {
            if (s.element.alive()) {
                ui.destroy(s.element);
            }
        }
        for (Tabs t : panes) {
            if (t.element.alive()) {
                ui.destroy(t.element);
            }
        }
        List<Split> allSplits = new ArrayList<>();
        List<Tabs> allPanes = new ArrayList<>();
        if (tree != null) {
            tree.parent = null;
            root.add(build(tree, allSplits, allPanes));
        }
        splits = allSplits.toArray(new Split[0]);
        panes = allPanes.toArray(new Tabs[0]);
        for (Tabs t : panes) {
            showActive(t);
        }
        dragSplit = null;
        save();
    }

    @AllowAllocation("the tree's elements are made when docking changes")
    private Element build(Node node, List<Split> allSplits, List<Tabs> allPanes) {
        if (node instanceof Split s) {
            Element e = ui.element("dock-split", s.style);
            ui.styles().facts(e.slot(), s.row ? ROW : COLUMN);
            s.element = e;
            s.splitters = new Element[s.children.size() - 1];
            for (int i = 0; i < s.children.size(); i++) {
                if (i > 0) {
                    Element splitter = ui.element("dock-splitter", null);
                    ui.styles().facts(splitter.slot(), s.row ? ROW : COLUMN);
                    splitter.semantics(Role.SEPARATOR, null);
                    interaction.focusable(splitter, true);
                    interaction.on(splitter, EventType.DRAG_START.bit() | EventType.DRAG.bit()
                        | EventType.DRAG_END.bit() | EventType.COMMAND.bit(), splitterListener);
                    s.splitters[i - 1] = splitter;
                    e.add(splitter);
                }
                Node child = s.children.get(i);
                child.parent = s;
                e.add(build(child, allSplits, allPanes));
            }
            allSplits.add(s);
            return e;
        }
        Tabs t = (Tabs) node;
        Element e = ui.element("dock-tabs", t.style);
        e.semantics(Role.GROUP, null);
        Element strip = ui.element("dock-tab-strip", null);
        strip.semantics(Role.TAB_LIST, null);
        Element body = ui.element("dock-pane-body", null);
        e.add(strip).add(body);
        t.element = e;
        t.strip = strip;
        t.body = body;
        for (DockPanel p : t.panels) {
            p.tabs = t;
            strip.add(p.tab);
            body.add(p.content);
        }
        allPanes.add(t);
        return e;
    }

    // ================================================================ the frame

    @Override
    public void frame(long nanos) {
        assertLayout();
    }

    /** Sets the layout inputs docking owns (split directions and ratios, the shown tab's content, the preview). */
    private void assertLayout() {
        if (tree != null) {
            flexChild(tree.element.slot(), 1);
        }
        for (Split s : splits) {
            int e = s.element.slot();
            ui.ownLayout(e, Ui.CODE_KIND);
            layout.kind(e, LayoutKind.FLEX).direction(e, s.row ? Direction.ROW : Direction.COLUMN);
            for (int i = 0; i < s.children.size(); i++) {
                flexChild(s.children.get(i).element.slot(), s.ratios[i]);
            }
            for (Element splitter : s.splitters) {
                int sp = splitter.slot();
                ui.ownLayout(sp, Ui.CODE_FLEX);
                layout.grow(sp, 0).shrink(sp, 0);
            }
        }
        for (Tabs t : panes) {
            int b = t.body.slot();
            ui.ownLayout(b, Ui.CODE_FLEX);
            layout.grow(b, 1);
            DockPanel shown = t.active();
            for (int i = 0; i < t.panels.size(); i++) {
                DockPanel p = t.panels.get(i);
                int c = p.content.slot();
                ui.ownLayout(c, Ui.CODE_FLEX);
                layout.visible(c, p == shown);
                layout.grow(c, 1);
            }
        }
        if (preview.showing()) {
            int p = previewElement.slot();
            ui.ownLayout(p, Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            float scale = HostBox.scale(ui);
            layout.width(p, hitRect[2] / scale, Unit.PX).height(p, hitRect[3] / scale, Unit.PX);
            if (!dragging()) {
                hidePreview();
            }
        }
    }

    private void flexChild(int slot, float ratio) {
        ui.ownLayout(slot, Ui.CODE_FLEX);
        layout.grow(slot, ratio).shrink(slot, 1).basis(slot, 0, Unit.PX);
    }

    private boolean dragging() {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            if (interaction.sessionAt(p).active()) {
                return true;
            }
        }
        return windowDragging;
    }

    private boolean windowDragging;

    @Override
    public void afterLayout() {
        if (zones.showing() && hit && hitTabs != null) {
            int t = hitTabs.element.slot();
            int z = zonesElement.slot();
            zones.position(layout.x(t) + (layout.width(t) - layout.width(z)) * 0.5f,
                layout.y(t) + (layout.height(t) - layout.height(z)) * 0.5f);
        }
    }

    // ================================================================ drop targets

    /**
     * Finds where a panel dropped at {@code x, y} (UI pixels) would dock: into {@link #hitTabs} at
     * {@link #hitPosition} (a null pane: the space's outer edge) and tab {@link #hitIndex}, previewed in
     * {@link #hitRect}. False if not over the space.
     */
    boolean target(float x, float y) {
        hit = false;
        int r = root.slot();
        if (tree == null || !layout.shown(r)) {
            return false;
        }
        float rx = layout.x(r);
        float ry = layout.y(r);
        float rw = layout.width(r);
        float rh = layout.height(r);
        if (x < rx || y < ry || x >= rx + rw || y >= ry + rh || rw <= 0 || rh <= 0) {
            return false;
        }
        ResolvedStyle style = ui.styles().styleOf(r);
        float depth = style.getFloat(WindowStyles.DOCK_ZONE);
        float band = depth * 0.25f;
        float fx = (x - rx) / rw;
        float fy = (y - ry) / rh;
        DockPosition outer = tree instanceof Split ? nearest(fx, fy, band) : null;
        hit = true;
        hitIndex = -1;
        for (Tabs t : panes) {
            int e = t.element.slot();
            float tx = layout.x(e);
            float ty = layout.y(e);
            float tw = layout.width(e);
            float th = layout.height(e);
            if (x < tx || y < ty || x >= tx + tw || y >= ty + th) {
                continue;
            }
            hitTabs = t;
            int s = t.strip.slot();
            if (y < layout.y(s) + layout.height(s)) { // on the tab strip: between its tabs
                hitPosition = DockPosition.CENTER;
                hitIndex = tabIndexAt(t, x);
                box(tx, ty, tw, th);
                return true;
            }
            if (outer != null) {
                break; // near the space's edge: the outer zones win over the pane's
            }
            DockPosition side = tw > 0 && th > 0 ? nearest((x - tx) / tw, (y - ty) / th, depth) : null;
            hitPosition = side == null ? DockPosition.CENTER : side;
            if (side == null) {
                box(tx, ty, tw, th);
            } else {
                side(side, tx, ty, tw, th, 0.5f);
            }
            return true;
        }
        if (outer != null) {
            hitTabs = null;
            hitPosition = outer;
            side(outer, rx, ry, rw, rh, 0.25f);
            return true;
        }
        hit = false;
        return false;
    }

    /** The side whose edge {@code fx, fy} (fractions of a box) is nearer than {@code depth}, or null. */
    private static DockPosition nearest(float fx, float fy, float depth) {
        float left = fx;
        float right = 1 - fx;
        float top = fy;
        float bottom = 1 - fy;
        float min = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (min >= depth) {
            return null;
        }
        if (min == left) {
            return DockPosition.LEFT;
        }
        if (min == right) {
            return DockPosition.RIGHT;
        }
        return min == top ? DockPosition.TOP : DockPosition.BOTTOM;
    }

    private void side(DockPosition side, float x, float y, float w, float h, float share) {
        switch (side) {
            case LEFT -> box(x, y, w * share, h);
            case RIGHT -> box(x + w * (1 - share), y, w * share, h);
            case TOP -> box(x, y, w, h * share);
            default -> box(x, y + h * (1 - share), w, h * share);
        }
    }

    private void box(float x, float y, float w, float h) {
        hitRect[0] = x;
        hitRect[1] = y;
        hitRect[2] = w;
        hitRect[3] = h;
    }

    /** Where a tab dropped at {@code x} goes in {@code t}'s strip: before the first tab whose middle is right of it. */
    private int tabIndexAt(Tabs t, float x) {
        for (int i = 0; i < t.panels.size(); i++) {
            int tab = t.panels.get(i).tab.slot();
            if (x < layout.x(tab) + layout.width(tab) * 0.5f) {
                return i;
            }
        }
        return t.panels.size();
    }

    private void showPreview() {
        preview.position(hitRect[0], hitRect[1]);
        if (!preview.showing()) {
            preview.show();
        }
        int p = previewElement.slot();
        float scale = HostBox.scale(ui);
        layout.width(p, hitRect[2] / scale, Unit.PX).height(p, hitRect[3] / scale, Unit.PX);
        if (hitTabs != null) {
            if (!zones.showing()) {
                zones.show();
            }
            for (int i = 0; i < 5; i++) {
                zone[i].state(UiState.SELECTED, POSITIONS[i] == hitPosition);
            }
        } else if (zones.showing()) {
            zones.hide();
        }
    }

    private void hidePreview() {
        preview.hide();
        zones.hide();
        hit = false;
    }

    /** Whether the drop preview shows (while a tab or window is dragged over a place it would dock). */
    public boolean previewShowing() {
        return preview.showing();
    }

    /** The drop preview's element ({@code dock-drop-preview}). */
    public Element previewElement() {
        return previewElement;
    }

    /** The zone guides' element ({@code dock-zones}). */
    public Element zonesElement() {
        return zonesElement;
    }

    /** Docks {@code panel} where the last target was found. */
    @AllowAllocation("docking is a user action")
    private void dropAtTarget(DockPanel panel) {
        if (hitTabs == null) {
            detachPanel(panel);
            dockOuter(panel, hitPosition);
            rebuild();
            activate(panel);
        } else {
            dockAt(panel, hitTabs, hitPosition, hitIndex);
        }
        save();
    }

    /**
     * For a {@link DockHost} with its own windows: {@code panel}'s floating window is dragged with the pointer at
     * {@code x, y} (this UI's pixels): shows where it would dock.
     */
    public void dragFloating(DockPanel panel, float x, float y, int modifiers) {
        windowDragging = true;
        if (panel.space() == this && (modifiers & Pointer.SHIFT) == 0 && target(x, y)) {
            showPreview();
        } else {
            hidePreview();
        }
    }

    /** For a {@link DockHost}: {@code panel}'s floating window was dropped at {@code x, y}; returns whether it docked. */
    @AllowAllocation("docking is a user action")
    public boolean dropFloating(DockPanel panel, float x, float y, int modifiers) {
        windowDragging = false;
        boolean docked = panel.space() == this && (modifiers & Pointer.SHIFT) == 0 && target(x, y);
        hidePreview();
        if (docked) {
            target(x, y);
            dropAtTarget(panel);
        }
        return docked;
    }

    @Override
    public void windowDragged(FloatingWindow window, float x, float y, int modifiers) {
        if (window.dockSource() instanceof DockPanel p && p.space() == this) {
            dragFloating(p, x, y, modifiers);
        }
    }

    @Override
    public boolean windowDropped(FloatingWindow window, float x, float y, int modifiers) {
        if (window.isOpen() && window.dockSource() instanceof DockPanel p && p.space() == this) {
            return dropFloating(p, x, y, modifiers);
        }
        return false;
    }

    // ================================================================ layout as data

    /** The arrangement as data: the tree, and the floating panels with their places. */
    @AllowAllocation("layouts are saved on user action")
    public DockLayout layout() {
        List<DockLayout.Floating> floating = new ArrayList<>();
        for (DockPanel p : panels) {
            if (p.floating && host != null && host.bounds(p, box)) {
                floating.add(new DockLayout.Floating(p.id(), box[0], box[1], box[2], box[3]));
            }
        }
        return new DockLayout(tree == null ? null : describe(tree), floating);
    }

    @AllowAllocation("layouts are saved on user action")
    private static DockLayout.Node describe(Node node) {
        if (node instanceof Split s) {
            List<Float> ratios = new ArrayList<>();
            List<DockLayout.Node> children = new ArrayList<>();
            for (int i = 0; i < s.children.size(); i++) {
                ratios.add(DockLayout.round(s.ratios[i]));
                children.add(describe(s.children.get(i)));
            }
            return new DockLayout.Split(s.row, ratios, children, s.style);
        }
        Tabs t = (Tabs) node;
        List<String> ids = new ArrayList<>();
        for (DockPanel p : t.panels) {
            ids.add(p.id());
        }
        return new DockLayout.Tabs(ids, Math.min(t.active, ids.size() - 1), t.style);
    }

    /**
     * Arranges the panels as {@code description} says: panels it names dock or float there (unknown ids are
     * skipped), the others close.
     */
    @AllowAllocation("layouts are restored on user action")
    public void restore(DockLayout description) {
        LayoutStore saving = persistence;
        persistence = null;
        try {
            for (DockPanel p : panels) {
                if (p.floating) {
                    p.floating = false;
                    if (host != null) {
                        host.release(p);
                    }
                }
                p.tabs = null;
            }
            tree = description.root() == null ? null : make(description.root());
            if (tree != null) {
                tree.parent = null;
            }
            rebuild();
            for (DockLayout.Floating f : description.floating()) {
                DockPanel p = find(f.panel());
                if (p != null && host != null && !p.isOpen()) {
                    p.floating = true;
                    host.undock(p, f.x(), f.y(), f.width(), f.height());
                }
            }
        } finally {
            persistence = saving;
        }
        save();
    }

    @AllowAllocation("layouts are restored on user action")
    private Node make(DockLayout.Node node) {
        if (node instanceof DockLayout.Split s) {
            Split split = new Split();
            split.row = s.row();
            split.style = s.style();
            List<Float> ratios = new ArrayList<>();
            for (int i = 0; i < s.children().size(); i++) {
                Node child = make(s.children().get(i));
                if (child != null) {
                    child.parent = split;
                    split.children.add(child);
                    ratios.add(s.ratios().get(i));
                }
            }
            if (split.children.isEmpty()) {
                return null;
            }
            if (split.children.size() == 1) {
                return split.children.get(0);
            }
            float sum = 0;
            for (Float r : ratios) {
                sum += r;
            }
            split.ratios = new float[ratios.size()];
            for (int i = 0; i < ratios.size(); i++) {
                split.ratios[i] = sum > 0 ? ratios.get(i) / sum : 1f / ratios.size();
            }
            return split;
        }
        DockLayout.Tabs t = (DockLayout.Tabs) node;
        Tabs tabs = new Tabs();
        tabs.style = t.style();
        for (int i = 0; i < t.panels().size(); i++) {
            DockPanel p = find(t.panels().get(i));
            if (p != null && p.tabs == null) {
                p.tabs = tabs;
                tabs.panels.add(p);
                if (i == t.active()) {
                    tabs.active = tabs.panels.size() - 1;
                }
            }
        }
        return tabs.panels.isEmpty() ? null : tabs;
    }

    /**
     * Remembers the arrangement in {@code store} under {@code key}: what is saved there is restored now (panels must
     * exist), and every change saves it.
     */
    @AllowAllocation("set up once")
    public void persistence(LayoutStore store, String key) {
        persistence = null;
        String text = store == null ? null : store.load(key);
        if (text != null) {
            restore(DockLayout.parse(text));
        }
        persistence = store;
        persistenceKey = key;
    }

    /** Saves the arrangement to the persistence store, if one is set. */
    @AllowAllocation("layouts are saved on user action")
    public void save() {
        if (persistence != null) {
            persistence.save(persistenceKey, layout().toText());
        }
    }

    // ================================================================ events

    private final class RootListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            DragSession drag = e.drag();
            DockPanel panel = drag == null ? null : drag.get(PANEL);
            switch (e.type()) {
                case DROP_OVER -> {
                    if (panel != null && panel.space() == DockSpace.this && (e.modifiers() & Pointer.SHIFT) == 0
                        && target(e.x(), e.y())) {
                        e.acceptDrop(DragOperation.MOVE);
                        showPreview();
                    } else {
                        hidePreview();
                    }
                }
                case DROP_LEAVE -> hidePreview();
                case DROP -> {
                    hidePreview();
                    if (panel != null && target(e.x(), e.y())) {
                        dropAtTarget(panel);
                    }
                }
                default -> {
                }
            }
        }
    }

    private DockPanel panelOf(int slot) {
        for (int i = 0; i < panels.size(); i++) {
            DockPanel p = panels.get(i);
            if (p.tab.slot() == slot || p.closeButton.slot() == slot) {
                return p;
            }
        }
        throw new IllegalStateException("not a tab of this dock space");
    }

    private final class TabListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            DockPanel p = panelOf(e.currentSlot());
            switch (e.type()) {
                case CLICK -> {
                    e.consume();
                    if (p.closable()) {
                        p.requestClose();
                    }
                }
                case PRESS -> {
                    if (e.button() == Pointer.PRIMARY && p.closeButton.slot() != e.targetSlot()) {
                        activate(p);
                    }
                }
                case DRAG_START -> {
                    if (p.closeButton.slot() == e.targetSlot()) {
                        return;
                    }
                    float ox = e.localX();
                    float oy = e.localY();
                    if (dragLabel != null) {
                        ui.text(dragLabel, p.title());
                        dragLabel.semantics(Role.TEXT, p.title());
                    }
                    e.startDrag(DragOperation.MOVE).put(PANEL, p).preview(dragTab, ox, oy);
                }
                case DRAG_END -> {
                    hidePreview();
                    // dropped where nothing docks it (not cancelled: the button was released): it floats there
                    if (e.drag() != null && e.dropOperation() == DragOperation.NONE && e.buttons() == 0
                        && host != null) {
                        int tab = p.tab.slot();
                        floatAt(p, e.x() - (e.pressX() - layout.x(tab)), e.y() - (e.pressY() - layout.y(tab)));
                    }
                }
                case COMMAND -> command(p, e);
                default -> {
                }
            }
        }

        private void command(DockPanel p, UiEvent e) {
            Tabs t = p.tabs;
            if (t == null) {
                return;
            }
            int i = t.panels.indexOf(p);
            int next = e.command() == UiCommand.RIGHT ? i + 1 : e.command() == UiCommand.LEFT ? i - 1 : i;
            if (next != i && next >= 0 && next < t.panels.size()) {
                e.consume();
                DockPanel to = t.panels.get(next);
                activate(to);
                interaction.focus(to.tab);
            }
        }
    }

    @AllowAllocation("undocking is a user action")
    private void floatAt(DockPanel p, float x, float y) {
        float scale = HostBox.scale(ui);
        float w = 320;
        float h = 240;
        if (p.tabs != null && layout.shown(p.tabs.element.slot())) {
            w = layout.width(p.tabs.element.slot()) / scale;
            h = layout.height(p.tabs.element.slot()) / scale;
        }
        host.toHost(x, y, point);
        undock(p, point[0], point[1], w, h);
    }

    private final class SplitterListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            switch (e.type()) {
                case DRAG_START -> {
                    if (begin(e.currentSlot())) {
                        e.consume();
                        drag(dragSplit.row ? e.x() - e.pressX() : e.y() - e.pressY());
                    }
                }
                case DRAG -> {
                    if (dragSplit != null) {
                        e.consume();
                        drag(dragSplit.row ? e.x() - e.pressX() : e.y() - e.pressY());
                    }
                }
                case DRAG_END -> {
                    if (dragSplit != null) {
                        dragSplit = null;
                        e.consume();
                        save();
                    }
                }
                case COMMAND -> {
                    if (!begin(e.currentSlot())) {
                        return;
                    }
                    float step = WindowManager.KEY_STEP.get() * HostBox.scale(ui);
                    UiCommand c = e.command();
                    float d = dragSplit.row ? (c == UiCommand.LEFT ? -step : c == UiCommand.RIGHT ? step : 0)
                        : (c == UiCommand.UP ? -step : c == UiCommand.DOWN ? step : 0);
                    if (d != 0) {
                        e.consume();
                        drag(d);
                        save();
                    }
                    dragSplit = null;
                }
                default -> {
                }
            }
        }
    }

    /** Starts a splitter drag at the splitter in {@code slot}; false if it is none of this space's. */
    private boolean begin(int slot) {
        for (Split s : splits) {
            for (int i = 0; i < s.splitters.length; i++) {
                if (s.splitters[i].slot() == slot) {
                    dragSplit = s;
                    dragIndex = i;
                    int a = s.children.get(i).element.slot();
                    int b = s.children.get(i + 1).element.slot();
                    dragA = s.row ? layout.width(a) : layout.height(a);
                    dragB = s.row ? layout.width(b) : layout.height(b);
                    dragRatio = s.ratios[i] + s.ratios[i + 1];
                    return true;
                }
            }
        }
        return false;
    }

    /** Moves the dragged splitter {@code delta} UI pixels from where it started, keeping both panes' minimum. */
    private void drag(float delta) {
        float total = dragA + dragB;
        if (total <= 0) {
            return;
        }
        float min = Math.min(ui.styles().styleOf(root.slot()).getFloat(WindowStyles.DOCK_MIN_PANE)
            * HostBox.scale(ui), total * 0.5f);
        float a = Math.min(Math.max(dragA + delta, min), total - min);
        dragSplit.ratios[dragIndex] = dragRatio * a / total;
        dragSplit.ratios[dragIndex + 1] = dragRatio * (total - a) / total;
    }

    @Override
    public String toString() {
        return "DockSpace[" + panels.size() + " panels, " + panes.length + " panes]";
    }
}
