package com.truesim.ui.style.value;

import java.util.Objects;

/** A drop shadow: its offset, blur radius and spread in panel units, and its colour. Keys hold lists of them. */
public record Shadow(float x, float y, float blur, float spread, Color color) {

    public Shadow {
        Objects.requireNonNull(color, "color");
    }

    /** A shadow offset by {@code x, y}, blurred by {@code blur}, in {@code color}. */
    public static Shadow of(float x, float y, float blur, Color color) {
        return new Shadow(x, y, blur, 0, color);
    }
}
