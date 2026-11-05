package com.truesim.ui.a11y.windows;

import com.truesim.ui.core.Role;

/** UI Automation's vocabulary for the UI's roles: control type ids, and a localized type where the id is Custom. */
final class ControlTypes {

    static final int BUTTON = 50000;
    static final int CHECK_BOX = 50002;
    static final int COMBO_BOX = 50003;
    static final int EDIT = 50004;
    static final int HYPERLINK = 50005;
    static final int IMAGE = 50006;
    static final int LIST_ITEM = 50007;
    static final int LIST = 50008;
    static final int MENU = 50009;
    static final int MENU_BAR = 50010;
    static final int MENU_ITEM = 50011;
    static final int PROGRESS_BAR = 50012;
    static final int RADIO_BUTTON = 50013;
    static final int SCROLL_BAR = 50014;
    static final int SLIDER = 50015;
    static final int SPINNER = 50016;
    static final int STATUS_BAR = 50017;
    static final int TAB = 50018;
    static final int TAB_ITEM = 50019;
    static final int TEXT = 50020;
    static final int TOOL_BAR = 50021;
    static final int TOOL_TIP = 50022;
    static final int TREE = 50023;
    static final int TREE_ITEM = 50024;
    static final int CUSTOM = 50025;
    static final int GROUP = 50026;
    static final int DATA_ITEM = 50029;
    static final int WINDOW = 50032;
    static final int PANE = 50033;
    static final int HEADER_ITEM = 50035;
    static final int TABLE = 50036;
    static final int SEPARATOR = 50038;

    private ControlTypes() {
    }

    /** The control type of {@code role}. Toggle buttons and switches are buttons with the Toggle pattern. */
    static int of(Role role) {
        return switch (role) {
            case NONE, CELL -> CUSTOM;
            case GROUP -> GROUP;
            case TEXT, ALERT -> TEXT;
            case IMAGE -> IMAGE;
            case BUTTON, TOGGLE_BUTTON, SWITCH -> BUTTON;
            case CHECKBOX -> CHECK_BOX;
            case SLIDER -> SLIDER;
            case TEXT_FIELD -> EDIT;
            case LIST -> LIST;
            case LIST_ITEM -> LIST_ITEM;
            case PROGRESS -> PROGRESS_BAR;
            case DIALOG, WINDOW -> WINDOW;
            case RADIO -> RADIO_BUTTON;
            case LINK -> HYPERLINK;
            case COMBO_BOX -> COMBO_BOX;
            case SPIN_BUTTON -> SPINNER;
            case SCROLL_BAR -> SCROLL_BAR;
            case SCROLL_VIEW, TAB_PANEL -> PANE;
            case MENU -> MENU;
            case MENU_BAR -> MENU_BAR;
            case MENU_ITEM -> MENU_ITEM;
            case TAB_LIST -> TAB;
            case TAB -> TAB_ITEM;
            case TREE -> TREE;
            case TREE_ITEM -> TREE_ITEM;
            case TABLE -> TABLE;
            case ROW -> DATA_ITEM;
            case COLUMN_HEADER -> HEADER_ITEM;
            case TOOLTIP -> TOOL_TIP;
            case TOOLBAR -> TOOL_BAR;
            case STATUS -> STATUS_BAR;
            case SEPARATOR -> SEPARATOR;
        };
    }

    /**
     * What a screen reader calls a role UI Automation has no control type for (Custom needs one), or what refines the
     * type (a switch, a toggle button, an alert); null: the type's own.
     */
    static String localized(Role role) {
        return switch (role) {
            case NONE -> "control";
            case CELL -> "cell";
            case SWITCH -> "switch";
            case TOGGLE_BUTTON -> "toggle button";
            case ALERT -> "alert";
            default -> null;
        };
    }
}
