package com.truesim.ui.core.event;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiStore;
import java.util.Arrays;

/**
 * The screen UI's hit test (design 4.7, step 1): per layer, a uniform grid over its interactive elements, rebuilt
 * only when one of them (or an element clipping them) moved, was restyled, or the layer's structure changed; then the
 * precise shape (a rounded rectangle from the element's radii) and the clips of all its clipping ancestors (rounded
 * too). Layers are searched front to back; a {@linkplain Layer#blocking() blocking} layer stops the search, and a
 * layer taking no input is skipped.
 *
 * <p>Each grid cell lists its elements in draw order (tree order: children over parents, later siblings over earlier
 * ones), so a pick walks one cell backwards and returns every element under the point, front-most first: the target
 * and what pass-through reaches next. Picking allocates nothing; a rebuild allocates only when the arrays grow.
 *
 * <p>Benchmarked against a plain walk of the tree ({@code PickingBenchmark}): the grid answers a pick in a fraction of
 * the time on a 2,000-element HUD and stays flat as the HUD grows, so it is the only technique kept.
 */
public final class Picking {

    /** The side of a grid cell in UI pixels before the grid is coarsened to at most {@link #MAX_CELLS} cells. */
    static final float CELL = 64;
    /** The most cells one layer's grid has. */
    static final int MAX_CELLS = 4096;

    private final Interaction interaction;
    private final UiStore store;
    private final Layers layers;
    private Grid[] grids = new Grid[0];
    private int rebuilds;
    private int blockedBy = -1;

    // per slot: whether a grid holds it as an element or a clip, and of which build
    private int[] relevantGen = new int[0];

    @AllowAllocation("one per UI")
    Picking(Interaction interaction) {
        this.interaction = interaction;
        this.store = interaction.store();
        this.layers = interaction.layers();
    }

    /** One layer's grid. */
    private static final class Grid {
        boolean dirty = true;
        int count;
        int[] slots = new int[16];
        float[] rect = new float[16 * 4]; // the hit rectangle: bounds clipped by every clipping ancestor
        float[] shape = new float[16 * 8]; // bounds x, y, w, h and radii tl, tr, br, bl
        int[] clip = new int[16]; // index into the clip table, or -1
        int clipCount;
        float[] clips = new float[8 * 8]; // the nearest clipping ancestor's bounds and radii
        int[] clipParent = new int[8]; // per clip, the clip of the clipping ancestor around it, or -1
        float originX;
        float originY;
        float cell = CELL;
        int cols;
        int rows;
        int[] cellStart = new int[2];
        int[] entries = new int[16];
    }

    // ---------------------------------------------------------------- invalidation

    /** Marks the grid of {@code slot}'s layer for a rebuild (structure, listeners, focusability or style changed). */
    public void invalidate(int slot) {
        int layer = layers.indexOf(slot);
        if (layer >= 0) {
            ensureGrids();
            grids[layer].dirty = true;
        }
    }

    /** Marks every layer's grid for a rebuild. */
    public void invalidateAll() {
        ensureGrids();
        for (Grid g : grids) {
            g.dirty = true;
        }
    }

    /**
     * Tells picking that {@code slot}'s rectangle changed: its layer's grid is rebuilt if it holds the element or uses
     * it as a clip. The UI frame calls this for each element layout moved.
     */
    public void moved(int slot) {
        if (slot < relevantGen.length && relevantGen[slot] == store.generation(slot) && relevantGen[slot] != 0) {
            invalidate(slot);
        }
    }

    @AllowAllocation("grows with the layers, rarely")
    private void ensureGrids() {
        if (grids.length < layers.count()) {
            int old = grids.length;
            grids = Arrays.copyOf(grids, layers.count());
            for (int i = old; i < grids.length; i++) {
                grids[i] = new Grid();
            }
        }
    }

    // ---------------------------------------------------------------- picking

    /**
     * Every interactive element under {@code x, y} (UI pixels), front-most first, into {@code out} (at most its
     * length): the top layer's first, down to and including a blocking layer. Returns how many.
     */
    public int pick(float x, float y, int[] out) {
        ensureGrids();
        int n = 0;
        blockedBy = -1;
        for (int li = layers.count() - 1; li >= 0; li--) {
            Layer layer = layers.at(li);
            if (layer.input() == Layer.Input.NONE) {
                continue;
            }
            Grid g = grids[li];
            if (g.dirty) {
                build(li, g);
            }
            n = collect(g, x, y, out, n);
            if (layer.blocking()) {
                blockedBy = li;
                break;
            }
        }
        return n;
    }

