package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.Versioned;

/**
 * Where the pointer hovers a chart, in data units of that chart's X axis (and Y axis), as a versioned value: charts
 * that share one crosshair show the hovered X in all of them (a vertical line and the nearest points, Grafana's shared
 * crosshair), while the hovered chart also shows the horizontal line and the tooltip.
 */
public final class Crosshair implements Versioned {

    private final ChangeClock clock;
    private Object source;
    private double x;
    private double y;
    private long version;

    /** A crosshair nobody hovers, stamped by {@code clock}. */
    @AllowAllocation("crosshairs are made when charts are built")
    public Crosshair(ChangeClock clock) {
        this.clock = clock;
        this.version = clock.stamp();
    }

    /** Whether a chart is hovered. */
    public boolean active() {
        return source != null;
    }

    /** The hovered chart, or null. */
    public Object source() {
        return source;
    }

    /** The hovered X in data units. */
    public double x() {
        return x;
    }

    /** The hovered Y in data units of the source chart's first Y axis. */
    public double y() {
        return y;
    }

    /** {@code source} is hovered at {@code x, y}. */
    public void set(Object hovered, double newX, double newY) {
        if (hovered == source && Double.compare(newX, x) == 0 && Double.compare(newY, y) == 0) {
            return;
        }
        source = hovered;
        x = newX;
        y = newY;
        version = clock.stamp();
    }

    /** {@code hovered} is no longer hovered (nothing happens when another chart is). */
    public void clear(Object hovered) {
        if (source == hovered && source != null) {
            source = null;
            version = clock.stamp();
        }
    }

    @Override
    public long version() {
        return version;
    }
}
