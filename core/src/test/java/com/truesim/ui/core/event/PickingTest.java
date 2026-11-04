package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PickingTest {

    private static List<String> pick(Rig r, float x, float y) {
        int[] out = new int[Interaction.MAX_HITS];
        int n = r.ia.picking().pick(x, y, out);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add(r.name(out[i]));
        }
        return names;
    }

    @Test
    void roundedCornersAreLeftOutAndRadiiTooLargeShrinkTogether() {
        assertThat(Picking.inside(1, 1, 0, 0, 100, 100, 20, 20, 20, 20)).isFalse();
        assertThat(Picking.inside(8, 8, 0, 0, 100, 100, 20, 20, 20, 20)).isTrue();
        assertThat(Picking.inside(98, 2, 0, 0, 100, 100, 0, 20, 0, 0)).isFalse();
        assertThat(Picking.inside(98, 98, 0, 0, 100, 100, 0, 0, 20, 0)).isFalse();
        assertThat(Picking.inside(2, 98, 0, 0, 100, 100, 0, 0, 0, 20)).isFalse();
        assertThat(Picking.inside(50, 50, 0, 0, 100, 100, 20, 20, 20, 20)).isTrue();
        assertThat(Picking.inside(100, 50, 0, 0, 100, 100, 0, 0, 0, 0)).isFalse(); // right edge excluded
        // a pill: radii of 999 shrink to half the height
        assertThat(Picking.inside(1, 1, 0, 0, 100, 20, 999, 999, 999, 999)).isFalse();
        assertThat(Picking.inside(10, 10, 0, 0, 100, 20, 999, 999, 999, 999)).isTrue();
        assertThat(Picking.inside(3, 10, 0, 0, 100, 20, 999, 999, 999, 999)).isTrue();
    }

    @Test
    void elementsArePickedFrontToBackByTheirShapeAndClip() {
        Rig r = new Rig();
        Element round = r.button(r.root(), "round", 0, 0, 100, 100);
        for (int k = 0; k < 4; k++) {
            r.geo.radii[round.slot() * 4 + k] = 30;
        }
        Element front = r.button(r.root(), "front", 50, 50, 100, 100);
        assertThat(pick(r, 2, 2)).isEmpty(); // the rounded corner
        assertThat(pick(r, 20, 20)).containsExactly("round");
        assertThat(pick(r, 60, 60)).containsExactly("front", "round");
        assertThat(pick(r, 140, 140)).containsExactly("front");

        // a clipping viewport: its child is cut to it, with the viewport's rounding
        Element view = r.box(r.root(), "view", 300, 0, 100, 100);
        r.geo.clip[view.slot()] = true;
        r.geo.radii[view.slot() * 4 + 3] = 40; // bottom left
        Element content = r.button(view, "content", 300, 50, 200, 200);
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 350, 80)).containsExactly("content");
        assertThat(pick(r, 450, 80)).isEmpty(); // outside the viewport
        assertThat(pick(r, 302, 98)).isEmpty(); // in the viewport's rounded corner
        // a nested clip, and a clip that cuts everything away
        Element inner = r.box(view, "inner", 320, 0, 20, 20);
        r.geo.clip[inner.slot()] = true;
        Element deep = r.button(inner, "deep", 300, 0, 100, 100);
        Element gone = r.box(r.root(), "gone", 600, 0, 0, 0);
        r.geo.clip[gone.slot()] = true;
        r.button(gone, "hidden-child", 600, 0, 50, 50);
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 330, 10)).containsExactly("deep");
        assertThat(pick(r, 310, 10)).isEmpty();
        assertThat(pick(r, 610, 10)).isEmpty();
        assertThat(front.alive() && content.alive() && deep.alive()).isTrue();
        // a square clip inside a rounded one: the outer rounding still cuts the point off
        Element squareIn = r.box(view, "squareIn", 300, 70, 30, 30);
        r.geo.clip[squareIn.slot()] = true;
        Element corner = r.button(squareIn, "corner", 300, 70, 30, 30);
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 302, 98)).as("in the outer clip's rounded corner").isEmpty();
        assertThat(pick(r, 325, 75)).containsExactly("corner", "content");
        assertThat(corner.alive()).isTrue();
    }

    @Test
    void pointerEventsDecideWhatIsPickedAndHiddenSubtreesNeverAre() {
        Rig r = new Rig();
        Element surface = r.box(r.root(), "surface", 0, 0, 100, 100);
        Element decoration = r.button(surface, "decoration", 0, 0, 100, 100);
        Element child = r.button(decoration, "child", 0, 0, 50, 50);
        assertThat(pick(r, 10, 10)).containsExactly("child", "decoration");
        r.ia.pickable(decoration, PointerEvents.NONE);
        assertThat(pick(r, 10, 10)).containsExactly("child");
        r.geo.events[surface.slot()] = PointerEvents.ALL;
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 70, 70)).containsExactly("surface");
        r.geo.events[child.slot()] = PointerEvents.NONE;
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 10, 10)).containsExactly("surface");
        r.ia.pickable(child, PointerEvents.ALL); // code wins over the style
        assertThat(pick(r, 10, 10)).containsExactly("child", "surface");
        r.ia.pickable(child, PointerEvents.AUTO);
        r.geo.events[child.slot()] = PointerEvents.AUTO;
        Element focusOnly = r.box(r.root(), "focus-only", 200, 0, 10, 10);
        r.ia.focusable(focusOnly, true);
        assertThat(pick(r, 205, 5)).containsExactly("focus-only");
        r.geo.hidden[decoration.slot()] = true;
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 10, 10)).containsExactly("surface");
        assertThat(r.ia.picking().pickTop(10, 10)).isEqualTo(surface.slot());
        assertThat(r.ia.picking().pickTop(900, 900)).isEqualTo(UiStore.NONE);
    }

    @Test
    void layersArePickedTopFirstAndBlockingLayersStopTheSearch() {
        Rig r = new Rig();
        Element hud = r.button(r.root(), "hud", 0, 0, 100, 100);
        Layer popup = r.layers.get(Layers.POPUP);
        Element menu = r.button(popup.root(), "menu", 50, 50, 100, 100);
        Layer tooltip = r.layers.get(Layers.TOOLTIP);
        r.button(tooltip.root(), "tip", 0, 0, 200, 200);
        assertThat(pick(r, 60, 60)).containsExactly("menu", "hud"); // the tooltip layer takes no input
        Layer modal = r.layers.get(Layers.MODAL);
        assertThat(modal.blocking()).isFalse(); // empty
        Element dialog = r.button(modal.root(), "dialog", 0, 0, 40, 40);
        assertThat(modal.blocking()).isTrue();
        assertThat(pick(r, 60, 60)).containsExactly("menu"); // the pop-up is above the modal layer
        assertThat(pick(r, 10, 10)).containsExactly("dialog");
        assertThat(pick(r, 300, 300)).isEmpty();
        assertThat(r.ia.picking().blockedBy()).isEqualTo(r.layers.indexOfRoot(modal.rootSlot()));
        assertThat(r.ia.picking().pickTop(300, 300)).isEqualTo(UiStore.NONE);
        dialog.detach();
        r.ia.picking().invalidateAll();
        assertThat(pick(r, 10, 10)).containsExactly("hud");
        Layer.Input before = popup.input();
        popup.input(Layer.Input.BLOCK);
        assertThat(pick(r, 10, 10)).isEmpty(); // pop-up shows the menu: blocks the hud
        popup.input(before);
        assertThat(r.ia.picking().pickTop(60, 60)).isEqualTo(menu.slot());
        assertThat(hud.alive()).isTrue();
    }

    @Test
    void gridsAreRebuiltOnlyWhenAnElementTheyHoldMoves() {
        Rig r = new Rig();
        Element a = r.button(r.root(), "a", 0, 0, 10, 10);
        Element label = r.box(r.root(), "label", 100, 0, 10, 10);
        assertThat(r.ia.picking().elementCount(0)).isEqualTo(1);
        int built = r.ia.picking().rebuilds();
        r.ia.picking().moved(label.slot()); // not in the grid: nothing to do
        pick(r, 5, 5);
        assertThat(r.ia.picking().rebuilds()).isEqualTo(built);
        r.ia.picking().moved(a.slot());
        pick(r, 5, 5);
        assertThat(r.ia.picking().rebuilds()).isEqualTo(built + 1);
        r.ia.picking().invalidate(r.store.create("panel", "detached").slot()); // in no layer: nothing
        pick(r, 5, 5);
        assertThat(r.ia.picking().rebuilds()).isEqualTo(built + 1);
    }

    @Test
    void hugeElementsCoarsenTheGridAndAgreeWithATreeWalk() {
        Rig r = new Rig();
        Random random = new Random(7);
        List<Element> all = new ArrayList<>();
        Element big = r.button(r.root(), "big", -50_000, -50_000, 100_000, 100_000);
        all.add(big);
        for (int i = 0; i < 200; i++) {
            Element parent = all.get(random.nextInt(all.size()));
            float x = random.nextInt(900);
            float y = random.nextInt(900);
            Element e = random.nextInt(4) == 0 ? r.box(parent, "b" + i, x, y, 5 + random.nextInt(200),
                5 + random.nextInt(200)) : r.button(parent, "e" + i, x, y, 5 + random.nextInt(200),
                5 + random.nextInt(200));
            for (int k = 0; k < 4; k++) {
                r.geo.radii[e.slot() * 4 + k] = random.nextInt(3) == 0 ? random.nextInt(40) : 0;
            }
            all.add(e);
        }
        r.ia.picking().invalidateAll();
        int[] out = new int[Interaction.MAX_HITS];
        int[] walk = new int[Interaction.MAX_HITS];
        for (int i = 0; i < 2_000; i++) {
            float x = random.nextFloat() * 1100 - 50;
            float y = random.nextFloat() * 1100 - 50;
            int n = r.ia.picking().pick(x, y, out);
            int m = TreeWalk.pick(r.ia, r.geo, r.store.rootSlot(), x, y, walk);
            assertThat(n).as("hits at %s,%s", x, y).isEqualTo(m);
            for (int k = 0; k < n; k++) {
                assertThat(out[k]).isEqualTo(walk[k]);
            }
        }
    }
}
