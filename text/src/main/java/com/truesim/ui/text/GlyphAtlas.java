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
 * MTSDF glyphs on atlas pages ({@link UiTexture}s in {@link UiTexture.Format#RGBA8_UNORM}), generated on demand.
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
 * <p>Used from the UI thread; only the generator runs elsewhere.
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

    private static final int GUTTER = 1; // texels between glyphs, against filtering across neighbours

    private final UiTextures textures;
    private final MsdfGenerator generator;
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
    private MsdfGeometry[] geometries = new MsdfGeometry[256];
    private float[] planes = new float[256 * 4]; // left, bottom, right, top in ems
    private float[] uvs = new float[256 * 4];

    private int[] queue = new int[256];
    private int queueHead;
    private int queueSize;
    private int generating;

    private record Done(int entry, int epoch, byte[] pixels) {
    }

    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
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
        this.textures = Objects.requireNonNull(textures, "textures");
        this.generator = Objects.requireNonNull(generator, "generator");
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
        long key = (long) face.id() << 32 | glyph & 0xFFFFFFFFL;
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
        int w = g.width() + GUTTER;
        int h = g.height() + GUTTER;
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
        geometries[e] = g;
        int o = e * 4;
        planes[o] = g.planeLeft();
        planes[o + 1] = g.planeBottom();
        planes[o + 2] = g.planeRight();
        planes[o + 3] = g.planeTop();
        float inv = 1f / pageSize;
        uvs[o] = slotX[e] * inv;
        uvs[o + 1] = slotY[e] * inv;
        uvs[o + 2] = (slotX[e] + g.width()) * inv;
        uvs[o + 3] = (slotY[e] + g.height()) * inv;
        return true;
    }

    private void install(int e, byte[] pixels) {
        if (pixels == null) {
            markEmpty(e);
            return;
        }
        copy(e, pixels);
        if (cache != null && cache.matches(emSize, pixelRange)) {
            cache.put(faces[e].source().key(), (int) keys[e], geometries[e], pixels);
        }
    }

    private void copy(int e, byte[] pixels) {
        MsdfGeometry g = geometries[e];
        UiTexture page = textures.get(pageIds[e]);
        byte[] dst = page.pixels();
        int row = g.width() * 4;
        for (int y = 0; y < g.height(); y++) {
            System.arraycopy(pixels, y * row, dst, ((slotY[e] + y) * pageSize + slotX[e]) * 4, row);
        }
        page.markDirty(slotX[e], slotY[e], g.width(), g.height());
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

    /** Entry {@code e}'s bitmap edges in ems from the pen position, y up: 0 left, 1 bottom, 2 right, 3 top. */
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

    /** The cache it reads and fills, or null. */
    public GlyphCache cache() {
        return cache;
    }
}
