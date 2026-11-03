package com.truesim.ui.layout;

/**
 * Measures leaf content (text, images) for the layout. Called during {@link Layout#update} for elements marked
 * {@link Layout#measured measured}, only when the element's cache has no result for the same constraints: the cache is
 * cleared when the element is marked {@code DIRTY_LAYOUT} (its content changed). Sizes are in panel units, without
 * padding; the modes are those of Yoga and CSS sizing.
 */
@FunctionalInterface
public interface Measure {

    /** No limit: the size the content wants (max-content). The size argument is 0. */
    int UNDEFINED = 0;
    /** Exactly this size. */
    int EXACTLY = 1;
    /** At most this size (wrap text to it). */
    int AT_MOST = 2;

    /** Measures {@code slot}'s content; returns {@link #size(float, float)}. */
    long measure(int slot, float width, int widthMode, float height, int heightMode);

    /** A size packed into a long, so measuring allocates nothing. */
    static long size(float width, float height) {
        return ((long) Float.floatToRawIntBits(width) << 32) | (Float.floatToRawIntBits(height) & 0xFFFF_FFFFL);
    }

    /** The width of a packed size. */
    static float width(long size) {
        return Float.intBitsToFloat((int) (size >>> 32));
    }

    /** The height of a packed size. */
    static float height(long size) {
        return Float.intBitsToFloat((int) size);
    }
}
