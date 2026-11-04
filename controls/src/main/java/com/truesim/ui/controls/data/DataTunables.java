package com.truesim.ui.controls.data;

import com.truesim.ui.core.Tunable;
import java.util.List;

/** The tunables of the data controls: scrolling physics and timing, list behaviour. */
public final class DataTunables {

    private DataTunables() {
    }

    /** How fast a fling slows down: the share of its speed lost per second (touch momentum). */
    public static final Tunable FRICTION = Tunable.of("ui.scroll.friction", 3.5f, 0.1f, 50,
        "How quickly a flung scroll view slows down (higher stops sooner)");
    /** A fling slower than this (panel units per second) stops at once. */
    public static final Tunable MIN_FLING_SPEED = Tunable.of("ui.scroll.minFlingSpeed", 40, 0, 10_000,
        "Slowest fling speed that keeps scrolling, in panel units per second");
    /** Whether dragging past the ends pulls the content (and springs back) or stops at the ends. */
    public static final Tunable OVERSCROLL = Tunable.choice("ui.scroll.overscroll", List.of("none", "bounce"),
        "bounce", "Whether touch scrolling past the ends stretches and springs back");
    /** How fast an overscrolled view springs back: the share of the overscroll recovered per second. */
    public static final Tunable BOUNCE_STIFFNESS = Tunable.of("ui.scroll.bounceStiffness", 12, 1, 100,
        "How quickly an overscrolled view springs back");
    /** Seconds scroll bars stay active (for auto-hiding themes) after the last scroll. */
    public static final Tunable AUTO_HIDE_DELAY = Tunable.of("ui.scroll.autoHideDelay", 1.2f, 0, 30,
        "Seconds scroll bars stay shown after scrolling, for themes that hide them");
    /** Seconds before a held scroll bar button or track repeats, and between repeats. */
    public static final Tunable REPEAT_DELAY = Tunable.of("ui.scroll.repeatDelay", 0.4f, 0.05f, 5,
        "Seconds before a held scroll button repeats");
    public static final Tunable REPEAT_INTERVAL = Tunable.of("ui.scroll.repeatInterval", 0.05f, 0.01f, 2,
        "Seconds between repeats of a held scroll button");
    /** Rows bound beyond the viewport on each side, so short scrolls show bound rows at once. */
    public static final Tunable OVERSCAN = Tunable.of("ui.list.overscan", 2, 0, 64,
        "Rows prepared beyond the visible ones on each side of a list");
    /** Seconds after the last typed character before type-ahead starts a new search (lists, drop-downs, menus). */
    public static final Tunable TYPE_AHEAD_TIMEOUT = Tunable.of("ui.list.typeAheadTimeout", 1, 0.1f, 10,
        "Seconds after which typing in a list starts a new search");

    /** How fast a selection box dragged past a view's edge scrolls it: panel units per second per panel unit past. */
    public static final Tunable BOX_SCROLL_RATE = Tunable.of("ui.list.boxScrollRate", 12, 0, 200,
        "How fast dragging a selection box past a list's edge scrolls it");
    /** How near a view's edge (panel units, inside it) a dragged selection box starts scrolling it. */
    public static final Tunable BOX_SCROLL_EDGE = Tunable.of("ui.list.boxScrollEdge", 8, 0, 200,
        "Distance from a list's edge at which a dragged selection box scrolls it");

    /** Every data-control tunable, for an engine to register as settings. */
    public static List<Tunable> all() {
        return List.of(FRICTION, MIN_FLING_SPEED, OVERSCROLL, BOUNCE_STIFFNESS, AUTO_HIDE_DELAY, REPEAT_DELAY,
            REPEAT_INTERVAL, OVERSCAN, TYPE_AHEAD_TIMEOUT, BOX_SCROLL_RATE, BOX_SCROLL_EDGE);
    }
}
