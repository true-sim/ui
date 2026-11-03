package com.truesim.ui.controls.anim;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.Styles;
import java.util.Arrays;

/**
 * Per-element values that override the resolved style while something animates or a control places a part (design
 * 4.8: "the animated values are written into the element's SoA overrides and stamped, so nothing allocates"):
 *
 * <ul>
 *   <li>drawing: {@link #OPACITY} (replaces the style's opacity; ancestors' multiply), {@link #SCALE}
 *   (about the element's centre, for its subtree; replaces the style's {@code scale}), {@link #TRANSLATE} (a drawn
 *   offset of the subtree, not seen by layout or picking), {@link #BACKGROUND} (the colours of a box or gradient
 *   background), {@link #COLOR} (text) and {@link #BORDER_COLOR};</li>
 *   <li>layout: {@link #WIDTH}, {@link #HEIGHT}, {@link #MIN_WIDTH}, {@link #MIN_HEIGHT}, {@link #OFFSETS} and
 *   {@link #ANCHORS}, written
 *   into the
 *   {@link Layout} and applied again after every style resolution, so a slider's thumb or a progress bar's fill
 *   keeps its place whatever its style does.</li>
 * </ul>
 *
 * <p>Setting a value marks what it affects dirty (draw for the element or its subtree, layout through the layout's
 * own setters), so it shows in the same frame. A slot reused for a new element starts with no overrides. Setting and
 * reading allocate nothing.
 */
public final class Visuals {

    public static final int OPACITY = 1;
    public static final int SCALE = 1 << 1;
    public static final int TRANSLATE = 1 << 2;
    public static final int BACKGROUND = 1 << 3;
    public static final int COLOR = 1 << 4;
    public static final int BORDER_COLOR = 1 << 5;
    public static final int WIDTH = 1 << 6;
    public static final int HEIGHT = 1 << 7;
    public static final int OFFSETS = 1 << 8;
    public static final int ANCHORS = 1 << 9;
    public static final int MIN_WIDTH = 1 << 10;
    public static final int MIN_HEIGHT = 1 << 11;

    /** The overrides drawn for the subtree (its descendants record again when they change). */
    static final int SUBTREE = OPACITY | SCALE | TRANSLATE;
    /** The overrides applied to layout. */
    static final int LAYOUT = WIDTH | HEIGHT | OFFSETS | ANCHORS | MIN_WIDTH | MIN_HEIGHT;

    /** Re-applies an element's style to its layout inputs: what clearing a layout override falls back to. */
    @FunctionalInterface
    public interface LayoutRestyler {
        /** Applies {@code slot}'s resolved style to its layout inputs again. */
        void restyle(int slot);
    }

    private final UiStore store;
    private final Layout layout;
    private final StyleResolver styles;
    private LayoutRestyler restyler;

    private int[] gen = new int[0];
    private int[] set = new int[0];
    private float[] opacity = new float[0];
    private float[] scale = new float[0];
    private float[] tx = new float[0];
    private float[] ty = new float[0];
    private long[] backgroundA = new long[0];
    private long[] backgroundB = new long[0];
    private long[] color = new long[0];
    private long[] borderColor = new long[0];
    private float[] width = new float[0];
    private float[] height = new float[0];
    private byte[] widthUnit = new byte[0];
    private byte[] heightUnit = new byte[0];
    private float[] minWidth = new float[0];
    private float[] minHeight = new float[0];
    private float[] offsets = new float[0];
    private float[] anchors = new float[0];

    // composed by compose()
    private float composedScale;
    private float composedX;
    private float composedY;
    private float composedOpacity;

    private static final Unit[] UNITS = {Unit.PX, Unit.EM, Unit.PERCENT, Unit.AUTO, Unit.FR};

    /** The overrides of {@code store}'s elements, laid out by {@code layout} and styled by {@code styles}. */
    @AllowAllocation("one per UI")
    public Visuals(UiStore store, Layout layout, StyleResolver styles) {
        this.store = store;
        this.layout = layout;
        this.styles = styles;
        ensure();
    }

    /** What clearing a layout override falls back to: the UI frame re-applies the element's style. */
    public void restyler(LayoutRestyler value) {
        restyler = value;
    }

