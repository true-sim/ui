package com.truesim.ui.style.value;

import java.util.Objects;

/**
 * A transition of one style key ({@code property}: a key name, or {@code all}): its duration and delay in seconds and
 * its easing.
 */
public record Transition(String property, float duration, Easing easing, float delay) {

    public Transition {
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(easing, "easing");
    }

    /** A transition of {@code property} over {@code duration} seconds. */
    public static Transition of(String property, float duration, Easing easing) {
        return new Transition(property, duration, easing, 0);
    }
}
