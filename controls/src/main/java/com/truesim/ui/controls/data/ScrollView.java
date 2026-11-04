package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.DataStyles.ScrollbarVisibility;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.GrowDirection;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;

/**
 * A viewport over content larger than it, with two stylable scroll bars (design 5.5): the base of {@link ScrollPane}
 * and the virtualized collections. It scrolls by
 * <ul>
 *   <li>the wheel and trackpads ({@code scroll-step} per notch; Shift turns vertical into horizontal), passing the
 *       wheel on to an outer view at its ends;</li>
 *   <li>touch and pen drags with momentum ({@link DataTunables#FRICTION}) and, optionally, overscroll that springs
 *       back ({@link DataTunables#OVERSCROLL});</li>
 *   <li>the scroll bars: thumb dragging, track paging and the end buttons, held to repeat;</li>
 *   <li>commands: page up and down, home and end, and the arrows while the view itself has focus;</li>
 *   <li>focus: an element inside focused by navigation (keys, gamepad) is scrolled into view.</li>
 * </ul>
 *
 * <p><b>Elements and styles.</b> The root (its type given by the subclass: {@code scroll-pane}, {@code list-view},
 * ...) holds the {@code scroll-viewport} (which clips: style {@code clip}) and two {@code scroll-bar}s with ids
 * {@code vertical} and {@code horizontal}; each bar is the track and holds {@code scroll-button}s with ids
 * {@code start} and {@code end} and a {@code scroll-thumb}. A bar's thickness is its style's width (vertical) or
 * height (horizontal); buttons are as long as their style says (0 hides them). Bars show by
 * {@link DataStyles#SCROLLBAR_VISIBILITY} and sit beside the viewport or over it ({@link DataStyles#SCROLLBAR_OVERLAY}).
 * While scrolling, hovered or held a bar has the fact {@code [active=true]} (for {@link DataTunables#AUTO_HIDE_DELAY}),
 * so a theme can fade idle bars out. Code places the parts; the theme decides every look.
 *
 * <p>Positions are in panel units. Input moves the view in the frame it arrives in; nothing allocates per frame.
 */
public abstract class ScrollView {

    private static final Facts ACTIVE = Facts.of("active", "true");
    private static final int NO_PART = 0;
    private static final int THUMB = 1;
    private static final int START = 2;
    private static final int END = 3;
    private static final int TRACK = 4;

    protected final Ui ui;
    protected final Layout layout;
    protected final Interaction interaction;
    /** The view's root element. */
    protected final Element root;
    /** The clipping viewport the content scrolls in. */
    protected final Element viewport;
    /** The horizontal scroll position. */
    protected final Scroller scrollX = new Scroller();
    /** The vertical scroll position. */
    protected final Scroller scrollY = new Scroller();
    private final Bar vertical;
    private final Bar horizontal;
    private final Events events = new Events();
    private final Updater updater = new Updater();

    private boolean dirty = true;
    private double lastTime = -1;
    private double now;
    private double lastActivity = Double.NEGATIVE_INFINITY;
    private int panPointer = -1;
    private Bar held;
    private int heldPart;
    private int heldPointer = -1;
    private float grab;
    private double nextRepeat;
    private float heldX;
    private float heldY;
    private float vSpace = -1;
    private float hSpace = -1;
    private float topSpace;
    private double appliedX;
    private double appliedY;

    /**
     * A view of type {@code type} named {@code name} for assistive technology (null: unnamed), made in {@code ui};
     * add {@link #root()} where it goes.
     */
    @AllowAllocation("controls are made when a UI is built")
    protected ScrollView(Ui ui, String type, String name) {
        this.ui = ui;
        this.layout = ui.layout();
        this.interaction = ui.interaction();
        root = ui.element(type, null);
        root.semantics(Role.SCROLL_VIEW, name);
        ui.codeLayout(root, Ui.CODE_KIND);
        layout.kind(root.slot(), LayoutKind.ANCHORS);
        viewport = ui.element("scroll-viewport", null);
        root.add(viewport);
        ui.codeLayout(viewport, Ui.CODE_PLACEMENT | Ui.CODE_KIND);
        layout.kind(viewport.slot(), LayoutKind.ANCHORS).anchors(viewport.slot(), 0, 0, 1, 1)
            .independent(viewport.slot(), true);
        vertical = new Bar(true);
        horizontal = new Bar(false);
        interaction.on(root, EventType.WHEEL.bit() | EventType.PRESS.bit() | EventType.DRAG_START.bit()
            | EventType.DRAG.bit() | EventType.RELEASE.bit() | EventType.COMMAND.bit() | EventType.FOCUS_IN.bit(),
            events);
        ui.addFrameListener(updater);
    }

