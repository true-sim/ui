package com.truesim.ui.charts;

/**
 * A column and its {@link MinMaxPyramid}, kept up to date as it changes: appended samples update the pyramid, any other
 * change rebuilds it. Polled once a frame; allocation-free in steady state.
 */
final class Tracked {

    Column column;
    final MinMaxPyramid pyramid = new MinMaxPyramid(64);
    private long seen = Long.MIN_VALUE;
    private long seenStructure = Long.MIN_VALUE;
    private boolean built;
    private int block = -1;

    Tracked(Column column) {
        this.column = column;
    }

    /** Forgets the pyramid: the next poll rebuilds it. */
    void invalidate() {
        built = false;
        seen = Long.MIN_VALUE;
    }

    /** Brings the pyramid up to date; true if the column changed. */
    boolean poll() {
        long v = column.version();
        int b = (int) ChartTunables.PYRAMID_BLOCK.get();
        if (v == seen && b == block && built) {
            return false;
        }
        if (b != block || !built || column.structureVersion() != seenStructure || pyramid.stale()
            || !column.appendedSince(seen, pyramid)) {
            pyramid.rebuild(column, b);
            built = true;
            block = b;
        }
        seen = v;
        seenStructure = column.structureVersion();
        return true;
    }
}
