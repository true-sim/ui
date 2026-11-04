package com.truesim.ui.controls.overlay;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import java.util.Arrays;

/**
 * The overlays of one {@link Ui} (design: the layer stack's pop-ups, tooltips, dialogs): made by {@link #create},
 * placed every frame as a {@linkplain Ui.FrameListener frame listener} right after layout, so an overlay sits next to where its
 * anchor was laid out in the same frame, and laid out again at its place. A frame with nothing moving costs a few
 * comparisons per showing overlay and allocates nothing.
 *
 * <pre>{@code
 * Overlays overlays = new Overlays(ui);
 * Overlay tip = overlays.create(content, Layers.POPUP).anchor(new ElementAnchor(ui.layout(), button));
 * tip.show();
 * }</pre>
 */
public final class Overlays implements Ui.FrameListener {

    private static final Facts[] SIDES = {Facts.of("side", "top"), Facts.of("side", "bottom"),
        Facts.of("side", "left"), Facts.of("side", "right")};

    private final Ui ui;
    private final Layout layout;
    private Overlay[] showing = new Overlay[8];
    private int count;
    private final float[] box = new float[4];
    private final float[] out = new float[3];
    private final float[] view = new float[4];
    private Viewports viewports;
    private boolean rtl;

    /** The overlays of {@code ui}; registers itself as one of its frame listeners. */
    @AllowAllocation("one per UI")
    public Overlays(Ui ui) {
        this.ui = ui;
        this.layout = ui.layout();
        ui.addFrameListener(this);
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    Interaction interaction() {
        return ui.interaction();
    }

    /** A new overlay showing {@code root} on the layer named {@code layerName} ({@code Layers.POPUP} and others). */
    @AllowAllocation("overlays are made when a UI is built or a pop-up opens")
    public Overlay create(Element root, String layerName) {
        return create(root, ui.layer(layerName));
    }

    /** A new overlay showing {@code root} on {@code layer}. */
    @AllowAllocation("overlays are made when a UI is built or a pop-up opens")
    public Overlay create(Element root, Layer layer) {
        return new Overlay(this, root, layer);
    }

    /** Lays start and end sides out right to left (START is the right side); returns this. */
    public Overlays rightToLeft(boolean value) {
        rtl = value;
        return this;
    }

    /**
     * Keeps anchored overlays inside the viewport holding their anchor, as {@code value} says (null: the host's box):
     * for a UI shown in several windows. Returns this.
     */
    public Overlays viewports(Viewports value) {
        viewports = value;
        return this;
    }

    /** How many overlays show. */
    public int showingCount() {
        return count;
    }

    /** Showing overlay {@code index}, in the order they were shown. */
    public Overlay showingAt(int index) {
        return showing[index];
    }

    @AllowAllocation("growing with the overlays shown at once, rarely")
    void shown(Overlay overlay) {
        if (overlay.index >= 0) {
            return;
        }
        if (count == showing.length) {
            showing = Arrays.copyOf(showing, count * 2);
        }
        overlay.index = count;
        showing[count++] = overlay;
        place(overlay); // where it was last placed, until this frame's layout places it again
    }

    void hidden(Overlay overlay) {
        int i = overlay.index;
        System.arraycopy(showing, i + 1, showing, i, count - i - 1);
        showing[--count] = null;
        overlay.index = -1;
        for (int k = i; k < count; k++) {
            showing[k].index = k;
        }
    }

    @Override
    public void frame(long nanos) {
        for (int i = 0; i < count; i++) {
            Overlay o = showing[i];
            if (!o.root().alive()) {
                o.hide();
                i--;
                continue;
            }
            place(o); // a restyle took the style's offsets: put it back where it is
        }
    }

    /** Sets the overlay's (and its backdrop's) layout inputs for its current place. */
    void place(Overlay o) {
        int hostSlot = o.host().slot();
        ui.ownLayout(hostSlot, Ui.CODE_KIND);
        layout.kind(hostSlot, LayoutKind.NONE);
        float scale = scale();
        int slot = o.root().slot();
        ui.ownLayout(slot, Ui.CODE_PLACEMENT);
        layout.offsets(slot, (o.x - HostBox.left(ui, hostSlot)) / scale, (o.y - HostBox.top(ui, hostSlot)) / scale, 0,
            0);
        layout.visible(slot, o.anchorVisible);
        Element backdrop = o.backdrop();
        if (backdrop != null && backdrop.parent() != null) {
            int b = backdrop.slot();
            ui.ownLayout(b, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            layout.offsets(b, 0, 0, 0, 0);
            layout.width(b, 100, Unit.PERCENT);
            layout.height(b, 100, Unit.PERCENT);
            layout.visible(b, o.anchorVisible);
        }
    }

    @Override
    public void afterLayout() {
        for (int i = 0; i < count; i++) {
            Overlay o = showing[i];
            if (o.appearing) {
                o.appear(); // styled now: fades in by its opacity transition
            }
            if (o.anchor() == null && !o.centredNow()) {
                continue;
            }
            int hostSlot = o.host().slot();
            int slot = o.root().slot();
            float vx = HostBox.left(ui, hostSlot);
            float vy = HostBox.top(ui, hostSlot);
            float vw = HostBox.width(ui, hostSlot);
            float vh = HostBox.height(ui, hostSlot);
            float w = layout.width(slot);
            float h = layout.height(slot);
            if (o.centredNow()) {
                o.x = vx + (vw - w) * 0.5f;
                o.y = vy + (vh - h) * 0.5f;
                o.anchorVisible = true;
                place(o);
                continue;
            }
            boolean visible = o.anchor().bounds(box);
            o.anchorVisible = visible;
            if (visible && viewports != null
                && viewports.viewportAt(box[0] + box[2] * 0.5f, box[1] + box[3] * 0.5f, view)) {
                vx = view[0];
                vy = view[1];
                vw = view[2];
                vh = view[3];
            }
            if (visible) {
                ResolvedStyle s = ui.styles().styleOf(slot);
                float scale = scale();
                Placement side = Placer.place(box[0], box[1], box[2], box[3], w, h, vx, vy, vw, vh,
                    s.get(OverlayStyles.PLACEMENT), s.get(OverlayStyles.PLACEMENT_ALIGN),
                    s.getFloat(OverlayStyles.PLACEMENT_GAP) * scale, s.getFloat(OverlayStyles.PLACEMENT_SHIFT) * scale,
                    s.getFloat(OverlayStyles.VIEWPORT_MARGIN) * scale, s.get(OverlayStyles.PLACEMENT_FLIP), rtl, out);
                o.x = out[Placer.X];
                o.y = out[Placer.Y];
                o.arrow = out[Placer.ARROW];
                if (side != o.side) {
                    o.side = side;
                    ui.styles().facts(slot, SIDES[side.ordinal()]);
                }
            }
            place(o);
        }
    }

    private float scale() {
        return HostBox.scale(ui);
    }
}
