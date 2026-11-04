package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.util.Arrays;
import java.util.List;

/**
 * What the profiler's painted views share: an element whose painter records everything, the content box of the last
 * recording (for the pointer), text blocks per name shaped once and reused, styled boxes, and a repaint when glyphs
 * it drew arrive in the atlas.
 */
abstract class PaintedView implements Painter, UiListener, Ui.FrameListener {

    protected final Ui ui;
    protected final Element root;
    protected float x0;
    protected float y0;
    protected float x1;
    protected float y1;
    protected float scale = 1;
    protected float opacity = 1;
    private float lx; // the content box's corner in the layout (where pointers are)
    private float ly;
    private float lw; // the element's size as last laid out
    private float lh;
    private TextBlock[] nameBlocks = new TextBlock[64];
    private long textSeen;
    private long textDrawn;

    @AllowAllocation("views are made when a UI is built")
    PaintedView(Ui ui, String type) {
        this.ui = ui;
        root = ui.element(type, null);
        ui.paint(root, this);
        ui.interaction().pickable(root, PointerEvents.ALL);
        ui.interaction().focusable(root, true);
        ui.interaction().on(root, EventType.ENTER.bit() | EventType.MOVE.bit() | EventType.PRESS.bit()
            | EventType.RELEASE.bit() | EventType.WHEEL.bit() | EventType.CLICK.bit() | EventType.EXIT.bit()
            | EventType.COMMAND.bit(), this);
        ui.addFrameListener(this);
    }

