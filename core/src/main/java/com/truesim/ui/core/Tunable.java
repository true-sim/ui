package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * A number the player or developer may tune, declared by the UI (the project's rule: tunables are settings). The UI
 * repository does not know the engine's settings; {@code engine-ui} registers each tunable as an engine setting of the
 * same name, range and description and writes changed values back with {@link #set}. Reading costs a field load.
 *
 * <p>A tunable is a number in a range, or a {@linkplain #choice choice} among named options: then its value is the
 * option's index ({@link #index()}), {@link #options()} names them, and an engine registers it as a choice setting.
 */
public final class Tunable {

    private final String name;
    private final float min;
    private final float max;
    private final float initial;
    private final String description;
    private final List<String> options;
    private volatile float value;
    private long version;

    private Tunable(String name, float initial, float min, float max, String description, List<String> options) {
        if (!(min <= initial && initial <= max)) {
            throw new IllegalArgumentException(name + ": " + initial + " is not in " + min + " to " + max);
        }
        this.name = Objects.requireNonNull(name, "name");
        this.min = min;
        this.max = max;
        this.initial = initial;
        this.value = initial;
        this.description = description;
        this.options = options;
    }

    /** A tunable number named {@code name} ({@code ui.scale}), {@code initial} in {@code min..max}. */
    @AllowAllocation("declared once")
    public static Tunable of(String name, float initial, float min, float max, String description) {
        return new Tunable(name, initial, min, max, description, List.of());
    }

    /**
     * A choice named {@code name} among {@code options} (at least one, distinct), {@code initial} at first; its value
     * is the chosen option's index.
     */
    @AllowAllocation("declared once")
    public static Tunable choice(String name, List<String> options, String initial, String description) {
        List<String> copy = List.copyOf(options);
        if (copy.isEmpty() || new java.util.HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + ": the options " + options + " are empty or repeat");
        }
        int index = copy.indexOf(initial);
        if (index < 0) {
            throw new IllegalArgumentException(name + ": " + initial + " is not one of " + copy);
        }
        return new Tunable(name, index, 0, copy.size() - 1, description, copy);
    }

    /** The current value. */
    public float get() {
        return value;
    }

    /** Sets the value, clamped into its range (a choice's to the nearest index); returns this. */
    public Tunable set(float newValue) {
        float clamped = newValue < min ? min : (newValue > max ? max : newValue);
        if (!options.isEmpty()) {
            clamped = (int) (clamped + 0.5f);
        }
        if (clamped != value) {
            value = clamped;
            version++;
        }
        return this;
    }

    /** Whether this is a choice among {@link #options()}. */
    public boolean isChoice() {
        return !options.isEmpty();
    }

    /** A choice's options; empty for a number. */
    public List<String> options() {
        return options;
    }

    /** The chosen option's index (the value as a whole number). */
    public int index() {
        return (int) value;
    }

    /** The chosen option's name; for a number, null. */
    public String option() {
        return options.isEmpty() ? null : options.get((int) value);
    }

    /** Chooses {@code option}, one of {@link #options()}; returns this. */
    public Tunable select(String option) {
        int index = options.indexOf(option);
        if (index < 0) {
            throw new IllegalArgumentException(name + ": " + option + " is not one of " + options);
        }
        return set(index);
    }

    /** Grows with every change. */
    public long version() {
        return version;
    }

    public String name() {
        return name;
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }

    public float initial() {
        return initial;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return options.isEmpty() ? name + "=" + value : name + "=" + option();
    }
}
