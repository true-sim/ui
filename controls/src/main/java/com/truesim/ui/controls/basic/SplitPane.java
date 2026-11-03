package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;

/**
 * Two panes split by a draggable splitter (type {@code split-pane}, fact {@code orientation=horizontal} (side by side)
 * or {@code vertical}; parts {@code split-first}, {@code splitter}, {@code split-second}), outside docking. The first
 * pane's share of the space is a {@link VersionedDouble} (0 to 1, two-way), set as a percentage so a resize keeps the
 * share without a frame of delay; the panes' {@code min-width} (or {@code min-height}) in pixels bound the drag.
 *
 * <p>The splitter takes focus: arrows move it by {@code ui.split.keyboardStep}, home and end collapse the first or
 * second pane, ACCEPT (or a double click) collapses the first pane or restores the share it had. Dragging shows
 * {@code :pressed} on the splitter; its cursor is the theme's.
 */
public final class SplitPane extends Control {

    private final boolean vertical;
    private final Element first;
    private final Element splitter;
    private final Element second;
    private VersionedDouble model;
    private Watch watch;
    private double restore = 0.5;
    private boolean dragging;
    private float grab;

    /** A split pane named {@code name}, side by side or {@code vertical}, bound to {@code share}. */
    @AllowAllocation("controls are built once")
    public SplitPane(Controls controls, String name, boolean vertical, VersionedDouble share) {
        super(controls, "split-pane", Role.GROUP, name);
        this.vertical = vertical;
        ui.styles().facts(root.slot(), Facts.of("orientation", vertical ? "vertical" : "horizontal"));
        first = part("split-first");
        splitter = part("splitter");
        splitter.semantics(Role.SEPARATOR, name);
        second = part("split-second");
        root.add(first).add(splitter).add(second);
        focusable(splitter);
        listen(splitter, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
            | EventType.CLICK.bit() | EventType.COMMAND.bit());
        controls.poll(this);
        bind(share);
    }

    /** Binds the split to {@code share}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public SplitPane bind(VersionedDouble share) {
        model = share;
        watch = share.watch();
        show();
        return this;
    }

    /** The model: the first pane's share. */
    public VersionedDouble model() {
        return model;
    }

    /** The first pane. */
    public Element first() {
        return first;
    }

    /** The second pane. */
    public Element second() {
        return second;
    }

    /** The splitter. */
    public Element splitter() {
        return splitter;
    }

    /** The first pane's share. */
    public double share() {
        return model.get();
    }

    /** Sets the first pane's share, kept within the panes' minimum sizes. */
    public void share(double value) {
        model.set(clamp(value));
        show();
    }

    /** Whether a pane is collapsed (the share is 0 or 1). */
    public boolean collapsed() {
        double s = model.get();
        return s <= 0 || s >= 1;
    }

    /** Collapses the first pane ({@code firstPane}) or the second, remembering the share to restore. */
    public void collapse(boolean firstPane) {
        if (!collapsed()) {
            restore = model.get();
        }
        model.set(firstPane ? 0 : 1);
        show();
    }

    /** Restores the share before the last collapse. */
    public void restore() {
        share(restore);
    }

    private void show() {
        watch.update();
        float s = (float) Math.max(0, Math.min(1, model.get()));
        com.truesim.ui.controls.anim.Visuals v = ui.visuals();
        if (vertical) {
            v.height(first.slot(), s * 100, Unit.PERCENT);
        } else {
            v.width(first.slot(), s * 100, Unit.PERCENT);
        }
        // a collapsed pane gives up its minimum size
        int minBit = vertical ? com.truesim.ui.controls.anim.Visuals.MIN_HEIGHT
            : com.truesim.ui.controls.anim.Visuals.MIN_WIDTH;
        collapse(v, first, s <= 0, minBit);
        collapse(v, second, s >= 1, minBit);
    }

    private void collapse(com.truesim.ui.controls.anim.Visuals v, Element pane, boolean collapsed, int minBit) {
        if (collapsed) {
            if (vertical) {
                v.minHeight(pane.slot(), 0);
            } else {
                v.minWidth(pane.slot(), 0);
            }
        } else {
            v.clear(pane.slot(), minBit);
        }
    }

    /** The space the share divides (the root's content size), in UI pixels; 0 before the first layout. */
    private float space() {
        Layout l = ui.layout();
        int r = root.slot();
        return Math.max(0, vertical ? l.height(r) : l.width(r));
    }

    private double clamp(double value) {
        double v = Math.max(0, Math.min(1, value));
        float space = space();
        if (space <= 0 || v <= 0 || v >= 1) {
            return v;
        }
        float scale = ui.layout().scale();
        float min1 = minimum(first) * scale;
        float min2 = minimum(second) * scale;
        float split = vertical ? ui.layout().height(splitter.slot()) : ui.layout().width(splitter.slot());
        double lo = min1 / space;
        double hi = (space - split - min2) / space;
        return hi < lo ? v : Math.max(lo, Math.min(hi, v));
    }

    private float minimum(Element pane) {
        Length m = ui.styles().styleOf(pane.slot()).get(vertical ? Styles.MIN_HEIGHT : Styles.MIN_WIDTH);
        return m.unit() == Length.Unit.PX ? m.value() : 0;
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
    }

    @Override
    public void handle(UiEvent event) {
        Layout l = ui.layout();
        int r = root.slot();
        switch (event.type()) {
            case PRESS -> {
                event.consume();
                dragging = true;
                splitter.state(UiState.PRESSED, true);
                int s = splitter.slot();
                grab = vertical ? event.y() - l.y(s) : event.x() - l.x(s);
            }
            case MOVE -> {
                if (dragging && event.buttons() != 0 && space() > 0) {
                    float at = vertical ? event.y() - l.y(r) : event.x() - l.x(r);
                    share((at - grab) / space());
                }
            }
            case RELEASE -> {
                dragging = false;
                splitter.state(UiState.PRESSED, false);
            }
            case CLICK -> {
                event.consume();
                if (event.clickCount() == 2 || event.pointerId() < 0) {
                    toggle();
                }
            }
            case COMMAND -> command(event);
            default -> {
                // nothing else
            }
        }
    }

    private void toggle() {
        if (collapsed()) {
            restore();
        } else {
            collapse(true);
        }
    }

    private void command(UiEvent event) {
        UiCommand c = event.command();
        float space = space();
        double step = space <= 0 ? 0.05 : Controls.SPLIT_STEP.get() * ui.layout().scale() / space;
        if (c == (vertical ? UiCommand.UP : UiCommand.LEFT)) {
            share(model.get() - step);
        } else if (c == (vertical ? UiCommand.DOWN : UiCommand.RIGHT)) {
            share(model.get() + step);
        } else if (c == UiCommand.HOME) {
            collapse(true);
        } else if (c == UiCommand.END) {
            collapse(false);
        } else {
            return;
        }
        event.consume();
    }
}
