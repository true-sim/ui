package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;

/**
 * Box selection on a free 2D canvas (Dear ImGui 1.91's box select, design 5.6): dragging from the canvas's empty space
 * (a press on the canvas itself, not on a child) draws a {@code selection-box} and selects the items whose rectangles
 * it touches: alone, with Shift added to what was selected when the drag began, with the shortcut modifier toggled.
 * The items are the caller's ({@link Items}: rectangles in the canvas's panel units, and their selected state); the
 * helper sets their state as the box moves, so the canvas shows the result live. Lists, trees and tables box-select
 * by row ranges themselves ({@link VirtualView}); the modes are these constants.
 *
 * <p>The canvas is laid out with anchors (or none): the box is its last child, placed by code. A drag allocates
 * nothing once the snapshot of the selection has grown to the item count.
 *
 * <pre>{@code
 * BoxSelect select = new BoxSelect(ui, canvas, new BoxSelect.Items() { ... });
 * }</pre>
 */
public final class BoxSelect implements UiListener {

    /** The box selects only what it covers. */
    public static final int REPLACE = 0;
    /** The box adds what it covers (Shift). */
    public static final int ADD = 1;
    /** The box flips what it covers (the shortcut modifier). */
    public static final int TOGGLE = 2;

    /** The items on a canvas: rectangles in its panel units, from its top left, and whether each is selected. */
    public interface Items {
        /** How many items. */
        int count();

        float x(int index);

        float y(int index);

        float width(int index);

        float height(int index);

        /** Whether item {@code index} is selected. */
        boolean selected(int index);

        /** Selects or deselects item {@code index}. */
        void selected(int index, boolean on);
    }

    private final Ui ui;
    private final Element canvas;
    private final Items items;
    private final Element box;
    private boolean[] before = new boolean[16];
    private int pointer = -1;
    private int pressPointer = -1;
    private int mode;
    private float startX;
    private float startY;
    private float nowX;
    private float nowY;

    /** Box selection of {@code items} on {@code canvas}. */
    @AllowAllocation("controls are made when a UI is built")
    public BoxSelect(Ui ui, Element canvas, Items items) {
        this.ui = ui;
        this.canvas = canvas;
        this.items = items;
        box = ui.element("selection-box", null);
        canvas.add(box);
        ui.codeLayout(box, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        ui.layout().anchors(box.slot(), 0, 0, 0, 0).visible(box.slot(), false);
        ui.interaction().on(canvas, EventType.PRESS.bit() | EventType.DRAG_START.bit() | EventType.DRAG.bit()
            | EventType.RELEASE.bit(), this);
    }

    /** The mode for pointer modifiers {@code modifiers} ({@link Pointer#SHIFT}, ...). */
    public static int modeOf(int modifiers) {
        if ((modifiers & Pointer.SHIFT) != 0) {
            return ADD;
        }
        return (modifiers & (Pointer.SHORTCUT | Pointer.CTRL | Pointer.META)) != 0 ? TOGGLE : REPLACE;
    }

    /** The box element (style {@code selection-box}). */
    public Element box() {
        return box;
    }

    /** Whether a box is being dragged. */
    public boolean active() {
        return pointer >= 0;
    }

    /** Drops the box being dragged, putting the selection back as it was. */
    public void cancel() {
        if (pointer < 0) {
            return;
        }
        int n = Math.min(items.count(), before.length);
        for (int i = 0; i < n; i++) {
            if (items.selected(i) != before[i]) {
                items.selected(i, before[i]);
            }
        }
        end();
    }

    private void end() {
        pointer = -1;
        ui.layout().visible(box.slot(), false);
    }

    @AllowAllocation("the snapshot grows to the item count, once")
    private void snapshot() {
        int n = items.count();
        if (before.length < n) {
            before = new boolean[Math.max(n, before.length * 2)];
        }
        for (int i = 0; i < n; i++) {
            before[i] = items.selected(i);
        }
    }

    /** Selects by the box from the start to the pointer, and places the box. */
    private void update() {
        float x0 = Math.min(startX, nowX);
        float y0 = Math.min(startY, nowY);
        float x1 = Math.max(startX, nowX);
        float y1 = Math.max(startY, nowY);
        int n = Math.min(items.count(), before.length);
        for (int i = 0; i < n; i++) {
            float x = items.x(i);
            float y = items.y(i);
            boolean in = x <= x1 && x + items.width(i) >= x0 && y <= y1 && y + items.height(i) >= y0;
            boolean want = switch (mode) {
                case REPLACE -> in;
                case ADD -> before[i] || in;
                default -> before[i] ^ in;
            };
            if (want != items.selected(i)) {
                items.selected(i, want);
            }
        }
        Layout l = ui.layout();
        int b = box.slot();
        l.offsets(b, x0, y0, x1, y1).width(b, x1 - x0, Unit.PX).height(b, y1 - y0, Unit.PX);
    }

    private float localX(float x) {
        float s = ui.layout().scale();
        return (x - ui.layout().x(canvas.slot())) / (s > 0 ? s : 1);
    }

    private float localY(float y) {
        float s = ui.layout().scale();
        return (y - ui.layout().y(canvas.slot())) / (s > 0 ? s : 1);
    }

    @Override
    public void handle(UiEvent e) {
        switch (e.type()) {
            case PRESS -> {
                boolean empty = e.targetSlot() == canvas.slot() && e.button() == Pointer.PRIMARY
                    && e.pointerKind() != Pointer.Kind.TOUCH;
                pressPointer = empty ? e.pointerId() : -1;
                startX = localX(e.x());
                startY = localY(e.y());
            }
            case DRAG_START -> {
                if (e.pointerId() != pressPointer || e.consumed() || pointer >= 0) {
                    return;
                }
                pointer = e.pointerId();
                mode = modeOf(e.modifiers());
                snapshot();
                nowX = localX(e.x());
                nowY = localY(e.y());
                ui.layout().visible(box.slot(), true);
                update();
                e.consume();
            }
            case DRAG -> {
                if (e.pointerId() == pointer) {
                    nowX = localX(e.x());
                    nowY = localY(e.y());
                    update();
                    e.consume();
                }
            }
            case RELEASE -> {
                if (e.pointerId() == pointer) {
                    nowX = localX(e.x());
                    nowY = localY(e.y());
                    update();
                    end();
                } else if (e.pointerId() == pressPointer && modeOf(e.modifiers()) == REPLACE) {
                    // a click on empty space selects nothing
                    for (int i = 0; i < items.count(); i++) {
                        if (items.selected(i)) {
                            items.selected(i, false);
                        }
                    }
                }
                pressPointer = -1;
            }
            default -> {
            }
        }
    }

    /** The selection when the drag began, item {@code index} (for tests). */
    boolean before(int index) {
        return index < before.length && before[index];
    }
}
