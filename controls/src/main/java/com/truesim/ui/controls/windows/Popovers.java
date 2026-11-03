package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.OverlayInput;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.overlay.Placement;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import java.util.Arrays;
import java.util.List;

/**
 * The popovers and hover cards of one UI ({@link Popover}). Hover cards open after the pointer rests on their trigger
 * for {@link #OPEN_DELAY} and use hover intent to stay open while the pointer travels to them: once the pointer leaves
 * the trigger and the card, the card stays while the pointer moves inside the triangle between where it left and the
 * card's near edge (for up to {@link #INTENT_TIME}), and otherwise closes after the grace period {@link #CLOSE_DELAY};
 * a card opened from inside another keeps its parent open, so moving between them never flickers. Keyboard and
 * gamepad open a hover card by visible focus on its trigger, a touch by a tap. Click popovers toggle on their trigger's
 * click (or {@code ACCEPT}), close on a press outside them and their children and on {@code CANCEL}, and take focus
 * when opened from the keyboard, returning it to the trigger when closed.
 */
public final class Popovers implements Ui.FrameListener {

    /** How long the pointer rests on a hover card's trigger before the card opens. */
    public static final Tunable OPEN_DELAY = Tunable.of("ui.hover.openDelay", 0.35f, 0, 5,
        "Seconds the pointer rests on a hover card's trigger before the card opens.");
    /** The grace period before a hover card closes once the pointer left it and its trigger. */
    public static final Tunable CLOSE_DELAY = Tunable.of("ui.hover.closeDelay", 0.25f, 0, 5,
        "Seconds a hover card stays after the pointer left it and its trigger.");
    /** How long moving towards a hover card (inside the safe triangle) keeps it open. */
    public static final Tunable INTENT_TIME = Tunable.of("ui.hover.intentTime", 0.8f, 0, 5,
        "Seconds a hover card stays open while the pointer moves towards it from its trigger.");

    private final Ui ui;
    private final Interaction interaction;
    private final UiStore store;
    private final Layout layout;
    private final Overlays overlays;
    private final UiListener triggerListener = new TriggerListener();
    private final UiListener surfaceListener = new SurfaceListener();
    private Popover[] all = new Popover[8];
    private int count;
    private Popover[] bySurface = new Popover[0];
    private Popover[] byTrigger = new Popover[0];
    private long now;
    private float lastX = Float.NaN;
    private float lastY = Float.NaN;

    /** The popovers of the UI of {@code overlays}; registers a frame hook. */
    @AllowAllocation("one per UI")
    public Popovers(Overlays overlays) {
        this.overlays = overlays;
        this.ui = overlays.ui();
        this.interaction = ui.interaction();
        this.store = ui.store();
        this.layout = ui.layout();
        ui.addFrameListener(this);
    }

    /** The tunables popovers read. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(OPEN_DELAY, CLOSE_DELAY, INTENT_TIME);
    }

    Ui ui() {
        return ui;
    }

    /**
     * A hover card: {@code content} in a {@code hover-card} element, opened by resting the pointer on
     * {@code trigger}. Its semantics are a group.
     */
    @AllowAllocation("popovers are made when a UI is built")
    public Popover hover(Element trigger, Element content) {
        return make(trigger, content, true, null);
    }

    /**
     * A click popover: {@code content} in a {@code popover} element opened by clicking {@code trigger}; a screen
     * reader announces it as a dialog named {@code name}.
     */
    @AllowAllocation("popovers are made when a UI is built")
    public Popover click(Element trigger, Element content, String name) {
        return make(trigger, content, false, name);
    }

    @AllowAllocation("popovers are made when a UI is built")
    private Popover make(Element trigger, Element content, boolean hover, String name) {
        Element surface = ui.element(hover ? "hover-card" : "popover", null);
        surface.add(content);
        surface.semantics(hover ? Role.GROUP : Role.DIALOG, name);
        Overlay overlay = overlays.create(surface, Layers.POPUP).input(OverlayInput.BLOCK_BOUNDS);
        Popover p = new Popover(this, trigger, surface, content, hover, overlay);
        if (count == all.length) {
            all = Arrays.copyOf(all, count * 2);
        }
        p.index = count;
        all[count++] = p;
        int n = store.capacity();
        if (bySurface.length < n) {
            bySurface = Arrays.copyOf(bySurface, n);
            byTrigger = Arrays.copyOf(byTrigger, n);
        }
        bySurface[surface.slot()] = p;
        byTrigger[trigger.slot()] = p;
        int mask = EventType.CLICK.bit() | EventType.FOCUS_IN.bit() | EventType.FOCUS_OUT.bit()
            | EventType.COMMAND.bit() | (hover ? EventType.ENTER.bit() : 0);
        interaction.on(trigger, mask, triggerListener);
        interaction.on(surface, EventType.PRESS_OUTSIDE.bit() | EventType.COMMAND.bit() | EventType.FOCUS_OUT.bit(),
            surfaceListener);
        return p;
    }

