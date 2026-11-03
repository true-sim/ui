package com.truesim.ui.core;

/**
 * An element's state bits: what the pseudo-classes of styles ({@code :hover}, {@code :pressed}, ...) select. A small
 * bit set, so the resolved variants of a style are few and computed once.
 */
public final class UiState {

    public static final int HOVER = 1;
    public static final int PRESSED = 1 << 1;
    public static final int FOCUSED = 1 << 2;
    public static final int DISABLED = 1 << 3;
    public static final int SELECTED = 1 << 4;
    public static final int CHECKED = 1 << 5;
    public static final int EXPANDED = 1 << 6;
    public static final int EDITING = 1 << 7;

    private static final String[] NAMES = {"hover", "pressed", "focused", "disabled", "selected", "checked",
        "expanded", "editing"};

    private UiState() {
    }

    /** The bit of a pseudo-class name ({@code "hover"}), or 0 if there is none of that name. */
    public static int of(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return 1 << i;
            }
        }
        return 0;
    }

    /** The pseudo-class name of a single bit. */
    public static String name(int bit) {
        int index = Integer.numberOfTrailingZeros(bit);
        if (Integer.bitCount(bit) != 1 || index >= NAMES.length) {
            throw new IllegalArgumentException("not a state bit: " + bit);
        }
        return NAMES[index];
    }

    /** How many state bits exist. */
    public static int count() {
        return NAMES.length;
    }
}
