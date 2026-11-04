package com.truesim.ui.charts;

/** The extent of data in X and Y (data units), grown point by point; what fitting reads. Allocation-free. */
public final class Bounds {

    /** The smallest X (positive infinity when empty). */
    public double minX;
    /** The largest X. */
    public double maxX;
    /** The smallest Y. */
    public double minY;
    /** The largest Y. */
    public double maxY;

    /** Empty bounds. */
    public Bounds() {
        clear();
    }

    /** Empties them. */
    public Bounds clear() {
        minX = Double.POSITIVE_INFINITY;
        maxX = Double.NEGATIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;
        return this;
    }

    /** Takes in X {@code x} (NaN and infinities are left out). */
    public void x(double x) {
        if (x - x == 0) {
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
        }
    }

    /** Takes in Y {@code y} (NaN and infinities are left out). */
    public void y(double y) {
        if (y - y == 0) {
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
    }

    /** Takes in the point {@code x, y}. */
    public void point(double x, double y) {
        x(x);
        y(y);
    }

    /** Whether any X was taken in. */
    public boolean hasX() {
        return minX <= maxX;
    }

    /** Whether any Y was taken in. */
    public boolean hasY() {
        return minY <= maxY;
    }
}
