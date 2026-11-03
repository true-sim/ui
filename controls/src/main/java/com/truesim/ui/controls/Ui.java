package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.StyleWatcher;
import com.truesim.ui.style.value.Anchors;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.ImageFit;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.TextOutline;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One retained UI: its elements, style resolution, layout, text and drawing, updated once a frame. The frame does
 * only what changed (design 4.2): the store queues changed elements; styles are resolved for those only, from the
 * resolver's cache (never looked up per frame otherwise); layout runs on dirty subtrees; only elements whose look or
 * place changed record their primitives again, into their range of the draw list, and only changed words are
 * uploaded. A frame where nothing changed costs a few comparisons and allocates nothing.
 *
 * <p>Everything presentational comes from the style sheet ({@link Styles}' keys): sizes, colours, fonts, spacing,
 * radii, borders, shadows, images, layout. Code makes elements, sets their type, id, style name, state and text.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * Element hud = ui.panel("hud");
 * Element health = ui.label("value", "100");
 * ui.root().add(hud.add(health));
 * // each frame:
 * ui.text(health, hpText);
 * ui.update(width, height);
 * renderer.submit(ui.layers(), 1, ui.textures(), width, height);
 * }</pre>
 */
public final class Ui {

    private static final int NONE = 0;
    private static final int TEXT = 1;
    private static final int IMAGE = 2;

    private static final com.truesim.ui.layout.LayoutKind[] KINDS = com.truesim.ui.layout.LayoutKind.values();
    private static final com.truesim.ui.layout.Direction[] DIRECTIONS = com.truesim.ui.layout.Direction.values();
    private static final com.truesim.ui.layout.Wrap[] WRAPS = com.truesim.ui.layout.Wrap.values();
    private static final com.truesim.ui.layout.Justify[] JUSTIFIES = com.truesim.ui.layout.Justify.values();
    private static final com.truesim.ui.layout.Align[] ALIGNS = com.truesim.ui.layout.Align.values();
    private static final com.truesim.ui.layout.BorderRegion[] REGIONS = com.truesim.ui.layout.BorderRegion.values();
    private static final Unit[] UNITS = {Unit.PX, Unit.EM, Unit.PERCENT, Unit.AUTO};
    private static final TextAlign[] TEXT_ALIGNS = TextAlign.values();
    private static final float RADIANS = 0.017453292f; // pi / 180

    private final UiStore store;
    private final StyleResolver resolver;
    private final Layout layout;
    private final TextSystem text;
    private final UiTextures textures;
    private final UiImages images;
    private final DrawList list = new DrawList();
    private final DrawList[] layers = {list};
    private final Measure measure = this::measure;
    private StyleWatcher watcher;

    // per slot
    private int[] content = new int[0];
    private TextBlock[] blocks = new TextBlock[0];
    private StringBuilder[] strings = new StringBuilder[0];
    private long[] drawnTextVersion = new long[0];
    private ResolvedStyle[] applied = new ResolvedStyle[0];
    private long[] appliedVersion = new long[0];
    private int[] textSlots = new int[16];
    private int textSlotCount;

    private boolean rebuild = true;
    private boolean worldSpace;
    private long seenCompletions = -1;
    private long seenTechnique = -1;
    private int recorded; // elements recorded in the last update
    private long frames;

    /** A UI styled by {@code sheet}, its text set by {@code text} (null: no text, for tests of boxes). */
    @AllowAllocation("one per UI")
    public Ui(StyleSheet sheet, TextSystem text) {
        this(sheet, text, text != null && text.atlas() != null ? null : new UiTextures());
    }

    /** As {@link #Ui(StyleSheet, TextSystem)}, its textures in {@code textures} (the atlas's, when it has one). */
    @AllowAllocation("one per UI")
    public Ui(StyleSheet sheet, TextSystem text, UiTextures textures) {
        this.store = new UiStore();
        this.resolver = new StyleResolver(store, sheet);
        this.layout = new Layout(store);
        this.text = text;
        UiTextures own = textures;
        if (own == null) {
            own = atlasTextures(text.atlas());
        }
        this.textures = own;
        this.images = new UiImages(own);
        ensure();
        store.semantics(store.rootSlot(), Role.GROUP, null);
    }

