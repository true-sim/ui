package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.Lane;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MTSDF glyphs, and hinted raster glyphs for small text, on atlas pages ({@link UiTexture}s in
 * {@link UiTexture.Format#RGBA8_UNORM}), generated on demand.
 *
 * <p><b>Flow.</b> {@link #entry} finds a glyph or queues it (allocation-free when known). Once per frame, at its start,
 * {@link #beginFrame()} hands over glyphs that finished generating (copies them into their page and marks the region
 * dirty, so the engine uploads it) and starts up to {@link AtlasSettings#glyphsPerFrame()} queued glyphs: their
 * outline is read and their place on a page reserved here, in queue order, then a job on the given {@link Lane}
 * generates the bitmap. A glyph not yet ready is drawn as nothing (a zero-sized quad) and appears the frame after its
 * job finished; a frame never waits for one.
 *
 * <p><b>Determinism.</b> Places are reserved in request order and every bitmap comes from the same
 * {@link MsdfGenerator} and {@link MsdfGeometry}, so the same glyph requests give byte-identical pages whichever thread
 * finished first, and whether the glyphs came from a {@link GlyphCache} baked at build time or were generated now.
 *
 * <p><b>Raster glyphs</b> (UI16, {@link AtlasSettings#smallText()}): {@link #rasterEntry} keys a glyph by face, glyph,
 * pixel size (in 1/64 pixel, FreeType's 26.6) and horizontal phase: the pen's position within its pixel in
 * {@link #RASTER_PHASES}ths, so a glyph drawn on whole pixels still sits where shaping put it (to a quarter pixel) and
 * spacing stays even. Its place is reserved from the outline's bounds at that size and phase, rounded out to whole
 * pixels plus {@link #RASTER_PAD} pixels each side for hinting's moves, so places stay in request order; the job
 * renders the {@link GlyphRasterizer}'s coverage into that box (all four channels: a texel's alpha is the coverage).
 * They share the pages, the lane, the per-frame budget and the placeholder rule with MTSDF glyphs, and
 * are not put in the {@link GlyphCache} (FreeType renders one in microseconds).
 *
 * <p><b>Colour glyphs</b> (emoji, {@link #colorEntry}): a face with colour bitmaps ({@link FontFace#colorStrike()})
 * draws them from colour pages of their own ({@link UiTexture.Format#RGBA8_SRGB}, straight alpha), keyed by face,
 * glyph and level: the bitmap halved that many times ({@link ColorBitmaps}), so it is sampled from at most twice the
 * pixels it covers. Their bitmaps' sizes are known only once rendered, so a colour glyph's place is reserved when its
 * job's bitmap is installed (their pages' layout follows the order jobs finish; what is drawn does not). They share
 * the lane, the per-frame budget and the placeholder rule, and are not cached on disk.
 *
 * <p>Used from the UI thread; only the generator and rasterizer run elsewhere.
 */
public final class GlyphAtlas {

    /** Waiting for a frame's budget. */
    public static final byte QUEUED = 1;
    /** Its job runs; its place is reserved. */
    public static final byte GENERATING = 2;
    /** On its page. */
    public static final byte READY = 3;
    /** Nothing to draw: no ink (a space), or it failed. */
    public static final byte EMPTY = 4;

    /** Pixels added on each side of a raster glyph's outline bounds: hinting moves edges by less than this. */
    public static final int RASTER_PAD = 2;
    /** Horizontal positions per pixel a raster glyph is rendered at (subpixel positioning). */
    public static final int RASTER_PHASES = 4;

    private static final int GUTTER = 1; // texels between glyphs, against filtering across neighbours
    private static final int MAX_FACE = 0x7FFF;
    private static final int MAX_SIZE_64 = 0xFFFF;
    private static final int MAX_RASTER_GLYPH = 0xFFFFFF; // a raster key keeps the phase above the glyph
    private static final int COLOR_KEY = 0x80000000; // a colour key: this bit, the level in bits 24-30, the glyph
    private static final int COLOR_GUTTER = 2; // texels between colour glyphs: their padding is bled colour

    private final UiTextures textures;
    private final MsdfGenerator generator;
    private final GlyphRasterizer rasterizer;
    private final Lane lane;
    private final GlyphCache cache;
    private final AtlasSettings settings;

    private float emSize;
    private float pixelRange;
    private int pageSize;
    private long settingsVersion;
    private final List<UiTexture> pages = new ArrayList<>();
    private int pageCount;
    private final SkylinePacker packer;

    private final LongIntMap index = new LongIntMap(1024);
    private int count;
    private long[] keys = new long[256];
    private FontFace[] faces = new FontFace[256];
    private byte[] states = new byte[256];
    private int[] pageIds = new int[256];
    private int[] slotX = new int[256];
    private int[] slotY = new int[256];
    private int[] widths = new int[256];
    private int[] heights = new int[256];
    private MsdfGeometry[] geometries = new MsdfGeometry[256];
    private float[] planes = new float[256 * 4]; // left, bottom, right, top in ems (raster: pixels)
    private float[] uvs = new float[256 * 4];

    private int[] queue = new int[256];
    private int queueHead;
    private int queueSize;
    private int generating;

    private record Done(int entry, int epoch, byte[] pixels) {
    }

    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();

    private record ColorDone(int entry, int epoch, ColorBitmaps.Prepared bitmap) {
    }

    private final ConcurrentLinkedQueue<ColorDone> colorDone = new ConcurrentLinkedQueue<>();
    private final List<UiTexture> colorPages = new ArrayList<>();
    private int colorPageCount;
    private final SkylinePacker colorPacker = new SkylinePacker(1, 1);
    private int epoch;
    private long completions;

    private final GlyphOutline outline = new GlyphOutline();
    private final float[] bounds = new float[4];

    /**
     * An atlas whose pages are made in {@code textures}, whose glyphs {@code generator} makes on {@code lane}'s threads
     * (null: on the thread that calls {@link #beginFrame()}, for tools and tests), taking glyphs from {@code cache}
     * first when it matches the settings (null for none) and adding the ones it generates to it.
     */
    @AllowAllocation("made once per UI")
    public GlyphAtlas(UiTextures textures, MsdfGenerator generator, Lane lane, GlyphCache cache,
        AtlasSettings settings) {
        this(textures, generator, null, lane, cache, settings);
    }

    /**
     * As {@link #GlyphAtlas(UiTextures, MsdfGenerator, Lane, GlyphCache, AtlasSettings)}, with hinted raster glyphs
     * for small text from {@code rasterizer} (thread-safe; null: MTSDF at every size).
     */
    @AllowAllocation("made once per UI")
    public GlyphAtlas(UiTextures textures, MsdfGenerator generator, GlyphRasterizer rasterizer, Lane lane,
        GlyphCache cache, AtlasSettings settings) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.rasterizer = rasterizer;
        this.lane = lane;
        this.cache = cache;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.packer = new SkylinePacker(1, 1);
        start();
    }

    private void start() {
        emSize = settings.emSize().get();
        pixelRange = settings.pixelRange().get();
        int size = (int) settings.pageSize().get();
        settingsVersion = settings.layoutVersion();
        if (size != pageSize) {
            pages.clear(); // pages of the old size stay in the texture table, unused
            pageSize = size;
        }
        pageCount = 0;
        newPage();
        if (size != colorPageSize) {
            colorPages.clear();
            colorPageSize = size;
        }
        colorPageCount = 0; // a colour page is made when the first colour glyph arrives
    }

    private int colorPageSize;

    @AllowAllocation("a new colour page, when one is full")
    private void newColorPage() {
        UiTexture page;
        if (colorPageCount < colorPages.size()) {
            page = colorPages.get(colorPageCount);
            Arrays.fill(page.pixels(), (byte) 0);
            page.markDirty(0, 0, colorPageSize, colorPageSize);
        } else {
            page = textures.create("colour glyph atlas " + colorPages.size(), colorPageSize, colorPageSize,
                UiTexture.Format.RGBA8_SRGB);
            colorPages.add(page);
        }
        colorPageCount++;
        colorPacker.reset(colorPageSize, colorPageSize);
    }

    @AllowAllocation("a new page, when one is full")
    private void newPage() {
        UiTexture page;
        if (pageCount < pages.size()) {
            page = pages.get(pageCount); // reused after a restart: clear it
            Arrays.fill(page.pixels(), (byte) 0);
            page.markDirty(0, 0, pageSize, pageSize);
        } else {
            page = textures.create("glyph atlas " + pages.size(), pageSize, pageSize, UiTexture.Format.RGBA8_UNORM);
            pages.add(page);
        }
        pageCount++;
        packer.reset(pageSize, pageSize);
    }

    /**
     * The entry of glyph {@code glyph} of {@code face}: known, or queued for generation. Entries stay valid until the
     * atlas starts over ({@link #epoch()} grows).
     */
    public int entry(FontFace face, int glyph) {
        return entry(face, glyph, 0);
    }

    /**
     * The entry of glyph {@code glyph} of {@code face} as hinted coverage at {@code sizePx} pixels per em (rounded to
     * 1/64 pixel), its pen {@code phase} {@link #RASTER_PHASES}ths of a pixel right of a whole pixel (0 to 3): known,
     * or queued. Without a rasterizer, the MTSDF entry.
     */
    public int rasterEntry(FontFace face, int glyph, float sizePx, int phase) {
        int size64 = (int) (sizePx * 64 + 0.5f);
        if (rasterizer == null || size64 <= 0 || size64 > MAX_SIZE_64 || glyph < 0 || glyph > MAX_RASTER_GLYPH
            || phase < 0 || phase >= RASTER_PHASES) {
            return entry(face, glyph, 0);
        }
        return entry(face, phase << 24 | glyph, size64);
    }

    /**
     * The entry of colour glyph {@code glyph} of {@code face} (a face with a {@linkplain FontFace#colorStrike() colour
     * strike}) to be drawn at {@code sizePx} pixels per em: its bitmap halved to the {@link ColorBitmaps#level} for that
     * size; known, or queued. Its {@link #plane}s are in ems, as an MTSDF glyph's; it is {@link #color}.
     */
    public int colorEntry(FontFace face, int glyph, float sizePx) {
        if (glyph < 0 || glyph > MAX_RASTER_GLYPH) {
            throw new IllegalArgumentException("glyph " + glyph + " is out of the colour key's range");
        }
        int level = ColorBitmaps.level(face.colorStrike(), sizePx);
        return entry(face, COLOR_KEY | level << 24 | glyph, 0);
    }

    /** Whether text of {@code sizePx} pixels per em, in world space or not, draws raster glyphs here. */
    public boolean rasterFor(float sizePx, boolean worldSpace) {
        return rasterizer != null && settings.hinted(sizePx, worldSpace);
    }

    private int entry(FontFace face, int glyph, int size64) {
        if (face.id() > MAX_FACE || face.id() < 0) {
            throw new IllegalArgumentException("face id " + face.id() + " is out of the atlas key's range");
        }
        long key = (long) face.id() << 48 | (long) size64 << 32 | glyph & 0xFFFFFFFFL;
        int e = index.get(key);
        if (e >= 0) {
            return e;
        }
        e = add(key, face);
        index.put(key, e);
        enqueue(e);
        return e;
    }

    private int add(long key, FontFace face) {
        if (count == keys.length) {
            grow();
        }
        int e = count++;
        keys[e] = key;
        faces[e] = face;
        states[e] = QUEUED;
        pageIds[e] = 0;
        geometries[e] = null;
        Arrays.fill(planes, e * 4, e * 4 + 4, 0);
        Arrays.fill(uvs, e * 4, e * 4 + 4, 0);
        return e;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int n = keys.length * 2;
        keys = Arrays.copyOf(keys, n);
        faces = Arrays.copyOf(faces, n);
        states = Arrays.copyOf(states, n);
        pageIds = Arrays.copyOf(pageIds, n);
        slotX = Arrays.copyOf(slotX, n);
        slotY = Arrays.copyOf(slotY, n);
        widths = Arrays.copyOf(widths, n);
        heights = Arrays.copyOf(heights, n);
        geometries = Arrays.copyOf(geometries, n);
        planes = Arrays.copyOf(planes, n * 4);
        uvs = Arrays.copyOf(uvs, n * 4);
    }

    private void enqueue(int e) {
        if (queueSize == queue.length) {
            growQueue();
        }
        queue[(queueHead + queueSize) % queue.length] = e;
        queueSize++;
    }

    @AllowAllocation("growing, rarely")
    private void growQueue() {
        int[] q = new int[queue.length * 2];
        for (int i = 0; i < queueSize; i++) {
            q[i] = queue[(queueHead + i) % queue.length];
        }
        queue = q;
        queueHead = 0;
    }

    /**
     * The frame's hand-over, at its start: installs finished glyphs, then starts up to the frame's budget of queued
     * ones. Never waits for a job. Allocates nothing when no glyph is new. If the em size, pixel range or page size
     * changed, the atlas first starts over.
     */
    public void beginFrame() {
        if (settings.layoutVersion() != settingsVersion) {
            restart();
        }
        for (Done d = done.poll(); d != null; d = done.poll()) {
            generating--;
            if (d.epoch() == epoch) {
                install(d.entry(), d.pixels());
            }
        }
        for (ColorDone d = colorDone.poll(); d != null; d = colorDone.poll()) {
            generating--;
            if (d.epoch() == epoch) {
                installColor(d.entry(), d.bitmap());
            }
        }
        int budget = (int) settings.glyphsPerFrame().get();
        for (int i = 0; i < budget && queueSize > 0; i++) {
            int e = queue[queueHead];
            queueHead = (queueHead + 1) % queue.length;
            queueSize--;
            start(e);
        }
    }

    /**
     * Generates every queued glyph now, on this thread, and waits for running jobs: for tools (the build-time baker)
     * and tests, never in a frame. Returns how many glyphs it handled.
     */
    public int finishAll() throws InterruptedException {
        int handled = 0;
        while (queueSize > 0 || generating > 0) {
            for (Done d = done.poll(); d != null; d = done.poll()) {
                generating--;
                if (d.epoch() == epoch) {
                    install(d.entry(), d.pixels());
                }
                handled++;
            }
            for (ColorDone d = colorDone.poll(); d != null; d = colorDone.poll()) {
                generating--;
                if (d.epoch() == epoch) {
                    installColor(d.entry(), d.bitmap());
                }
                handled++;
            }
            if (queueSize > 0) {
                int e = queue[queueHead];
                queueHead = (queueHead + 1) % queue.length;
                queueSize--;
                startNow(e);
                handled++;
            } else if (generating > 0) {
                Thread.sleep(1);
            }
        }
        return handled;
    }

    private void restart() {
        epoch++;
        index.clear();
        count = 0;
        queueSize = 0;
        queueHead = 0;
        start();
    }

    private void start(int e) {
        if (isColor(e)) {
            states[e] = GENERATING;
            if (lane == null) {
                installColor(e, colorNow(faces[e], colorGlyph(e), colorLevel(e)));
            } else {
                submitColor(e);
            }
            return;
        }
        if (isRaster(e)) {
            if (prepareRaster(e)) {
                if (lane == null) {
                    install(e, rasterNow(e));
                } else {
                    submitRaster(e);
                }
            }
            return;
        }
        MsdfGeometry g = prepare(e);
        if (g == null) {
            return;
        }
        if (lane == null) {
            install(e, generate(outline, g));
        } else {
            submit(e, outline.copy(), g);
        }
    }

    private void startNow(int e) {
        if (isColor(e)) {
            installColor(e, colorNow(faces[e], colorGlyph(e), colorLevel(e)));
            return;
        }
        if (isRaster(e)) {
            if (prepareRaster(e)) {
                install(e, rasterNow(e));
            }
            return;
        }
        MsdfGeometry g = prepare(e);
        if (g != null) {
            install(e, generate(outline, g));
        }
    }

    /** Reserves the glyph's place and returns its geometry with its outline read; null if it is done already. */
    private MsdfGeometry prepare(int e) {
        FontFace face = faces[e];
        int glyph = (int) keys[e];
        if (cache != null && cache.matches(emSize, pixelRange)) {
            GlyphCache.Glyph cached = cache.get(face.source().key(), glyph);
            if (cached != null) {
                if (cached.geometry() == null) {
                    markEmpty(e);
                } else if (reserve(e, cached.geometry())) {
                    copy(e, cached.pixels());
                }
                return null;
            }
        }
        if (!face.outline(glyph, outline) || outline.isEmpty()) {
            markEmpty(e);
            if (cache != null && cache.matches(emSize, pixelRange)) {
                cache.put(face.source().key(), glyph, null, null);
            }
            return null;
        }
        outline.bounds(bounds);
        MsdfGeometry g = MsdfGeometry.fit(bounds, emSize, pixelRange);
        if (!reserve(e, g)) {
            return null;
        }
        states[e] = GENERATING;
        return g;
    }

    /** Reserves a raster glyph's box from its outline's bounds at its size; false if it is done already (empty). */
    private boolean prepareRaster(int e) {
        FontFace face = faces[e];
        if (!face.outline(rasterGlyph(e), outline) || outline.isEmpty()) {
            markEmpty(e);
            return false;
        }
        outline.bounds(bounds);
        float size = size64(e) / 64f;
        float shift = offset(e);
        int left = (int) Math.floor(bounds[0] * size + shift) - RASTER_PAD;
        int bottom = (int) Math.floor(bounds[1] * size) - RASTER_PAD;
        int right = (int) Math.ceil(bounds[2] * size + shift) + RASTER_PAD;
        int top = (int) Math.ceil(bounds[3] * size) + RASTER_PAD;
        if (!reserve(e, right - left, top - bottom)) {
            return false;
        }
        geometries[e] = null;
        int o = e * 4;
        planes[o] = left;
        planes[o + 1] = bottom;
        planes[o + 2] = right;
        planes[o + 3] = top;
        states[e] = GENERATING;
        return true;
    }

    private boolean isRaster(int e) {
        return (keys[e] >>> 32 & MAX_SIZE_64) != 0;
    }

    private boolean isColor(int e) {
        return (keys[e] >>> 32 & MAX_SIZE_64) == 0 && ((int) keys[e] & COLOR_KEY) != 0;
    }

    private int colorGlyph(int e) {
        return (int) keys[e] & MAX_RASTER_GLYPH;
    }

    private int colorLevel(int e) {
        return (int) keys[e] >>> 24 & 0x7F;
    }

    /**
     * A colour glyph's prepared bitmap, on this or a job's thread: reads only its arguments; null if it has none or
     * rendering failed.
     */
    private ColorBitmaps.Prepared colorNow(FontFace face, int glyph, int level) {
        if (rasterizer == null) {
            return null;
        }
        try {
            GlyphRasterizer.ColorBitmap b = rasterizer.color(face, glyph);
            return b == null ? null : ColorBitmaps.prepare(b, level);
        } catch (RuntimeException ex) {
            return null; // a broken bitmap draws nothing rather than stopping the UI
        }
    }

    @AllowAllocation("one job per new colour glyph")
    private void submitColor(int e) {
        int jobEpoch = epoch;
        FontFace face = faces[e];
        int glyph = colorGlyph(e);
        int level = colorLevel(e);
        generating++;
        lane.execute(() -> colorDone.add(new ColorDone(e, jobEpoch, colorNow(face, glyph, level))));
    }

    /** Reserves a colour glyph's place on a colour page and copies its bitmap there. */
    private void installColor(int e, ColorBitmaps.Prepared b) {
        if (b == null) {
            markEmpty(e);
            return;
        }
        int w = b.width() + COLOR_GUTTER;
        int h = b.height() + COLOR_GUTTER;
        if (w > colorPageSize || h > colorPageSize) {
            markEmpty(e);
            return;
        }
        if (colorPageCount == 0 || !colorPacker.pack(w, h)) {
            newColorPage();
            colorPacker.pack(w, h);
        }
        UiTexture page = colorPages.get(colorPageCount - 1);
        int x = colorPacker.x();
        int y = colorPacker.y();
        pageIds[e] = page.id();
        slotX[e] = x;
        slotY[e] = y;
        widths[e] = b.width();
        heights[e] = b.height();
        int o = e * 4;
        planes[o] = b.left();
        planes[o + 1] = b.top() - b.emHeight();
        planes[o + 2] = b.left() + b.emWidth();
        planes[o + 3] = b.top();
        float inv = 1f / colorPageSize;
        uvs[o] = x * inv;
        uvs[o + 1] = y * inv;
        uvs[o + 2] = (x + b.width()) * inv;
        uvs[o + 3] = (y + b.height()) * inv;
        byte[] dst = page.pixels();
        int row = b.width() * 4;
        for (int r = 0; r < b.height(); r++) {
            System.arraycopy(b.pixels(), r * row, dst, ((y + r) * colorPageSize + x) * 4, row);
        }
        page.markDirty(x, y, b.width(), b.height());
        states[e] = READY;
        completions++;
    }

    private int size64(int e) {
        return (int) (keys[e] >>> 32 & MAX_SIZE_64);
    }

    private int rasterGlyph(int e) {
        return (int) keys[e] & MAX_RASTER_GLYPH;
    }

    /** A raster entry's pen offset right of its whole pixel, pixels. */
    private float offset(int e) {
        return ((int) keys[e] >>> 24) / (float) RASTER_PHASES;
    }

    /** Entry {@code e}'s coverage now, on this thread. */
    private byte[] rasterNow(int e) {
        int o = e * 4;
        return raster(faces[e], rasterGlyph(e), size64(e), offset(e), (int) planes[o], (int) planes[o + 3],
            widths[e], heights[e]);
    }

    /**
     * A raster glyph's coverage as RGBA texels of its {@code w x h} box, whose top left is {@code left} pixels right
     * of the pen and {@code top} above the baseline: the rasterizer's bitmap placed by its offset, clipped to the box;
     * null if the glyph has no ink or rasterizing failed. Runs on a job's thread: reads only its arguments.
     */
    @AllowAllocation("a bitmap per new raster glyph")
    private byte[] raster(FontFace face, int glyph, int size64, float shift, int left, int top, int w, int h) {
        GlyphRasterizer.Coverage c;
        try {
            c = rasterizer.rasterize(face, glyph, size64 / 64f, shift);
        } catch (RuntimeException ex) {
            return null; // a broken glyph draws nothing rather than stopping the UI
        }
        if (c == null) {
            return null;
        }
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < c.height(); y++) {
            int row = top - c.top() + y;
            if (row < 0 || row >= h) {
                continue;
            }
            for (int x = 0; x < c.width(); x++) {
                int col = c.left() - left + x;
                if (col >= 0 && col < w) {
                    byte v = c.pixels()[y * c.width() + x];
                    int t = (row * w + col) * 4;
                    out[t] = v;
                    out[t + 1] = v;
                    out[t + 2] = v;
                    out[t + 3] = v;
                }
            }
        }
        return out;
    }

    @AllowAllocation("one job per new glyph")
    private void submitRaster(int e) {
        int jobEpoch = epoch;
        FontFace face = faces[e];
        int glyph = rasterGlyph(e);
        int size64 = size64(e);
        float shift = offset(e);
        int o = e * 4;
        int left = (int) planes[o];
        int top = (int) planes[o + 3];
        int w = widths[e];
        int h = heights[e];
        generating++;
        lane.execute(() -> done.add(new Done(e, jobEpoch, raster(face, glyph, size64, shift, left, top, w, h))));
    }

    private void markEmpty(int e) {
        states[e] = EMPTY;
        completions++;
    }

    private byte[] generate(GlyphOutline o, MsdfGeometry g) {
        try {
            return generator.generate(o, g);
        } catch (RuntimeException ex) {
            return null; // a broken outline draws nothing rather than stopping the UI
        }
    }

    @AllowAllocation("one job per new glyph")
    private void submit(int e, GlyphOutline o, MsdfGeometry g) {
        int jobEpoch = epoch;
        generating++;
        lane.execute(() -> done.add(new Done(e, jobEpoch, generate(o, g))));
    }

    private boolean reserve(int e, MsdfGeometry g) {
        if (!reserve(e, g.width(), g.height())) {
            return false;
        }
        geometries[e] = g;
        int o = e * 4;
        planes[o] = g.planeLeft();
        planes[o + 1] = g.planeBottom();
        planes[o + 2] = g.planeRight();
        planes[o + 3] = g.planeTop();
        return true;
    }

    /** Reserves a {@code width x height} texel place for entry {@code e}; false (and empty) if no page holds it. */
    private boolean reserve(int e, int width, int height) {
        int w = width + GUTTER;
        int h = height + GUTTER;
        if (w > pageSize || h > pageSize) {
            markEmpty(e); // larger than a page: the em size is too large for the page size
            return false;
        }
        if (!packer.pack(w, h)) {
            newPage();
            packer.pack(w, h); // fits: an empty page takes anything up to its size
        }
        UiTexture page = pages.get(pageCount - 1);
        pageIds[e] = page.id();
        slotX[e] = packer.x();
        slotY[e] = packer.y();
        widths[e] = width;
        heights[e] = height;
        int o = e * 4;
        float inv = 1f / pageSize;
        uvs[o] = slotX[e] * inv;
        uvs[o + 1] = slotY[e] * inv;
        uvs[o + 2] = (slotX[e] + width) * inv;
        uvs[o + 3] = (slotY[e] + height) * inv;
        return true;
    }

    private void install(int e, byte[] pixels) {
        if (pixels == null) {
            markEmpty(e);
            return;
        }
        copy(e, pixels);
        if (cache != null && geometries[e] != null && cache.matches(emSize, pixelRange)) {
            cache.put(faces[e].source().key(), (int) keys[e], geometries[e], pixels);
        }
    }

    private void copy(int e, byte[] pixels) {
        UiTexture page = textures.get(pageIds[e]);
        byte[] dst = page.pixels();
        int row = widths[e] * 4;
        for (int y = 0; y < heights[e]; y++) {
            System.arraycopy(pixels, y * row, dst, ((slotY[e] + y) * pageSize + slotX[e]) * 4, row);
        }
        page.markDirty(slotX[e], slotY[e], widths[e], heights[e]);
        states[e] = READY;
        completions++;
    }

    /** The state of entry {@code e}: {@link #QUEUED}, {@link #GENERATING}, {@link #READY} or {@link #EMPTY}. */
    public byte state(int e) {
        return states[e];
    }

    /** The texture id of entry {@code e}'s page (0 until it has a place). */
    public int texture(int e) {
        return pageIds[e];
    }

    /** Whether entry {@code e} is a colour glyph on a colour page (drawn with {@code FLAG_COLOR}). */
    public boolean color(int e) {
        return isColor(e);
    }

    /** Colour pages in use. */
    public int colorPageCount() {
        return colorPageCount;
    }

    /** Colour page {@code i}. */
    public UiTexture colorPage(int i) {
        if (i < 0 || i >= colorPageCount) {
            throw new IndexOutOfBoundsException(i);
        }
        return colorPages.get(i);
    }

    /** Whether entry {@code e} is a hinted raster glyph (its {@link #plane}s are whole pixels). */
    public boolean raster(int e) {
        return isRaster(e);
    }

    /** The raster glyphs' rasterizer, or null. */
    public GlyphRasterizer rasterizer() {
        return rasterizer;
    }

    /**
     * Entry {@code e}'s bitmap edges from the pen position, y up: 0 left, 1 bottom, 2 right, 3 top; in ems for an
     * MTSDF or {@link #color} glyph, in whole pixels for a {@link #raster} one.
     */
    public float plane(int e, int edge) {
        return planes[e * 4 + edge];
    }

    /** Entry {@code e}'s region on its page, 0 to 1, v down: 0 u0, 1 v0, 2 u1, 3 v1. */
    public float uv(int e, int corner) {
        return uvs[e * 4 + corner];
    }

    /** MTSDF texels per em of the current glyphs. */
    public float emSize() {
        return emSize;
    }

    /** The distance range of the current glyphs, texels. */
    public float pixelRange() {
        return pixelRange;
    }

    /** Grows when the atlas starts over: entries from before are void. */
    public int epoch() {
        return epoch;
    }

    /** Grows whenever a glyph becomes ready or turns out empty: text waiting for glyphs records again. */
    public long completions() {
        return completions;
    }

    /** Glyphs known. */
    public int size() {
        return count;
    }

    /** Glyphs queued or generating. */
    public int pending() {
        return queueSize + generating;
    }

    /** Pages in use. */
    public int pageCount() {
        return pageCount;
    }

    /** Page {@code i}. */
    public UiTexture page(int i) {
        if (i < 0 || i >= pageCount) {
            throw new IndexOutOfBoundsException(i);
        }
        return pages.get(i);
    }

    /** The textures its pages are made in. */
    public UiTextures textures() {
        return textures;
    }

    /** The tunables the atlas reads. */
    public AtlasSettings settings() {
        return settings;
    }

    /** The disk cache, or null. */
    public GlyphCache cache() {
        return cache;
    }
}