    /** The index of the layer that blocked the last {@link #pick}, or -1. */
    public int blockedBy() {
        return blockedBy;
    }

    /** The front-most interactive element under {@code x, y}, or {@link UiStore#NONE}. */
    public int pickTop(float x, float y) {
        ensureGrids();
        for (int li = layers.count() - 1; li >= 0; li--) {
            Layer layer = layers.at(li);
            if (layer.input() == Layer.Input.NONE) {
                continue;
            }
            Grid g = grids[li];
            if (g.dirty) {
                build(li, g);
            }
            int top = collectTop(g, x, y);
            if (top != UiStore.NONE || layer.blocking()) {
                return top;
            }
        }
        return UiStore.NONE;
    }

    private int collect(Grid g, float x, float y, int[] out, int n) {
        int cell = cellOf(g, x, y);
        if (cell < 0) {
            return n;
        }
        for (int e = g.cellStart[cell + 1] - 1; e >= g.cellStart[cell] && n < out.length; e--) {
            int i = g.entries[e];
            if (hits(g, i, x, y)) {
                out[n++] = g.slots[i];
            }
        }
        return n;
    }

    private int collectTop(Grid g, float x, float y) {
        int cell = cellOf(g, x, y);
        if (cell < 0) {
            return UiStore.NONE;
        }
        for (int e = g.cellStart[cell + 1] - 1; e >= g.cellStart[cell]; e--) {
            int i = g.entries[e];
            if (hits(g, i, x, y)) {
                return g.slots[i];
            }
        }
        return UiStore.NONE;
    }

    private static int cellOf(Grid g, float x, float y) {
        if (g.count == 0) {
            return -1;
        }
        float fx = (x - g.originX) / g.cell;
        float fy = (y - g.originY) / g.cell;
        if (fx < 0 || fy < 0 || fx >= g.cols || fy >= g.rows) {
            return -1;
        }
        return (int) fy * g.cols + (int) fx;
    }

