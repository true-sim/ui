package com.truesim.ui.controls.data;

import com.truesim.ui.core.Element;

/**
 * Shows values of a type in views (design 4.3): the same renderer draws a list's rows, a table column's cells, a
 * tree's nodes, a combo box's closed view and pop-up items and a property panel's read-only values. Views are
 * elements the renderer builds once per {@link #kind} and binds again and again: virtualized containers recycle them
 * per renderer and kind, so scrolling creates nothing once the visible views exist.
 *
 * <p>Rules: {@link #create} may allocate (it runs while the visible window fills); {@link #bind} must not, in steady
 * state (write text through {@code Ui.text}, which copies into the label's own buffer, and set states); what a view
 * looks like comes from styles on its element types, never from the renderer. Name the value for assistive
 * technology with {@link CellState#name(CharSequence)} (live text: a label's {@code Ui.text}).
 *
 * @param <T> the value type
 */
public interface ValueRenderer<T> {

    /** A kind per value, for recycling mixed views (a folder row and a file row); the default is one kind. */
    default int kind(T value) {
        return 0;
    }

    /** A new view for values of {@code kind}. */
    Element create(int kind, ViewContext context);

    /**
     * Shows {@code value} in {@code view} (a view this renderer created for the value's kind). {@code state} tells the
     * cell's place and states (selected, focused, depth, expanded, editing, ...); its states are already on the cell's
     * element for styles.
     */
    void bind(Element view, T value, CellState state);

    /**
     * What kind of views this renderer makes: renderers answering equal view types make interchangeable views for
     * the same {@link #kind} (a table recycles them across columns: every int column's cells are number labels).
     * The default, the renderer itself, shares views with no other renderer.
     */
    default Object viewType() {
        return this;
    }

    /** The view no longer shows a value: release references, stop effects. */
    default void unbind(Element view) {
    }
}
