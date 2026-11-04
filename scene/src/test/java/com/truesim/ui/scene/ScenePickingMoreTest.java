package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The scene bridge's edges: handles before the scene, views coming and going, pointers it never saw. */
class ScenePickingMoreTest {

    @Test
    void handlesComeBeforeTheSceneAndCanBeRemoved() {
        SceneRig r = ScenePickingTest.rig();
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<String> seen = new ArrayList<>();
        r.picking.add(node, Interactive.of(EventType.PRESS, e -> seen.add("node")));
        Element handle = r.ui.panel(null);
        r.ui.layer("overlay").root().add(handle);
        r.ui.on(handle, EventType.PRESS, e -> seen.add("handle"));
        boolean[] over = {true};
        HandlePicker picker = (pointer, view, ray) -> over[0] ? handle.slot() : UiStore.NONE;
        HandlePicker other = (pointer, view, ray) -> UiStore.NONE;
        r.picking.addHandles(picker);
        r.picking.addHandles(other);
        r.picking.addHandles(other); // grows past two
        r.frame();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(seen).containsExactly("handle", "node");
        double[] point = new double[3];
        r.picking.removeHandles(picker);
        r.picking.removeHandles(picker); // not there: nothing
        seen.clear();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(seen).containsExactly("node");
        assertThat(r.picking.worldHitCount(42)).isZero();
        assertThat(r.picking.viewOf(42)).isNull();
        assertThat(r.picking.ray(42, new double[6])).isFalse();
        assertThat(r.picking.hovered(NodeHandle.NONE)).isFalse();
        assertThat(r.picking.pressed(node)).isFalse();
        assertThat(point[0]).isZero();
    }

    @Test
    void viewsAreAddedReplacedAndRemovedAndAPointerOffEveryViewPicksNothing() {
        SceneRig r = ScenePickingTest.rig();
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        r.picking.add(node, Interactive.create());
        Element[] es = new Element[5];
        SceneView[] vs = new SceneView[5];
        for (int i = 0; i < es.length; i++) {
            es[i] = r.ui.panel(null);
            vs[i] = new SceneView().viewport(0, 0, 1, 1);
            r.picking.view(es[i], vs[i]); // past the room for four
        }
        r.picking.view(es[0], vs[1]); // replaced
        r.picking.view(es[1], null); // removed
        r.picking.view(r.ui.panel(null), null); // never added: nothing
        r.frame();
        r.view.viewport(0, 0, 100, 100); // the main view no longer under the pointer
        r.mouse(400, 300, 0);
        assertThat(r.picking.worldHitCount(0)).isZero();
        assertThat(r.picking.ray(0, new double[6])).isFalse();
        r.picking.mainView(null);
        r.mouse(50, 50, 0);
        assertThat(r.picking.worldHitCount(0)).isZero();
    }

    @Test
    void tooltipsNeedATextSystemAndNodesAreRemovedWithTheirTooltip() {
        SceneRig r = ScenePickingTest.rig(); // no text system: no tooltip shows
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        r.picking.add(node, Interactive.create().tooltip("no text"));
        r.frame();
        r.mouse(400, 300, 0).frames(60);
        assertThat(r.picking.tooltip()).isNull();
        assertThat(r.picking.tooltipNode()).isEqualTo(NodeHandle.NONE);
        SceneRig t = new SceneRig(com.truesim.ui.style.tss.Tss.defaultTheme(), ScenePickingTest.jdkText(), 800, 600);
        long lamp = t.box(0, 0, -10, 1, Mobility.STATIC);
        t.picking.add(lamp, Interactive.create().tooltip("A lamp"));
        t.frame();
        t.mouse(400, 300, 0).frames(60);
        assertThat(t.picking.tooltipNode()).isEqualTo(lamp);
        t.picking.remove(lamp);
        t.frame();
        assertThat(t.picking.tooltipNode()).isEqualTo(NodeHandle.NONE);
        t.picking.remove(lamp); // gone already: nothing
    }

    @Test
    void aCameraViewWithoutAPictureDrawsNoImage() {
        SceneRig r = ScenePickingTest.rig();
        CameraViewSource empty = new CameraViewSource() {
            @Override
            public void request(int widthPx, int heightPx) {
            }

            @Override
            public com.truesim.ui.core.draw.UiTexture texture() {
                return null;
            }

            @Override
            public int width() {
                return 0;
            }

            @Override
            public int height() {
                return 0;
            }

            @Override
            public long version() {
                return 0;
            }
        };
        CameraView view = new CameraView(r.ui, empty, null, null);
        r.ui.root().add(view.element());
        SceneRig.place(r.ui, view.element(), 10, 10, 100, 80);
        r.frames(2);
        assertThat(view.placed()).isTrue();
        assertThat(view.source()).isSameAs(empty);
        assertThat(view.view().viewportWidth()).isEqualTo(94); // the content box: the theme's border and padding off
        view.dispose();
    }
}