    // ---------------------------------------------------------------- what subclasses provide

    /** The content's width in panel units, read after layout. */
    protected abstract double contentWidth();

    /** The content's height in panel units, read after layout. */
    protected abstract double contentHeight();

    /** Shows the content at scroll position {@code x, y} (panel units): called before layout when it changed. */
    protected abstract void scrolled(double x, double y);

    /** Called every frame before layout, before the scroll position is applied: poll models here. */
    protected void beforeLayout(double seconds) {
    }

    /**
     * Called after layout, before the content's size is read and the viewport's size is taken (the scrollers'
     * viewport lengths are last frame's); returns true if it changed elements that need another styles and layout
     * pass this frame. A changed scroll position or viewport is shown with {@link #scrolled} right after.
     */
    protected boolean afterLayout() {
        return false;
    }

    /**
     * A command bubbling to the view from its focused descendant or the view itself, before the view's own scrolling
     * commands: consume it to keep it.
     */
    protected void command(UiEvent e) {
    }

    /** Space above the viewport (and the vertical bar) for a header, in panel units, read after layout. */
    protected float top() {
        return 0;
    }

    /** Whether the view itself, rather than something inside, has the focus commands come from. */
    protected boolean focusedSelf(int slot) {
        return slot == root.slot() || slot == viewport.slot();
    }

    // ---------------------------------------------------------------- API

    /** The view's root element: add it where the view goes. */
    public Element root() {
        return root;
    }

    /** The viewport element. */
    public Element viewport() {
        return viewport;
    }

    /** The horizontal position. */
    public Scroller horizontal() {
        return scrollX;
    }

    /** The vertical position. */
    public Scroller vertical() {
        return scrollY;
    }

    /** The vertical scroll bar's root element (the track). */
    public Element verticalBar() {
        return vertical.bar;
    }

    /** The horizontal scroll bar's root element (the track). */
    public Element horizontalBar() {
        return horizontal.bar;
    }

    /** Makes the view itself focusable, so keys scroll it when nothing inside has focus; returns this. */
    public ScrollView focusable(boolean on) {
        interaction.focusable(root, on);
        return this;
    }

    /** Scrolls to {@code x, y} (clamped) now; shows in this frame's layout. */
    public void scrollTo(double x, double y) {
        boolean moved = scrollX.scrollTo(x) | scrollY.scrollTo(y);
        if (moved) {
            activity();
        }
        dirty = true;
    }

    /**
     * Scrolls the least so that the content rectangle {@code x, y, w, h} (panel units, content coordinates) is in
     * view.
     */
    public void reveal(double x, double y, double w, double h) {
        boolean moved = scrollX.reveal(x, x + w) | scrollY.reveal(y, y + h);
        if (moved) {
            activity();
            dirty = true;
        }
    }

    /**
     * Scrolls {@code element} (inside the content, laid out) into view; nothing if it is not inside this view.
     */
    public void reveal(Element element) {
        int slot = element.slot();
        if (!inside(slot) || !layout.shown(slot)) {
            return;
        }
        float s = scale();
        int v = viewport.slot();
        // the layout shows the positions applied before it, not what input changed since
        reveal(appliedX + (layout.x(slot) - layout.x(v)) / s, appliedY + (layout.y(slot) - layout.y(v)) / s,
            layout.width(slot) / s, layout.height(slot) / s);
    }