    /** The view's element. */
    public Element element() {
        return root;
    }

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        return Measure.size(0, 0);
    }

    @Override
    public int spare(int recorded) {
        return recorded / 2 + 256;
    }

    @Override
    public final void paint(int slot, DrawList list, float px0, float py0, float px1, float py1, float fade) {
        x0 = px0;
        y0 = py0;
        x1 = px1;
        y1 = py1;
        opacity = fade;
        scale = ui.layout().scale();
        textDrawn = 0;
        record(list);
        textSeen = textDrawn;
    }

    /** Records the view into {@code list} over the content box {@code x0..y1}. */
    protected abstract void record(DrawList list);

    /** Asks for a new recording. */
    protected final void repaint() {
        ui.repaint(root);
    }

    @Override
    public void frame(long nanos) {
        update(nanos);
    }

    /** Once a frame, before layout: poll what the view shows and {@link #repaint()} when it changed. */
    protected abstract void update(long nanos);

    @Override
    public void afterLayout() {
        int slot = root.slot();
        ResolvedStyle rs = styleOf(root);
        float s = ui.layout().scale();
        float bw = rs.get(Styles.BORDER).width() * s;
        lx = ui.layout().x(slot) + bw + rs.get(Styles.PADDING).left() * s;
        ly = ui.layout().y(slot) + bw + rs.get(Styles.PADDING).top() * s;
        float w = ui.layout().width(slot);
        float h = ui.layout().height(slot);
        if (w != lw || h != lh || s != scale) {
            lw = w;
            lh = h;
            repaint(); // a new size: record for it, this frame
        }
        // drawn glyphs that arrived in the atlas since: record again
        if (textSeen != textVersions()) {
            repaint();
        }
    }

    private long textVersions() {
        long v = 0;
        for (TextBlock b : nameBlocks) {
            if (b != null) {
                v += b.version();
            }
        }
        return v + ownTextVersion();
    }

    /** The versions of text blocks a view keeps beyond the name blocks. */
    protected long ownTextVersion() {
        return 0;
    }

    @Override
    public void handle(UiEvent event) {
        // views override what they take
    }

    // ---------------------------------------------------------------- styles and drawing

    protected final ResolvedStyle styleOf(Element part) {
        return ui.styles().styleOf(part.slot());
    }

    /** The text block showing name {@code id} of {@code recording} in {@code style}, shaped when first asked. */
    protected final TextBlock nameBlock(Recording recording, int id, ResolvedStyle style) {
        if (id < 0) {
            return null;
        }
        if (id >= nameBlocks.length) {
            nameBlocks = grow(nameBlocks, id + 1);
        }
        TextBlock b = nameBlocks[id];
        if (b == null) {
            b = newBlock();
            if (b == null) {
                return null;
            }
            nameBlocks[id] = b;
        }
        shape(b, recording.nameOf(id), style);
        textDrawn += b.version();
        return b;
    }

    /** Counts a block drawn now towards the glyph check. */
    protected final void drew(TextBlock block) {
        if (block != null) {
            textDrawn += block.version();
        }
    }

    @AllowAllocation("blocks grow to the most names met")
    private static TextBlock[] grow(TextBlock[] blocks, int n) {
        return Arrays.copyOf(blocks, Math.max(n, blocks.length * 2));
    }

    /** A new text block (null without a text system). */
    @AllowAllocation("text blocks are made when first needed")
    protected final TextBlock newBlock() {
        TextSystem t = ui.textSystem();
        return t == null ? null : new TextBlock(t);
    }

    /** Shapes {@code text} into {@code block} in {@code style}'s font, on one line. */
    protected final void shape(TextBlock block, CharSequence text, ResolvedStyle style) {
        if (block != null) {
            block.set(text, style.get(Styles.FONT_FAMILY), style.getFloat(Styles.FONT_SIZE) * scale,
                style.getInt(Styles.FONT_WEIGHT), style.get(Styles.FONT_STYLE) != FontStyle.NORMAL, TextAlign.START,
                Float.POSITIVE_INFINITY, style.getFloat(Styles.LINE_HEIGHT));
        }
    }

    /** {@code style}'s background colour (a box paint's), faded. */
    protected final long background(ResolvedStyle style) {
        Paint bg = style.get(Styles.BACKGROUND);
        return bg instanceof Paint.Box box ? Rgba.fade(box.color().rgba(), opacity) : Rgba.TRANSPARENT;
    }

    /** {@code style}'s text colour, faded. */
    protected final long textColor(ResolvedStyle style) {
        return Rgba.fade(style.rgba(Styles.COLOR), opacity);
    }

    /** {@code style}'s stroke colour, faded. */
    protected final long stroke(ResolvedStyle style) {
        return Rgba.fade(style.rgba(ChartStyles.STROKE), opacity);
    }

    /** {@code style}'s line width in pixels (at least one). */
    protected final float lineWidth(ResolvedStyle style) {
        return Math.max(1, style.getFloat(ChartStyles.LINE_WIDTH) * scale);
    }

    /** {@code style}'s palette colour for {@code key} (names keep their colour), faded. */
    protected final long palette(ResolvedStyle style, int key) {
        List<Color> p = style.get(ChartStyles.PALETTE);
        if (p.isEmpty()) {
            return Rgba.fade(Rgba.WHITE, opacity);
        }
        int h = (key * 0x9E3779B1) >>> 7;
        return Rgba.fade(p.get(Math.floorMod(h, p.size())).rgba(), opacity);
    }

    /** A box in {@code style}'s background, radius and border. */
    protected final void box(DrawList list, ResolvedStyle style, float bx0, float by0, float bx1, float by1) {
        box(list, style, bx0, by0, bx1, by1, background(style));
    }

    /** A box in {@code fill} with {@code style}'s radius and border. */
    protected final void box(DrawList list, ResolvedStyle style, float bx0, float by0, float bx1, float by1,
        long fill) {
        float r = Math.min(style.get(Styles.RADIUS).topLeft() * scale, Math.min(bx1 - bx0, by1 - by0) * 0.5f);
        Border border = style.get(Styles.BORDER);
        long bc = Rgba.fade(border.color().rgba(), opacity);
        if (Rgba.a(fill) <= 0 && (border.width() <= 0 || Rgba.a(bc) <= 0)) {
            return;
        }
        list.box(bx0, by0, bx1, by1, fill, r, r, r, r, border.width() * scale, bc);
    }

    /** A text block at {@code x, y} (its top left) in {@code color}. */
    protected final void text(DrawList list, TextBlock block, float x, float y, long color) {
        block.record(list, x, y, color, 0, 0, 0);
    }

    /** Drawing coordinate {@code x} (what the view records in) as a pointer's (the layout's). */
    public final float pointerX(float x) {
        return x - x0 + lx;
    }

    /** Drawing coordinate {@code y} as a pointer's. */
    public final float pointerY(float y) {
        return y - y0 + ly;
    }

    /** A pointer's {@code x} (the layout's) as a drawing coordinate. */
    protected final float drawX(float pointer) {
        return pointer - lx + x0;
    }

    /** A pointer's {@code y} as a drawing coordinate. */
    protected final float drawY(float pointer) {
        return pointer - ly + y0;
    }

    /** The content box's left edge in drawing coordinates, as last recorded. */
    public final float left() {
        return x0;
    }

    /** The content box's top edge in drawing coordinates. */
    public final float top() {
        return y0;
    }

    /** The content box's right edge in drawing coordinates. */
    public final float right() {
        return x1;
    }

    /** The content box's bottom edge in drawing coordinates. */
    public final float bottom() {
        return y1;
    }

    /** Whether drawing coordinate {@code px, py} lies in the content box. */
    protected final boolean inside(float px, float py) {
        return px >= x0 && px < x1 && py >= y0 && py < y1;
    }

    /** {@code style}'s row height in pixels. */
    protected final float rowHeight(ResolvedStyle style) {
        return Math.max(2, style.getFloat(com.truesim.ui.controls.profiler.ProfilerStyles.ROW_HEIGHT) * scale);
    }

    /** {@code style}'s horizontal padding (left) in pixels. */
    protected final float padLeft(ResolvedStyle style) {
        return style.get(Styles.PADDING).left() * scale;
    }
}
