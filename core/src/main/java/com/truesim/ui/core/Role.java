package com.truesim.ui.core;

/**
 * An element's role in the semantics (accessibility) tree (design UI27): what a screen reader announces it as. The OS
 * bridge comes later; the tree is kept from the start so every control is checked for roles and names.
 */
public enum Role {
    /** Not exposed: layout and decoration only. */
    NONE,
    /** A container of other elements. */
    GROUP,
    /** Static text. */
    TEXT,
    /** An image with a description. */
    IMAGE,
    BUTTON,
    CHECKBOX,
    SLIDER,
    TEXT_FIELD,
    LIST,
    LIST_ITEM,
    PROGRESS,
    DIALOG
}
