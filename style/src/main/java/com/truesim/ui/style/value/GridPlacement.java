package com.truesim.ui.style.value;

/** Where a grid child goes on one axis: its first track (1-based, 0 for the next free one) and how many it spans. */
public record GridPlacement(int start, int span) {

    /** The next free track, one track wide. */
    public static final GridPlacement AUTO = new GridPlacement(0, 1);
}
