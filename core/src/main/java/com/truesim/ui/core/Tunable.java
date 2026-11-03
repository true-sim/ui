package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A number the player or developer may tune, declared by the UI (the project's rule: tunables are settings). The UI
 * repository does not know the engine's settings; {@code engine-ui} registers each tunable as an engine setting of the
 * same name, range and description and writes changed values back with {@link #set}. Reading costs a field load.
 */
public final class Tunable {

    private final String name;
    private final float min;
    private final float max;
    private final float initial;
    private final String description;
    private volatile float value;
    private long version;

    private Tunable(String name, float initial, float min, float max, String description) {
        if (!(min <= initial && initial <= max)) {
            throw new IllegalArgumentException(name + ": " + initial + " is not in " + min + " to " + max);
        }
        this.name = Objects.requireNonNull(name, "name");
        this.min = min;
        this.max = max;
        this.initial = initial;
        this.value = initial;
        this.description = description;
    }

    /** A tunable number named {@code name} ({@code ui.scale}), {@code initial} in {@code min..max}. */
    @AllowAllocation("declared once")
    public static Tunable of(String name, float initial, float min, float max, String description) {
        return new Tunable(name, initial, min, max, description);
    }

    /** The current value. */
    public float get() {
        return value;
    }

    /** Sets the value, clamped into its range; returns this. */
    public Tunable set(float newValue) {
        float clamped = newValue < min ? min : (newValue > max ? max : newValue);
        if (clamped != value) {
            value = clamped;
            version++;
        }
        return this;
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
        return name + "=" + value;
    }
}
