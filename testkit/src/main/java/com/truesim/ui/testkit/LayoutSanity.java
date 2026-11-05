package com.truesim.ui.testkit;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.BasicStyles;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks a laid-out and recorded {@link Ui} for the visible layout defects a screenshot shows (design section 12),
 * over every layer, so a gallery test finds them in any control without knowing it:
 *
 * <ul>
 * <li>{@link Kind#CLIP}: what an element draws (its range's primitives where they draw: their clip's offset added),
 * as far as its own clip lets it show, lies outside the clip of its nearest clipping ancestor (content escaping a
 * viewport that should hide it).</li>
 * <li>{@link Kind#OVERLAP}: two shown siblings in a {@code stack}, {@code flex} or {@code grid} container overlap
 * (overlay, anchors, border and absolute layouts place children over one another on purpose, and so do children with
 * a negative margin).</li>
 * <li>{@link Kind#TEXT_OVERFLOW}: an element's visible glyphs (inked area: an MTSDF quad's distance-range padding
 * taken off) leave its box, unless its clip hides what does not fit.</li>
 * <li>{@link Kind#TEXT_OVERLAP}: two elements' glyphs draw over one another with nothing opaque drawn between them
 * over the overlap, whatever the layouts (rows anchored to one place).</li>
 * <li>{@link Kind#TAB_UNDER_OVERFLOW}, {@link Kind#TAB_PARTIAL}: a {@code tab} shows under its strip's overflow
 * button ({@code #tab-overflow}); with {@code tab-overflow: menu}, a tab shows in part (it belongs in the menu).</li>
 * </ul>
 *
 * <p>Elements count when shown (laid out and not hidden), not {@linkplain Ui#isPart style parts}, and with an area.
 * Shadows and focus rings draw outside boxes on purpose and are left out.
 */
@AllowAllocation("test helper")
public final class LayoutSanity {

    /** What is wrong. */
    public enum Kind {
        /** Drawn outside its clipping ancestor's clip. */
        CLIP,
        /** Overlaps a sibling in a stacking container. */
        OVERLAP,
        /** Text leaves the element's box. */
        TEXT_OVERFLOW,
        /** A tab shows under the strip's overflow button. */
        TAB_UNDER_OVERFLOW,
        /** A tab shows in part while overflowing tabs belong in the menu. */
        TAB_PARTIAL,
        /** Two elements' text draws over one another with nothing opaque between them. */
        TEXT_OVERLAP
    }

    /** One defect: its kind, the element (and the other one, for overlaps), and where. */
    public record Violation(Kind kind, String element, String other, String detail) {
        @Override
        public String toString() {
            return kind + " " + element + (other == null ? "" : " / " + other) + ": " + detail;
        }
    }

    /** How far, in pixels, a rectangle may pass an edge (snapping and antialiasing). */
    public static final float TOLERANCE = 1.01f;

    /** How far a glyph's ink may pass its box sideways, as a share of its inked height (italic overhang). */
    public static final float SLANT = 0.4f;

    private final Ui ui;
    private final UiStore store;
    private final Layout layout;
    private final List<Violation> out = new ArrayList<>();
    private float inkHeight; // of the last glyph made visible, unclipped
    // per layer, in drawing order: elements' glyph ink and opaque boxes
    private int order;
    private final List<Ink> inks = new ArrayList<>();
    private final List<float[]> covers = new ArrayList<>(); // x0 y0 x1 y1 order

    /** The glyphs one element shows, where they show, and its place in the drawing order. */
    private record Ink(int slot, int order, float[] union, List<float[]> glyphs) {
    }

    private LayoutSanity(Ui ui) {
        this.ui = ui;
        this.store = ui.store();
        this.layout = ui.layout();
    }

    /** The defects of {@code ui} as last updated, in tree order of each layer (empty: none). */
    public static List<Violation> check(Ui ui) {
        LayoutSanity s = new LayoutSanity(ui);
        s.run();
        return s.out;
    }

    /** Throws an {@link AssertionError} listing the defects, if {@code ui} has any. */
    public static void assertSane(Ui ui, String what) {
        List<Violation> v = check(ui);
        if (!v.isEmpty()) {
            StringBuilder b = new StringBuilder(what).append(": ").append(v.size()).append(" layout defects");
            for (Violation x : v) {
                b.append("\n  ").append(x);
            }
            throw new AssertionError(b.toString());
        }
    }

    private void run() {
        DrawList[] lists = ui.layers();
        for (int li = 0; li < ui.layerCount(); li++) {
            int root = ui.layerStack().at(li).rootSlot();
            inks.clear();
            covers.clear();
            walk(root, lists[li].baked()); // where primitives draw: transforms and clip offsets applied
            overlaps();
        }
    }

    private boolean counts(int slot) {
        return store.alive(slot) && layout.shown(slot) && !ui.isPart(store.element(slot))
            && layout.width(slot) > 0 && layout.height(slot) > 0;
    }

    private void walk(int slot, DrawList list) {
        if (!store.alive(slot) || !layout.shown(slot)) {
            return; // a hidden subtree draws nothing
        }
        order++;
        if (counts(slot)) {
            drawn(slot, list);
            siblings(slot);
            tabs(slot, list);
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            walk(c, list);
        }
    }

    // ------------------------------------------------------------------ what an element draws

    private void drawn(int slot, DrawList list) {
        int start = store.rangeStart(slot);
        int end = start + store.rangeCapacity(slot);
        float[] clip = clipOfAncestor(slot);
        // the box where it draws: scaled and moved as its primitives are (an animated scale or offset)
        ui.visuals().compose(slot);
        float k = ui.visuals().composedScale();
        float bx0 = layout.x(slot) * k + ui.visuals().composedX();
        float by0 = layout.y(slot) * k + ui.visuals().composedY();
        float bx1 = bx0 + layout.width(slot) * k;
        float by1 = by0 + layout.height(slot) * k;
        float[] r = new float[4];
        Ink ink = null;
        boolean reported = false;
        for (int i = start; i < end && i < list.size(); i++) {
            int header = list.header(i);
            int kind = Primitive.kind(header);
            if (kind == Primitive.EMPTY || kind == Primitive.SHADOW || !visible(list, i, r)) {
                continue;
            }
            if (kind == Primitive.BOX && ring(list, i, bx0, by0, bx1, by1)) {
                continue; // a focus ring or glow around the box
            }
            if (kind == Primitive.BOX && opaque(list, i)) {
                covers.add(new float[] {r[0], r[1], r[2], r[3], order});
            }
            if (kind == Primitive.GLYPH) {
                if (ink == null) {
                    ink = new Ink(slot, order, r.clone(), new ArrayList<>());
                    inks.add(ink);
                }
                ink.glyphs.add(r.clone());
                grow(ink.union, r);
            }
            if (reported) {
                continue;
            }
            if (clip != null && outside(r, clip[0], clip[1], clip[2], clip[3])) {
                report(Kind.CLIP, slot, -1, r, clip);
                reported = true;
                continue;
            }
            float slant = Math.max(TOLERANCE, inkHeight * SLANT); // italic ink passes the advance box
            if (kind == Primitive.GLYPH && (r[0] < bx0 - slant || r[2] > bx1 + slant || r[1] < by0 - TOLERANCE
                || r[3] > by1 + TOLERANCE)) {
                report(Kind.TEXT_OVERFLOW, slot, -1, r, new float[] {bx0, by0, bx1, by1});
                reported = true;
            }
        }
    }

    private static void grow(float[] u, float[] r) {
        u[0] = Math.min(u[0], r[0]);
        u[1] = Math.min(u[1], r[1]);
        u[2] = Math.max(u[2], r[2]);
        u[3] = Math.max(u[3], r[3]);
    }

    /** Whether primitive {@code i} draws nothing: every colour it uses fully transparent (faded out). */
    private static boolean transparent(DrawList list, int i) {
        int header = list.header(i);
        int kind = Primitive.kind(header);
        if (Rgba.a(list.colorAt(i, Primitive.COLOR_A)) > 0) {
            return false;
        }
        if (kind == Primitive.BOX) {
            boolean gradient = (Primitive.flags(header) & Primitive.FLAG_GRADIENT) != 0;
            return !(gradient && Rgba.a(list.colorAt(i, Primitive.COLOR_B)) > 0)
                && !(list.floatAt(i, Primitive.WIDTH) > 0 && Rgba.a(list.colorAt(i, Primitive.BORDER_COLOR)) > 0);
        }
        return kind != Primitive.GLYPH || list.floatAt(i, Primitive.OUTLINE) <= 0
            || Rgba.a(list.colorAt(i, Primitive.COLOR_B)) <= 0;
    }

    /** Whether box {@code i} fills its rectangle opaquely (what is drawn before it there is hidden). */
    private static boolean opaque(DrawList list, int i) {
        boolean gradient = (Primitive.flags(list.header(i)) & Primitive.FLAG_GRADIENT) != 0;
        return Rgba.a(list.colorAt(i, Primitive.COLOR_A)) >= 0.9f
            && (!gradient || Rgba.a(list.colorAt(i, Primitive.COLOR_B)) >= 0.9f);
    }

    /**
     * Text over text: two elements' glyphs overlap and nothing opaque is drawn between them over the overlap (a stuck
     * header's background over the rows scrolling under it, a window over a panel).
     */
    private void overlaps() {
        for (int a = 0; a < inks.size(); a++) {
            Ink first = inks.get(a);
            for (int b = a + 1; b < inks.size(); b++) {
                Ink second = inks.get(b);
                if (!crosses(first.union, second.union)) {
                    continue;
                }
                float[] hit = firstOverlap(first, second);
                if (hit != null && !covered(hit, first.order, second.order)) {
                    report(Kind.TEXT_OVERLAP, first.slot, second.slot, first.union, second.union);
                }
            }
        }
    }

    private static boolean crosses(float[] a, float[] b) {
        return Math.min(a[2], b[2]) - Math.max(a[0], b[0]) > TOLERANCE
            && Math.min(a[3], b[3]) - Math.max(a[1], b[1]) > TOLERANCE;
    }

    private static float[] firstOverlap(Ink a, Ink b) {
        for (float[] g : a.glyphs) {
            for (float[] h : b.glyphs) {
                if (crosses(g, h)) {
                    return new float[] {Math.max(g[0], h[0]), Math.max(g[1], h[1]), Math.min(g[2], h[2]),
                        Math.min(g[3], h[3])};
                }
            }
        }
        return null;
    }

    /** Whether an opaque box drawn after {@code from} and up to {@code to} covers {@code r}. */
    private boolean covered(float[] r, int from, int to) {
        for (float[] c : covers) {
            if (c[4] > from && c[4] <= to && c[0] <= r[0] && c[1] <= r[1] && c[2] >= r[2] && c[3] >= r[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Primitive {@code i}'s visible rectangle into {@code r}: where it draws (its clip's offset added; a glyph's inked
     * area), cut by its clip; false if nothing of it shows.
     */
    private boolean visible(DrawList list, int i, float[] r) {
        int header = list.header(i);
        if (transparent(list, i)) {
            return false;
        }
        float x0 = list.drawnX(i, Primitive.X0);
        float y0 = list.drawnY(i, Primitive.Y0);
        float x1 = list.drawnX(i, Primitive.X1);
        float y1 = list.drawnY(i, Primitive.Y1);
        int kind = Primitive.kind(header);
        if (kind != Primitive.LINE && list.drawnRotated(i)) { // a rotated box: the bounds of its corners
            list.drawnBounds(i, list.floatAt(i, Primitive.X0), list.floatAt(i, Primitive.Y0),
                list.floatAt(i, Primitive.X1), list.floatAt(i, Primitive.Y1), r);
            x0 = r[0];
            y0 = r[1];
            x1 = r[2];
            y1 = r[3];
        }
        if (kind == Primitive.LINE) {
            float w = list.floatAt(i, Primitive.WIDTH) * 0.5f;
            float ax = Math.min(x0, x1);
            float bx = Math.max(x0, x1);
            float ay = Math.min(y0, y1);
            float by = Math.max(y0, y1);
            x0 = ax - w;
            x1 = bx + w;
            y0 = ay - w;
            y1 = by + w;
        } else if (kind == Primitive.GLYPH) {
            if (!ink(list, i, r)) {
                return false; // a space, or not in the atlas yet
            }
            x0 = r[0];
            y0 = r[1];
            x1 = r[2];
            y1 = r[3];
            inkHeight = y1 - y0; // before its clip cuts it
        }
        int clip = Primitive.clip(header);
        if (clip != DrawList.NO_CLIP) {
            float[] c = list.clips();
            int o = clip * DrawList.CLIP_FLOATS;
            x0 = Math.max(x0, c[o]);
            y0 = Math.max(y0, c[o + 1]);
            x1 = Math.min(x1, c[o + 2]);
            y1 = Math.min(y1, c[o + 3]);
        }
        r[0] = x0;
        r[1] = y0;
        r[2] = x1;
        r[3] = y1;
        return x1 - x0 > 0.5f && y1 - y0 > 0.5f;
    }

    /**
     * Glyph {@code i}'s inked rectangle where it draws into {@code r}, read from its atlas texels (MTSDF pages: the
     * true distance in alpha, inside from half; raster and colour glyphs: alpha coverage); false if it has no ink.
     */
    private boolean ink(DrawList list, int i, float[] r) {
        int header = list.header(i);
        boolean turned = list.drawnRotated(i); // rotated text: the ink in its own coordinates, then where that draws
        float x0 = turned ? list.floatAt(i, Primitive.X0) : list.drawnX(i, Primitive.X0);
        float y0 = turned ? list.floatAt(i, Primitive.Y0) : list.drawnY(i, Primitive.Y0);
        float x1 = turned ? list.floatAt(i, Primitive.X1) : list.drawnX(i, Primitive.X1);
        float y1 = turned ? list.floatAt(i, Primitive.Y1) : list.drawnY(i, Primitive.Y1);
        int texture = list.intAt(i, Primitive.TEXTURE);
        if (x1 <= x0 || y1 <= y0 || texture <= 0 || texture >= ui.textures().count()) {
            return false;
        }
        UiTexture t = ui.textures().get(texture);
        int uv0 = list.intAt(i, Primitive.UV);
        int uv1 = list.intAt(i, Primitive.UV + 1);
        float u0 = (uv0 & 0xFFFF) / 65535f;
        float v0 = (uv0 >>> 16) / 65535f;
        float u1 = (uv1 & 0xFFFF) / 65535f;
        float v1 = (uv1 >>> 16) / 65535f;
        int w = t.width();
        int tx0 = (int) Math.floor(u0 * w);
        int ty0 = (int) Math.floor(v0 * t.height());
        int tx1 = (int) Math.ceil(u1 * w);
        int ty1 = (int) Math.ceil(v1 * t.height());
        int threshold = (Primitive.flags(header) & (Primitive.FLAG_RASTER | Primitive.FLAG_COLOR)) == 0 ? 128 : 32;
        byte[] p = t.pixels();
        int ax = Integer.MAX_VALUE;
        int ay = Integer.MAX_VALUE;
        int bx = Integer.MIN_VALUE;
        int by = Integer.MIN_VALUE;
        for (int y = Math.max(0, ty0); y < Math.min(t.height(), ty1); y++) {
            for (int x = Math.max(0, tx0); x < Math.min(w, tx1); x++) {
                if ((p[(y * w + x) * 4 + 3] & 0xFF) >= threshold) {
                    ax = Math.min(ax, x);
                    ay = Math.min(ay, y);
                    bx = Math.max(bx, x + 1);
                    by = Math.max(by, y + 1);
                }
            }
        }
        if (ax > bx) {
            return false;
        }
        float sx = (x1 - x0) / ((u1 - u0) * w);
        float sy = (y1 - y0) / ((v1 - v0) * t.height());
        r[0] = x0 + (ax - u0 * w) * sx;
        r[1] = y0 + (ay - v0 * t.height()) * sy;
        r[2] = x0 + (bx - u0 * w) * sx;
        r[3] = y0 + (by - v0 * t.height()) * sy;
        if (turned) {
            list.drawnBounds(i, r[0], r[1], r[2], r[3], r);
        }
        return true;
    }

    /** Whether box {@code i} is drawn around the element's box (a focus ring): larger on every side. */
    private static boolean ring(DrawList list, int i, float x0, float y0, float x1, float y1) {
        return list.drawnX(i, Primitive.X0) < x0 - 0.5f && list.drawnY(i, Primitive.Y0) < y0 - 0.5f
            && list.drawnX(i, Primitive.X1) > x1 + 0.5f && list.drawnY(i, Primitive.Y1) > y1 + 0.5f;
    }

    /**
     * The clip rectangle of the nearest clipping ancestor (its box within its own ancestors' clips, each where it draws:
     * scaled and moved by its opacity, scale and offset), or null.
     */
    private float[] clipOfAncestor(int slot) {
        float[] c = null;
        for (int a = store.parent(slot); a != UiStore.NONE; a = store.parent(a)) {
            if (!ui.styles().styleOf(a).get(Styles.CLIP)) {
                continue;
            }
            ui.visuals().compose(a); // where it cuts: scaled and moved as it draws
            float k = ui.visuals().composedScale();
            float x0 = layout.x(a) * k + ui.visuals().composedX();
            float y0 = layout.y(a) * k + ui.visuals().composedY();
            float x1 = x0 + layout.width(a) * k;
            float y1 = y0 + layout.height(a) * k;
            if (c == null) {
                c = new float[] {x0, y0, x1, y1};
            } else {
                c[0] = Math.max(c[0], x0);
                c[1] = Math.max(c[1], y0);
                c[2] = Math.min(c[2], x1);
                c[3] = Math.min(c[3], y1);
            }
        }
        return c;
    }

    private static boolean outside(float[] r, float x0, float y0, float x1, float y1) {
        return r[0] < x0 - TOLERANCE || r[1] < y0 - TOLERANCE || r[2] > x1 + TOLERANCE || r[3] > y1 + TOLERANCE;
    }

    // ------------------------------------------------------------------ siblings

    private void siblings(int slot) {
        int parent = store.parent(slot);
        if (parent == UiStore.NONE) {
            return;
        }
        LayoutKind k = layout.kind(parent);
        if (k != LayoutKind.STACK && k != LayoutKind.FLEX && k != LayoutKind.GRID || overlapping(slot)) {
            return;
        }
        for (int o = store.nextSibling(slot); o != UiStore.NONE; o = store.nextSibling(o)) {
            if (!counts(o) || overlapping(o)) {
                continue;
            }
            float ix = Math.min(layout.x(slot) + layout.width(slot), layout.x(o) + layout.width(o))
                - Math.max(layout.x(slot), layout.x(o));
            float iy = Math.min(layout.y(slot) + layout.height(slot), layout.y(o) + layout.height(o))
                - Math.max(layout.y(slot), layout.y(o));
            if (ix > TOLERANCE && iy > TOLERANCE) {
                report(Kind.OVERLAP, slot, o, rect(slot), rect(o));
            }
        }
    }

    /** Whether the element overlaps its neighbours on purpose: a negative margin. */
    private boolean overlapping(int slot) {
        Insets m = ui.styles().styleOf(slot).get(Styles.MARGIN);
        return m.top() < 0 || m.right() < 0 || m.bottom() < 0 || m.left() < 0;
    }

    private float[] rect(int slot) {
        return new float[] {layout.x(slot), layout.y(slot), layout.x(slot) + layout.width(slot),
            layout.y(slot) + layout.height(slot)};
    }

    // ------------------------------------------------------------------ tab strips

    private void tabs(int slot, DrawList list) {
        if (!"tab-overflow".equals(store.id(slot))) {
            return;
        }
        int header = store.parent(slot);
        int pane = header == UiStore.NONE ? UiStore.NONE : store.parent(header);
        boolean menu = pane != UiStore.NONE
            && ui.styles().styleOf(pane).get(BasicStyles.TAB_OVERFLOW) == BasicStyles.TabOverflow.MENU;
        float[] button = rect(slot);
        tabsUnder(header, list, button, menu);
    }

    private void tabsUnder(int slot, DrawList list, float[] button, boolean menu) {
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (!counts(c)) {
                continue;
            }
            if ("tab".equals(store.type(c))) {
                float[] v = visibleBox(c, list);
                if (v == null) {
                    continue;
                }
                float ix = Math.min(v[2], button[2]) - Math.max(v[0], button[0]);
                float iy = Math.min(v[3], button[3]) - Math.max(v[1], button[1]);
                if (ix > 0 && iy > 0) {
                    report(Kind.TAB_UNDER_OVERFLOW, c, -1, v, button);
                } else if (menu && (v[2] - v[0] < layout.width(c) - TOLERANCE
                    || v[3] - v[1] < layout.height(c) - TOLERANCE)) {
                    report(Kind.TAB_PARTIAL, c, -1, v, rect(c));
                }
            } else {
                tabsUnder(c, list, button, menu);
            }
        }
    }

    /** The union of what the element's own range shows (cut by its clips), or null. */
    private float[] visibleBox(int slot, DrawList list) {
        float[] u = null;
        float[] r = new float[4];
        int start = store.rangeStart(slot);
        int end = start + store.rangeCapacity(slot);
        for (int i = start; i < end && i < list.size(); i++) {
            int kind = Primitive.kind(list.header(i));
            if (kind == Primitive.EMPTY || kind == Primitive.SHADOW || !visible(list, i, r)) {
                continue;
            }
            if (u == null) {
                u = r.clone();
            } else {
                u[0] = Math.min(u[0], r[0]);
                u[1] = Math.min(u[1], r[1]);
                u[2] = Math.max(u[2], r[2]);
                u[3] = Math.max(u[3], r[3]);
            }
        }
        return u;
    }

    // ------------------------------------------------------------------ reporting

    private void report(Kind kind, int slot, int other, float[] drawn, float[] bounds) {
        out.add(new Violation(kind, describe(slot), other < 0 ? null : describe(other),
            String.format(Locale.ROOT, "%s within %s", box(drawn), box(bounds))));
    }

    private static String box(float[] r) {
        return String.format(Locale.ROOT, "[%.1f %.1f %.1f %.1f]", r[0], r[1], r[2], r[3]);
    }

    /** The element as a path of {@code type.style#id} from its layer's root, with its semantic name. */
    private String describe(int slot) {
        StringBuilder b = new StringBuilder();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            StringBuilder one = new StringBuilder(store.type(s));
            if (store.style(s) != null) {
                one.append('.').append(store.style(s));
            }
            if (store.id(s) != null) {
                one.append('#').append(store.id(s));
            }
            b.insert(0, b.length() == 0 ? one : one.append(" > "));
        }
        String label = store.label(slot);
        if (label != null) {
            b.append(" \"").append(label).append('"');
        }
        return b.toString();
    }
}
