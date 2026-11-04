package com.truesim.ui.props;

import com.truesim.ui.core.bind.Bindable;

/**
 * One edit of a property panel's value (design 5.11, undo hooks): the whole root value before and after, the path of
 * what changed ({@code waypoints[2].position.x}) and the operation ({@code set}, or a collection's {@code add},
 * {@code remove}, {@code move}, {@code duplicate}, {@code clear}). A drag or a slider's continuous change is one edit
 * per gesture. {@link #undo} and {@link #redo} write the root into the panel's holder, where every editor sees it at
 * once; an editor's undo stack merges edits by listening ({@link PropertyPanel#onEdit}). Roots are immutable records
 * (UI20); a mutable root edited in place has the same object before and after, so it cannot be undone this way.
 *
 * @param path the path of the changed value from the root
 * @param operation what was done
 * @param before the root before
 * @param after the root after
 * @param target the holder of the root
 */
public record Edit(String path, String operation, Object before, Object after, Bindable<Object> target) {

    /** Writes {@link #before} back. */
    public void undo() {
        target.set(before);
    }

    /** Writes {@link #after} again. */
    public void redo() {
        target.set(after);
    }

    /** Told of each edit (after it was written). */
    @FunctionalInterface
    public interface Listener {
        /** {@code edit} was made. */
        void edited(Edit edit);
    }
}
