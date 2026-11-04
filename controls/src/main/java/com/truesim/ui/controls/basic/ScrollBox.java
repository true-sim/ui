package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.layout.Layout;

/**
 * The minimal scrolling the pop-ups and tab strips of this package need (the data controls' scroll views are a
 * separate, fuller control): a viewport (the theme gives it {@code layout: none}, a maximum size and {@code clip})
 * holding one content element moved by its offsets. The wheel scrolls by {@code ui.controls.wheelStep};
 * {@link #reveal} brings a child into view. Reads the layout of the last frame; allocates nothing.
 */
final class ScrollBox {

    private final Controls controls;
    final Element viewport;
    final Element content;
    private final boolean horizontal;
    private float scroll;

    @AllowAllocation("controls are built once")
    ScrollBox(Controls controls, Element viewport, Element content, boolean horizontal) {
        this.controls = controls;
        this.viewport = viewport;
        this.content = content;
        this.horizontal = horizontal;
        viewport.add(content);
        apply();
    }

    /** The scroll position in panel units. */
    float scroll() {
        return scroll;
    }

    /** How far it can scroll, in panel units. */
    float range() {
        Layout l = controls.layout();
        int v = viewport.slot();
        int c = content.slot();
        float px = horizontal ? l.width(c) - (l.width(v)) : l.height(c) - l.height(v);
        return Math.max(0, controls.units(px));
    }

    /** Scrolls to {@code position} (clamped); true if it moved. */
    boolean scrollTo(float position) {
        float p = Math.max(0, Math.min(position, range()));
        if (p == scroll) {
            return false;
        }
        scroll = p;
        apply();
        return true;
    }

    /** Scrolls by {@code notches} of the wheel (positive: towards the start, as wheels report up); true if it moved. */
    boolean wheel(float notches) {
        return scrollTo(scroll - notches * Controls.WHEEL_STEP.get());
    }

    /** Scrolls the least needed to show {@code child} (a descendant of the content) whole. */
    void reveal(Element child) {
        Layout l = controls.layout();
        int c = child.slot();
        int v = viewport.slot();
        float start = horizontal ? l.x(c) - l.x(v) : l.y(c) - l.y(v);
        float size = horizontal ? l.width(c) : l.height(c);
        float view = horizontal ? l.width(v) : l.height(v);
        float s = scroll;
        float startUnits = controls.units(start) + s;
        float endUnits = startUnits + controls.units(size);
        float viewUnits = controls.units(view);
        if (startUnits < s) {
            s = startUnits;
        } else if (endUnits > s + viewUnits) {
            s = endUnits - viewUnits;
        }
        scrollTo(s);
    }

    /** Back to the start. */
    void reset() {
        scroll = 0;
        apply();
    }

    private void apply() {
        if (horizontal) {
            controls.ui().visuals().offsets(content.slot(), -scroll, 0, 0, 0);
        } else {
            controls.ui().visuals().offsets(content.slot(), 0, -scroll, 0, 0);
        }
    }
}
