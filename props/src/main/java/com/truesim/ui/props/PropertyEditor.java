package com.truesim.ui.props;

import com.truesim.ui.core.Element;

/**
 * The editor of one property row (design 5.11): its element, made by an {@link EditorFactory} registered by name in
 * {@link Editors}, bound to the row's value through {@link PropertyRow#bind}. Editors are rebuilt when a style picks
 * another one, and dropped with their row's panel.
 */
public interface PropertyEditor {

    /** The editor's element, added to the row after its label. */
    Element element();

    /** Once a frame, in the panel's frame: what the editor polls itself (the default: nothing). */
    default void poll() {
        // bindings are polled by the UI
    }

    /** After each layout pass: what the editor reads from resolved styles (a collection's kind and operations). */
    default void afterLayout() {
        // nothing by default
    }

    /** The editor is dropped: release what it holds beyond its elements (its controls' polling, nested panels). */
    default void dispose() {
        // nothing by default
    }

    /** An editor of {@code element} doing nothing more. */
    @com.truesim.annotations.AllowAllocation("editors are made when a panel is built")
    static PropertyEditor of(Element element) {
        return () -> element;
    }
}
