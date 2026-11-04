package com.truesim.ui.core.event;

/** Whether pointers hit an element: the style key {@code pointer-events}, or {@link Interaction#pickable} in code. */
public enum PointerEvents {
    /** Hit when interactive: it listens to pointer events, is focusable, or code made it pickable. */
    AUTO,
    /** Never hit (its descendants still can): decoration over interactive content. */
    NONE,
    /** Always hit, listening or not: an opaque surface that keeps pointers from what is below it. */
    ALL
}
