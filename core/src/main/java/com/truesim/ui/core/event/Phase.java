package com.truesim.ui.core.event;

/** Where an event is on its route (DOM-like): from the root down, at its target, or back up. */
public enum Phase {
    /** Root to the target's parent: listeners registered for capture. */
    CAPTURE,
    /** At the target: capture listeners first, then the others. */
    TARGET,
    /** The target's parent to the root. */
    BUBBLE
}
