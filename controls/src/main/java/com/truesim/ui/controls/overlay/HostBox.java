package com.truesim.ui.controls.overlay;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;

/**
 * The content box of a container that hosts overlays or windows (inside its border and padding), in UI pixels: what
 * they are placed and kept within. Reads the last layout and the container's resolved style.
 */
public final class HostBox {

    private HostBox() {
    }

    /** The layout's scale from panel units to UI pixels (1 before the first layout). */
    public static float scale(Ui ui) {
        float s = ui.layout().scale();
        return s > 0 ? s : 1;
    }

    /** The left edge of {@code slot}'s content box. */
    public static float left(Ui ui, int slot) {
        ResolvedStyle s = ui.styles().styleOf(slot);
        return ui.layout().x(slot) + (s.get(Styles.PADDING).left() + s.get(Styles.BORDER).width()) * scale(ui);
    }

    /** The top edge of {@code slot}'s content box. */
    public static float top(Ui ui, int slot) {
        ResolvedStyle s = ui.styles().styleOf(slot);
        return ui.layout().y(slot) + (s.get(Styles.PADDING).top() + s.get(Styles.BORDER).width()) * scale(ui);
    }

    /** The width of {@code slot}'s content box. */
    public static float width(Ui ui, int slot) {
        ResolvedStyle s = ui.styles().styleOf(slot);
        float insets = s.get(Styles.PADDING).left() + s.get(Styles.PADDING).right() + 2 * s.get(Styles.BORDER).width();
        return ui.layout().width(slot) - insets * scale(ui);
    }

    /** The height of {@code slot}'s content box. */
    public static float height(Ui ui, int slot) {
        ResolvedStyle s = ui.styles().styleOf(slot);
        float insets = s.get(Styles.PADDING).top() + s.get(Styles.PADDING).bottom() + 2 * s.get(Styles.BORDER).width();
        return ui.layout().height(slot) - insets * scale(ui);
    }
}
