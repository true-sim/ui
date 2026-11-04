package com.truesim.ui.core.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;

/**
 * A block of screen pixels a {@link ScreenSampler} read: {@link #size()} pixels per side centred on {@link #x()},
 * {@link #y()} (UI pixels), linear light with HDR intensities, row by row from the top. Reused: a sampler writes into
 * it ({@link #set}), so polling allocates nothing.
 */
public final class ScreenSample {

    private final float[] rgb = new float[ScreenSampler.MAX_SIZE * ScreenSampler.MAX_SIZE * 3];
    private float x;
    private float y;
    private int size;
    private long sequence;

    /** An empty sample (size 0). */
    @AllowAllocation("one per eyedropper")
    public ScreenSample() {
        // filled by a sampler
    }

    /**
     * Starts answer {@code number} (increasing; a poll copies only newer ones) for the block of {@code blockSize}
     * pixels centred on {@code px, py}; fill it with {@link #pixel}.
     */
    public void set(long number, float px, float py, int blockSize) {
        if (blockSize < 1 || blockSize > ScreenSampler.MAX_SIZE) {
            throw new IllegalArgumentException("block size " + blockSize);
        }
        sequence = number;
        x = px;
        y = py;
        size = blockSize;
    }

    /** Sets pixel {@code column, row} of the block to linear {@code r, g, b} (HDR: may exceed 1). */
    public void pixel(int column, int row, float r, float g, float b) {
        int i = (row * size + column) * 3;
        rgb[i] = r;
        rgb[i + 1] = g;
        rgb[i + 2] = b;
    }

    /** The answer's number (0: nothing yet). */
    public long sequence() {
        return sequence;
    }

    /** The UI pixel the block is centred on, across. */
    public float x() {
        return x;
    }

    /** The UI pixel the block is centred on, down. */
    public float y() {
        return y;
    }

    /** Pixels per side (0: nothing yet). */
    public int size() {
        return size;
    }

    /** Pixel {@code column, row} as a packed linear colour ({@link Rgba}, alpha 1; HDR intensities kept). */
    public long color(int column, int row) {
        int i = (row * size + column) * 3;
        return Rgba.pack(rgb[i], rgb[i + 1], rgb[i + 2], 1);
    }

    /** The centre pixel: the sampled colour. */
    public long center() {
        return size == 0 ? Rgba.TRANSPARENT : color(size / 2, size / 2);
    }

    /** Copies {@code other} into this. */
    public void copy(ScreenSample other) {
        sequence = other.sequence;
        x = other.x;
        y = other.y;
        size = other.size;
        System.arraycopy(other.rgb, 0, rgb, 0, size * size * 3);
    }
}