    @AllowAllocation("growing with the store, rarely")
    private void ensure() {
        int n = store.capacity();
        if (gen.length >= n) {
            return;
        }
        gen = Arrays.copyOf(gen, n);
        set = Arrays.copyOf(set, n);
        opacity = Arrays.copyOf(opacity, n);
        scale = Arrays.copyOf(scale, n);
        tx = Arrays.copyOf(tx, n);
        ty = Arrays.copyOf(ty, n);
        backgroundA = Arrays.copyOf(backgroundA, n);
        backgroundB = Arrays.copyOf(backgroundB, n);
        color = Arrays.copyOf(color, n);
        borderColor = Arrays.copyOf(borderColor, n);
        width = Arrays.copyOf(width, n);
        height = Arrays.copyOf(height, n);
        widthUnit = Arrays.copyOf(widthUnit, n);
        heightUnit = Arrays.copyOf(heightUnit, n);
        minWidth = Arrays.copyOf(minWidth, n);
        minHeight = Arrays.copyOf(minHeight, n);
        offsets = Arrays.copyOf(offsets, n * 4);
        anchors = Arrays.copyOf(anchors, n * 4);
    }

    /** Makes {@code slot}'s overrides its current element's (a reused slot starts with none); returns the slot. */
    private int own(int slot) {
        if (slot >= gen.length) {
            ensure();
        }
        int g = store.generation(slot);
        if (gen[slot] != g) {
            gen[slot] = g;
            set[slot] = 0;
        }
        return slot;
    }

    /** The overrides set on {@code slot} (the bits of this class). */
    public int flags(int slot) {
        return slot < gen.length && gen[slot] == store.generation(slot) ? set[slot] : 0;
    }

    /** Whether {@code slot} has every override in {@code bits}. */
    public boolean has(int slot, int bits) {
        return (flags(slot) & bits) == bits;
    }

    // ---------------------------------------------------------------- drawing

    /** Draws {@code element} and its subtree at opacity {@code value} instead of its style's. */
    public void opacity(Element element, float value) {
        opacity(element.slot(), value);
    }

    /** As {@link #opacity(Element, float)}, by slot. */
    public void opacity(int slot, float value) {
        own(slot);
        if ((set[slot] & OPACITY) == 0 || opacity[slot] != value) {
            opacity[slot] = value;
            set[slot] |= OPACITY;
            subtreeChanged(slot);
        }
    }

    /** The opacity of {@code slot}: its override, or its style's. */
    public float opacity(int slot) {
        return (flags(slot) & OPACITY) != 0 ? opacity[slot] : styles.styleOf(slot).getFloat(Styles.OPACITY);
    }

    /** Scales {@code element} and its subtree about its centre by {@code value}, instead of its style's scale. */
    public void scale(Element element, float value) {
        scale(element.slot(), value);
    }

    /** As {@link #scale(Element, float)}, by slot. */
    public void scale(int slot, float value) {
        own(slot);
        if ((set[slot] & SCALE) == 0 || scale[slot] != value) {
            scale[slot] = value;
            set[slot] |= SCALE;
            subtreeChanged(slot);
        }
    }

    /** The scale of {@code slot}: its override, or its style's. */
    public float scale(int slot) {
        return (flags(slot) & SCALE) != 0 ? scale[slot] : styles.styleOf(slot).getFloat(Styles.SCALE);
    }

    /** Draws {@code element} and its subtree moved by {@code x, y} panel units (layout and picking do not move). */
    public void translate(Element element, float x, float y) {
        translate(element.slot(), x, y);
    }

    /** As {@link #translate(Element, float, float)}, by slot. */
    public void translate(int slot, float x, float y) {
        own(slot);
        if ((set[slot] & TRANSLATE) == 0 || tx[slot] != x || ty[slot] != y) {
            tx[slot] = x;
            ty[slot] = y;
            set[slot] |= TRANSLATE;
            subtreeChanged(slot);
        }
    }

    /** The drawn offset's x of {@code slot}, 0 without one. */
    public float translateX(int slot) {
        return (flags(slot) & TRANSLATE) != 0 ? tx[slot] : 0;
    }

    /** The drawn offset's y of {@code slot}, 0 without one. */
    public float translateY(int slot) {
        return (flags(slot) & TRANSLATE) != 0 ? ty[slot] : 0;
    }

    /**
     * Draws {@code element}'s background in packed colours ({@code Rgba}) instead of its style's: {@code a} fills a
     * box, {@code a} to {@code b} a gradient (its angle stays the style's). Other paints are not affected.
     */
    public void background(Element element, long a, long b) {
        background(element.slot(), a, b);
    }

