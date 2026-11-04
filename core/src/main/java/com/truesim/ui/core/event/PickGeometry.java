package com.truesim.ui.core.event;

import com.truesim.ui.core.input.Cursor;

/**
 * What picking and focus read of each element, implemented by the UI frame over its layout and resolved styles:
 * rectangles in UI pixels, the corner radii of the shape, whether it clips its descendants, its {@code pointer-events}
 * and {@code cursor}. Read when a layer's hit grid is rebuilt (after a layout or style change) and when focus moves,
 * not per pointer.
 */
public interface PickGeometry {

    /** The element's left edge in UI pixels. */
    float x(int slot);

    /** The element's top edge in UI pixels. */
    float y(int slot);

    /** The element's width in UI pixels. */
    float width(int slot);

    /** The element's height in UI pixels. */
    float height(int slot);

    /** Whether the element takes part in layout: a hidden one and its subtree are never hit nor focused. */
    boolean shown(int slot);

    /** The radius of corner {@code corner} (0 top left, 1 top right, 2 bottom right, 3 bottom left) in UI pixels. */
    float radius(int slot, int corner);

    /** Whether the element clips its descendants to its shape (style key {@code clip}). */
    boolean clips(int slot);

    /** The element's {@code pointer-events}. */
    PointerEvents pointerEvents(int slot);

    /** The pointer's shape over the element ({@code cursor}); {@link Cursor#AUTO} takes the parent's. */
    Cursor cursor(int slot);
}
