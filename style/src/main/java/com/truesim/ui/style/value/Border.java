package com.truesim.ui.style.value;

import java.util.Objects;

/** A border: its width in panel units and its colour. */
public record Border(float width, Color color) {

    /** No border. */
    public static final Border NONE = new Border(0, Color.TRANSPARENT);

    public Border {
        Objects.requireNonNull(color, "color");
    }

    /** A border {@code width} wide in {@code color}. */
    public static Border of(float width, Color color) {
        return new Border(width, color);
    }
}
