package com.truesim.ui.controls.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Anchored placement: sides, alignment, flipping at every edge of the viewport, shifting back inside it. */
class PlacerTest {

    private final float[] out = new float[3];

    /** A 100 x 40 overlay at an anchor box in an 800 x 600 viewport, gap 4, margin 8, flipping. */
    private Placement place(float ax, float ay, float aw, float ah, Placement preferred, PlacementAlign align) {
        return Placer.place(ax, ay, aw, ah, 100, 40, 0, 0, 800, 600, preferred, align, 4, 0, 8, true, false, out);
    }

    @Test
    void itSitsOnThePreferredSideAlignedAndAtTheGap() {
        assertThat(place(300, 300, 60, 20, Placement.BOTTOM, PlacementAlign.CENTER)).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.X]).isEqualTo(280); // centred on the anchor: 330 - 50
        assertThat(out[Placer.Y]).isEqualTo(324); // below it at the gap
        assertThat(out[Placer.ARROW]).isEqualTo(50);
        assertThat(place(300, 300, 60, 20, Placement.TOP, PlacementAlign.START)).isEqualTo(Placement.TOP);
        assertThat(out[Placer.X]).isEqualTo(300);
        assertThat(out[Placer.Y]).isEqualTo(256);
        assertThat(out[Placer.ARROW]).isEqualTo(30);
        assertThat(place(300, 300, 60, 20, Placement.LEFT, PlacementAlign.END)).isEqualTo(Placement.LEFT);
        assertThat(out[Placer.X]).isEqualTo(196);
        assertThat(out[Placer.Y]).isEqualTo(280); // bottom edges lined up
        assertThat(place(300, 300, 60, 20, Placement.RIGHT, PlacementAlign.CENTER)).isEqualTo(Placement.RIGHT);
        assertThat(out[Placer.X]).isEqualTo(364);
        assertThat(out[Placer.Y]).isEqualTo(290);
    }

    @Test
    void itFlipsAtEveryEdge() {
        // top edge: no room above, so below
        assertThat(place(300, 10, 60, 20, Placement.TOP, PlacementAlign.CENTER)).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.Y]).isEqualTo(34);
        // bottom edge: above
        assertThat(place(300, 570, 60, 20, Placement.BOTTOM, PlacementAlign.CENTER)).isEqualTo(Placement.TOP);
        assertThat(out[Placer.Y]).isEqualTo(526);
        // left edge: right
        assertThat(place(10, 300, 20, 20, Placement.LEFT, PlacementAlign.CENTER)).isEqualTo(Placement.RIGHT);
        assertThat(out[Placer.X]).isEqualTo(34);
        // right edge: left
        assertThat(place(760, 300, 20, 20, Placement.RIGHT, PlacementAlign.CENTER)).isEqualTo(Placement.LEFT);
        assertThat(out[Placer.X]).isEqualTo(656);
    }

    @Test
    void withNoRoomAboveOrBelowItGoesAcrossAndWithNoRoomAnywhereTheRoomierSideStaysOnScreen() {
        // a tall anchor filling the height: neither above nor below fits, the end side (right) does
        assertThat(place(300, 20, 60, 560, Placement.TOP, PlacementAlign.CENTER)).isEqualTo(Placement.RIGHT);
        assertThat(out[Placer.X]).isEqualTo(364);
        // a viewport too small for any side: the roomier of preferred and opposite, pushed inside the margin
        Placement side = Placer.place(10, 10, 60, 30, 100, 40, 0, 0, 120, 60, Placement.BOTTOM, PlacementAlign.CENTER,
            4, 0, 8, true, false, out);
        assertThat(side).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.X]).isEqualTo(8);
        assertThat(out[Placer.Y]).isEqualTo(12); // 60 - 8 - 40: covering the anchor rather than leaving the screen
        // without flipping it stays put, shifted back inside
        side = Placer.place(300, 570, 60, 20, 100, 40, 0, 0, 800, 600, Placement.BOTTOM, PlacementAlign.CENTER, 4, 0,
            8, false, false, out);
        assertThat(side).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.Y]).isEqualTo(552);
    }

    @Test
    void alongItsSideItIsShiftedBackInsideTheViewport() {
        assertThat(place(2, 300, 20, 20, Placement.BOTTOM, PlacementAlign.CENTER)).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.X]).isEqualTo(8); // not -38: kept a margin from the left edge
        assertThat(out[Placer.ARROW]).isEqualTo(4); // the arrow still points at the anchor's centre (12)
        assertThat(place(790, 300, 10, 20, Placement.TOP, PlacementAlign.START)).isEqualTo(Placement.TOP);
        assertThat(out[Placer.X]).isEqualTo(692);
        assertThat(place(300, 590, 20, 10, Placement.RIGHT, PlacementAlign.CENTER)).isEqualTo(Placement.RIGHT);
        assertThat(out[Placer.Y]).isEqualTo(552);
        assertThat(place(300, 0, 20, 4, Placement.LEFT, PlacementAlign.CENTER)).isEqualTo(Placement.LEFT);
        assertThat(out[Placer.Y]).isEqualTo(8);
    }

    @Test
    void startAndEndFollowTheTextDirectionAndShiftsMirror() {
        Placer.place(300, 300, 60, 20, 100, 40, 0, 0, 800, 600, Placement.START, PlacementAlign.START, 4, 10, 8, true,
            false, out);
        assertThat(out[Placer.X]).isEqualTo(196);
        assertThat(out[Placer.Y]).isEqualTo(310);
        Placement side = Placer.place(300, 300, 60, 20, 100, 40, 0, 0, 800, 600, Placement.START,
            PlacementAlign.START, 4, 0, 8, true, true, out);
        assertThat(side).isEqualTo(Placement.RIGHT);
        assertThat(out[Placer.X]).isEqualTo(364);
        side = Placer.place(300, 300, 60, 20, 100, 40, 0, 0, 800, 600, Placement.BOTTOM, PlacementAlign.START, 4, 10,
            8, true, true, out);
        assertThat(side).isEqualTo(Placement.BOTTOM);
        assertThat(out[Placer.X]).isEqualTo(250); // right edges lined up (360 - 100), shifted towards the start
        side = Placer.place(300, 300, 60, 20, 100, 40, 0, 0, 800, 600, Placement.END, PlacementAlign.END, 4, 0, 8,
            true, true, out);
        assertThat(side).isEqualTo(Placement.LEFT);
        assertThat(Placement.START.opposite()).isEqualTo(Placement.END);
        assertThat(Placement.END.opposite()).isEqualTo(Placement.START);
        assertThat(Placement.of(1)).isEqualTo(Placement.BOTTOM);
        assertThat(Placer.room(Placement.LEFT, 300, 300, 60, 20, 100, 40, 0, 0, 800, 600, 4, 8)).isEqualTo(188);
    }
}
