package com.truesim.ui.controls.data;

/**
 * The rows of a virtualized view along its scroll axis (design 5.6): where row {@code i} starts and how long it is,
 * and which row covers an offset, so scrolling never touches off-screen rows. {@link FixedExtents} answers in O(1);
 * {@link MeasuredExtents} keeps measured and estimated lengths in a Fenwick tree, O(log n) both ways. Offsets are
 * doubles in panel units: a million rows stay exact.
 */
public interface Extents {

    /** How many rows. */
    int count();

    /** The length of all rows. */
    double total();

    /** Where row {@code index} starts ({@code index == count()}: the total). */
    double offset(int index);

    /** Row {@code index}'s length. */
    double size(int index);

    /** The row covering {@code offset}, clamped to the rows; -1 when there are none. */
    int indexAt(double offset);
}
