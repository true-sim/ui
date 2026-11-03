package com.truesim.ui.controls.overlay;

import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleTypes;

/**
 * The style keys of anchored overlays (tooltips, popovers, hover cards, menus): which side of the anchor they prefer,
 * how they line up with it, the gap, a shift along the side, the margin kept from the viewport's edges and whether they
 * may flip to another side. Read from the overlay's root element. Registered for {@code .tss} as a
 * {@link StyleKeyHolder} service.
 */
public final class OverlayStyles implements StyleKeyHolder {

    /** The preferred side of the anchor: {@code top}, {@code bottom}, {@code left}, {@code right}, {@code start}, {@code end}. */
    public static final StyleKey<Placement> PLACEMENT = StyleKey.of("placement", Placement.class, Placement.BOTTOM);
    /** How the overlay lines up with its anchor along that side: {@code start}, {@code center}, {@code end}. */
    public static final StyleKey<PlacementAlign> PLACEMENT_ALIGN =
        StyleKey.of("placement-align", PlacementAlign.class, PlacementAlign.CENTER);
    /** The gap between the anchor and the overlay, in panel units. */
    public static final StyleKey<Float> PLACEMENT_GAP = StyleKey.number("placement-gap", 0, -10_000, 10_000);
    /** A shift along the side (towards the end side), in panel units. */
    public static final StyleKey<Float> PLACEMENT_SHIFT = StyleKey.number("placement-shift", 0, -10_000, 10_000);
    /** The room kept between the overlay and the viewport's edges, in panel units. */
    public static final StyleKey<Float> VIEWPORT_MARGIN = StyleKey.number("viewport-margin", 0, 0, 10_000);
    /** Whether the overlay may move to another side of its anchor when it does not fit on its preferred one. */
    public static final StyleKey<Boolean> PLACEMENT_FLIP = StyleKey.of("placement-flip", StyleTypes.bool(), true);

    /** For the service loader: creating it registers the keys. */
    public OverlayStyles() {
    }
}