    /** Whether the scroll bars are active (scrolled lately, hovered or held): what auto-hiding themes show. */
    public boolean active() {
        return vertical.active || horizontal.active;
    }

    /** Asks for the scroll position to be applied again before the next layout (subclasses' content changed). */
    protected void invalidate() {
        dirty = true;
    }

    /** The scale from panel units to UI pixels. */
    protected final float scale() {
        float s = layout.scale();
        return s > 0 ? s : 1;
    }

    /** The frame's time in seconds (the UI clock). */
    protected final double now() {
        return now;
    }

    /** Whether {@code slot} is the viewport or inside it. */
    protected final boolean inside(int slot) {
        UiStore store = ui.store();
        int v = viewport.slot();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == v) {
                return true;
            }
        }
        return false;
    }

    private void activity() {
        lastActivity = now;
    }

    // ---------------------------------------------------------------- the frame

    private void frame(long nanos) {
        double t = nanos / 1e9;
        double dt = lastTime < 0 || t < lastTime ? 0 : t - lastTime;
        lastTime = t;
        now = t;
        boolean bounce = DataTunables.OVERSCROLL.index() == 1;
        if (scrollX.step(dt, bounce) | scrollY.step(dt, bounce)) {
            dirty = true;
            activity();
        }
        if (held != null && heldPart != THUMB && t >= nextRepeat) {
            nextRepeat = t + DataTunables.REPEAT_INTERVAL.get();
            press(held, heldPart, heldX, heldY);
        }
        beforeLayout(t);
        if (dirty) {
            dirty = false;
            apply();
            vertical.thumb();
            horizontal.thumb();
        }
        boolean busy = scrollX.dragging() || scrollY.dragging() || scrollX.moving() || scrollY.moving() || held != null;
        boolean recent = t - lastActivity < DataTunables.AUTO_HIDE_DELAY.get() || busy;
        vertical.activate(recent);
        horizontal.activate(recent);
    }

    private void apply() {
        appliedX = scrollX.position();
        appliedY = scrollY.position();
        scrolled(appliedX, appliedY);
    }

    private boolean laidOut() {
        float s = scale();
        int v = viewport.slot();
        int r = root.slot();
        float rootW = layout.width(r) / s;
        float top = top();
        float rootH = layout.height(r) / s - top;
        boolean changed = vertical.style() | horizontal.style();
        changed |= afterLayout(); // the content's metrics first (measured rows)
        double cw = contentWidth();
        double ch = contentHeight();
        // decided against the root, not the viewport the bars shrink: no flip-flopping between frames
        float vt = vertical.overlay ? 0 : vertical.thickness;
        float ht = horizontal.overlay ? 0 : horizontal.thickness;
        boolean needV = ch > rootH;
        boolean needH = cw > rootW - (needV ? vt : 0);
        needV = ch > rootH - (needH ? ht : 0);
        boolean showV = vertical.shows(needV);
        boolean showH = horizontal.shows(needH);
        changed |= vertical.visible(showV) | horizontal.visible(showH);
        float vs = showV && !vertical.overlay ? vertical.thickness : 0;
        float hs = showH && !horizontal.overlay ? horizontal.thickness : 0;
        if (vs != vSpace || hs != hSpace || top != topSpace) {
            vSpace = vs;
            hSpace = hs;
            topSpace = top;
            layout.offsets(v, 0, top, -vs, -hs);
            layout.offsets(vertical.bar.slot(), -vertical.thickness, top, 0, -hs);
            layout.offsets(horizontal.bar.slot(), 0, -horizontal.thickness, -vs, 0);
            changed = true;
        }
        long before = scrollX.version() + scrollY.version();
        // the viewport's size after this frame's placement (it may change in the second pass)
        scrollX.extents(cw, Math.max(0, rootW - vs));
        scrollY.extents(ch, Math.max(0, rootH - hs));
        if (scrollX.version() + scrollY.version() != before) {
            dirty = true;
        }
        if (dirty) {
            dirty = false;
            apply();
            changed = true;
        }
        changed |= vertical.thumb() | horizontal.thumb();
        return changed;
    }

    // ---------------------------------------------------------------- input

    private void wheel(UiEvent e) {
        float step = ui.styles().styleOf(root.slot()).getFloat(DataStyles.SCROLL_STEP);
        float dx = e.scrollX();
        float dy = e.scrollY();
        // Shift turns the wheel sideways; so does a view that only scrolls sideways (a horizontal list)
        if (dx == 0 && ((e.modifiers() & Pointer.SHIFT) != 0 || !scrollY.overflows() && scrollX.overflows())) {
            dx = -dy;
            dy = 0;
        }
        double mx = dx * step;
        double my = -dy * step;
        boolean moved = false;
        if (mx != 0 && scrollX.canScroll(mx)) {
            moved = scrollX.scrollBy(mx);
        }
        if (my != 0 && scrollY.canScroll(my)) {
            moved |= scrollY.scrollBy(my);
        }
        if (moved) {
            e.consume(); // at its ends the wheel goes on to an outer view
            activity();
            dirty = true;
        }
    }

    private void command(UiEvent e, UiCommand c) {
        command(e);
        if (e.consumed()) {
            return;
        }
        float step = ui.styles().styleOf(root.slot()).getFloat(DataStyles.SCROLL_STEP);
        double page = scrollY.viewport() * 0.9;
        boolean self = focusedSelf(e.targetSlot());
        boolean moved;
        switch (c) {
            case PAGE_UP -> moved = scrollY.scrollBy(-page);
            case PAGE_DOWN -> moved = scrollY.scrollBy(page);
            case HOME -> moved = self && (scrollY.scrollTo(0) | scrollX.scrollTo(0));
            case END -> moved = self && scrollY.scrollTo(scrollY.range());
            case UP -> moved = self && scrollY.scrollBy(-step);
            case DOWN -> moved = self && scrollY.scrollBy(step);
            case LEFT -> moved = self && scrollX.scrollBy(-step);
            case RIGHT -> moved = self && scrollX.scrollBy(step);
            default -> moved = false;
        }
        if (moved) {
            e.consume();
            activity();
            dirty = true;
        }
    }

    private void press(Bar bar, int part, float x, float y) {
        double along = bar.along(x, y);
        Scroller s = bar.scroller;
        switch (part) {
            case START -> s.scrollBy(-ui.styles().styleOf(root.slot()).getFloat(DataStyles.SCROLL_STEP));
            case END -> s.scrollBy(ui.styles().styleOf(root.slot()).getFloat(DataStyles.SCROLL_STEP));
            case TRACK -> {
                if (along < bar.thumbStart) {
                    s.scrollBy(-s.viewport() * 0.9);
                } else if (along > bar.thumbStart + bar.thumbLength) {
                    s.scrollBy(s.viewport() * 0.9);
                }
            }
            default -> {
            }
        }
        activity();
        dirty = true;
    }

    /** The view's input, one listener for all event types. */
    private final class Events implements UiListener {

        @Override
        public void handle(UiEvent e) {
            switch (e.type()) {
                case WHEEL -> wheel(e);
                case PRESS -> {
                    if (scrollX.moving() || scrollY.moving()) {
                        scrollX.stop(); // a touch on a moving list stops it
                        scrollY.stop();
                        dirty = true;
                    }
                }
                case DRAG_START -> {
                    if (e.pointerKind() == Pointer.Kind.TOUCH && !e.consumed()
                        && (scrollX.overflows() || scrollY.overflows())) {
                        panPointer = e.pointerId();
                        double t = e.time() / 1e9;
                        scrollX.beginDrag(t);
                        scrollY.beginDrag(t);
                        e.consume();
                    }
                }
                case DRAG -> {
                    if (e.pointerId() == panPointer) {
                        float s = scale();
                        double t = e.time() / 1e9;
                        boolean bounce = DataTunables.OVERSCROLL.index() == 1;
                        if (scrollX.overflows()) {
                            scrollX.drag(-e.deltaX() / s, t, bounce);
                        }
                        scrollY.drag(-e.deltaY() / s, t, bounce);
                        activity();
                        dirty = true;
                        e.consume();
                    }
                }
                case RELEASE -> {
                    if (e.pointerId() == panPointer) {
                        panPointer = -1;
                        double t = e.time() / 1e9;
                        scrollX.endDrag(t);
                        scrollY.endDrag(t);
                        dirty = true;
                    }
                }
                case COMMAND -> command(e, e.command());
                case FOCUS_IN -> {
                    int t = e.targetSlot();
                    if (e.focusVisible() && t != root.slot() && inside(t)) {
                        reveal(ui.store().element(t));
                    }
                }
                default -> {
                }
            }
        }
    }

    /** The frame listener. */
    private final class Updater implements Ui.FrameListener {

        @Override
        public void frame(long nanos) {
            ScrollView.this.frame(nanos);
        }

        @Override
        public void afterLayout() {
            laidOut();
        }
    }

    /** One scroll bar: the track, two buttons and the thumb, and the bar's own input. */
    private final class Bar implements UiListener {

        final boolean isVertical;
        final Scroller scroller;
        final Element bar;
        final Element start;
        final Element end;
        final Element thumbElement;
        float thickness;
        boolean overlay;
        ScrollbarVisibility policy = ScrollbarVisibility.AUTO;
        boolean shown = true;
        boolean active;
        float thumbStart = -1;
        float thumbLength = -1;
        float trackStart;
        float trackLength;

        @AllowAllocation("controls are made when a UI is built")
        Bar(boolean isVertical) {
            this.isVertical = isVertical;
            this.scroller = isVertical ? scrollY : scrollX;
            bar = ui.element("scroll-bar", null).id(isVertical ? "vertical" : "horizontal");
            bar.semantics(Role.SCROLL_BAR, null);
            root.add(bar);
            // the bar's length comes from its anchors: a theme sets one thickness for both bars
            ui.codeLayout(bar, Ui.CODE_KIND | Ui.CODE_PLACEMENT | (isVertical ? Ui.CODE_HEIGHT : Ui.CODE_WIDTH));
            int b = bar.slot();
            layout.kind(b, LayoutKind.ANCHORS);
            if (isVertical) {
                layout.anchors(b, 1, 0, 1, 1);
            } else {
                layout.anchors(b, 0, 1, 1, 1);
            }
            start = part("start");
            end = part("end");
            thumbElement = ui.element("scroll-thumb", null);
            bar.add(thumbElement);
            ui.codeLayout(thumbElement, Ui.CODE_PLACEMENT | (isVertical ? Ui.CODE_HEIGHT : Ui.CODE_WIDTH));
            interaction.pickable(thumbElement, PointerEvents.ALL);
            if (isVertical) {
                layout.anchors(start.slot(), 0, 0, 1, 0);
                layout.anchors(end.slot(), 0, 1, 1, 1).growDirection(end.slot(), GrowDirection.END,
                    GrowDirection.BEGIN);
                layout.anchors(thumbElement.slot(), 0, 0, 1, 0);
            } else {
                layout.anchors(start.slot(), 0, 0, 0, 1);
                layout.anchors(end.slot(), 1, 0, 1, 1).growDirection(end.slot(), GrowDirection.BEGIN,
                    GrowDirection.END);
                layout.anchors(thumbElement.slot(), 0, 0, 0, 1);
            }
            interaction.on(bar, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit(), this);
        }

        @AllowAllocation("controls are made when a UI is built")
        private Element part(String id) {
            Element e = ui.element("scroll-button", null).id(id);
            bar.add(e);
            ui.codeLayout(e, Ui.CODE_PLACEMENT);
            interaction.pickable(e, PointerEvents.ALL);
            return e;
        }

        /** Reads the bar's look: thickness, policy, overlay. Returns whether anything changed. */
        boolean style() {
            ResolvedStyle s = ui.styles().styleOf(bar.slot());
            Length l = s.get(isVertical ? Styles.WIDTH : Styles.HEIGHT);
            float t = l.unit() == Length.Unit.PX ? l.value() : 0;
            boolean o = s.get(DataStyles.SCROLLBAR_OVERLAY);
            ScrollbarVisibility p = s.get(DataStyles.SCROLLBAR_VISIBILITY);
            boolean changed = t != thickness || o != overlay || p != policy;
            thickness = t;
            overlay = o;
            policy = p;
            if (changed) {
                vSpace = -1; // place the viewport again
            }
            return changed;
        }

        boolean shows(boolean overflows) {
            return policy == ScrollbarVisibility.ALWAYS || policy == ScrollbarVisibility.AUTO && overflows;
        }

        boolean visible(boolean on) {
            if (on == shown) {
                return false;
            }
            shown = on;
            layout.visible(bar.slot(), on);
            return true;
        }

        void activate(boolean recent) {
            boolean on = recent || bar.is(com.truesim.ui.core.UiState.HOVER);
            if (on != active) {
                active = on;
                ui.styles().facts(bar.slot(), on ? ACTIVE : Facts.NONE);
            }
        }

        /** The pointer's place along the track, in panel units from the bar's start. */
        double along(float x, float y) {
            float s = scale();
            return isVertical ? (y - layout.y(bar.slot())) / s : (x - layout.x(bar.slot())) / s;
        }

        /** Places the thumb for the scroller's position; returns whether it moved. */
        boolean thumb() {
            float s = scale();
            int b = bar.slot();
            float barLength = (isVertical ? layout.height(b) : layout.width(b)) / s;
            float startLength = (isVertical ? layout.height(start.slot()) : layout.width(start.slot())) / s;
            float endLength = (isVertical ? layout.height(end.slot()) : layout.width(end.slot())) / s;
            trackStart = startLength;
            trackLength = Math.max(0, barLength - startLength - endLength);
            double content = scroller.content();
            double range = scroller.range();
            float min = ui.styles().styleOf(thumbElement.slot()).getFloat(DataStyles.THUMB_MIN);
            float length = content > 0 && range > 0 ? (float) (trackLength * scroller.viewport() / content)
                : trackLength;
            length = Math.min(trackLength, Math.max(min, length));
            double position = Math.max(0, Math.min(range, scroller.position()));
            float at = range > 0 ? (float) ((trackLength - length) * position / range) : 0;
            float begin = trackStart + at;
            if (begin == thumbStart && length == thumbLength) {
                return false;
            }
            thumbStart = begin;
            thumbLength = length;
            int t = thumbElement.slot();
            if (isVertical) {
                layout.offsets(t, 0, begin, 0, begin).height(t, length, Unit.PX);
            } else {
                layout.offsets(t, begin, 0, begin, 0).width(t, length, Unit.PX);
            }
            return true;
        }

        @Override
        public void handle(UiEvent e) {
            switch (e.type()) {
                case PRESS -> {
                    if (e.button() != Pointer.PRIMARY) {
                        return;
                    }
                    int target = e.targetSlot();
                    int part = target == thumbElement.slot() ? THUMB
                        : target == start.slot() ? START : target == end.slot() ? END : TRACK;
                    held = this;
                    heldPart = part;
                    heldPointer = e.pointerId();
                    heldX = e.x();
                    heldY = e.y();
                    if (part == THUMB) {
                        grab = (float) (along(e.x(), e.y()) - thumbStart);
                    } else {
                        nextRepeat = now + DataTunables.REPEAT_DELAY.get();
                        press(this, part, e.x(), e.y());
                    }
                    e.consume();
                }
                case MOVE -> {
                    if (held == this && e.pointerId() == heldPointer) {
                        heldX = e.x();
                        heldY = e.y();
                        if (heldPart == THUMB) {
                            double free = trackLength - thumbLength;
                            double at = along(e.x(), e.y()) - grab - trackStart;
                            scroller.scrollTo(free > 0 ? at / free * scroller.range() : 0);
                            activity();
                            dirty = true;
                        }
                    }
                }
                case RELEASE -> {
                    if (held == this && e.pointerId() == heldPointer) {
                        held = null;
                        heldPointer = -1;
                    }
                }
                default -> {
                }
            }
        }
    }

}
