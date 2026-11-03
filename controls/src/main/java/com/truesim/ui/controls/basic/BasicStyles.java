package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleTypes;

/**
 * The style keys of the basic and container controls (beyond {@link com.truesim.ui.style.Styles}): registered for
 * {@code .tss} compilation as an installed {@link StyleKeyHolder}, so the shipped themes (partials {@code _basic.tss}
 * and {@code _basic-test.tss}) style them.
 */
@AllowAllocation("keys are declared once")
public final class BasicStyles implements StyleKeyHolder {

    /** Seconds a loading spinner takes for one turn. */
    public static final StyleKey<Float> SPINNER_PERIOD = StyleKey.of("spinner-period", StyleTypes.duration(), 1f);
    /** How many dots a loading spinner shows (2 to 24). */
    public static final StyleKey<Float> SPINNER_DOTS = StyleKey.number("spinner-dots", 8, 2, 24);
    /** The smallest opacity of a spinner's trailing dot. */
    public static final StyleKey<Float> SPINNER_FADE = StyleKey.of("spinner-fade", StyleTypes.fraction(), 0.15f);
    /** Seconds an indeterminate progress bar's fill takes to sweep across. */
    public static final StyleKey<Float> PROGRESS_PERIOD = StyleKey.of("progress-period", StyleTypes.duration(), 1.4f);
    /** Seconds a button shows {@code :pressed} after the ACCEPT command activated it (keys and gamepads). */
    public static final StyleKey<Float> PRESS_FLASH = StyleKey.of("press-flash", StyleTypes.duration(), 0.1f);
    /** How a tab strip shows more tabs than fit: scroll them, wrap them onto more rows, or list them in a menu. */
    public static final StyleKey<TabOverflow> TAB_OVERFLOW =
        StyleKey.of("tab-overflow", TabOverflow.class, TabOverflow.SCROLL);

    /** Made by {@link java.util.ServiceLoader}: initialises the keys. */
    public BasicStyles() {
        // the keys are static
    }

    /** How a tab strip shows more tabs than fit ({@link #TAB_OVERFLOW}). */
    public enum TabOverflow {
        /** One row that scrolls (the wheel, arrows, keeping the selected tab in view), plus the overflow menu. */
        SCROLL,
        /** As many rows as needed. */
        WRAP,
        /** One row; tabs that do not fit are only in the overflow menu. */
        MENU
    }
}