    private static boolean hits(Grid g, int i, float x, float y) {
        int r = i * 4;
        if (x < g.rect[r] || y < g.rect[r + 1] || x >= g.rect[r + 2] || y >= g.rect[r + 3]) {
            return false;
        }
        int s = i * 8;
        float[] sh = g.shape;
        if (!inside(x, y, sh[s], sh[s + 1], sh[s + 2], sh[s + 3], sh[s + 4], sh[s + 5], sh[s + 6], sh[s + 7])) {
            return false;
        }
        // inside every clip on the way, rounded corners included (the rectangle has every clip's box already): a
        // point a rounded clip further out cuts off is clipped away, however square the nearer clips are
        float[] cl = g.clips;
        for (int c = g.clip[i]; c >= 0; c = g.clipParent[c]) {
            int o = c * 8;
            if (!inside(x, y, cl[o], cl[o + 1], cl[o + 2], cl[o + 3], cl[o + 4], cl[o + 5], cl[o + 6], cl[o + 7])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code px, py} is inside the rounded rectangle {@code x, y, w, h} with corner radii {@code tl, tr, br,
     * bl}; radii too large for the box shrink together, as in CSS.
     */
    public static boolean inside(float px, float py, float x, float y, float w, float h, float tl, float tr, float br,
        float bl) {
        if (px < x || py < y || px >= x + w || py >= y + h) {
            return false;
        }
        if (tl <= 0 && tr <= 0 && br <= 0 && bl <= 0) {
            return true;
        }
        float f = 1;
        f = shrink(f, w, tl + tr);
        f = shrink(f, w, bl + br);
        f = shrink(f, h, tl + bl);
        f = shrink(f, h, tr + br);
        float lx = px - x;
        float ly = py - y;
        float rx = x + w - px;
        float ry = y + h - py;
        return corner(lx, ly, tl * f) && corner(rx, ly, tr * f) && corner(rx, ry, br * f) && corner(lx, ry, bl * f);
    }

    private static float shrink(float f, float side, float sum) {
        return sum > side && sum > 0 ? Math.min(f, side / sum) : f;
    }

    /** Whether a point {@code dx, dy} in from a corner of radius {@code r} is inside its rounding. */
    private static boolean corner(float dx, float dy, float r) {
        if (r <= 0 || dx >= r || dy >= r) {
            return true;
        }
        float cx = r - dx;
        float cy = r - dy;
        return cx * cx + cy * cy <= r * r;
    }

    // ---------------------------------------------------------------- building

    private void build(int layerIndex, Grid g) {
        rebuilds++;
        g.dirty = false;
        g.count = 0;
        g.clipCount = 0;
        if (relevantGen.length < store.capacity()) {
            growRelevant();
        }
        PickGeometry geo = interaction.geometry();
        int root = layers.at(layerIndex).rootSlot();
        collectTree(g, geo, root, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -1);
        if (g.count == 0) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < g.count; i++) {
            int r = i * 4;
            minX = Math.min(minX, g.rect[r]);
            minY = Math.min(minY, g.rect[r + 1]);
            maxX = Math.max(maxX, g.rect[r + 2]);
            maxY = Math.max(maxY, g.rect[r + 3]);
        }
        float w = maxX - minX;
        float h = maxY - minY;
        float cell = CELL;
        while ((int) (w / cell + 1) * (int) (h / cell + 1) > MAX_CELLS) {
            cell *= 2;
        }
        g.cell = cell;
        g.originX = minX;
        g.originY = minY;
        g.cols = (int) (w / cell) + 1;
        g.rows = (int) (h / cell) + 1;
        int cells = g.cols * g.rows;
        if (g.cellStart.length < cells + 1) {
            g.cellStart = grow(g.cellStart, cells + 1);
        }
        Arrays.fill(g.cellStart, 0, cells + 1, 0);
        int total = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < g.count; i++) {
                int r = i * 4;
                int c0 = (int) ((g.rect[r] - minX) / cell);
                int r0 = (int) ((g.rect[r + 1] - minY) / cell);
                int c1 = Math.min(g.cols - 1, (int) ((g.rect[r + 2] - minX) / cell));
                int r1 = Math.min(g.rows - 1, (int) ((g.rect[r + 3] - minY) / cell));
                for (int row = r0; row <= r1; row++) {
                    for (int col = c0; col <= c1; col++) {
                        int c = row * g.cols + col;
                        if (pass == 0) {
                            g.cellStart[c + 1]++;
                            total++;
                        } else {
                            g.entries[g.cellStart[c]++] = i;
                        }
                    }
                }
            }
            if (pass == 0) {
                for (int c = 0; c < cells; c++) {
                    g.cellStart[c + 1] += g.cellStart[c];
                }
                if (g.entries.length < total) {
                    g.entries = grow(g.entries, total);
                }
            } else {
                // the fill advanced each start to the next cell's: shift them back
                for (int c = cells; c > 0; c--) {
                    g.cellStart[c] = g.cellStart[c - 1];
                }
                g.cellStart[0] = 0;
            }
        }
    }

    /** Depth first in draw order, the hit rectangle clipped by {@code cx0..cy1}, the nearest clip {@code clip}. */
    private void collectTree(Grid g, PickGeometry geo, int slot, float cx0, float cy0, float cx1, float cy1,
        int clip) {
        if (!geo.shown(slot)) {
            return;
        }
        float x = geo.x(slot);
        float y = geo.y(slot);
        float w = geo.width(slot);
        float h = geo.height(slot);
        if (interaction.pickableNow(slot, geo)) {
            float x0 = Math.max(x, cx0);
            float y0 = Math.max(y, cy0);
            float x1 = Math.min(x + w, cx1);
            float y1 = Math.min(y + h, cy1);
            if (x0 < x1 && y0 < y1) {
                add(g, geo, slot, x0, y0, x1, y1, x, y, w, h, clip);
            }
        }
        int childClip = clip;
        float nx0 = cx0;
        float ny0 = cy0;
        float nx1 = cx1;
        float ny1 = cy1;
        if (geo.clips(slot)) {
            relevantGen[slot] = store.generation(slot);
            nx0 = Math.max(x, cx0);
            ny0 = Math.max(y, cy0);
            nx1 = Math.min(x + w, cx1);
            ny1 = Math.min(y + h, cy1);
            if (nx0 >= nx1 || ny0 >= ny1) {
                return; // clipped away: nothing below can be hit
            }
            childClip = addClip(g, geo, slot, x, y, w, h, clip);
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            collectTree(g, geo, c, nx0, ny0, nx1, ny1, childClip);
        }
    }

    private void add(Grid g, PickGeometry geo, int slot, float x0, float y0, float x1, float y1, float x, float y,
        float w, float h, int clip) {
        if (g.count == g.slots.length) {
            growGrid(g);
        }
        int i = g.count++;
        relevantGen[slot] = store.generation(slot);
        g.slots[i] = slot;
        int r = i * 4;
        g.rect[r] = x0;
        g.rect[r + 1] = y0;
        g.rect[r + 2] = x1;
        g.rect[r + 3] = y1;
        int s = i * 8;
        g.shape[s] = x;
        g.shape[s + 1] = y;
        g.shape[s + 2] = w;
        g.shape[s + 3] = h;
        for (int k = 0; k < 4; k++) {
            g.shape[s + 4 + k] = geo.radius(slot, k);
        }
        g.clip[i] = clip;
    }

    private int addClip(Grid g, PickGeometry geo, int slot, float x, float y, float w, float h, int parent) {
        if ((g.clipCount + 1) * 8 > g.clips.length) {
            g.clips = growFloats(g.clips, g.clips.length * 2);
        }
        if (g.clipCount == g.clipParent.length) {
            g.clipParent = Arrays.copyOf(g.clipParent, g.clipParent.length * 2);
        }
        g.clipParent[g.clipCount] = parent;
        int o = g.clipCount * 8;
        g.clips[o] = x;
        g.clips[o + 1] = y;
        g.clips[o + 2] = w;
        g.clips[o + 3] = h;
        for (int k = 0; k < 4; k++) {
            g.clips[o + 4 + k] = geo.radius(slot, k);
        }
        return g.clipCount++;
    }

    @AllowAllocation("grows with the interactive elements, rarely")
    private static void growGrid(Grid g) {
        int n = g.slots.length * 2;
        g.slots = Arrays.copyOf(g.slots, n);
        g.rect = Arrays.copyOf(g.rect, n * 4);
        g.shape = Arrays.copyOf(g.shape, n * 8);
        g.clip = Arrays.copyOf(g.clip, n);
    }

    @AllowAllocation("grows with the store, rarely")
    private void growRelevant() {
        relevantGen = Arrays.copyOf(relevantGen, store.capacity());
    }

    @AllowAllocation("grows with the interactive elements, rarely")
    private static int[] grow(int[] a, int n) {
        return Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    @AllowAllocation("grows with the clipping elements, rarely")
    private static float[] growFloats(float[] a, int n) {
        return Arrays.copyOf(a, n);
    }

    /** How many grids were built so far (a test counts rebuilds). */
    public int rebuilds() {
        return rebuilds;
    }

    /** The interactive elements in layer {@code layerIndex}'s grid as last built (for tests and debug overlays). */
    public int elementCount(int layerIndex) {
        ensureGrids();
        Grid g = grids[layerIndex];
        if (g.dirty) {
            build(layerIndex, g);
        }
        return g.count;
    }

    /**
     * Layer {@code layerIndex}'s grid as last built, for debug overlays: {@code out} gets its origin x and y, its cell
     * side (UI pixels), its columns and its rows; returns how many cells it has (0 when it holds nothing).
     */
    public int grid(int layerIndex, float[] out) {
        ensureGrids();
        Grid g = grids[layerIndex];
        if (g.dirty) {
            build(layerIndex, g);
        }
        if (g.count == 0) {
            return 0;
        }
        out[0] = g.originX;
        out[1] = g.originY;
        out[2] = g.cell;
        out[3] = g.cols;
        out[4] = g.rows;
        return g.cols * g.rows;
    }

    /** How many elements cell {@code column, row} of layer {@code layerIndex}'s grid lists (see {@link #grid}). */
    public int cellCount(int layerIndex, int column, int row) {
        Grid g = grids[layerIndex];
        int cell = row * g.cols + column;
        return g.cellStart[cell + 1] - g.cellStart[cell];
    }
}