    private static UiTextures atlasTextures(GlyphAtlas atlas) {
        return atlas.textures();
    }

    // ---------------------------------------------------------------- building

    /** The root: the screen the UI covers. */
    public Element root() {
        return store.root();
    }

    /** A new panel (type {@code panel}) with style name {@code style} (or null). */
    @AllowAllocation("elements are made when a UI is built")
    public Element panel(String style) {
        return element("panel", style);
    }

    /** A new element of {@code type} with style name {@code style} (or null). */
    @AllowAllocation("elements are made when a UI is built")
    public Element element(String type, String style) {
        Element e = store.create(type, null);
        if (style != null) {
            e.style(style);
        }
        ensure();
        content[e.slot()] = NONE;
        applied[e.slot()] = null; // a reused slot applies its style again
        return e;
    }

    /** A new label (type {@code label}) showing {@code initial}. */
    @AllowAllocation("elements are made when a UI is built")
    public Element label(String style, CharSequence initial) {
        if (text == null) {
            throw new IllegalStateException("a UI without a text system has no labels");
        }
        Element e = element("label", style);
        int slot = e.slot();
        content[slot] = TEXT;
        if (blocks[slot] == null) {
            blocks[slot] = new TextBlock(text);
            strings[slot] = new StringBuilder();
        }
        blocks[slot].worldSpace(worldSpace);
        strings[slot].setLength(0);
        strings[slot].append(initial);
        drawnTextVersion[slot] = -1;
        layout.measured(slot, true);
        if (textSlotCount == textSlots.length) {
            textSlots = Arrays.copyOf(textSlots, textSlotCount * 2);
        }
        textSlots[textSlotCount++] = slot;
        e.semantics(Role.TEXT, initial.toString());
        return e;
    }

    /** A new image element (type {@code image}); the image and its fit come from the style's {@code image}. */
    @AllowAllocation("elements are made when a UI is built")
    public Element image(String style) {
        Element e = element("image", style);
        content[e.slot()] = IMAGE;
        layout.measured(e.slot(), true);
        e.semantics(Role.IMAGE, null);
        return e;
    }

    /** Sets a label's text; nothing happens (and nothing allocates) when it is the same. */
    public void text(Element label, CharSequence value) {
        int slot = label.slot();
        if (content[slot] != TEXT) {
            throw new IllegalArgumentException(label + " is not a label");
        }
        StringBuilder s = strings[slot];
        if (s.length() == value.length() && contentEquals(s, value)) {
            return;
        }
        s.setLength(0);
        s.append(value);
        store.markDirty(slot, UiStore.DIRTY_TEXT | UiStore.DIRTY_LAYOUT | UiStore.DIRTY_DRAW);
    }