    /** As {@link #background(Element, long, long)}, by slot. */
    public void background(int slot, long a, long b) {
        own(slot);
        if ((set[slot] & BACKGROUND) == 0 || backgroundA[slot] != a || backgroundB[slot] != b) {
            backgroundA[slot] = a;
            backgroundB[slot] = b;
            set[slot] |= BACKGROUND;
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
    }

    /** The first background colour override. */
    public long backgroundA(int slot) {
        return backgroundA[slot];
    }

    /** The second background colour override (a gradient's end). */
    public long backgroundB(int slot) {
        return backgroundB[slot];
    }

    /** Draws {@code element}'s text in packed colour {@code rgba} instead of its style's. */
    public void color(Element element, long rgba) {
        color(element.slot(), rgba);
    }

    /** As {@link #color(Element, long)}, by slot. */
    public void color(int slot, long rgba) {
        own(slot);
        if ((set[slot] & COLOR) == 0 || color[slot] != rgba) {
            color[slot] = rgba;
            set[slot] |= COLOR;
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
    }

    /** The text colour override. */
    public long color(int slot) {
        return color[slot];
    }

    /** Draws {@code element}'s border in packed colour {@code rgba} instead of its style's (the width stays). */
    public void borderColor(int slot, long rgba) {
        own(slot);
        if ((set[slot] & BORDER_COLOR) == 0 || borderColor[slot] != rgba) {
            borderColor[slot] = rgba;
            set[slot] |= BORDER_COLOR;
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
    }

    /** The border colour override. */
    public long borderColor(int slot) {
        return borderColor[slot];
    }

    // ---------------------------------------------------------------- layout

    /** Sizes {@code element}'s width to {@code value} in {@code unit}, whatever its style says. */
    public void width(Element element, float value, Unit unit) {
        width(element.slot(), value, unit);
    }

    /** As {@link #width(Element, float, Unit)}, by slot. */
    public void width(int slot, float value, Unit unit) {
        own(slot);
        width[slot] = value;
        widthUnit[slot] = (byte) unit.ordinal();
        set[slot] |= WIDTH;
        layout.width(slot, value, unit);
    }

    /** Sizes {@code element}'s height to {@code value} in {@code unit}, whatever its style says. */
    public void height(Element element, float value, Unit unit) {
        height(element.slot(), value, unit);
    }

    /** As {@link #height(Element, float, Unit)}, by slot. */
    public void height(int slot, float value, Unit unit) {
        own(slot);
        height[slot] = value;
        heightUnit[slot] = (byte) unit.ordinal();
        set[slot] |= HEIGHT;
        layout.height(slot, value, unit);
    }

    /** Keeps {@code slot} at least {@code value} panel units wide, whatever its style says. */
    public void minWidth(int slot, float value) {
        own(slot);
        minWidth[slot] = value;
        set[slot] |= MIN_WIDTH;
        layout.minWidth(slot, value, Unit.PX);
    }

    /** Keeps {@code slot} at least {@code value} panel units high, whatever its style says. */
    public void minHeight(int slot, float value) {
        own(slot);
        minHeight[slot] = value;
        set[slot] |= MIN_HEIGHT;
        layout.minHeight(slot, value, Unit.PX);
    }

    /** The width override's value. */
    public float width(int slot) {
        return width[slot];
    }

    /** The height override's value. */
    public float height(int slot) {
        return height[slot];
    }

    /** Sets {@code element}'s offsets (left, top, right, bottom; the position in a {@code none} layout). */
    public void offsets(Element element, float left, float top, float right, float bottom) {
        offsets(element.slot(), left, top, right, bottom);
    }

    /** As {@link #offsets(Element, float, float, float, float)}, by slot. */
    public void offsets(int slot, float left, float top, float right, float bottom) {
        own(slot);
        int o = slot * 4;
        offsets[o] = left;
        offsets[o + 1] = top;
        offsets[o + 2] = right;
        offsets[o + 3] = bottom;
        set[slot] |= OFFSETS;
        layout.offsets(slot, left, top, right, bottom);
    }

    /** Offset {@code edge} (0 left, 1 top, 2 right, 3 bottom) of the override. */
    public float offset(int slot, int edge) {
        return offsets[slot * 4 + edge];
    }

    /** Sets {@code element}'s anchors (fractions of its parent's content box, in an anchors layout). */
    public void anchors(Element element, float left, float top, float right, float bottom) {
        anchors(element.slot(), left, top, right, bottom);
    }

    /** As {@link #anchors(Element, float, float, float, float)}, by slot. */
    public void anchors(int slot, float left, float top, float right, float bottom) {
        own(slot);
        int o = slot * 4;
        anchors[o] = left;
        anchors[o + 1] = top;
        anchors[o + 2] = right;
        anchors[o + 3] = bottom;
        set[slot] |= ANCHORS;
        layout.anchors(slot, left, top, right, bottom);
    }

    /** Anchor {@code edge} (0 left, 1 top, 2 right, 3 bottom) of the override. */
    public float anchor(int slot, int edge) {
        return anchors[slot * 4 + edge];
    }

    /** Applies {@code slot}'s layout overrides again: the UI frame calls this after applying its style. */
    public void applyLayout(int slot) {
        int f = flags(slot);
        if ((f & LAYOUT) == 0) {
            return;
        }
        if ((f & WIDTH) != 0) {
            layout.width(slot, width[slot], UNITS[widthUnit[slot]]);
        }
        if ((f & HEIGHT) != 0) {
            layout.height(slot, height[slot], UNITS[heightUnit[slot]]);
        }
        if ((f & MIN_WIDTH) != 0) {
            layout.minWidth(slot, minWidth[slot], Unit.PX);
        }
        if ((f & MIN_HEIGHT) != 0) {
            layout.minHeight(slot, minHeight[slot], Unit.PX);
        }
        int o = slot * 4;
        if ((f & OFFSETS) != 0) {
            layout.offsets(slot, offsets[o], offsets[o + 1], offsets[o + 2], offsets[o + 3]);
        }
        if ((f & ANCHORS) != 0) {
            layout.anchors(slot, anchors[o], anchors[o + 1], anchors[o + 2], anchors[o + 3]);
        }
    }

    // ---------------------------------------------------------------- clearing

    /** Removes {@code element}'s overrides in {@code bits}: its style shows again. */
    public void clear(Element element, int bits) {
        clear(element.slot(), bits);
    }

    /** As {@link #clear(Element, int)}, by slot. */
    public void clear(int slot, int bits) {
        int f = flags(slot);
        int gone = f & bits;
        if (gone == 0) {
            return;
        }
        set[slot] = f & ~bits;
        if ((gone & SUBTREE) != 0) {
            subtreeChanged(slot);
        } else {
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
        if ((gone & LAYOUT) != 0 && restyler != null) {
            restyler.restyle(slot);
        }
    }

    private void subtreeChanged(int slot) {
        store.markDirty(slot, UiStore.DIRTY_DRAW);
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            subtreeChanged(c);
        }
    }

    // ---------------------------------------------------------------- composition for drawing

    /**
     * Composes what {@code slot} draws with: the opacity of it and its ancestors (styles times overrides) and the
     * transform of their scales (about each one's centre) and drawn offsets, read back by {@link #composedOpacity()},
     * {@link #composedScale()}, {@link #composedX()} and {@link #composedY()}. Walks the ancestors; allocates nothing.
     */
    public void compose(int slot) {
        float s = 1;
        float x = 0;
        float y = 0;
        float o = 1;
        float unit = layout.scale() > 0 ? layout.scale() : 1;
        for (int e = slot; e != UiStore.NONE; e = store.parent(e)) {
            ResolvedStyle style = styles.styleOf(e);
            int f = flags(e);
            o *= (f & OPACITY) != 0 ? opacity[e] : style.getFloat(Styles.OPACITY);
            float es = (f & SCALE) != 0 ? scale[e] : style.getFloat(Styles.SCALE);
            float ex = (f & TRANSLATE) != 0 ? tx[e] * unit : 0;
            float ey = (f & TRANSLATE) != 0 ? ty[e] * unit : 0;
            if (es != 1 || ex != 0 || ey != 0) {
                float cx = layout.x(e) + layout.width(e) * 0.5f;
                float cy = layout.y(e) + layout.height(e) * 0.5f;
                x = es * (x - cx) + cx + ex;
                y = es * (y - cy) + cy + ey;
                s *= es;
            }
        }
        composedScale = s;
        composedX = x;
        composedY = y;
        composedOpacity = o;
    }

    /** The composed opacity of the last {@link #compose}. */
    public float composedOpacity() {
        return composedOpacity;
    }

    /** The composed scale of the last {@link #compose}. */
    public float composedScale() {
        return composedScale;
    }

    /** The composed transform's x offset of the last {@link #compose}: a point maps to {@code scale * x + this}. */
    public float composedX() {
        return composedX;
    }

    /** The composed transform's y offset of the last {@link #compose}. */
    public float composedY() {
        return composedY;
    }

    /** Whether {@code slot} draws with a transform of its own (a scale other than 1 or a drawn offset). */
    public boolean transformed(int slot) {
        int f = flags(slot);
        float es = (f & SCALE) != 0 ? scale[slot] : styles.styleOf(slot).getFloat(Styles.SCALE);
        return es != 1 || (f & TRANSLATE) != 0 && (tx[slot] != 0 || ty[slot] != 0);
    }

    /** Marks {@code slot}'s subtree to record again (its transform or opacity changed). */
    public void redrawSubtree(int slot) {
        subtreeChanged(slot);
    }
}
