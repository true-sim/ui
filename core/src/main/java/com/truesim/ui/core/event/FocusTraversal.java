package com.truesim.ui.core.event;

import com.truesim.ui.core.UiStore;

/**
 * A container's own focus navigation (Lemur's {@code FocusTraversal}): a list moving by rows, a grid by cells, a tab
 * strip wrapping around. The containers of the focused element are asked from the nearest up; one without an answer
 * returns {@link UiStore#NONE} and the next is asked, then the default (spatial search, tree order). Explicit
 * neighbours ({@link Interaction#neighbour}) come before all of them.
 */
@FunctionalInterface
public interface FocusTraversal {

    /**
     * The slot to focus from {@code from} (a descendant of {@code container}) in {@code direction}, or
     * {@link UiStore#NONE} to let the containers above decide.
     */
    int relative(int container, int from, FocusDirection direction);
}
