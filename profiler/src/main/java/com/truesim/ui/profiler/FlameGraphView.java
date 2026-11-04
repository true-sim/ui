package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.text.TextBlock;

/**
 * A flame graph of one lane over a span ({@link FlameTree}): the span on the top row, each call path below its
 * caller, as wide as the time spent in it, in the order first met. A click on a node zooms to it (its width fills the
 * view); a click on the top row zooms out. Parts: {@code profiler-flame} ({@code palette}), {@code profiler-flame-node}
 * ({@code row-height}, text; {@code :selected} for the focused node). Records without allocating.
 */
public final class FlameGraphView extends PaintedView {

    private final Recording recording;
    private final FlameTree tree = new FlameTree();
    private final Element nodePart;
    private final Element focusPart;
    private int lane;
    private long from;
    private long to;
    private int focus;
    private int hovered = -1;
    private final TextBlock title;
    private final StringBuilder scratch = new StringBuilder(96);

    /** A flame graph of {@code recording} in {@code ui}; give it a lane and span with {@link #show}. */
    @AllowAllocation("views are made when a UI is built")
    public FlameGraphView(Ui ui, Recording recording) {
        super(ui, "profiler-flame");
        this.recording = recording;
        root.semantics(Role.IMAGE, "Flame graph");
        nodePart = ui.part(root, "profiler-flame-node", null);
        focusPart = ui.part(root, "profiler-flame-node", null);
        focusPart.state(UiState.SELECTED, true);
        title = newBlock();
    }

    /** Builds the graph of lane {@code lane} over {@code from..to} (the focus goes back to the span). */
    public void show(int lane, long from, long to) {
        this.lane = lane;
        this.from = from;
        this.to = to;
        if (lane >= 0 && lane < recording.lanes()) {
            tree.build(recording, lane, from, to, (int) ProfilerTunables.MAX_NODES.get());
        } else {
            tree.empty(to - from);
        }
        if (focus >= tree.count()) {
            focus = 0;
        }
        hovered = -1;
        repaint();
    }

    /** The tree shown. */
    public FlameTree tree() {
        return tree;
    }

    /** The lane shown. */
    public int lane() {
        return lane;
    }

    /** The node the view is zoomed to (0: the span). */
    public int focus() {
        return focus;
    }

    /** Zooms to node {@code node} (0: the whole span). */
    public void focus(int node) {
        focus = node >= 0 && node < tree.count() ? node : 0;
        repaint();
    }

    /** The height of a row in pixels. */
    public float rowPixels() {
        return rowHeight(styleOf(nodePart));
    }

    /** The node under the pointer, or -1. */
    public int hovered() {
        return hovered;
    }

    @Override
    protected void update(long nanos) {
        // shown on request (show)
    }

    @Override
    protected long ownTextVersion() {
        return title == null ? 0 : title.version();
    }

    private boolean under(int node, int ancestor) {
        for (int n = node; n >= 0; n = tree.parent(n)) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void record(DrawList list) {
        ResolvedStyle fs = styleOf(root);
        ResolvedStyle ns = styleOf(nodePart);
        float row = rowHeight(ns);
        float w = x1 - x0;
        if (tree.count() == 0 || w <= 0) {
            return;
        }
        long span = Math.max(1, tree.time(focus));
        long base = tree.offset(focus);
        int baseDepth = tree.depth(focus);
        float pad = padLeft(ns);
        long tc = textColor(ns);
        // the top row: the span or the focused node
        float ty1 = Math.min(y1, y0 + row);
        box(list, ns, x0, y0, x1, ty1, background(ns));
        if (title != null && ty1 - y0 >= row) {
            scratch.setLength(0);
            if (lane >= 0 && lane < recording.lanes()) {
                scratch.append(recording.laneName(lane));
            }
            if (focus > 0) {
                scratch.append(" > ").append(recording.nameOf(tree.name(focus)));
            }
            scratch.append("  ");
            Units.duration(scratch, tree.time(focus));
            shape(title, scratch, ns);
            drew(title);
            if (title.width() + 2 * pad <= w && title.height() <= row) {
                text(list, title, x0 + pad, y0 + (row - title.height()) * 0.5f, tc);
            }
        }
        float minLabel = ns.getFloat(com.truesim.ui.controls.profiler.ProfilerStyles.LABEL_MIN_WIDTH) * scale;
        for (int i = 1; i < tree.count(); i++) {
            if (focus != 0 && !under(i, focus) || i == focus) {
                continue;
            }
            int d = tree.depth(i) - baseDepth;
            float y = y0 + d * row;
            if (y + row > y1) {
                continue;
            }
            float a = x0 + (float) ((double) (tree.offset(i) - base) * w / span);
            float b = a + (float) ((double) tree.time(i) * w / span);
            a = Math.max(x0, a);
            b = Math.min(x1, b);
            if (b - a < 0.5f) {
                continue;
            }
            int name = tree.name(i);
            box(list, i == hovered ? styleOf(focusPart) : ns, a, y + 1, b, y + row - 1, palette(fs, name));
            if (b - a >= minLabel) {
                TextBlock t = nameBlock(recording, name, ns);
                if (t != null && t.width() + 2 * pad <= b - a && t.height() <= row) {
                    text(list, t, a + pad, y + (row - t.height()) * 0.5f, tc);
                }
            }
        }
    }

    /** The node under pixel {@code x, y} (drawing coordinates) as last drawn, or -1 (0: the top row). */
    public int nodeAt(float x, float y) {
        if (!inside(x, y) || tree.count() == 0) {
            return -1;
        }
        float row = rowHeight(styleOf(nodePart));
        int d = (int) ((y - y0) / row);
        if (d == 0) {
            return 0;
        }
        long span = Math.max(1, tree.time(focus));
        long base = tree.offset(focus);
        int baseDepth = tree.depth(focus);
        double t = base + (x - x0) * (double) span / Math.max(1, x1 - x0);
        for (int i = 1; i < tree.count(); i++) {
            if (tree.depth(i) - baseDepth == d && (focus == 0 || under(i, focus)) && t >= tree.offset(i)
                && t < tree.offset(i) + tree.time(i)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void handle(UiEvent event) {
        switch (event.type()) {
            case ENTER, MOVE -> {
                int n = nodeAt(drawX(event.x()), drawY(event.y()));
                if (n != hovered) {
                    hovered = n;
                    repaint();
                }
            }
            case EXIT -> {
                if (hovered >= 0) {
                    hovered = -1;
                    repaint();
                }
            }
            case CLICK -> {
                if (event.button() == Pointer.PRIMARY) {
                    int n = nodeAt(drawX(event.x()), drawY(event.y()));
                    if (n >= 0) {
                        event.consume();
                        focus(n == 0 ? (focus == 0 ? 0 : tree.parent(focus) < 0 ? 0 : tree.parent(focus)) : n);
                    }
                }
            }
            default -> {
                // nothing else
            }
        }
    }
}
