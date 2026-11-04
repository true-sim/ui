package com.truesim.ui.charts;

/**
 * What lies under a point of a chart: a series, which of its items (a bar group's column, a pie's slice), which sample,
 * its data values and where it is drawn. Filled by the chart's queries without allocating.
 */
public final class Hit {

    /** The series hit, or null. */
    public Series series;
    /** The item of the series (0 for single-item series). */
    public int item;
    /** The sample's absolute index. */
    public long index = -1;
    /** The sample's X in data units. */
    public double x;
    /** The sample's Y in data units. */
    public double y;
    /** Where the sample is drawn, in pixels of the chart's content box. */
    public float px;
    /** Where the sample is drawn, in pixels of the chart's content box. */
    public float py;
    /** The distance from the query point in pixels (0 inside a bar or slice). */
    public double distance = Double.POSITIVE_INFINITY;

    /** Whether something was hit. */
    public boolean found() {
        return series != null;
    }

    /** Forgets the hit. */
    public Hit clear() {
        series = null;
        item = 0;
        index = -1;
        x = Double.NaN;
        y = Double.NaN;
        px = Float.NaN;
        py = Float.NaN;
        distance = Double.POSITIVE_INFINITY;
        return this;
    }

    /** Copies {@code other}. */
    public Hit set(Hit other) {
        series = other.series;
        item = other.item;
        index = other.index;
        x = other.x;
        y = other.y;
        px = other.px;
        py = other.py;
        distance = other.distance;
        return this;
    }

    @Override
    public String toString() {
        return series == null ? "Hit[none]" : "Hit[" + series.name() + " item " + item + " #" + index + " (" + x
            + ", " + y + ") at " + px + "," + py + " d=" + distance + "]";
    }
}
