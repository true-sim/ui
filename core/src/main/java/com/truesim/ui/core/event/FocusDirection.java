package com.truesim.ui.core.event;

/** A direction focus moves in: spatial, in tab order, or to the ends of the tab order. */
public enum FocusDirection {
    UP, DOWN, LEFT, RIGHT,
    /** The next in tab order (wraps). */
    NEXT,
    /** The previous in tab order (wraps). */
    PREVIOUS,
    /** The first in tab order. */
    FIRST,
    /** The last in tab order. */
    LAST;

    /** Whether this is a spatial direction (up, down, left, right). */
    public boolean spatial() {
        return ordinal() <= RIGHT.ordinal();
    }
}
