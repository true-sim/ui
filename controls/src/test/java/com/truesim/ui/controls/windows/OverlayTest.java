package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.overlay.ElementAnchor;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.OverlayInput;
import com.truesim.ui.controls.overlay.Placement;
import com.truesim.ui.controls.overlay.PointAnchor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Overlays through the real frame: placed next to their anchor in the same frame, flipped, blocking, modal. */
class OverlayTest {

    static final StyleSheet SHEET = Tss.compileString("overlay.tss", """
        layer { layout: none; }
        panel { layout: none; }
        panel.tip { width: 100px; height: 40px; placement: top; placement-gap: 4px; viewport-margin: 8px;
          &[side=bottom] { background: box(#ff0000); } }
        panel.box { pointer-events: all; }
        backdrop { background: box(rgba(black, 0.5)); }
        """);

    private static Fx fx() {
        Fx f = new Fx(SHEET, false);
        return f;
    }

    @Test
    void anAnchoredOverlayIsPlacedNextToItsAnchorInTheFrameItShows() {
        Fx f = fx();
        Element anchor = f.box(300, 300, 60, 20);
        Element tip = f.ui.panel("tip");
        Overlay o = f.overlays.create(tip, Layers.POPUP).anchor(new ElementAnchor(f.ui.layout(), anchor));
        f.run();
        o.show();
        f.run();
        assertThat(f.x(tip)).isEqualTo(280);
        assertThat(f.y(tip)).isEqualTo(256);
        assertThat(o.side()).isEqualTo(Placement.TOP);
        assertThat(o.arrow()).isEqualTo(50);
        assertThat(tip.parent()).isEqualTo(f.ui.layer(Layers.POPUP).root());
        assertThat(f.overlays.showingCount()).isEqualTo(1);
        assertThat(f.overlays.showingAt(0)).isSameAs(o);
        // the anchor moves (a restyle, a layout change): the overlay follows in the same frame
        f.ui.addFrameHook(new com.truesim.ui.controls.UiFrameHook() {
            @Override
            public void beforeLayout(com.truesim.ui.controls.Ui ui) {
                ui.layout().offsets(anchor.slot(), 400, 10, 0, 0);
            }
        });
        f.run();
        assertThat(f.y(tip)).isEqualTo(34); // no room above: flipped below
        assertThat(o.side()).isEqualTo(Placement.BOTTOM);
        assertThat(f.ui.styles().styleOf(tip.slot()).get(com.truesim.ui.style.Styles.BACKGROUND).toString())
            .contains("ff0000"); // the side is a fact styles select: tooltip[side=bottom]
        o.hide();
        f.run();
        assertThat(tip.parent()).isNull();
        assertThat(f.overlays.showingCount()).isZero();
    }

    @Test
    void anOverlayOfAHiddenAnchorHidesAndAPointAnchorIsFollowedEveryFrame() {
        Fx f = fx();
        Element tip = f.ui.panel("tip");
        PointAnchor point = new PointAnchor(500, 400);
        Overlay o = f.overlays.create(tip, Layers.TOOLTIP).anchor(point).input(OverlayInput.PASS_THROUGH).show();
        f.run();
        assertThat(f.x(tip)).isEqualTo(450);
        assertThat(f.y(tip)).isEqualTo(356);
        point.set(600, 200); // a scene object's projected position, moved by the engine
        f.run();
        assertThat(f.x(tip)).isEqualTo(550);
        assertThat(f.y(tip)).isEqualTo(156);
        point.visible(false); // behind the camera
        f.run();
        assertThat(o.anchorVisible()).isFalse();
        assertThat(f.ui.layout().shown(tip.slot())).isFalse();
        point.visible(true);
        f.run();
        assertThat(f.ui.layout().shown(tip.slot())).isTrue();
        // an anchor element that leaves the tree hides it too
        Element anchor = f.box(20, 20, 10, 10);
        o.anchor(new ElementAnchor(f.ui.layout(), anchor));
        f.run();
        assertThat(o.anchorVisible()).isTrue();
        anchor.detach();
        f.run();
        assertThat(o.anchorVisible()).isFalse();
        assertThat(new ElementAnchor(f.ui.layout(), null).bounds(new float[4])).isFalse();
    }

