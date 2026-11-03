package com.truesim.ui.style.value;

/** Space around content (padding, margin, offsets), in panel units, in CSS order: top, right, bottom, left. */
public record Insets(float top, float right, float bottom, float left) {

    /** No space. */
    public static final Insets NONE = new Insets(0, 0, 0, 0);

    /** The same on every side. */
    public static Insets of(float all) {
        return new Insets(all, all, all, all);
    }

    /** {@code vertical} at the top and bottom, {@code horizontal} at the left and right. */
    public static Insets of(float vertical, float horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }

    /** Each side. */
    public static Insets of(float top, float right, float bottom, float left) {
        return new Insets(top, right, bottom, left);
    }
}
