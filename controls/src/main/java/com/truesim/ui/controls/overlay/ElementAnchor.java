package com.truesim.ui.controls.overlay;

import com.truesim.ui.core.Element;
import com.truesim.ui.layout.Layout;

/**
 * An anchor at an element's laid-out box: a tooltip at its target, a popover at its trigger. Hidden while the element
 * is destroyed, out of the tree or not laid out (hidden).
 */
public final class ElementAnchor implements OverlayAnchor {

    private final Layout layout;
    private Element element;

    /** An anchor at {@code element}'s box in {@code layout}. */
    public ElementAnchor(Layout layout, Element element) {
        this.layout = layout;
        this.element = element;
    }

    /** Anchors at {@code value} instead; returns this. */
    public ElementAnchor element(Element value) {
        element = value;
        return this;
    }

    /** The element anchored to. */
    public Element element() {
        return element;
    }

    @Override
    public boolean bounds(float[] out) {
        Element e = element;
        if (e == null || !e.alive()) {
            return false;
        }
        int slot = e.slot();
        if (!layout.shown(slot) || e.store().rootOf(slot) < 0) {
            return false;
        }
        out[0] = layout.x(slot);
        out[1] = layout.y(slot);
        out[2] = layout.width(slot);
        out[3] = layout.height(slot);
        return true;
    }
}
