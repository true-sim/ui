package com.truesim.ui.controls.data;

import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleTypes;

/**
 * The style keys of the data controls (scroll views, lists, trees, tables), beside the standard {@code Styles}.
 * Registered for {@code .tss} as a {@link StyleKeyHolder} service, so themes use them like any other key. Everything else of the data
 * controls' look (rows, cells, scroll bars, thumbs, headers, indicators, editors and their states) is styled with the
 * standard keys on their element types; see {@code _data.tss}.
 */
public final class DataStyles implements StyleKeyHolder {

    /** For the service loader: creating it registers the keys. */
    public DataStyles() {
    }

    /** How far the wheel (one notch) and the arrow commands scroll, in panel units. */
    public static final StyleKey<Float> SCROLL_STEP = StyleKey.number("scroll-step", 40, 1, 100_000);
    /** When a scroll bar shows: {@code auto} (when its axis overflows), {@code always} or {@code never}. */
    public static final StyleKey<ScrollbarVisibility> SCROLLBAR_VISIBILITY =
        StyleKey.of("scrollbar-visibility", ScrollbarVisibility.class, ScrollbarVisibility.AUTO);
    /** Whether scroll bars float over the content (taking no space) instead of beside it. */
    public static final StyleKey<Boolean> SCROLLBAR_OVERLAY = StyleKey.of("scrollbar-overlay", StyleTypes.bool(),
        false);
    /** The shortest a scroll bar's thumb gets, in panel units. */
    public static final StyleKey<Float> THUMB_MIN = StyleKey.number("thumb-min", 16, 0, 100_000);
    /** How far a tree indents each level, in panel units. */
    public static final StyleKey<Float> INDENT = StyleKey.number("indent", 16, 0, 10_000);
    /** The narrowest a table column gets by resizing, in panel units. */
    public static final StyleKey<Float> COLUMN_MIN_WIDTH = StyleKey.number("column-min-width", 24, 0, 100_000);

    /** When a scroll bar shows. */
    public enum ScrollbarVisibility {
        /** When its axis overflows. */
        AUTO,
        /** Always, disabled when nothing overflows. */
        ALWAYS,
        /** Never: the view still scrolls by wheel, touch, keys and focus. */
        NEVER
    }
}