    /** How many popovers exist. */
    public int count() {
        return count;
    }

    /** The top-most open popover, or null. */
    public Popover top() {
        Popover top = null;
        for (int i = 0; i < count; i++) {
            Popover p = all[i];
            if (p.isOpen() && (top == null || p.overlay.layer() == top.overlay.layer() && order(p) > order(top))) {
                top = p;
            }
        }
        return top;
    }

    private int order(Popover p) {
        int i = 0;
        for (int s = store.firstChild(p.overlay.host().slot()); s != UiStore.NONE; s = store.nextSibling(s)) {
            if (s == p.surface().slot()) {
                return i;
            }
            i++;
        }
        return -1;
    }

    void open(Popover p) {
        if (p.isOpen()) {
            p.overlay.show(); // to the top
            return;
        }
        p.parent = parentOf(p.trigger());
        p.leftAt = -1;
        p.overlay.show();
        p.listener.opened(p);
    }

    /** The open popover whose surface holds {@code element}, nearest first, or null. */
    private Popover parentOf(Element element) {
        for (int s = element.slot(); s != UiStore.NONE; s = store.parent(s)) {
            Popover owner = s < bySurface.length ? bySurface[s] : null;
            if (owner != null && owner.surface().slot() == s && owner.isOpen()) {
                return owner;
            }
        }
        return null;
    }

    void close(Popover p) {
        if (!p.isOpen()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            if (all[i].parent == p && all[i].isOpen()) {
                close(all[i]);
            }
        }
        boolean focusInside = interaction.focusedSlot(0) != UiStore.NONE
            && within(interaction.focusedSlot(0), p.surface().slot());
        p.overlay.hide();
        p.parent = null;
        p.hoverSince = -1;
        p.leftAt = -1;
        p.focusOpened = false;
        if (focusInside && interaction.focusable(p.trigger())) {
            interaction.focus(p.trigger());
        }
        p.listener.closed(p);
    }

    /** Closes every open popover. */
    public void closeAll() {
        for (int i = 0; i < count; i++) {
            if (all[i].parent == null) {
                close(all[i]);
            }
        }
    }

