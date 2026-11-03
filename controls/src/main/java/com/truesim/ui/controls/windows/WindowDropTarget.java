package com.truesim.ui.controls.windows;

/**
 * Something a floating window can be dropped on while dragged by its title bar: a dock space docks it
 * ({@link WindowManager#addDropTarget}). Positions are UI pixels; {@code modifiers} are the pointer's
 * ({@code Pointer.SHIFT}...).
 */
public interface WindowDropTarget {

    /** {@code window} is dragged with the pointer at {@code x, y}: show a drop preview if it would be taken here. */
    void windowDragged(FloatingWindow window, float x, float y, int modifiers);

    /**
     * The drag of {@code window} ended at {@code x, y}: take it (dock it) and return true, or return false; in both
     * cases hide any preview. A window another target took is no longer open.
     */
    boolean windowDropped(FloatingWindow window, float x, float y, int modifiers);
}
