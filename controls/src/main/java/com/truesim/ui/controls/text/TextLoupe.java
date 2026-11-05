package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.BasicStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.ResolvedStyle;

/**
 * The magnifier over a dragged touch selection handle (a {@code text-loupe} on the {@link Layers#TOOLTIP} layer): the
 * editor's text around the dragged end, {@code loupe-zoom} times its size, in a box of its own ({@code clip: true}
 * in the themes) above the line at {@code loupe-gap}, below it where the screen ends above. The text is recorded
 * again into the loupe's range under a baked scale (glyphs are distance fields: sharp at any size), so it is the same
 * text, selection and colours the editor draws, in the same frame. It takes no input.
 */
final class TextLoupe implements Painter {

    private final Ui ui;
    private final TextEditor editor;
    private final Element element;
    private final float[] focus = new float[4];
    private final float[] drawn = new float[3]; // the editor's drawn scale and offset (an animated scale of it)
    private boolean shown;

    @AllowAllocation("made the first time a handle is dragged")
    TextLoupe(Ui ui, TextEditor editor) {
        this.ui = ui;
        this.editor = editor;
        this.element = ui.element("text-loupe", null);
        ui.paint(element, this);
    }

    Element element() {
        return element;
    }

    boolean shown() {
        return shown;
    }

    void show() {
        if (!shown) {
            shown = true;
            ui.layer(Layers.TOOLTIP).root().add(element);
        }
        place();
        ui.repaint(element);
    }

    void hide() {
        if (shown) {
            shown = false;
            element.detach();
        }
    }

    /** Puts the loupe above the dragged end's line, centred on it, kept on the screen. */
    void place() {
        if (!shown || !editor.loupeFocus(focus)) {
            return;
        }
        Layout l = ui.layout();
        int s = element.slot();
        float scale = l.scale() > 0 ? l.scale() : 1;
        float w = l.shown(s) ? l.width(s) : 0;
        float h = l.shown(s) ? l.height(s) : 0;
        int r = ui.store().rootSlot();
        float sw = l.width(r);
        float sh = l.height(r);
        float gap = ui.styles().styleOf(s).getFloat(BasicStyles.LOUPE_GAP) * scale;
        ui.drawnTransform(editor.element(), drawn); // over the pixels, where an animation scales or moves the editor
        float k = drawn[0];
        float fx = k * focus[0] + drawn[1];
        float top = k * focus[2] + drawn[2];
        float bottom = k * focus[3] + drawn[2];
        float x = Math.max(0, Math.min(sw - w, fx - w * 0.5f));
        float y = top - gap - h;
        if (y < 0) {
            y = Math.min(sh - h, bottom + gap + k * editor.handleExtent());
        }
        ui.visuals().offsets(s, x / scale, y / scale, 0, 0);
    }

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        return Measure.size(widthMode == Measure.UNDEFINED ? 0 : width, heightMode == Measure.UNDEFINED ? 0 : height);
    }

    @Override
    public int spare(int recorded) {
        return recorded + 64;
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        if (!editor.loupeFocus(focus)) {
            return;
        }
        ResolvedStyle st = ui.styles().styleOf(slot);
        ui.drawnTransform(editor.element(), drawn);
        float zoom = st.getFloat(TextStyles.LOUPE_ZOOM) * drawn[0]; // times the text's size as it draws
        float cx = (x0 + x1) * 0.5f;
        float cy = (y0 + y1) * 0.5f;
        float halfH = (y1 - y0) * 0.5f / zoom;
        float s0 = list.recordScale();
        float ox0 = list.recordOffsetX();
        float oy0 = list.recordOffsetY();
        // the editor's point (fx, fy) at the loupe's centre: p' = zoom * (p - f) + c, then what was baked before
        list.transform(s0 * zoom, s0 * (cx - zoom * focus[0]) + ox0, s0 * (cy - zoom * focus[1]) + oy0);
        editor.recordMagnified(list, focus[1] - halfH, focus[1] + halfH, opacity);
        list.transform(s0, ox0, oy0);
    }
}
