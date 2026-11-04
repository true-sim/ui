package com.truesim.ui.profiler;

/** What a lane of a {@link Recording} holds. */
public enum LaneKind {
    /** The zones of one CPU thread, nested by depth. */
    THREAD,
    /** The passes of one GPU queue (depth 0: the frame on that queue, 1: its passes), on the CPU's clock. */
    GPU,
    /** Events of the runtime (garbage collections, safepoints, compilations): marks on the timeline. */
    MARKERS;

    private static final LaneKind[] ALL = values();

    /** The kind of ordinal {@code ordinal}. */
    public static LaneKind of(int ordinal) {
        return ALL[ordinal];
    }
}
