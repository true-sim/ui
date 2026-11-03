package com.truesim.ui.controls;

import com.truesim.ui.core.draw.DrawList;

/**
 * An element's own content, measured and recorded by a control rather than by the frame (a text field's text, caret
 * and selection; a chart's series): {@link Ui#paint}. The frame still draws the element's box (shadows, background,
 * border, focus ring) from its style, then asks the painter for the rest, inside the content box (the box less border
 * and padding). With {@code clip: true} in its style the content is clipped to the element's rounded box, as its
 * children are.
 *
 * <p>The frame calls the painter only when the element is marked: {@code DIRTY_LAYOUT} to measure again (content of
 * another size), {@code DIRTY_DRAW} to record again ({@link Ui#repaint}). Recording must not allocate.
 */
public interface Painter {

    /**
     * The content's size in panel units ({@link com.truesim.ui.layout.Measure#size}) for the given room (in panel
     * units, with the layout's {@link com.truesim.ui.layout.Measure} modes).
     */
    long measure(int slot, float width, int widthMode, float height, int heightMode);

    /**
     * Records the content into {@code list}'s open range: the content box is {@code x0, y0, x1, y1} in pixels;
     * {@code opacity} is the element's (fade colours by it).
     */
    void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity);

    /**
     * Primitives to reserve beyond the {@code recorded} ones, so content that grows a little (typing, scrolling to
     * longer lines) records into its range without the layer being rebuilt.
     */
    default int spare(int recorded) {
        return recorded / 2 + 16;
    }
}