    @Test
    void aBlockingOverlayDimsAndBlocksTheScreenAndAModalOneTrapsFocusAndClosesOnCancel() {
        Fx f = fx();
        Element button = f.box(20, 20, 100, 40);
        List<String> clicks = new ArrayList<>();
        f.ui.on(button, EventType.CLICK, e -> clicks.add("hud"));
        f.ui.interaction().focusable(button, true);
        Element dialog = f.ui.panel("tip");
        Element inside = f.ui.panel("box");
        dialog.add(inside);
        f.ui.interaction().focusable(inside, true);
        Overlay o = f.overlays.create(dialog, Layers.OVERLAY).centred().input(OverlayInput.BLOCK_SCREEN).modal(true)
            .lightDismiss(true);
        f.run();
        f.ui.interaction().focus(button);
        f.click(50, 30);
        assertThat(clicks).containsExactly("hud");
        o.show();
        f.run().run(); // laid out in the first frame, focused inside in the second
        assertThat(f.x(dialog)).isEqualTo(590); // centred: (1280 - 100) / 2
        assertThat(f.y(dialog)).isEqualTo(340);
        Element backdrop = o.backdrop();
        assertThat(f.w(backdrop)).isEqualTo(1280);
        assertThat(f.h(backdrop)).isEqualTo(720);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(inside); // trapped in the modal overlay
        f.command(UiCommand.CANCEL);
        assertThat(o.showing()).isFalse();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(button); // focus came back
        o.show();
        f.run();
        f.click(50, 30); // on the backdrop: blocked, and light dismiss closes the dialog
        assertThat(clicks).containsExactly("hud");
        assertThat(o.showing()).isFalse();
        // without light dismiss a press on the backdrop only blocks
        o.lightDismiss(false).show();
        f.run().click(50, 30);
        assertThat(o.showing()).isTrue();
        assertThat(clicks).containsExactly("hud");
        o.listener(new Overlay.Listener() {
            @Override
            public void dismissed(Overlay overlay) {
                clicks.add("asked");
            }
        });
        f.command(UiCommand.CANCEL);
        assertThat(clicks).containsExactly("hud", "asked");
        assertThat(o.showing()).isTrue();
        o.hide();
        o.input(OverlayInput.PASS_THROUGH).modal(false).show();
        f.run().click(50, 30);
        assertThat(clicks).containsExactly("hud", "asked", "hud"); // no backdrop: the HUD gets the press
        assertThat(o.backdrop().parent()).isNull();
    }

    @Test
    void passThroughLetsPointersFallThroughExceptOnInteractiveElementsAndBlockBoundsTakesItsBox() {
        Fx f = fx();
        Element button = f.box(20, 20, 300, 300);
        List<String> clicks = new ArrayList<>();
        f.ui.on(button, EventType.CLICK, e -> clicks.add("hud"));
        Element panel = f.ui.panel("tip");
        Overlay o = f.overlays.create(panel, Layers.POPUP).position(40, 40).input(OverlayInput.PASS_THROUGH).show();
        f.run();
        assertThat(f.x(panel)).isEqualTo(40);
        f.click(60, 60);
        assertThat(clicks).containsExactly("hud");
        o.input(OverlayInput.BLOCK_BOUNDS);
        f.run().click(60, 60);
        assertThat(clicks).containsExactly("hud");
        f.click(200, 200); // outside the overlay's box
        assertThat(clicks).containsExactly("hud", "hud");
        o.position(10, 10);
        f.run();
        assertThat(f.x(panel)).isEqualTo(10);
        assertThat(o.x()).isEqualTo(10);
        assertThat(o.toString()).contains("showing");
        assertThat(f.mouse(30, 30, Pointer.PRIMARY).ui.interaction().overUi(0)).isTrue();
    }
}
