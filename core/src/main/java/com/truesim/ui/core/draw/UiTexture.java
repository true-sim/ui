package com.truesim.ui.core.draw;

import com.truesim.annotations.AllowAllocation;

/**
 * A texture the UI draws from, as pixels in memory: a glyph atlas page, an image, a nine-slice skin. The draw list
 * names it by {@link #id()}; the engine's renderer uploads it to a bindless slot and again, only the changed region,
 * whenever {@link #version()} grows. Written from one thread at a time, the UI's or a job's handing it over at frame
 * start.
 *
 * <p>Several renderers may upload one texture: UIs sharing a text system share its atlas pages (a world panel's UI and
 * the screen's). Each keeps the version it uploaded and asks {@link #regionSince} what changed after it; the region
 * since {@link #clean()} ({@link #dirty()}) is one consumer's, and a second one clearing it would take the other's
 * changes.
 */
public final class UiTexture {

    /** How the pixels are stored. */
    public enum Format {
        /** Four 8-bit channels holding data, sampled as they are: MTSDF atlases. */
        RGBA8_UNORM,
        /** Four 8-bit channels, colours sRGB-encoded, sampled as linear: images. */
        RGBA8_SRGB
    }

    private static final int HISTORY = 256;

    private final int id;
    private final String name;
    private final int width;
    private final int height;
    private final Format format;
    private final byte[] pixels;
    private long version;
    private int dirtyX0;
    private int dirtyY0;
    private int dirtyX1;
    private int dirtyY1;
    // the last HISTORY regions written, by version: the write making version v + 1 at v % HISTORY
    private final int[] history = new int[HISTORY * 4];

    @AllowAllocation("textures are made when loaded")
    UiTexture(int id, String name, int width, int height, Format format) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a texture of " + width + " x " + height);
        }
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.format = format;
        this.pixels = new byte[width * height * 4];
        markDirty(0, 0, width, height);
    }

    /** The id primitives name it by. */
    public int id() {
        return id;
    }

    /** What it is, for debugging. */
    public String name() {
        return name;
    }

    /** Width in texels. */
    public int width() {
        return width;
    }

    /** Height in texels. */
    public int height() {
        return height;
    }

    /** How the pixels are stored. */
    public Format format() {
        return format;
    }

    /** The pixels, rows top to bottom, four bytes each; whoever writes them calls {@link #markDirty} after. */
    public byte[] pixels() {
        return pixels;
    }

    /** Marks a region written: the renderer uploads it. */
    public void markDirty(int x, int y, int w, int h) {
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        if (dirtyX0 >= dirtyX1) {
            dirtyX0 = x0;
            dirtyY0 = y0;
            dirtyX1 = x1;
            dirtyY1 = y1;
        } else {
            dirtyX0 = Math.min(dirtyX0, x0);
            dirtyY0 = Math.min(dirtyY0, y0);
            dirtyX1 = Math.max(dirtyX1, x1);
            dirtyY1 = Math.max(dirtyY1, y1);
        }
        int at = (int) (version % HISTORY) * 4;
        history[at] = x0;
        history[at + 1] = y0;
        history[at + 2] = x1;
        history[at + 3] = y1;
        version++;
    }

    /**
     * The region written after version {@code since} (one a renderer uploaded), as x, y, width, height into {@code out}:
     * 1; 0 when nothing was; -1 when it is too long ago to know (more than the last 256 writes, or a version it never
     * had): the whole texture. Allocates nothing.
     */
    public int regionSince(long since, int[] out) {
        if (since == version) {
            return 0;
        }
        if (since < 0 || since > version || version - since > HISTORY) {
            return -1;
        }
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = 0;
        int y1 = 0;
        for (long v = since; v < version; v++) {
            int h = (int) (v % HISTORY) * 4;
            x0 = Math.min(x0, history[h]);
            y0 = Math.min(y0, history[h + 1]);
            x1 = Math.max(x1, history[h + 2]);
            y1 = Math.max(y1, history[h + 3]);
        }
        out[0] = x0;
        out[1] = y0;
        out[2] = x1 - x0;
        out[3] = y1 - y0;
        return 1;
    }

    /** Grows with every change. */
    public long version() {
        return version;
    }

    /** Whether a region waits to be uploaded. */
    public boolean dirty() {
        return dirtyX0 < dirtyX1;
    }

    /** The region written since {@link #clean()}: left. */
    public int dirtyX() {
        return dirtyX0;
    }

    /** The region's top. */
    public int dirtyY() {
        return dirtyY0;
    }

    /** The region's width, 0 when clean. */
    public int dirtyWidth() {
        return dirtyX1 - dirtyX0;
    }

    /** The region's height, 0 when clean. */
    public int dirtyHeight() {
        return dirtyY1 - dirtyY0;
    }

    /** Marks everything uploaded. */
    public void clean() {
        dirtyX0 = 0;
        dirtyY0 = 0;
        dirtyX1 = 0;
        dirtyY1 = 0;
    }

    @Override
    public String toString() {
        return "UiTexture[" + id + " " + name + " " + width + "x" + height + " " + format + "]";
    }
}
