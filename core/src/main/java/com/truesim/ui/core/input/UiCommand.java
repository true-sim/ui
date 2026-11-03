package com.truesim.ui.core.input;

/**
 * An abstract UI command (design UI6): what controls react to, whatever device produced it. The engine's {@code ui}
 * action group maps its actions to these ({@code engine-ui}'s {@code UiActions}); tests push them directly. Repeats
 * (a held arrow) arrive as more commands, filtered by the action system.
 */
public enum UiCommand {
    // ui.navigation
    UP, DOWN, LEFT, RIGHT, NEXT, PREVIOUS, PAGE_UP, PAGE_DOWN, HOME, END, ACCEPT, CANCEL, CONTEXT_MENU, MENU,
    // ui.text (only while a text field has the text context)
    SELECT_ALL, COPY, CUT, PASTE, UNDO, REDO, DELETE_BACK, DELETE_FORWARD, DELETE_WORD_BACK, DELETE_WORD_FORWARD,
    WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END, DOC_START, DOC_END, SUBMIT, NEWLINE;

    private static final UiCommand[] ALL = values();

    /** The command of an ordinal, without copying {@code values()} (docs/pitfalls.md). */
    public static UiCommand of(int ordinal) {
        return ALL[ordinal];
    }

    /** How many commands exist. */
    public static int count() {
        return ALL.length;
    }
}
