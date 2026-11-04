package com.truesim.ui.controls.data;

/** Rows of one length: offsets by multiplication, O(1). */
public final class FixedExtents implements Extents {

    private int count;
    private double size;

    /** {@code count} rows {@code size} long. */
    public FixedExtents(int count, double size) {
        set(count, size);
    }

    /** Sets the rows; returns whether anything changed. */
    public boolean set(int newCount, double newSize) {
        if (newCount < 0 || !(newSize >= 0)) {
            throw new IllegalArgumentException("rows: " + newCount + " of " + newSize);
        }
        boolean changed = newCount != count || newSize != size;
        count = newCount;
        size = newSize;
        return changed;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public double total() {
        return count * size;
    }

    @Override
    public double offset(int index) {
        return index * size;
    }

    @Override
    public double size(int index) {
        return size;
    }

    @Override
    public int indexAt(double offset) {
        if (count == 0) {
            return -1;
        }
        if (size <= 0 || offset <= 0) {
            return 0;
        }
        double i = Math.floor(offset / size);
        return i >= count ? count - 1 : (int) i;
    }
}
