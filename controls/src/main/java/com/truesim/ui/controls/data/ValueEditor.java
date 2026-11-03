package com.truesim.ui.controls.data;

import com.truesim.common.state.VersionedValue;
import com.truesim.ui.core.Element;

/**
 * Edits one value in place (design 4.3): a table cell's inline editor, a property panel's field. The container
 * creates the editor's view once, starts {@link #edit} with a {@link VersionedValue} holding the current value, focuses
 * {@link #focusTarget} (so commands and text reach it) and ends with {@link #commit} (ACCEPT or SUBMIT, focus leaving)
 * or {@link #cancel} (CANCEL); on a commit it writes the holder's value into its model. Editing may allocate when it
 * starts and on each edit; it is a user action, not a frame.
 *
 * <p>This is also the hook for editors of other packages: a text field from the text-editing controls becomes a
 * table's text editor by implementing this interface.
 *
 * @param <T> the value type
 */
public interface ValueEditor<T> {

    /** A new editor view. */
    Element create(ViewContext context);

    /** Starts editing {@code value} in {@code view}: show its value; edits go into it. */
    void edit(Element view, VersionedValue<T> value);

    /** Ends editing, keeping the edits: returns false to refuse an invalid value (editing goes on). */
    default boolean commit(Element view) {
        return true;
    }

    /** Ends editing, dropping the edits. */
    default void cancel(Element view) {
    }

    /** The element to focus while editing. */
    default Element focusTarget(Element view) {
        return view;
    }
}
