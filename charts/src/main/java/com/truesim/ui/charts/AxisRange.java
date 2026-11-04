package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.Versioned;

/**
 * The visible range of one or more axes, {@code min} to {@code max} in data units, as a versioned value: axes that
 * share one range object are linked (zooming or panning one moves all of them, in this chart or in others), and a chart
 * notices a change by its version, once a frame, without listeners. The game may set it too (a fixed window, a
 * replay's time).
 */
public final class AxisRange implements Versioned {

    private final ChangeClock clock;
    private double min;
    private double max;
    private long version;

    /** A range from {@code min} to {@code max} (above {@code min}), stamped by {@code clock}. */
    @AllowAllocation("ranges are made when a chart is built")
    public AxisRange(ChangeClock clock, double min, double max) {
        this.clock = clock;
        check(min, max);
        this.min = min;
        this.max = max;
        this.version = clock.stamp();
    }

    private static void check(double min, double max) {
        if (!(max > min) || Double.isInfinite(max - min)) {
            throw new IllegalArgumentException("an axis range needs min < max, both finite: " + min + ".." + max);
        }
    }

    /** The low end. */
    public double min() {
        return min;
    }

    /** The high end. */
    public double max() {
        return max;
    }

    /** {@code max - min}. */
    public double span() {
        return max - min;
    }

    /**
     * Sets the range; returns whether it changed. A range that is not one (empty, reversed, infinite or NaN) is ignored
     * and returns false: zooming to the resolution of doubles stops there.
     */
    public boolean set(double newMin, double newMax) {
        if (!(newMax > newMin) || Double.isInfinite(newMax - newMin)) {
            return false;
        }
        if (newMin == min && newMax == max) {
            return false;
        }
        min = newMin;
        max = newMax;
        version = clock.stamp();
        return true;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "AxisRange[" + min + ", " + max + "]";
    }
}
