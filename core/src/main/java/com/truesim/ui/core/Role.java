package com.truesim.ui.core;

/**
 * An element's role in the semantics (accessibility) tree (design UI27): what a screen reader announces it as, through an OS
 * bridge ({@code ui-a11y}); the tree is kept from the start so every control is checked for roles and names.
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
    DIALOG,
    /** A button that stays on or off. */
    TOGGLE_BUTTON,
    RADIO,
    SWITCH,
    LINK,
    COMBO_BOX,
    SPIN_BUTTON,
    SCROLL_BAR,
    SCROLL_VIEW,
    MENU,
    MENU_BAR,
    MENU_ITEM,
    TAB_LIST,
    TAB,
    TAB_PANEL,
    TREE,
    TREE_ITEM,
    TABLE,
    ROW,
    CELL,
    COLUMN_HEADER,
    TOOLTIP,
    WINDOW,
    TOOLBAR,
    STATUS,
    SEPARATOR,
    ALERT
}
