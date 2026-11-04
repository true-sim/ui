package com.truesim.ui.console;

import java.util.List;

/**
 * One console variable (design 5.10): a setting the console reads and sets by name. The engine implements it over
 * its settings, so values go through the constraint overlay: {@link #set} says why a value is refused (out of range,
 * locked by a preset, unsupported by the hardware) and {@link #availability} why the setting is not available now.
 * Values are text as the console shows and parses them ({@code PHYSICAL}, {@code 1.5}, {@code on}).
 */
public interface ConsoleVariable {

    /** What a variable holds; the console checks words against it before {@link #set}. */
    enum Kind {
        BOOLEAN, INTEGER, DECIMAL, CHOICE, TEXT
    }

    /** The name ({@code render.bloom}). */
    String name();

    /** What it does, for {@code help} and the completion pop-up. */
    String description();

    Kind kind();

    /** The current value as text. */
    String value();

    /** The default value as text. */
    String defaultValue();

    /** The smallest value of a number, or NaN when unbounded. */
    default double min() {
        return Double.NaN;
    }

    /** The largest value of a number, or NaN when unbounded. */
    default double max() {
        return Double.NaN;
    }

    /** A choice's options, as text. */
    default List<String> choices() {
        return List.of();
    }

    /** Null when it can be set now; otherwise why not ({@code locked by the Low preset}). */
    default String availability() {
        return null;
    }

    /**
     * Sets it to {@code value} (already checked against {@link #kind}, a choice's option as declared): null when it
     * was set, else why not (the constraint overlay's reason).
     */
    String set(String value);
}
