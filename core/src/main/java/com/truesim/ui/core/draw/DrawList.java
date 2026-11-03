package com.truesim.ui.core.draw;

import static com.truesim.ui.core.draw.Primitive.WORDS;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The primitives of one UI layer as data: {@link Primitive}'s 64-byte records packed in an {@code int[]}, plus a table
 * of clip rectangles. Recorded by the UI, uploaded by the engine's renderer, compared as data by golden tests.
 *
 * <p><b>Ranges.</b> Each element owns a range of the list ({@link #allocate}), recorded in tree order so that later
 * ranges draw over earlier ones. An element records into its range again only when its look changed
 * ({@link #begin}, the primitive calls, {@link #end}); the rest of its range is padded with {@link Primitive#EMPTY}.
 * A range that needs more than its capacity reports it ({@link #end} returns false), and its owner rebuilds the list
 * in tree order, with larger capacities ({@link #reset}).
 *
 * <p><b>Dirty words.</b> Writes compare before they write: only words that really change widen the dirty range
 * ({@link #dirtyFrom()}, {@link #dirtyTo()}), so re-recording an unchanged element dirties nothing, and a frame where
 * nothing changed uploads nothing. Recording allocates nothing once the arrays have grown.
 */
public final class DrawList {

    /** Floats per clip rectangle: x0, y0, x1, y1, corner radius. */
    public static final int CLIP_FLOATS = 5;
    /** The clip that clips nothing, always at index 0. */
    public static final int NO_CLIP = 0;

    private int[] words;
    private int size; // primitives in use: the end of the last range
    private float[] clips;
    private int clipCount;
    private boolean clipsDirty;
    private int dirtyFrom;
    private int dirtyTo;
    private long version;

    // the range being recorded
    private int cursor = -1;
    private int rangeEnd;
    private int needed;
    private int clip;
    private float scale = 1; // the range's transform: x' = scale * x + offsetX
    private float offsetX;
    private float offsetY;
    private final int[] scratch = new int[WORDS];

    /** An empty list. */
    @AllowAllocation("created once per layer")
    public DrawList() {
        words = new int[256 * WORDS];
        clips = new float[16 * CLIP_FLOATS];
        reset();
    }

    /** Forgets every range and clip: the list is recorded again from the start, all of it dirty. */
    public void reset() {
        size = 0;
        clipCount = 1;
        clips[0] = -Float.MAX_VALUE;
        clips[1] = -Float.MAX_VALUE;
        clips[2] = Float.MAX_VALUE;
        clips[3] = Float.MAX_VALUE;
        clips[4] = 0;
        clipsDirty = true;
        dirtyFrom = 0;
        dirtyTo = 0;
        version++;
    }

    /** A new range of {@code capacity} primitives at the end of the list, all empty: its first primitive's index. */
    public int allocate(int capacity) {
        int start = size;
        ensure(start + capacity);
        Arrays.fill(words, start * WORDS, (start + capacity) * WORDS, 0);
        size = start + capacity;
        markDirty(start, start + capacity);
        return start;
    }

    /** Starts recording the range at {@code start} of {@code capacity} primitives, unclipped. */
    public void begin(int start, int capacity) {
        if (start < 0 || start + capacity > size) {
            throw new IllegalArgumentException("not a range of this list");
        }
        cursor = start;
        rangeEnd = start + capacity;
        needed = 0;
        clip = NO_CLIP;
        scale = 1;
        offsetX = 0;
        offsetY = 0;
    }

    /**
     * Ends the range: pads what was not recorded with {@link Primitive#EMPTY}. False if more primitives were recorded
     * than fitted ({@link #needed()} says how many): the owner rebuilds the list with a larger range.
     */
    public boolean end() {
        boolean fits = cursor <= rangeEnd;
        Arrays.fill(scratch, 0);
        for (int i = cursor; i < rangeEnd; i++) {
            put(i, scratch);
        }
        cursor = -1;
        return fits;
    }

    /** The primitives the last range recorded, also those that did not fit. */
    public int needed() {
        return needed;
    }

    /** The clip index the next primitives of the range use. */
    public void clip(int index) {
        if (index < 0 || index >= clipCount) {
            throw new IllegalArgumentException("no clip " + index);
        }
        this.clip = index;
    }

    /**
     * Transforms the next primitives of the range (until {@link #begin} starts another): every point maps to
     * {@code scale * p + (offsetX, offsetY)}, and radii, border, line and outline widths, shadow blur, glyph pixel
     * ranges and nine-slice insets scale with it. An element's {@code scale} style and animated offsets draw this way
     * (a per-element transform kept in the primitives themselves, so the renderer needs nothing new); clip rectangles
     * are not transformed.
     */
    public void transform(float scale, float offsetX, float offsetY) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** A new clip rectangle with rounded corners: its index. */
    public int addClip(float x0, float y0, float x1, float y1, float radius) {
        if ((clipCount + 1) * CLIP_FLOATS > clips.length) {
            clips = grow(clips, clips.length * 2);
        }
        int index = clipCount++;
        setClip(index, x0, y0, x1, y1, radius);
        return index;
    }

    /** Moves clip {@code index}. */
    public void setClip(int index, float x0, float y0, float x1, float y1, float radius) {
        if (index <= NO_CLIP || index >= clipCount) {
            throw new IllegalArgumentException("no clip " + index);
        }
        int o = index * CLIP_FLOATS;
        if (clips[o] != x0 || clips[o + 1] != y0 || clips[o + 2] != x1 || clips[o + 3] != y1 || clips[o + 4] != radius) {
            clips[o] = x0;
            clips[o + 1] = y0;
            clips[o + 2] = x1;
            clips[o + 3] = y1;
            clips[o + 4] = radius;
            clipsDirty = true;
            version++;
        }
    }

    /** A rounded box: {@code fill}, radii from the top left clockwise, a border of {@code borderWidth} pixels. */
    public void box(float x0, float y0, float x1, float y1, long fill, float topLeft, float topRight, float bottomRight,
        float bottomLeft, float borderWidth, long borderColor) {
        int[] p = start(Primitive.BOX, 0, x0, y0, x1, y1, fill, 0);
        p[Primitive.RADII] = Primitive.halves(topLeft, topRight);
        p[Primitive.RADII + 1] = Primitive.halves(bottomRight, bottomLeft);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(borderWidth);
        p[Primitive.BORDER_COLOR] = (int) borderColor;
        p[Primitive.BORDER_COLOR + 1] = (int) (borderColor >>> 32);
        emit(p);
    }

    /** A rounded box filled with a linear gradient from {@code from} to {@code to} along {@code angle} radians. */
    public void gradientBox(float x0, float y0, float x1, float y1, long from, long to, float angle, float topLeft,
        float topRight, float bottomRight, float bottomLeft, float borderWidth, long borderColor) {
        int[] p = start(Primitive.BOX, Primitive.FLAG_GRADIENT, x0, y0, x1, y1, from, to);
        p[Primitive.RADII] = Primitive.halves(topLeft, topRight);
        p[Primitive.RADII + 1] = Primitive.halves(bottomRight, bottomLeft);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(borderWidth);
        p[Primitive.BORDER_COLOR] = (int) borderColor;
        p[Primitive.BORDER_COLOR + 1] = (int) (borderColor >>> 32);
        p[Primitive.GRADIENT_ANGLE] = Float.floatToRawIntBits(angle);
        emit(p);
    }

    /**
     * The soft shadow of a rounded box at {@code x0..y1} (already offset and spread), blurred by a Gaussian of
     * {@code sigma} pixels: the primitive's quad is the box grown by three sigma.
     */
    public void shadow(float x0, float y0, float x1, float y1, long color, float topLeft, float topRight,
        float bottomRight, float bottomLeft, float sigma) {
        int[] p = start(Primitive.SHADOW, 0, x0, y0, x1, y1, color, 0);
        p[Primitive.RADII] = Primitive.halves(topLeft, topRight);
        p[Primitive.RADII + 1] = Primitive.halves(bottomRight, bottomLeft);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(sigma);
        emit(p);
    }

    /**
     * A glyph: the quad {@code x0..y1} on screen shows atlas region {@code u0..v1} of {@code texture}.
     * {@code pixelRange} is the atlas's distance range in screen pixels at this size; {@code outlineWidth} pixels of
     * {@code outlineColor} around it (0 for none); {@code weight} shifts the edge (positive is bolder, in pixels).
     */
    public void glyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int texture,
        float pixelRange, long color, float outlineWidth, long outlineColor, float weight) {
        int[] p = start(Primitive.GLYPH, 0, x0, y0, x1, y1, color, outlineColor);
        p[Primitive.UV] = Primitive.unorm16(u0, v0);
        p[Primitive.UV + 1] = Primitive.unorm16(u1, v1);
        p[Primitive.TEXTURE] = texture;
        p[Primitive.PIXEL_RANGE] = Float.floatToRawIntBits(pixelRange);
        p[Primitive.OUTLINE] = Float.floatToRawIntBits(outlineWidth);
        p[Primitive.WEIGHT] = Float.floatToRawIntBits(weight);
        emit(p);
    }

    /**
     * A raster glyph ({@link Primitive#FLAG_RASTER}): the quad {@code x0..y1} on screen, on whole pixels, shows
     * coverage region {@code u0..v1} of {@code texture} texel for pixel, in {@code color}.
     */
    public void rasterGlyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
        int texture, long color) {
        int[] p = start(Primitive.GLYPH, Primitive.FLAG_RASTER, x0, y0, x1, y1, color, 0);
        p[Primitive.UV] = Primitive.unorm16(u0, v0);
        p[Primitive.UV + 1] = Primitive.unorm16(u1, v1);
        p[Primitive.TEXTURE] = texture; // pixel range, outline and weight stay 0
        emit(p);
    }

    /**
     * A colour glyph ({@link Primitive#FLAG_COLOR}): the quad {@code x0..y1} on screen shows the colour bitmap region
     * {@code u0..v1} of {@code texture} (an {@code RGBA8_SRGB} page), filtered, multiplied by {@code tint} (white with
     * the text's opacity for an emoji's own colours).
     */
    public void colorGlyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
        int texture, long tint) {
        int[] p = start(Primitive.GLYPH, Primitive.FLAG_COLOR, x0, y0, x1, y1, tint, 0);
        p[Primitive.UV] = Primitive.unorm16(u0, v0);
        p[Primitive.UV + 1] = Primitive.unorm16(u1, v1);
        p[Primitive.TEXTURE] = texture; // pixel range, outline and weight stay 0
        emit(p);
    }

    /** An image: region {@code u0..v1} of {@code texture}, multiplied by {@code tint}. */
    public void image(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int texture,
        long tint) {
        int[] p = start(Primitive.IMAGE, 0, x0, y0, x1, y1, tint, 0);
        p[Primitive.UV] = Primitive.unorm16(u0, v0);
        p[Primitive.UV + 1] = Primitive.unorm16(u1, v1);
        p[Primitive.TEXTURE] = texture;
        emit(p);
    }

    /**
     * A nine-slice image: region {@code u0..v1} of {@code texture}, its borders {@code sourceLeft..sourceBottom} texels
     * wide (0 to 255) kept at {@code left..bottom} pixels on screen, edges and centre stretched.
     */
    public void nineSlice(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int texture,
        long tint, float left, float top, float right, float bottom, int sourceLeft, int sourceTop, int sourceRight,
        int sourceBottom) {
        int[] p = start(Primitive.NINE_SLICE, 0, x0, y0, x1, y1, tint, 0);
        p[Primitive.UV] = Primitive.unorm16(u0, v0);
        p[Primitive.UV + 1] = Primitive.unorm16(u1, v1);
        p[Primitive.TEXTURE] = texture;
        p[Primitive.INSETS] = Primitive.halves(left, top);
        p[Primitive.INSETS + 1] = Primitive.halves(right, bottom);
        p[Primitive.SOURCE_INSETS] = texels(sourceLeft) | texels(sourceTop) << 8 | texels(sourceRight) << 16
            | texels(sourceBottom) << 24;
        emit(p);
    }

    /** An anti-aliased line from {@code (xa, ya)} to {@code (xb, yb)}, {@code width} pixels wide. */
    public void line(float xa, float ya, float xb, float yb, float width, long color) {
        int[] p = start(Primitive.LINE, 0, xa, ya, xb, yb, color, 0);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(width);
        emit(p);
    }

    private static int texels(int v) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException("nine-slice insets are 0 to 255 texels: " + v);
        }
        return v;
    }

    private int[] start(int kind, int flags, float x0, float y0, float x1, float y1, long a, long b) {
        if (cursor < 0) {
            throw new IllegalStateException("record primitives between begin and end");
        }
        int[] p = scratch;
        Arrays.fill(p, 0);
        p[Primitive.HEADER] = Primitive.header(kind, flags, clip);
        p[Primitive.X0] = Float.floatToRawIntBits(scale * x0 + offsetX);
        p[Primitive.Y0] = Float.floatToRawIntBits(scale * y0 + offsetY);
        p[Primitive.X1] = Float.floatToRawIntBits(scale * x1 + offsetX);
        p[Primitive.Y1] = Float.floatToRawIntBits(scale * y1 + offsetY);
        p[Primitive.COLOR_A] = (int) a;
        p[Primitive.COLOR_A + 1] = (int) (a >>> 32);
        p[Primitive.COLOR_B] = (int) b;
        p[Primitive.COLOR_B + 1] = (int) (b >>> 32);
        return p;
    }

    private void emit(int[] p) {
        if (scale != 1) {
            scaleSizes(p);
        }
        needed++;
        if (cursor < rangeEnd) {
            put(cursor, p);
        }
        cursor++;
    }

    /** Scales the sizes of primitive {@code p} (not its corners: {@link #start} moved those) by the transform. */
    private void scaleSizes(int[] p) {
        int kind = Primitive.kind(p[Primitive.HEADER]);
        switch (kind) {
            case Primitive.BOX, Primitive.SHADOW -> {
                p[Primitive.RADII] = scaleHalves(p[Primitive.RADII]);
                p[Primitive.RADII + 1] = scaleHalves(p[Primitive.RADII + 1]);
                p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH]);
            }
            case Primitive.GLYPH -> {
                p[Primitive.PIXEL_RANGE] = scaleFloat(p[Primitive.PIXEL_RANGE]);
                p[Primitive.OUTLINE] = scaleFloat(p[Primitive.OUTLINE]);
                p[Primitive.WEIGHT] = scaleFloat(p[Primitive.WEIGHT]);
            }
            case Primitive.NINE_SLICE -> {
                p[Primitive.INSETS] = scaleHalves(p[Primitive.INSETS]);
                p[Primitive.INSETS + 1] = scaleHalves(p[Primitive.INSETS + 1]);
            }
            case Primitive.LINE -> p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH]);
            default -> {
                // images: the quad alone
            }
        }
    }

    private int scaleFloat(int bits) {
        return Float.floatToRawIntBits(Float.intBitsToFloat(bits) * scale);
    }

    private int scaleHalves(int word) {
        return Primitive.halves(Float.float16ToFloat((short) word) * scale,
            Float.float16ToFloat((short) (word >>> 16)) * scale);
    }

    private void put(int index, int[] p) {
        int o = index * WORDS;
        boolean changed = false;
        for (int i = 0; i < WORDS; i++) {
            if (words[o + i] != p[i]) {
                words[o + i] = p[i];
                changed = true;
            }
        }
        if (changed) {
            markDirty(index, index + 1);
            version++;
        }
    }

    private void markDirty(int from, int to) {
        if (dirtyFrom == dirtyTo) {
            dirtyFrom = from;
            dirtyTo = to;
        } else {
            dirtyFrom = Math.min(dirtyFrom, from);
            dirtyTo = Math.max(dirtyTo, to);
        }
    }

    private void ensure(int primitives) {
        if (primitives * WORDS > words.length) {
            words = grow(words, Math.max(words.length * 2, primitives * WORDS));
        }
    }

    @AllowAllocation("growing, rarely")
    private static int[] grow(int[] array, int length) {
        return Arrays.copyOf(array, length);
    }

    @AllowAllocation("growing, rarely")
    private static float[] grow(float[] array, int length) {
        return Arrays.copyOf(array, length);
    }

    /** The packed primitives: {@link #size()} records of {@link Primitive#WORDS} words. */
    public int[] words() {
        return words;
    }

    /** Primitives in use, empty ones included. */
    public int size() {
        return size;
    }

    /** The packed clip table: {@link #clipCount()} records of {@link #CLIP_FLOATS} floats. */
    public float[] clips() {
        return clips;
    }

    /** Clip rectangles, the unclipped one included. */
    public int clipCount() {
        return clipCount;
    }

    /** The first primitive changed since {@link #clean()}. */
    public int dirtyFrom() {
        return dirtyFrom;
    }

    /** The primitive after the last changed since {@link #clean()}; equal to {@link #dirtyFrom()} if none changed. */
    public int dirtyTo() {
        return dirtyTo;
    }

    /** Whether the clip table changed since {@link #clean()}. */
    public boolean clipsDirty() {
        return clipsDirty;
    }

    /** Marks everything uploaded: the renderer calls it after copying the dirty words and clips. */
    public void clean() {
        dirtyFrom = 0;
        dirtyTo = 0;
        clipsDirty = false;
    }

    /** Grows with every change: a renderer compares it to skip unchanged lists. */
    public long version() {
        return version;
    }

    /** The header word of primitive {@code index}. */
    public int header(int index) {
        return words[index * WORDS];
    }

    /** Float word {@code word} of primitive {@code index}. */
    public float floatAt(int index, int word) {
        return Float.intBitsToFloat(words[index * WORDS + word]);
    }

    /** Int word {@code word} of primitive {@code index}. */
    public int intAt(int index, int word) {
        return words[index * WORDS + word];
    }

    /** Colour words {@code word, word + 1} of primitive {@code index}. */
    public long colorAt(int index, int word) {
        return words[index * WORDS + word] & 0xFFFFFFFFL | (long) words[index * WORDS + word + 1] << 32;
    }
}
