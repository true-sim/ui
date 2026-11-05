package com.truesim.ui.core.draw;

import static com.truesim.ui.core.draw.Primitive.WORDS;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The primitives of one UI layer as data: {@link Primitive}'s 64-byte records packed in an {@code int[]}, plus a table
 * of clip rectangles and a table of transforms. Recorded by the UI, uploaded by the engine's renderer, compared as data
 * by golden tests.
 *
 * <p><b>Ranges.</b> Each element owns a range of the list ({@link #allocate}), recorded in tree order so that later
 * ranges draw over earlier ones. An element records into its range again only when its look changed
 * ({@link #begin}, the primitive calls, {@link #end}); the rest of its range is padded with {@link Primitive#EMPTY}.
 * A range that needs more than its capacity reports it ({@link #end} returns false), and its owner rebuilds the list
 * in tree order, with larger capacities ({@link #reset}).
 *
 * <p><b>Dirty words.</b> Writes compare before they write: only primitives that really change are marked, in a few
 * sorted ranges ({@link #dirtyRanges()}: changes far apart upload apart, close ones merge, see {@link DirtyRanges}),
 * so re-recording an unchanged element dirties nothing, and a frame where nothing changed uploads nothing. Recording
 * allocates nothing once the arrays have grown.
 *
 * <p><b>Transforms.</b> Each primitive names an entry of the transform table ({@link Primitive#TRANSFORM}; design 6.1,
 * "the per-element transform table, one 32-byte entry"): a scale, an offset and an opacity ({@link #TRANSFORM_FLOATS}
 * floats). Entry 0 is the identity. A primitive's coordinates and sizes are recorded in its own space and drawn at
 * {@code scale * (p + clip offset) + offset}, its sizes (radii, border, line and outline widths, shadow blur, glyph
 * pixel range, outline and weight, nine-slice insets) times the scale, its colours' alpha times the opacity. An element
 * whose opacity, scale or drawn offset animates owns an entry its subtree shares, so the animation rewrites that entry
 * ({@link #setTransform}: 32 bytes, in dirty ranges of their own, {@link #transformRanges()}), not the primitives. A
 * clip names a transform too ({@link #setClipTransform}): it cuts where its owner draws, scaled and moved with it.
 *
 * <p><b>Clips and their offsets.</b> Each primitive names a clip of the clip table: a rounded rectangle it is cut to,
 * and an offset added to the primitive's own coordinates before its transform ({@link #setClipOffset}). A scrolled
 * region records its content once, unscrolled, under a clip of its own; scrolling moves that clip's offset instead of
 * recording the content again. Everything that reads positions back applies the offset and the transform:
 * {@link #drawnX}, {@link #drawnY}, {@link #drawnScale}, {@link #drawnOpacity}, {@link #drawnClip}, or reads
 * {@link #baked()}, a copy with both applied (CPU previews, checks, goldens).
 *
 * <p><b>Vertices.</b> {@link Primitive#PATH} and {@link Primitive#COLUMNS} draw points of the list's vertex table ({@link #VERTEX_FLOATS} floats each), in blocks a recorder allocates once
 * ({@link #allocateVertices}) and writes again whenever it records ({@link #pathVertex}, {@link #columnVertex}): writes
 * compare before they write, so unchanged points upload nothing, and a stream appending points uploads theirs alone
 * ({@link #vertexRanges()}). Blocks last until {@link #reset} (watch {@link #generation()}); a recorder needing more
 * takes a larger block (the old one stays unused until the list is recorded again from the start). Primitives sharing
 * vertices (a line and its area) share their clip and transform.
 */
public final class DrawList {

    /**
     * Floats per clip (32 bytes): x0, y0, x1, y1, corner radius (in the space of its transform), the offset x, y added
     * to its primitives' coordinates, then the index of the transform its rectangle is drawn by (a float).
     */
    public static final int CLIP_FLOATS = 8;
    /** The clip that clips nothing, always at index 0. */
    public static final int NO_CLIP = 0;
    /** Floats per transform table entry (32 bytes): scale, offset x, offset y, opacity, then four reserved (0). */
    public static final int TRANSFORM_FLOATS = 8;
    /** The identity transform (scale 1, no offset, opaque), always at index 0. */
    public static final int NO_TRANSFORM = 0;
    /**
     * Floats per vertex of the vertex table (16 bytes): x, y, the path's length up to it and a spare float (paths and
     * areas), or a pixel column's first, last, lowest and highest y (columns).
     */
    public static final int VERTEX_FLOATS = 4;

    private int[] words;
    private int size; // primitives in use: the end of the last range
    private float[] clips;
    private int clipCount;
    private boolean clipsDirty;
    private final DirtyRanges dirty = new DirtyRanges();
    private float[] transforms;
    private int transformCount;
    private final DirtyRanges transformsDirty = new DirtyRanges();
    private float[] vertices;
    private int vertexCount;
    private final DirtyRanges verticesDirty = new DirtyRanges();
    private long version;
    private long generation; // grows with every reset: transforms and clips handed out before are gone

    // the range being recorded
    private int cursor = -1;
    private int rangeEnd;
    private int needed;
    private int clip;
    private int transform;
    private float scale = 1; // a transform baked while recording: x' = scale * x + offsetX
    private float offsetX;
    private float offsetY;
    private final int[] scratch = new int[WORDS];

    /** An empty list. */
    @AllowAllocation("created once per layer")
    public DrawList() {
        words = new int[256 * WORDS];
        clips = new float[16 * CLIP_FLOATS];
        transforms = new float[16 * TRANSFORM_FLOATS];
        vertices = new float[64 * VERTEX_FLOATS];
        reset();
    }

    /** Forgets every range, clip and transform: the list is recorded again from the start, all of it dirty. */
    public void reset() {
        size = 0;
        clipCount = 1;
        Arrays.fill(clips, 0, CLIP_FLOATS, 0);
        clips[0] = -Float.MAX_VALUE;
        clips[1] = -Float.MAX_VALUE;
        clips[2] = Float.MAX_VALUE;
        clips[3] = Float.MAX_VALUE;
        clipsDirty = true;
        dirty.clear();
        transformCount = 1;
        identity(0);
        transformsDirty.clear();
        transformsDirty.add(0, 1);
        vertexCount = 0;
        verticesDirty.clear();
        version++;
        generation++;
    }

    /** Grows with every {@link #reset}: transform entries and clips handed out before it are gone. */
    public long generation() {
        return generation;
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

    /** Starts recording the range at {@code start} of {@code capacity} primitives, unclipped and untransformed. */
    public void begin(int start, int capacity) {
        if (start < 0 || start + capacity > size) {
            throw new IllegalArgumentException("not a range of this list");
        }
        cursor = start;
        rangeEnd = start + capacity;
        needed = 0;
        clip = NO_CLIP;
        transform = NO_TRANSFORM;
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

    /** The transform table entry the next primitives of the range name ({@link #NO_TRANSFORM} after {@link #begin}). */
    public void useTransform(int index) {
        if (index < 0 || index >= transformCount) {
            throw new IllegalArgumentException("no transform " + index);
        }
        this.transform = index;
    }

    /**
     * Bakes a transform into the next primitives of the range as they are recorded (until {@link #begin} starts
     * another): every point maps to {@code scale * p + (offsetX, offsetY)}, and radii, border, line and outline widths,
     * shadow blur, glyph pixel ranges and nine-slice insets scale with it. The UI records into its clip's content space
     * this way (an offset); what animates belongs in the transform table ({@link #useTransform}), not here.
     */
    public void transform(float scale, float offsetX, float offsetY) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** The scale {@link #transform} bakes into what is recorded now (1 after {@link #begin}). */
    public float recordScale() {
        return scale;
    }

    /** The x offset {@link #transform} bakes into what is recorded now. */
    public float recordOffsetX() {
        return offsetX;
    }

    /** The y offset {@link #transform} bakes into what is recorded now. */
    public float recordOffsetY() {
        return offsetY;
    }

    // ---------------------------------------------------------------- the transform table

    /** A new transform table entry, the identity: its index. */
    public int addTransform() {
        if ((transformCount + 1) * TRANSFORM_FLOATS > transforms.length) {
            transforms = grow(transforms, transforms.length * 2);
        }
        int index = transformCount++;
        identity(index);
        transformsDirty.add(index, index + 1);
        version++;
        return index;
    }

    private void identity(int index) {
        int o = index * TRANSFORM_FLOATS;
        Arrays.fill(transforms, o, o + TRANSFORM_FLOATS, 0);
        transforms[o] = 1;
        transforms[o + 3] = 1;
    }

    /**
     * Sets transform entry {@code index}: its primitives draw at {@code scale * p + (x, y)} (their clip's offset added
     * to {@code p} first), their sizes times {@code scale}, their colours' alpha times {@code opacity}. Marks the entry
     * dirty only when it changed.
     */
    public void setTransform(int index, float scale, float x, float y, float opacity) {
        if (index <= NO_TRANSFORM || index >= transformCount) {
            throw new IllegalArgumentException("no transform " + index);
        }
        int o = index * TRANSFORM_FLOATS;
        if (transforms[o] != scale || transforms[o + 1] != x || transforms[o + 2] != y
            || transforms[o + 3] != opacity) {
            transforms[o] = scale;
            transforms[o + 1] = x;
            transforms[o + 2] = y;
            transforms[o + 3] = opacity;
            transformsDirty.add(index, index + 1);
            version++;
        }
    }

    /** The scale of transform entry {@code index}. */
    public float transformScale(int index) {
        return transforms[index * TRANSFORM_FLOATS];
    }

    /** The x offset of transform entry {@code index}. */
    public float transformX(int index) {
        return transforms[index * TRANSFORM_FLOATS + 1];
    }

    /** The y offset of transform entry {@code index}. */
    public float transformY(int index) {
        return transforms[index * TRANSFORM_FLOATS + 2];
    }

    /** The opacity of transform entry {@code index}. */
    public float transformOpacity(int index) {
        return transforms[index * TRANSFORM_FLOATS + 3];
    }

    /** The packed transform table: {@link #transformCount()} entries of {@link #TRANSFORM_FLOATS} floats. */
    public float[] transforms() {
        return transforms;
    }

    /** Transform entries, the identity included. */
    public int transformCount() {
        return transformCount;
    }

    /** The transform entries changed since {@link #clean()}, as ranges of entries: what a renderer uploads. */
    public DirtyRanges transformRanges() {
        return transformsDirty;
    }

    // ---------------------------------------------------------------- the vertex table

    /**
     * A new block of {@code count} vertices at the end of the vertex table, all 0: its first vertex's index. It lasts
     * until {@link #reset} ({@link #generation()} changes).
     */
    public int allocateVertices(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("vertices: " + count);
        }
        int first = vertexCount;
        if ((first + count) * VERTEX_FLOATS > vertices.length) {
            vertices = grow(vertices, Math.max(vertices.length * 2, (first + count) * VERTEX_FLOATS));
        }
        Arrays.fill(vertices, first * VERTEX_FLOATS, (first + count) * VERTEX_FLOATS, 0);
        vertexCount = first + count;
        verticesDirty.add(first, first + count);
        version++;
        return first;
    }

    /**
     * Sets vertex {@code index} of a path: the point {@code x, y} and the path's {@code length} up to it (for
     * dashes; 0 if not dashed). While a range records, its baked transform ({@link #transform}) applies, as to its
     * primitives. NaN coordinates break the path.
     */
    public void pathVertex(int index, float x, float y, float length) {
        if (cursor >= 0) {
            vertex(index, scale * x + offsetX, scale * y + offsetY, scale * length, 0);
        } else {
            vertex(index, x, y, length, 0);
        }
    }

    /**
     * Sets vertex {@code index} of columns: a pixel column's {@code first}, {@code last}, {@code low} and {@code high}
     * sample's y (NaN: no samples). While a range records, its baked transform applies.
     */
    public void columnVertex(int index, float first, float last, float low, float high) {
        if (cursor >= 0) {
            vertex(index, scale * first + offsetY, scale * last + offsetY, scale * low + offsetY,
                scale * high + offsetY);
        } else {
            vertex(index, first, last, low, high);
        }
    }

    private void vertex(int index, float a, float b, float c, float d) {
        if (index < 0 || index >= vertexCount) {
            throw new IllegalArgumentException("no vertex " + index);
        }
        int o = index * VERTEX_FLOATS;
        float[] v = vertices;
        // by bits: NaN equals NaN, so an unchanged break uploads nothing
        if (Float.floatToRawIntBits(v[o]) != Float.floatToRawIntBits(a)
            || Float.floatToRawIntBits(v[o + 1]) != Float.floatToRawIntBits(b)
            || Float.floatToRawIntBits(v[o + 2]) != Float.floatToRawIntBits(c)
            || Float.floatToRawIntBits(v[o + 3]) != Float.floatToRawIntBits(d)) {
            v[o] = a;
            v[o + 1] = b;
            v[o + 2] = c;
            v[o + 3] = d;
            verticesDirty.add(index, index + 1);
            version++;
        }
    }

    /** The packed vertex table: {@link #vertexCount()} vertices of {@link #VERTEX_FLOATS} floats. */
    public float[] vertices() {
        return vertices;
    }

    /** Vertices allocated. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Float {@code component} (0 to 3) of vertex {@code index}. */
    public float vertexAt(int index, int component) {
        return vertices[index * VERTEX_FLOATS + component];
    }

    /** The vertices changed since {@link #clean()}, as ranges of vertices: what a renderer uploads. */
    public DirtyRanges vertexRanges() {
        return verticesDirty;
    }

    // ---------------------------------------------------------------- clips

    /** A new clip rectangle with rounded corners, drawn by the identity transform: its index. */
    public int addClip(float x0, float y0, float x1, float y1, float radius) {
        if ((clipCount + 1) * CLIP_FLOATS > clips.length) {
            clips = grow(clips, clips.length * 2);
        }
        int index = clipCount++;
        int o = index * CLIP_FLOATS;
        clips[o + 5] = 0;
        clips[o + 6] = 0;
        clips[o + 7] = NO_TRANSFORM;
        clips[o] = Float.NaN; // compared unequal: set below
        setClip(index, x0, y0, x1, y1, radius);
        return index;
    }

    /**
     * Sets the offset added to the coordinates of clip {@code index}'s primitives when they are drawn (a scrolled
     * region's scroll); the clip rectangle itself does not move. Marks the clip table dirty only when it changed.
     */
    public void setClipOffset(int index, float x, float y) {
        if (index <= NO_CLIP || index >= clipCount) {
            throw new IllegalArgumentException("no clip " + index);
        }
        int o = index * CLIP_FLOATS;
        if (clips[o + 5] != x || clips[o + 6] != y) {
            clips[o + 5] = x;
            clips[o + 6] = y;
            clipsDirty = true;
            version++;
        }
    }

    /**
     * Makes clip {@code index}'s rectangle and radius drawn by transform entry {@code transform} (its owner's: the clip
     * cuts where its owner draws). Marks the clip table dirty only when it changed.
     */
    public void setClipTransform(int index, int transform) {
        if (index <= NO_CLIP || index >= clipCount) {
            throw new IllegalArgumentException("no clip " + index);
        }
        if (transform < 0 || transform >= transformCount) {
            throw new IllegalArgumentException("no transform " + transform);
        }
        int o = index * CLIP_FLOATS;
        if (clips[o + 7] != transform) {
            clips[o + 7] = transform;
            clipsDirty = true;
            version++;
        }
    }

    /** The transform entry clip {@code index}'s rectangle is drawn by. */
    public int clipTransform(int index) {
        return (int) clips[index * CLIP_FLOATS + 7];
    }

    /** Where clip {@code index} cuts, its transform applied, into {@code out}: x0, y0, x1, y1, radius. */
    public void drawnClip(int index, float[] out) {
        int o = index * CLIP_FLOATS;
        int t = clipTransform(index);
        float s = transformScale(t);
        float tx = transformX(t);
        float ty = transformY(t);
        out[0] = clips[o] * s + tx;
        out[1] = clips[o + 1] * s + ty;
        out[2] = clips[o + 2] * s + tx;
        out[3] = clips[o + 3] * s + ty;
        out[4] = clips[o + 4] * s;
    }

    /** The horizontal offset of clip {@code index}'s primitives ({@link #setClipOffset}). */
    public float clipOffsetX(int index) {
        return clips[index * CLIP_FLOATS + 5];
    }

    /** The vertical offset of clip {@code index}'s primitives ({@link #setClipOffset}). */
    public float clipOffsetY(int index) {
        return clips[index * CLIP_FLOATS + 6];
    }

    /** Moves clip {@code index} (in the space of its transform). */
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

    // ---------------------------------------------------------------- where primitives draw

    /** Where primitive {@code index}'s word {@code word} (an x coordinate) draws: its clip's offset, then transform. */
    public float drawnX(int index, int word) {
        int t = transformOf(index);
        return (floatAt(index, word) + clipOffsetX(Primitive.clip(header(index)))) * transformScale(t) + transformX(t);
    }

    /** Where primitive {@code index}'s word {@code word} (a y coordinate) draws: its clip's offset, then transform. */
    public float drawnY(int index, int word) {
        int t = transformOf(index);
        return (floatAt(index, word) + clipOffsetY(Primitive.clip(header(index)))) * transformScale(t) + transformY(t);
    }

    /** The scale primitive {@code index}'s sizes draw at: its transform's. */
    public float drawnScale(int index) {
        return transformScale(transformOf(index));
    }

    /** The opacity primitive {@code index}'s colours draw at: its transform's. */
    public float drawnOpacity(int index) {
        return transformOpacity(transformOf(index));
    }

    /** The transform table entry primitive {@code index} names. */
    public int transformOf(int index) {
        return words[index * WORDS + Primitive.TRANSFORM];
    }

    // ---------------------------------------------------------------- primitives

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

    /**
     * A filled triangle with corners {@code a, b, c} in {@code color}; {@code edges} ({@link Primitive#EDGE_AB},
     * {@link Primitive#EDGE_BC}, {@link Primitive#EDGE_CA}) names its anti-aliased edges: a shape's outline, not the
     * edges its triangles share.
     */
    public void triangle(float xa, float ya, float xb, float yb, float xc, float yc, long color, int edges) {
        emit(startTriangle(xa, ya, xb, yb, xc, yc, color, edges & 7));
    }

    private int[] startTriangle(float xa, float ya, float xb, float yb, float xc, float yc, long color, int flags) {
        float x0 = Math.min(xa, Math.min(xb, xc));
        float y0 = Math.min(ya, Math.min(yb, yc));
        float x1 = Math.max(xa, Math.max(xb, xc));
        float y1 = Math.max(ya, Math.max(yb, yc));
        int[] p = start(Primitive.TRIANGLE, flags, x0, y0, x1, y1, color, 0);
        p[Primitive.XA] = Float.floatToRawIntBits(scale * xa + offsetX); // the corners move as the box does
        p[Primitive.YA] = Float.floatToRawIntBits(scale * ya + offsetY);
        p[Primitive.XB] = Float.floatToRawIntBits(scale * xb + offsetX);
        p[Primitive.YB] = Float.floatToRawIntBits(scale * yb + offsetY);
        p[Primitive.XC] = Float.floatToRawIntBits(scale * xc + offsetX);
        p[Primitive.YC] = Float.floatToRawIntBits(scale * yc + offsetY);
        return p;
    }

    /**
     * A solid rounded box depth tested against the scene drawn under the UI ({@link Primitive#FLAG_DEPTH}): a 3D
     * handle's dot at view depth {@code depth} (distance along the view's forward axis, scene units), drawn at
     * {@code occludedOpacity} times its colour where the scene is nearer. Otherwise as {@link #box}.
     */
    public void depthBox(float x0, float y0, float x1, float y1, float depth, long fill, float topLeft, float topRight,
        float bottomRight, float bottomLeft, float borderWidth, long borderColor, float occludedOpacity) {
        int[] p = start(Primitive.BOX, Primitive.FLAG_DEPTH, x0, y0, x1, y1, fill, 0);
        p[Primitive.RADII] = Primitive.halves(topLeft, topRight);
        p[Primitive.RADII + 1] = Primitive.halves(bottomRight, bottomLeft);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(borderWidth);
        p[Primitive.BORDER_COLOR] = (int) borderColor;
        p[Primitive.BORDER_COLOR + 1] = (int) (borderColor >>> 32);
        p[Primitive.DEPTH] = Primitive.depthWord(depth, occludedOpacity);
        emit(p);
    }

    /**
     * A line depth tested against the scene drawn under the UI ({@link Primitive#FLAG_DEPTH}): a projected 3D segment
     * whose ends lie at view depths {@code depthA} and {@code depthB}, drawn at {@code occludedOpacity} times its
     * colour where the scene is nearer. Otherwise as {@link #line}.
     */
    public void depthLine(float xa, float ya, float depthA, float xb, float yb, float depthB, float width, long color,
        float occludedOpacity) {
        int[] p = start(Primitive.LINE, Primitive.FLAG_DEPTH, xa, ya, xb, yb, color, 0);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(width);
        p[Primitive.DEPTH] = Primitive.depthWord(depthA, occludedOpacity);
        p[Primitive.DEPTH_B] = Float.floatToRawIntBits(depthB);
        emit(p);
    }

    /**
     * A triangle depth tested against the scene drawn under the UI ({@link Primitive#FLAG_DEPTH}): a projected 3D
     * triangle whose corners lie at view depths {@code depthA}, {@code depthB}, {@code depthC}, drawn at
     * {@code occludedOpacity} times its colour where the scene is nearer. Otherwise as {@link #triangle}.
     */
    public void depthTriangle(float xa, float ya, float depthA, float xb, float yb, float depthB, float xc, float yc,
        float depthC, long color, int edges, float occludedOpacity) {
        int[] p = startTriangle(xa, ya, xb, yb, xc, yc, color, edges & 7 | Primitive.FLAG_DEPTH);
        p[Primitive.DEPTH] = Primitive.depthWord(depthA, occludedOpacity);
        p[Primitive.DEPTH_B] = Primitive.halves(depthB / depthA - 1, depthC / depthA - 1);
        emit(p);
    }

    /**
     * A polyline through vertices {@code first} to {@code first + count - 1} ({@link #pathVertex}), {@code width}
     * pixels wide in {@code color}: {@code stroke} names its joins and caps ({@link Primitive#JOIN_MITER},
     * {@link Primitive#JOIN_BEVEL}, {@link Primitive#JOIN_ROUND}, {@link Primitive#CAP_SQUARE},
     * {@link Primitive#CAP_ROUND}; 0: butt ends, no joins), miters longer than {@code miterLimit} widths are beveled;
     * dashed by {@code dash, gap, dash2, gap2} pixels along the path (all 0: solid; {@code dash2, gap2} 0: repeat the
     * first pair), the pattern starting {@code phase} pixels in. Write the vertices first: its box is theirs.
     */
    public void path(int first, int count, float width, long color, int stroke, float miterLimit, float dash,
        float gap, float dash2, float gap2, float phase) {
        int[] p = start(Primitive.PATH, 0, 0, 0, 0, 0, color, 0);
        boolean miter = (stroke & 3) == Primitive.JOIN_MITER;
        pointBounds(p, first, count, scale * Math.max(width, 1) * 0.5f * (miter ? Math.max(1.5f, miterLimit) : 1.5f)
            + 1);
        p[Primitive.FIRST_VERTEX] = first;
        p[Primitive.VERTEX_COUNT] = count;
        p[Primitive.MITER_LIMIT] = Float.floatToRawIntBits(miterLimit);
        p[Primitive.DASH_PHASE] = Float.floatToRawIntBits(phase);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(width);
        boolean second = dash2 > 0 || gap2 > 0;
        p[Primitive.DASHES] = Primitive.halves(dash, gap);
        p[Primitive.DASHES + 1] = second ? Primitive.halves(dash2, gap2) : Primitive.halves(dash, gap);
        p[Primitive.STROKE] = stroke & 15;
        emit(p);
    }

    /**
     * {@code count} pixel columns from vertex {@code first} on ({@link #columnVertex}), the first centred at
     * {@code x}, the next {@code step} pixels on each (negative: leftwards, an inverted axis's columns in their data's
     * order, which connectors follow): stroked {@code width} pixels wide in {@code color} (the M4
     * envelope's look: a vertical stroke over each column's span, connectors where neighbouring spans do not touch;
     * {@code steps}: connectors as stairs), or, when {@code width} is 0, filled from {@code baseline} to each column's
     * farthest sample. Write the vertices first.
     */
    public void columns(int first, int count, float x, float step, float width, long color, float baseline,
        boolean steps) {
        if (first < 0 || count < 0 || first + count > vertexCount) {
            throw new IllegalArgumentException("vertices " + first + " + " + count + " of " + vertexCount);
        }
        int[] p = start(Primitive.COLUMNS, 0, 0, 0, 0, 0, color, 0);
        boolean fill = !(width > 0);
        float cx = scale * x + offsetX;
        float base = scale * baseline + offsetY;
        float reach = scale * (Math.max(width, 1) * 0.5f + 1);
        float lo = fill ? base : Float.POSITIVE_INFINITY;
        float hi = fill ? base : Float.NEGATIVE_INFINITY;
        for (int i = first * VERTEX_FLOATS, end = (first + count) * VERTEX_FLOATS; i < end; i++) {
            float v = vertices[i];
            if (v < lo) {
                lo = v;
            }
            if (v > hi) {
                hi = v;
            }
        }
        if (!(lo <= hi)) {
            lo = cx;
            hi = cx;
            reach = 0;
        }
        float half = scale * Math.abs(step) * 0.5f;
        float last = cx + scale * step * Math.max(0, count - 1);
        p[Primitive.X0] = Float.floatToRawIntBits(Math.min(cx, last) - half - reach);
        p[Primitive.Y0] = Float.floatToRawIntBits(lo - reach);
        p[Primitive.X1] = Float.floatToRawIntBits(Math.max(cx, last) + half + reach);
        p[Primitive.Y1] = Float.floatToRawIntBits(hi + reach);
        p[Primitive.FIRST_VERTEX] = first;
        p[Primitive.VERTEX_COUNT] = count;
        p[Primitive.BASELINE] = Float.floatToRawIntBits(base);
        p[Primitive.COLUMN_X] = Float.floatToRawIntBits(cx);
        p[Primitive.WIDTH] = Float.floatToRawIntBits(fill ? 0 : width);
        p[Primitive.COLUMN_STEP] = Float.floatToRawIntBits(step);
        p[Primitive.STROKE] = (fill ? Primitive.COLUMNS_FILL : 0) | (steps ? Primitive.COLUMNS_STEPS : 0);
        emit(p);
    }

    /** The box of path vertices {@code first..first + count - 1} (stored transformed) grown by {@code grow}. */
    private void pointBounds(int[] p, int first, int count, float grow) {
        if (first < 0 || count < 0 || first + count > vertexCount) {
            throw new IllegalArgumentException("vertices " + first + " + " + count + " of " + vertexCount);
        }
        float x0 = Float.POSITIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY;
        float y1 = Float.NEGATIVE_INFINITY;
        for (int i = first; i < first + count; i++) {
            float x = vertices[i * VERTEX_FLOATS];
            float y = vertices[i * VERTEX_FLOATS + 1];
            if (x == x && y == y) {
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        float g = grow;
        if (!(x0 <= x1)) {
            x0 = 0;
            y0 = 0;
            x1 = 0;
            y1 = 0;
            g = 0;
        }
        p[Primitive.X0] = Float.floatToRawIntBits(x0 - g);
        p[Primitive.Y0] = Float.floatToRawIntBits(y0 - g);
        p[Primitive.X1] = Float.floatToRawIntBits(x1 + g);
        p[Primitive.Y1] = Float.floatToRawIntBits(y1 + g);
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
        p[Primitive.TRANSFORM] = transform;
        return p;
    }

    private void emit(int[] p) {
        if (scale != 1) {
            scaleSizes(p, scale);
        }
        needed++;
        if (cursor < rangeEnd) {
            put(cursor, p);
        }
        cursor++;
    }

    /** Scales the sizes of primitive {@code p} (not its corners) by {@code scale}. */
    private static void scaleSizes(int[] p, float scale) {
        int kind = Primitive.kind(p[Primitive.HEADER]);
        switch (kind) {
            case Primitive.BOX, Primitive.SHADOW -> {
                p[Primitive.RADII] = scaleHalves(p[Primitive.RADII], scale);
                p[Primitive.RADII + 1] = scaleHalves(p[Primitive.RADII + 1], scale);
                p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH], scale);
            }
            case Primitive.GLYPH -> {
                p[Primitive.PIXEL_RANGE] = scaleFloat(p[Primitive.PIXEL_RANGE], scale);
                p[Primitive.OUTLINE] = scaleFloat(p[Primitive.OUTLINE], scale);
                p[Primitive.WEIGHT] = scaleFloat(p[Primitive.WEIGHT], scale);
            }
            case Primitive.NINE_SLICE -> {
                p[Primitive.INSETS] = scaleHalves(p[Primitive.INSETS], scale);
                p[Primitive.INSETS + 1] = scaleHalves(p[Primitive.INSETS + 1], scale);
            }
            case Primitive.LINE -> p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH], scale);
            case Primitive.PATH -> {
                p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH], scale);
                p[Primitive.DASH_PHASE] = scaleFloat(p[Primitive.DASH_PHASE], scale);
                p[Primitive.DASHES] = scaleHalves(p[Primitive.DASHES], scale);
                p[Primitive.DASHES + 1] = scaleHalves(p[Primitive.DASHES + 1], scale);
            }
            case Primitive.COLUMNS -> {
                p[Primitive.WIDTH] = scaleFloat(p[Primitive.WIDTH], scale);
                p[Primitive.COLUMN_STEP] = scaleFloat(p[Primitive.COLUMN_STEP], scale);
            }
            default -> {
                // images: the quad alone
            }
        }
    }

    private static int scaleFloat(int bits, float scale) {
        return Float.floatToRawIntBits(Float.intBitsToFloat(bits) * scale);
    }

    private static int scaleHalves(int word, float scale) {
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
        dirty.add(from, to);
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

    /** The first primitive changed since {@link #clean()}: the start of the first {@linkplain #dirtyRanges range}. */
    public int dirtyFrom() {
        return dirty.first();
    }

    /**
     * The primitive after the last changed since {@link #clean()} (the end of the last {@linkplain #dirtyRanges range});
     * equal to {@link #dirtyFrom()} if none changed.
     */
    public int dirtyTo() {
        return dirty.end();
    }

    /** The primitives changed since {@link #clean()}, as a few sorted ranges: what a renderer uploads. */
    public DirtyRanges dirtyRanges() {
        return dirty;
    }

    /** Whether the clip table changed since {@link #clean()}. */
    public boolean clipsDirty() {
        return clipsDirty;
    }

    /** Marks everything uploaded: the renderer calls it after copying the dirty primitives, clips and transforms. */
    public void clean() {
        dirty.clear();
        transformsDirty.clear();
        verticesDirty.clear();
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

    /**
     * The view depth depth tested primitive {@code index} ({@link Primitive#FLAG_DEPTH}) has at pixel {@code x, y}
     * (where it draws), as the renderer works it out: a box's one depth; along a line, its ends' depths interpolated at
     * the pixel's projection onto it (clamped to the segment); over a triangle, its corners' by the pixel's barycentric
     * weights (extended past its edges). Under a perspective view the reciprocals are interpolated, under an
     * {@code orthographic} one the depths. For CPU previews and tests; NaN for a primitive not depth tested.
     */
    public float depthAt(int index, float x, float y, boolean orthographic) {
        int header = header(index);
        if ((Primitive.flags(header) & Primitive.FLAG_DEPTH) == 0) {
            return Float.NaN;
        }
        float za = Primitive.depthOf(intAt(index, Primitive.DEPTH));
        int kind = Primitive.kind(header);
        if (kind == Primitive.LINE) {
            float zb = floatAt(index, Primitive.DEPTH_B);
            float ax = drawnX(index, Primitive.X0);
            float ay = drawnY(index, Primitive.Y0);
            float dx = drawnX(index, Primitive.X1) - ax;
            float dy = drawnY(index, Primitive.Y1) - ay;
            float squared = dx * dx + dy * dy;
            float t = squared > 0 ? Math.clamp(((x - ax) * dx + (y - ay) * dy) / squared, 0f, 1f) : 0;
            return interpolated(za, zb, 0, 1 - t, t, 0, orthographic);
        }
        if (kind == Primitive.TRIANGLE) {
            int r = intAt(index, Primitive.DEPTH_B);
            float zb = za * (1 + Float.float16ToFloat((short) r));
            float zc = za * (1 + Float.float16ToFloat((short) (r >>> 16)));
            float ax = drawnX(index, Primitive.XA);
            float ay = drawnY(index, Primitive.YA);
            float bx = drawnX(index, Primitive.XB) - ax;
            float by = drawnY(index, Primitive.YB) - ay;
            float cx = drawnX(index, Primitive.XC) - ax;
            float cy = drawnY(index, Primitive.YC) - ay;
            float area = bx * cy - by * cx;
            if (area == 0) {
                return za;
            }
            float px = x - ax;
            float py = y - ay;
            float wb = (px * cy - py * cx) / area;
            float wc = (bx * py - by * px) / area;
            return interpolated(za, zb, zc, 1 - wb - wc, wb, wc, orthographic);
        }
        return za;
    }

    private static float interpolated(float za, float zb, float zc, float wa, float wb, float wc,
        boolean orthographic) {
        if (orthographic) {
            return wa * za + wb * zb + wc * zc;
        }
        return 1 / (wa / za + wb / zb + (wc == 0 ? 0 : wc / zc));
    }

    /**
     * A copy of this list as it draws, for CPU previews, layout checks and goldens (not for frames): every primitive's
     * clip offset and transform applied to its coordinates, sizes and colours (its transform then the identity, its
     * clip's offset 0), every clip's transform applied to its rectangle and radius. Read it with the plain accessors.
     */
    @AllowAllocation("previews, checks and goldens")
    public DrawList baked() {
        DrawList b = new DrawList();
        b.words = Arrays.copyOf(words, words.length);
        b.size = size;
        b.clips = Arrays.copyOf(clips, clips.length);
        b.clipCount = clipCount;
        b.vertices = Arrays.copyOf(vertices, vertices.length);
        b.vertexCount = vertexCount;
        boolean[] bakedVertex = new boolean[vertexCount]; // vertices two primitives share are baked once
        int[] p = new int[WORDS];
        for (int i = 0; i < size; i++) {
            int kind = Primitive.kind(header(i));
            if (kind == Primitive.EMPTY) {
                continue;
            }
            System.arraycopy(words, i * WORDS, p, 0, WORDS);
            p[Primitive.X0] = Float.floatToRawIntBits(drawnX(i, Primitive.X0));
            p[Primitive.Y0] = Float.floatToRawIntBits(drawnY(i, Primitive.Y0));
            p[Primitive.X1] = Float.floatToRawIntBits(drawnX(i, Primitive.X1));
            p[Primitive.Y1] = Float.floatToRawIntBits(drawnY(i, Primitive.Y1));
            if (kind == Primitive.TRIANGLE) { // the corners draw as the box does
                for (int w = Primitive.XA; w <= Primitive.YC; w += 2) {
                    p[w] = Float.floatToRawIntBits(drawnX(i, w));
                    p[w + 1] = Float.floatToRawIntBits(drawnY(i, w + 1));
                }
            }
            boolean overVertices = kind == Primitive.PATH || kind == Primitive.COLUMNS;
            if (overVertices) {
                bakeVertices(b, i, kind, p, bakedVertex);
            }
            float s = drawnScale(i);
            if (s != 1) {
                scaleSizes(p, s);
            }
            float opacity = drawnOpacity(i);
            if (opacity != 1) {
                fade(p, Primitive.COLOR_A, opacity);
                if (!overVertices && (Primitive.flags(p[Primitive.HEADER]) & Primitive.FLAG_DEPTH) == 0) {
                    fade(p, Primitive.COLOR_B, opacity); // over vertices or depth tested, words 7-8 are not colour B
                }
                if (kind == Primitive.BOX) {
                    fade(p, Primitive.BORDER_COLOR, opacity);
                }
            }
            p[Primitive.TRANSFORM] = NO_TRANSFORM;
            System.arraycopy(p, 0, b.words, i * WORDS, WORDS);
        }
        float[] r = new float[5];
        for (int c = 1; c < clipCount; c++) {
            drawnClip(c, r);
            int o = c * CLIP_FLOATS;
            System.arraycopy(r, 0, b.clips, o, 5);
            b.clips[o + 5] = 0;
            b.clips[o + 6] = 0;
            b.clips[o + 7] = NO_TRANSFORM;
        }
        return b;
    }

    /** Bakes primitive {@code i}'s coordinate words and its vertices (into {@code b}) as it draws. */
    private void bakeVertices(DrawList b, int i, int kind, int[] p, boolean[] done) {
        int t = transformOf(i);
        int clip = Primitive.clip(header(i));
        float s = transformScale(t);
        float ox = clipOffsetX(clip);
        float oy = clipOffsetY(clip);
        float tx = transformX(t);
        float ty = transformY(t);
        if (kind == Primitive.COLUMNS) {
            p[Primitive.BASELINE] = Float.floatToRawIntBits(drawnY(i, Primitive.BASELINE));
            p[Primitive.COLUMN_X] = Float.floatToRawIntBits(drawnX(i, Primitive.COLUMN_X));
        }
        int first = intAt(i, Primitive.FIRST_VERTEX);
        int count = intAt(i, Primitive.VERTEX_COUNT);
        for (int v = first; v < first + count && v < vertexCount; v++) {
            if (done[v]) {
                continue;
            }
            done[v] = true;
            int o = v * VERTEX_FLOATS;
            if (kind == Primitive.COLUMNS) {
                for (int c = 0; c < 4; c++) {
                    b.vertices[o + c] = (vertices[o + c] + oy) * s + ty;
                }
            } else {
                b.vertices[o] = (vertices[o] + ox) * s + tx;
                b.vertices[o + 1] = (vertices[o + 1] + oy) * s + ty;
                b.vertices[o + 2] = vertices[o + 2] * s;
            }
        }
    }

    private static void fade(int[] p, int word, float opacity) {
        long c = p[word] & 0xFFFFFFFFL | (long) p[word + 1] << 32;
        if (c != 0) {
            long f = Rgba.fade(c, opacity);
            p[word] = (int) f;
            p[word + 1] = (int) (f >>> 32);
        }
    }
}
