package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickMesh;
import com.truesim.scene.core.Pickable;
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
    void pickableModelPartsRouteToTheirInteractiveAncestor() {
        SceneRig r = ScenePickingTest.rig();
        long car = r.scene.create();
        long body = r.box(0, 0, -10, 1, Mobility.STATIC);
        long wheel = r.box(0, 0, -12, 1, Mobility.STATIC);
        long loose = r.box(0, 0, -14, 1, Mobility.STATIC);
        r.scene.setParent(body, car);
        r.scene.setParent(wheel, car);
        // the model marked its parts pickable (as glTF extras do); the game listens on the model's root
        r.scene.components().set(body, Pickable.TYPE, Pickable.mesh());
        r.scene.components().set(wheel, Pickable.TYPE, Pickable.bounds());
        r.scene.components().set(loose, Pickable.TYPE, Pickable.bounds());
        List<Long> targets = new ArrayList<>();
        r.picking.add(car, Interactive.of(EventType.PRESS, e -> targets.add(r.picking.targetNode(e))));
        assertThat(r.scene.components().has(car, Pickable.TYPE)).as("the root has no box: given one, never hit").isTrue();
        r.frame();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(targets).as("both parts are the car: once").containsExactly(car);
        assertThat(r.picking.worldHitCount(com.truesim.ui.testkit.SyntheticInput.MOUSE)).isOne();
        // a part made interactive keeps the pickable it had; taking it away leaves it
        r.picking.add(body, Interactive.create());
        assertThat(r.scene.components().get(body, Pickable.TYPE).shape()).isEqualTo(Pickable.Shape.MESH);
        r.picking.remove(body);
        assertThat(r.scene.components().has(body, Pickable.TYPE)).isTrue();
        // a shape given replaces it, and goes with the interactive
        PickMesh tri = new PickMesh(new float[] {-1, -1, 0, 1, -1, 0, -1, 1, 0}, new int[] {0, 1, 2});
        r.picking.add(body, Interactive.create().mesh(tri));
        assertThat(r.scene.components().get(body, Pickable.TYPE).geometry()).isSameAs(tri);
        r.picking.add(body, Interactive.create().mesh(null));
        assertThat(r.scene.components().get(body, Pickable.TYPE).geometry()).as("its renderable's").isNull();
        r.picking.add(body, Interactive.create().shape((n, box, ox, oy, oz, dx, dy, dz, max) -> box));
        assertThat(r.scene.components().get(body, Pickable.TYPE).shape()).isEqualTo(Pickable.Shape.CUSTOM);
        r.picking.add(body, Interactive.create().bounds());
        assertThat(r.scene.components().get(body, Pickable.TYPE).shape()).isEqualTo(Pickable.Shape.BOUNDS);
        r.picking.remove(body);
        assertThat(r.scene.components().has(body, Pickable.TYPE)).isFalse();
        // only so deep along the ray, and only the pick layers asked for
        r.picking.add(loose, Interactive.of(EventType.PRESS, e -> targets.add(r.picking.targetNode(e))));
        ScenePicking.SCENE_HITS.set(1);
        try {
            targets.clear();
            r.frame();
            r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
            assertThat(targets).containsExactly(car);
        } finally {
            ScenePicking.SCENE_HITS.set(4);
        }
        r.scene.components().get(loose, Pickable.TYPE).layers(2);
        r.picking.pickLayers(2);
        targets.clear();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(targets).containsExactly(loose);
    }

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
        assertThat(r.picking.claimed(com.truesim.ui.testkit.SyntheticInput.MOUSE)).as("a handle took it").isTrue();
        assertThat(r.picking.reach(com.truesim.ui.testkit.SyntheticInput.MOUSE)).isPositive();
        over[0] = false;
        r.frame();
        assertThat(r.picking.claimed(com.truesim.ui.testkit.SyntheticInput.MOUSE)).as("the scene's").isFalse();
        over[0] = true;
        assertThat(r.picking.claimed(42)).as("a pointer it never saw").isTrue();
        assertThat(r.picking.reach(42)).isZero();
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