    private boolean within(int slot, int ancestor) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code slot} is in {@code p}'s surface or in an open popover opened from it. */
    private boolean inside(Popover p, int slot) {
        if (within(slot, p.surface().slot())) {
            return true;
        }
        for (int i = 0; i < count; i++) {
            Popover c = all[i];
            if (c.parent == p && c.isOpen() && inside(c, slot)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ hover intent

    @Override
    public void frame(long nanos) {
        UiInput input = ui.input();
        now = nanos;
        Pointer pointer = null;
        if (input != null) {
            for (int i = 0; i < input.pointerCount(); i++) {
                Pointer p = input.pointerAt(i);
                if (p.kind() != Pointer.Kind.TOUCH) {
                    pointer = p;
                    break;
                }
            }
        }
        int hovered = UiStore.NONE;
        if (pointer != null) {
            Element h = interaction.hovered(pointer.id());
            hovered = h == null ? UiStore.NONE : h.slot();
        }
        for (int i = 0; i < count; i++) {
            Popover p = all[i];
            if (p.isHover() && !p.focusOpened) {
                hoverIntent(p, pointer, hovered);
            }
        }
        if (pointer != null) {
            lastX = pointer.x();
            lastY = pointer.y();
        }
    }

    private void hoverIntent(Popover p, Pointer pointer, int hovered) {
        if (!p.trigger().alive()) {
            close(p);
            return;
        }
        boolean overTrigger = hovered != UiStore.NONE && within(hovered, p.trigger().slot());
        if (!p.isOpen()) {
            if (overTrigger && pointer.buttons() == 0) {
                if (p.hoverSince < 0) {
                    p.hoverSince = now;
                }
                if (now - p.hoverSince >= seconds(OPEN_DELAY.get())) {
                    open(p);
                }
            } else {
                p.hoverSince = -1;
            }
            return;
        }
        if (overTrigger || hovered != UiStore.NONE && inside(p, hovered)) {
            p.leftAt = -1;
            return;
        }
        if (p.leftAt < 0) {
            p.leftAt = now;
            p.leaveX = lastX == lastX ? lastX : (pointer == null ? 0 : pointer.x());
            p.leaveY = lastY == lastY ? lastY : (pointer == null ? 0 : pointer.y());
        }
        boolean towards = pointer != null && now - p.leftAt < seconds(INTENT_TIME.get())
            && inSafeTriangle(p, pointer.x(), pointer.y());
        if (!towards && now - p.leftAt >= seconds(CLOSE_DELAY.get())) {
            close(p);
        }
    }

    /** Whether {@code x, y} lies in the triangle from where the pointer left to the card's near edge. */
    boolean inSafeTriangle(Popover p, float x, float y) {
        int s = p.surface().slot();
        float x0 = layout.x(s);
        float y0 = layout.y(s);
        float x1 = x0 + layout.width(s);
        float y1 = y0 + layout.height(s);
        Placement side = p.overlay.side();
        float bx0;
        float by0;
        float bx1;
        float by1;
        if (side == Placement.TOP) {
            bx0 = x0;
            by0 = y1;
            bx1 = x1;
            by1 = y1;
        } else if (side == Placement.LEFT) {
            bx0 = x1;
            by0 = y0;
            bx1 = x1;
            by1 = y1;
        } else if (side == Placement.RIGHT) {
            bx0 = x0;
            by0 = y0;
            bx1 = x0;
            by1 = y1;
        } else {
            bx0 = x0;
            by0 = y0;
            bx1 = x1;
            by1 = y0;
        }
        return inTriangle(x, y, p.leaveX, p.leaveY, bx0, by0, bx1, by1);
    }

    static boolean inTriangle(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = cross(px, py, ax, ay, bx, by);
        float d2 = cross(px, py, bx, by, cx, cy);
        float d3 = cross(px, py, cx, cy, ax, ay);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    private static float cross(float px, float py, float ax, float ay, float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }

    private static long seconds(float s) {
        return (long) (s * 1e9);
    }

    /** Moves focus to the first focusable element in {@code p}'s surface. */
    private void focusInto(Popover p) {
        Element first = firstFocusable(p.surface().slot());
        if (first != null) {
            interaction.focus(first);
        }
    }

    private Element firstFocusable(int slot) {
        Element e = store.element(slot);
        if (e != null && interaction.focusable(e)) {
            return e;
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            Element found = firstFocusable(c);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ================================================================ events

    /** A trigger's clicks, focus and commands. */
    private final class TriggerListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            Popover p = byTrigger[e.currentSlot()];
            switch (e.type()) {
                case CLICK -> {
                    if (!p.isHover() || e.pointerKind() == Pointer.Kind.TOUCH || e.pointerId() < 0) {
                        boolean keys = e.pointerId() < 0;
                        p.toggle();
                        if (p.isOpen() && keys) {
                            p.focusOpened = p.isHover();
                            focusInto(p);
                        }
                    }
                }
                case FOCUS_IN -> {
                    if (p.isHover() && e.focusVisible() && e.targetSlot() == e.currentSlot() && !p.isOpen()) {
                        p.focusOpened = true;
                        open(p);
                    }
                }
                case FOCUS_OUT -> {
                    Element to = e.related();
                    if (p.isHover() && p.focusOpened && (to == null || !inside(p, to.slot()))) {
                        close(p);
                    }
                }
                case COMMAND -> {
                    if (e.command() == UiCommand.CANCEL && p.isOpen()) {
                        e.consume();
                        close(p);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** A surface's presses outside, its focus leaving and CANCEL inside it. */
    private final class SurfaceListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            Popover p = bySurface[e.currentSlot()];
            if (!p.isOpen()) {
                return;
            }
            switch (e.type()) {
                case PRESS_OUTSIDE -> {
                    Element hit = interaction.hovered(e.pointerId());
                    int h = hit == null ? UiStore.NONE : hit.slot();
                    if (h != UiStore.NONE && (within(h, p.trigger().slot()) || inside(p, h))) {
                        return; // the trigger toggles it; a child keeps it
                    }
                    close(p);
                }
                case COMMAND -> {
                    if (e.command() == UiCommand.CANCEL) {
                        e.consume();
                        close(p);
                    }
                }
                case FOCUS_OUT -> {
                    Element to = e.related();
                    if (p.isHover() && p.focusOpened && (to == null || !inside(p, to.slot())
                        && !within(to.slot(), p.trigger().slot()))) {
                        close(p);
                    }
                }
                default -> {
                }
            }
        }
    }
}
