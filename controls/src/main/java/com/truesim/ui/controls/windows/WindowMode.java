package com.truesim.ui.controls.windows;

/** How a floating window shows: at its own place and size, minimised to its title bar, or filling its host. */
public enum WindowMode {
    NORMAL,
    MINIMISED,
    MAXIMISED;

    private static final WindowMode[] ALL = values();

    /** The mode's name in layout text: {@code normal}, {@code minimised}, {@code maximised}. */
    public String key() {
        return switch (this) {
            case NORMAL -> "normal";
            case MINIMISED -> "minimised";
            default -> "maximised";
        };
    }

    /** The mode named {@code key} in layout text. */
    public static WindowMode ofKey(String key) {
        for (WindowMode m : ALL) {
            if (m.key().equals(key)) {
                return m;
            }
        }
        throw new IllegalArgumentException("no window mode " + key);
    }
}
