package com.truesim.ui.core.event;

import com.truesim.ui.core.input.Pointer;

/** What a drop does with a drag session's payload, as bits: a source allows some, a target accepts some. */
public final class DragOperation {

    public static final int NONE = 0;
    public static final int MOVE = 1;
    public static final int COPY = 2;
    public static final int LINK = 4;
    public static final int ALL = MOVE | COPY | LINK;

    private DragOperation() {
    }

    /**
     * The one operation of {@code possible} the user prefers: {@link #LINK} or {@link #COPY} while their modifier is
     * held ({@link Pointer#DRAG_LINK}, {@link Pointer#DRAG_COPY}) and possible, else {@link #MOVE}, {@link #COPY},
     * {@link #LINK} in that order; {@link #NONE} if none is possible.
     */
    public static int preferred(int possible, int modifiers) {
        if ((modifiers & Pointer.DRAG_LINK) != 0 && (possible & LINK) != 0) {
            return LINK;
        }
        if ((modifiers & Pointer.DRAG_COPY) != 0 && (possible & COPY) != 0) {
            return COPY;
        }
        if ((possible & MOVE) != 0) {
            return MOVE;
        }
        if ((possible & COPY) != 0) {
            return COPY;
        }
        return possible & LINK;
    }
}
