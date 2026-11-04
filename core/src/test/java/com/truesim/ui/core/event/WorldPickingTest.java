package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.input.Pointer;
import org.junit.jupiter.api.Test;

/**
 * Hits beyond the screen layers ({@link WorldPicking}: world panels, scene objects) follow the screen layers' hits and
 * take part in routing as they do: pass-through, capture, clicks; a blocking layer stops them.
 */
class WorldPickingTest {

    @Test
    void worldHitsFollowTheScreenHitsAndTakeEventsPassedOn() {
        Rig r = new Rig();
        Element hud = r.button(r.root(), "hud", 0, 0, 100, 100);
        Element node = r.store.create("panel", "node"); // stands for a scene node: on no screen layer's grid
        r.layers.add("scene", -10).input(Layer.Input.NONE).root().add(node);
        r.logAll(node);
        double[] seen = new double[6];
        WorldPicking world = (pointer, hits, screenCount) -> {
            seen[0] = pointer.hasRay() ? pointer.rayDirectionZ() : 0;
            seen[1] = screenCount;
            hits[screenCount] = node.slot();
            return screenCount + 1;
        };
        r.ia.worldPicking(world);
        assertThat(r.ia.worldPicking()).isSameAs(world);
        r.mouse(50, 50, 0).ray(0, 0, 0, 0, 0, -1);
        r.frame();
        assertThat(seen[0]).isEqualTo(-1);
        assertThat(seen[1]).isEqualTo(1);
        r.click(50, 50);
        // the HUD button takes no press for itself: it passes on to the node, which the click then goes to
        assertThat(r.take(EventType.PRESS, EventType.CLICK)).contains("PRESS hud", "PRESS node", "CLICK hud");
        r.mouse(500, 500, 0).clearRay();
        r.frame();
        r.click(500, 500);
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK node");
        assertThat(r.ia.hovered(0)).isEqualTo(node);
        // a HUD element watching only the hover passes presses on to the world behind it
        Element tip = r.box(r.root(), "tip", 300, 300, 50, 50);
        r.ia.on(tip, EventType.ENTER.bit() | EventType.EXIT.bit(), e -> { });
        r.frame();
        r.click(320, 320);
        assertThat(r.take(EventType.PRESS)).containsExactly("PRESS node");
        r.mouse(500, 500, 0);
        r.frame();
        // a blocking layer showing something keeps pointers from the world
        Element dialog = r.box(r.layers.get("modal").root(), "dialog", 0, 0, 10, 10);
        r.frame();
        assertThat(r.ia.hovered(0)).isNotEqualTo(node);
        dialog.destroy();
        Pointer p = r.mouse(1, 1, 0);
        assertThat(p.hasRay()).isFalse();
        assertThat(p.ray(1, 2, 3, 4, 5, 6).rayOriginX() + p.rayOriginY() + p.rayOriginZ() + p.rayDirectionX()
            + p.rayDirectionY()).isEqualTo(15);
    }
}