    private static boolean contentEquals(StringBuilder a, CharSequence b) {
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** A label's current text. */
    public CharSequence text(Element label) {
        return strings[label.slot()];
    }

    /** Destroys {@code element} and its subtree. */
    public void destroy(Element element) {
        int slot = element.slot();
        element.destroy();
        for (int i = 0; i < textSlotCount; i++) {
            if (!store.alive(textSlots[i])) {
                textSlots[i--] = textSlots[--textSlotCount];
            }
        }
        content[slot] = NONE;
        rebuild = true;
    }

    /**
     * Marks this UI as a world-space panel (3D or VR: its text draws MTSDF at every size, sharp at any distance and
     * angle) or a screen UI (the default: small text may draw hinted raster glyphs, {@code ui.text.smallText}). Changes
     * no layout; the labels record again.
     */
    public void worldSpace(boolean on) {
        if (on == worldSpace) {
            return;
        }
        worldSpace = on;
        for (int i = 0; i < textSlotCount; i++) {
            int slot = textSlots[i];
            blocks[slot].worldSpace(on);
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
    }

    /** Whether this UI is a world-space panel. */
    public boolean worldSpace() {
        return worldSpace;
    }

    /** Follows a development file watch: each update applies its last good sheet. */
    public void watch(StyleWatcher sheets) {
        this.watcher = sheets;
    }

    @AllowAllocation("growing with the store, rarely")
    private void ensure() {
        int n = store.capacity();
        if (content.length < n) {
            content = Arrays.copyOf(content, n);
            blocks = Arrays.copyOf(blocks, n);
            strings = Arrays.copyOf(strings, n);
            drawnTextVersion = Arrays.copyOf(drawnTextVersion, n);
            applied = Arrays.copyOf(applied, n);
            appliedVersion = Arrays.copyOf(appliedVersion, n);
        }
    }

    // ---------------------------------------------------------------- the frame

    /**
     * Brings the UI up to date for a {@code width x height} pixel screen: hands over finished glyphs, applies a
     * reloaded sheet and token changes, resolves the styles of changed elements, lays out what changed and records
     * what changed. Call once a frame, after input and game code changed the UI's state, before submitting.
     */
    public void update(float width, float height) {
        frames++;
        recorded = 0;
        GlyphAtlas atlas = text == null ? null : text.atlas();
        if (atlas != null) {
            atlas.beginFrame();
            long technique = atlas.settings().techniqueVersion();
            if (atlas.completions() != seenCompletions || technique != seenTechnique) {
                seenCompletions = atlas.completions();
                seenTechnique = technique;
                for (int i = 0; i < textSlotCount; i++) {
                    int slot = textSlots[i];
                    if (blocks[slot].version() != drawnTextVersion[slot]) {
                        store.markDirty(slot, UiStore.DIRTY_DRAW);
                    }
                }
            }
        }
        if (watcher != null) {
            watcher.apply(resolver);
        }
        resolver.update();

        // styles and text: the queue grows while resolving (children of changed contexts)
        for (int i = 0; i < store.dirtyCount(); i++) {
            int slot = store.dirtySlots()[i];
            if (!store.alive(slot)) {
                continue;
            }
            int bits = store.dirty(slot);
            if ((bits & UiStore.DIRTY_STYLE) != 0) {
                resolver.resolve(slot);
            }
            // compared, not taken from the bit: resolving a child resolves its parents first and clears their bits
            ResolvedStyle style = resolver.styleOf(slot);
            if (style != applied[slot] || style.version() != appliedVersion[slot]) {
                applied[slot] = style;
                appliedVersion[slot] = style.version();
                apply(slot, style);
                store.markDirty(slot, UiStore.DIRTY_DRAW | UiStore.DIRTY_TEXT);
            }
            if ((store.dirty(slot) & UiStore.DIRTY_STRUCTURE) != 0) {
                rebuild = true;
            }
        }

        layout.update(measure, width, height);
        int[] moved = layout.changedSlots();
        for (int i = 0; i < layout.changedCount(); i++) {
            store.markDirty(moved[i], UiStore.DIRTY_DRAW);
        }

        if (rebuild) {
            rebuildDrawList();
        } else {
            for (int i = 0; i < store.dirtyCount(); i++) {
                int slot = store.dirtySlots()[i];
                if (store.alive(slot) && (store.dirty(slot) & UiStore.DIRTY_DRAW) != 0) {
                    store.clearDirty(slot, UiStore.DIRTY_DRAW);
                    if (!record(slot)) {
                        rebuildDrawList();
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < store.dirtyCount(); i++) {
            store.clearDirty(store.dirtySlots()[i], -1);
        }
        store.clearDirtyQueue();
    }

    // ---------------------------------------------------------------- styles onto layout and text

    private void apply(int slot, ResolvedStyle s) {
        Border border = s.get(Styles.BORDER);
        Insets padding = s.get(Styles.PADDING);
        Insets margin = s.get(Styles.MARGIN);
        float bw = border.width();
        layout.kind(slot, KINDS[s.get(Styles.LAYOUT).ordinal()])
            .direction(slot, DIRECTIONS[s.get(Styles.DIRECTION).ordinal()])
            .wrap(slot, WRAPS[s.get(Styles.WRAP).ordinal()])
            .justifyContent(slot, JUSTIFIES[s.get(Styles.JUSTIFY_CONTENT).ordinal()])
            .alignItems(slot, ALIGNS[s.get(Styles.ALIGN_ITEMS).ordinal()])
            .alignSelf(slot, ALIGNS[s.get(Styles.ALIGN_SELF).ordinal()])
            .gap(slot, s.getFloat(Styles.GAP), s.getFloat(Styles.GAP))
            .padding(slot, padding.top() + bw, padding.right() + bw, padding.bottom() + bw, padding.left() + bw)
            .margin(slot, margin.top(), margin.right(), margin.bottom(), margin.left())
            .grow(slot, s.getFloat(Styles.GROW))
            .shrink(slot, s.getFloat(Styles.SHRINK))
            .aspectRatio(slot, s.getFloat(Styles.ASPECT_RATIO))
            .region(slot, REGIONS[s.get(Styles.REGION).ordinal()])
            .fontSize(slot, s.getFloat(Styles.FONT_SIZE));
        Length basis = s.get(Styles.BASIS);
        layout.basis(slot, basis.value(), UNITS[basis.unit().ordinal()]);
        Length v = s.get(Styles.WIDTH);
        layout.width(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.HEIGHT);
        layout.height(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MIN_WIDTH);
        layout.minWidth(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MIN_HEIGHT);
        layout.minHeight(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MAX_WIDTH);
        layout.maxWidth(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MAX_HEIGHT);
        layout.maxHeight(slot, v.value(), UNITS[v.unit().ordinal()]);
        Anchors anchors = s.get(Styles.ANCHORS);
        layout.anchors(slot, anchors.left(), anchors.top(), anchors.right(), anchors.bottom());
        Insets offsets = s.get(Styles.OFFSETS);
        layout.offsets(slot, offsets.left(), offsets.top(), offsets.right(), offsets.bottom());
        GridPlacement column = s.get(Styles.GRID_COLUMN);
        layout.gridColumnPlacement(slot, column.start(), column.span());
        GridPlacement row = s.get(Styles.GRID_ROW);
        layout.gridRowPlacement(slot, row.start(), row.span());
        List<Track> columns = s.get(Styles.GRID_TEMPLATE_COLUMNS);
        layout.gridColumns(slot, columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Track t = columns.get(i);
            layout.gridColumn(slot, i, minUnit(t), minValue(t), maxUnit(t), maxValue(t));
        }
        List<Track> rows = s.get(Styles.GRID_TEMPLATE_ROWS);
        layout.gridRows(slot, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Track t = rows.get(i);
            layout.gridRow(slot, i, minUnit(t), minValue(t), maxUnit(t), maxValue(t));
        }
    }

    private static Unit minUnit(Track t) {
        return switch (t) {
            case Track.Fixed f -> Unit.PX;
            case Track.MinMax m -> minUnit(m.min());
            default -> Unit.AUTO;
        };
    }

    private static float minValue(Track t) {
        return switch (t) {
            case Track.Fixed f -> f.px();
            case Track.MinMax m -> minValue(m.min());
            default -> 0;
        };
    }

    private static Unit maxUnit(Track t) {
        return switch (t) {
            case Track.Fixed f -> Unit.PX;
            case Track.Fraction f -> Unit.FR;
            case Track.MinMax m -> maxUnit(m.max());
            default -> Unit.AUTO;
        };
    }

    private static float maxValue(Track t) {
        return switch (t) {
            case Track.Fixed f -> f.px();
            case Track.Fraction f -> f.fr();
            case Track.MinMax m -> maxValue(m.max());
            default -> 0;
        };
    }

    /** Sets the label's text block for a box {@code maxWidthPx} pixels wide. */
    private boolean shape(int slot, float maxWidthPx) {
        ResolvedStyle s = resolver.styleOf(slot);
        float scale = layout.scale();
        return blocks[slot].set(strings[slot], s.get(Styles.FONT_FAMILY), s.getFloat(Styles.FONT_SIZE) * scale,
            s.getInt(Styles.FONT_WEIGHT), s.get(Styles.FONT_STYLE) != FontStyle.NORMAL,
            TEXT_ALIGNS[s.get(Styles.TEXT_ALIGN).ordinal()], maxWidthPx, s.getFloat(Styles.LINE_HEIGHT));
    }

    private long measure(int slot, float width, int widthMode, float height, int heightMode) {
        float scale = layout.scale();
        if (content[slot] == TEXT) {
            shape(slot, widthMode == Measure.UNDEFINED ? Float.POSITIVE_INFINITY : width * scale + 0.5f);
            TextBlock b = blocks[slot];
            return Measure.size(b.width() / scale, b.height() / scale);
        }
        if (content[slot] == IMAGE) {
            UiTexture t = images.get(resolver.styleOf(slot).get(Styles.IMAGE).name());
            return t == null ? Measure.size(0, 0) : Measure.size(t.width(), t.height());
        }
        return Measure.size(0, 0);
    }

    // ---------------------------------------------------------------- drawing

    private void rebuildDrawList() {
        list.reset();
        rebuild = false;
        allocate(store.rootSlot());
    }

    /** Gives {@code slot} and its subtree ranges in tree order (parents under children), recording them. */
    private void allocate(int slot) {
        int start = list.size();
        list.begin(start, 0); // count only
        recordPrimitives(slot);
        list.end();
        int needed = list.needed();
        // text grows by a few glyphs (a counter's digits) without a rebuild
        int capacity = needed + (content[slot] == TEXT ? Math.max(4, needed / 4) : 0);
        if (capacity > 0) {
            start = list.allocate(capacity);
        }
        store.range(slot, start, capacity);
        if (needed > 0) {
            record(slot);
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            allocate(c);
        }
    }

    /** Records the element's primitives into its range; false if they do not fit (a rebuild gives it a larger one). */
    private boolean record(int slot) {
        if (!attached(slot)) {
            return true; // not shown: recorded when added (adding rebuilds)
        }
        list.begin(store.rangeStart(slot), store.rangeCapacity(slot));
        recordPrimitives(slot);
        recorded++;
        return list.end();
    }

    private boolean attached(int slot) {
        int s = slot;
        while (store.parent(s) != UiStore.NONE) {
            s = store.parent(s);
        }
        return s == store.rootSlot();
    }

    private void recordPrimitives(int slot) {
        if (slot == store.rootSlot() && !resolver.styleOf(slot).isSet(Styles.BACKGROUND)) {
            return;
        }
        ResolvedStyle s = resolver.styleOf(slot);
        float scale = layout.scale();
        float x0 = layout.x(slot);
        float y0 = layout.y(slot);
        float x1 = x0 + layout.width(slot);
        float y1 = y0 + layout.height(slot);
        float opacity = s.getFloat(Styles.OPACITY);
        Radii r = s.get(Styles.RADIUS);
        float tl = r.topLeft() * scale;
        float tr = r.topRight() * scale;
        float br = r.bottomRight() * scale;
        float bl = r.bottomLeft() * scale;

        List<Shadow> shadows = s.get(Styles.SHADOW);
        for (int i = 0; i < shadows.size(); i++) {
            Shadow sh = shadows.get(i);
            float spread = sh.spread() * scale;
            float dx = sh.x() * scale;
            float dy = sh.y() * scale;
            list.shadow(x0 + dx - spread, y0 + dy - spread, x1 + dx + spread, y1 + dy + spread,
                Rgba.fade(sh.color().rgba(), opacity), tl + spread, tr + spread, br + spread, bl + spread,
                sh.blur() * scale * 0.5f);
        }

        Border border = s.get(Styles.BORDER);
        float bw = border.width() * scale;
        long borderColor = Rgba.fade(border.color().rgba(), opacity);
        Paint paint = s.get(Styles.BACKGROUND);
        switch (paint) {
            case Paint.Box box -> list.box(x0, y0, x1, y1, Rgba.fade(box.color().rgba(), opacity), tl, tr, br, bl, bw,
                borderColor);
            case Paint.LinearGradient g -> list.gradientBox(x0, y0, x1, y1, Rgba.fade(g.from().rgba(), opacity),
                Rgba.fade(g.to().rgba(), opacity), (g.angle() - 90) * RADIANS, tl, tr, br, bl, bw, borderColor);
            case Paint.Image image -> {
                UiTexture t = images.get(image.name());
                if (t != null) {
                    list.image(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), Rgba.fade(image.tint().rgba(), opacity));
                }
                borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
            }
            case Paint.NineSlice nine -> {
                UiTexture t = images.get(nine.image());
                if (t != null) {
                    Insets d = nine.destination();
                    float l = d == null ? nine.left() * scale : d.left() * scale;
                    float tp = d == null ? nine.top() * scale : d.top() * scale;
                    float rt = d == null ? nine.right() * scale : d.right() * scale;
                    float bt = d == null ? nine.bottom() * scale : d.bottom() * scale;
                    list.nineSlice(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), Rgba.fade(nine.tint().rgba(), opacity), l, tp, rt,
                        bt, nine.left(), nine.top(), nine.right(), nine.bottom());
                }
                borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
            }
            default -> borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
        }

        Insets padding = s.get(Styles.PADDING);
        float cx0 = x0 + bw + padding.left() * scale;
        float cy0 = y0 + bw + padding.top() * scale;
        float cx1 = x1 - bw - padding.right() * scale;
        float cy1 = y1 - bw - padding.bottom() * scale;
        if (content[slot] == TEXT) {
            shape(slot, cx1 - cx0 + 0.5f);
            TextOutline outline = s.get(Styles.TEXT_OUTLINE);
            TextBlock b = blocks[slot];
            b.record(list, cx0, cy0, Rgba.fade(s.rgba(Styles.COLOR), opacity), outline.width() * scale,
                Rgba.fade(outline.color().rgba(), opacity), 0);
            drawnTextVersion[slot] = b.version();
        } else if (content[slot] == IMAGE) {
            recordImage(s.get(Styles.IMAGE), opacity, cx0, cy0, cx1, cy1);
        }
    }

    private void borderOnly(float x0, float y0, float x1, float y1, float tl, float tr, float br, float bl, float bw,
        long color) {
        if (bw > 0) {
            list.box(x0, y0, x1, y1, Rgba.TRANSPARENT, tl, tr, br, bl, bw, color);
        }
    }

    private void recordImage(ImageSpec spec, float opacity, float x0, float y0, float x1, float y1) {
        UiTexture t = images.get(spec.name());
        if (t == null || x1 <= x0 || y1 <= y0) {
            return;
        }
        long tint = Rgba.fade(spec.tint().rgba(), opacity);
        float w = x1 - x0;
        float h = y1 - y0;
        float iw = t.width() * layout.scale();
        float ih = t.height() * layout.scale();
        ImageFit fit = spec.fit();
        if (fit == ImageFit.FILL) {
            list.image(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), tint);
        } else if (fit == ImageFit.COVER) {
            float k = Math.max(w / iw, h / ih);
            float du = (1 - w / (iw * k)) * 0.5f;
            float dv = (1 - h / (ih * k)) * 0.5f;
            list.image(x0, y0, x1, y1, du, dv, 1 - du, 1 - dv, t.id(), tint);
        } else {
            float k = fit == ImageFit.CONTAIN ? Math.min(w / iw, h / ih) : 1;
            float dw = iw * k;
            float dh = ih * k;
            float ox = x0 + (w - dw) * 0.5f;
            float oy = y0 + (h - dh) * 0.5f;
            list.image(ox, oy, ox + dw, oy + dh, 0, 0, 1, 1, t.id(), tint);
        }
    }

    // ---------------------------------------------------------------- access

    /** The element store. */
    public UiStore store() {
        return store;
    }

    /** The style resolver: tokens, inline styles, facts, a new sheet. */
    public StyleResolver styles() {
        return resolver;
    }

    /** The layout: positions and sizes after {@link #update}. */
    public Layout layout() {
        return layout;
    }

    /** The registered images. */
    public UiImages images() {
        return images;
    }

    /** The textures primitives name: atlas pages and images. */
    public UiTextures textures() {
        return textures;
    }

    /** The draw lists, back to front (one layer in A1). */
    public DrawList[] layers() {
        return layers;
    }

    /** How many layers {@link #layers()} holds. */
    public int layerCount() {
        return 1;
    }

    /** The text system, or null. */
    public TextSystem textSystem() {
        return text;
    }

    /** Elements that recorded primitives in the last update. */
    public int recorded() {
        return recorded;
    }

    /** Updates so far. */
    public long frames() {
        return frames;
    }

    /** The tunables this UI reads, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public List<Tunable> tunables() {
        List<Tunable> all = new ArrayList<>();
        all.add(Layout.UI_SCALE);
        all.add(StyleResolver.CACHE_LIMIT);
        all.add(StyleWatcher.DELAY);
        if (text != null && text.atlas() != null) {
            all.addAll(text.atlas().settings().all());
        }
        return all;
    }
}
