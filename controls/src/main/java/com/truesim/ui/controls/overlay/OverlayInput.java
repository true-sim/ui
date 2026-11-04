package com.truesim.ui.controls.overlay;

/** How an overlay takes pointer input. */
public enum OverlayInput {
    /** Only its interactive elements take pointers; everywhere else (inside it too) they fall through. */
    PASS_THROUGH,
    /** Its whole box takes pointers (a surface): nothing under it gets them. */
    BLOCK_BOUNDS,
    /** It takes every pointer on the screen (its layer) through a backdrop under it: nothing below gets them. */
    BLOCK_SCREEN
}
