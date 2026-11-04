package com.truesim.ui.charts;

import com.truesim.common.state.RangeVisitor;
import com.truesim.common.state.Versioned;

/**
 * One column of a series' data: values by absolute index from {@link #first()} (inclusive) to {@link #end()}
 * (exclusive). Arrays start at 0; a ring's window moves as it streams (its indices count every sample appended); an
 * implicit column ({@link Columns#index}) has values everywhere. {@link Columns} makes them over {@code common-state}'s
 * versioned arrays and rings, plain arrays, and getters.
 *
 * <p>Charts poll {@link #version()} once a frame. A change other than appending (an entry set, a ring cleared) shows as
 * a new {@link #structureVersion()}, and the chart rebuilds what it derived (min-max pyramids, grids, bins); appended
 * samples are visited by {@link #appendedSince}, and only they are added.
 */
public abstract class Column implements Versioned {

    /** The absolute index of the first value. */
    public abstract long first();

    /** The absolute index after the last value. */
    public abstract long end();

    /** The value at absolute index {@code index} (from {@link #first()} to {@link #end()}). */
    public abstract double get(long index);

    /** How many values there are. */
    public long size() {
        return end() - first();
    }

    /** When the column last changed other than by appending: consumers rebuild what they derived. */
    public long structureVersion() {
        return version();
    }

    /**
     * Visits the absolute indices appended after version {@code since} and returns true, or returns false when that
     * cannot be told (a change other than appending, or too long ago): the consumer rebuilds. Only rings append.
     */
    public boolean appendedSince(long since, RangeVisitor visitor) {
        return false;
    }

    /**
     * The slots values are stored in: a ring's capacity (index {@code i} lives in slot {@code i mod capacity}), an
     * array's length; what a {@link MinMaxPyramid} sizes itself by.
     */
    public long capacity() {
        return size();
    }

    /** For an implicit column ({@code start + index * step}): its start; NaN for stored values. */
    public double uniformStart() {
        return Double.NaN;
    }

    /** For an implicit column: its step; NaN for stored values. */
    public double uniformStep() {
        return Double.NaN;
    }
}
