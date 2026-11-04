package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkTextBackend;
import com.truesim.ui.core.draw.UiTextures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scene objects and world panels take part in the UI's pointer interaction (design 4.7): picked by the pointer's ray
 * after the screen layers, nearest first, routed with the same listeners, states, capture, pass-through and drag and
 * drop as elements.
 */
class ScenePickingTest {

    static TextSystem jdkText() {
        JdkTextBackend backend = new JdkTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    static SceneRig rig() {
        return new SceneRig(Tss.defaultTheme(), null, 800, 600);
    }

    /** What listeners saw: type, node, click count. */
    record Seen(EventType type, long node, int clicks) {
    }

    private static Interactive recording(SceneRig r, List<Seen> seen) {
        return Interactive.of(e -> seen.add(new Seen(e.type(), r.picking.node(e), e.clickCount())));
    }

    private static List<EventType> types(List<Seen> seen) {
        return seen.stream().map(Seen::type).toList();
    }

    @Test
    void aClickOnASceneNodeReachesItsListenersWithClickCounts() {
        SceneRig r = rig();
        long crate = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<Seen> seen = new ArrayList<>();
        Element e = r.picking.add(crate, recording(r, seen));
        r.frame();
        r.mouse(400, 300, 0);
        assertThat(types(seen)).contains(EventType.ENTER);
        assertThat(r.picking.hovered(crate)).isTrue();
        assertThat(e.is(UiState.HOVER)).isTrue();
        seen.clear();
        r.mouse(400, 300, Pointer.PRIMARY);
        assertThat(r.picking.pressed(crate)).isTrue();
        r.mouse(400, 300, 0).click(400, 300);
        List<Seen> clicks = seen.stream().filter(s -> s.type() == EventType.CLICK).toList();
        assertThat(clicks).extracting(Seen::clicks).containsExactly(1, 2);
        assertThat(clicks).extracting(Seen::node).containsOnly(crate);
        r.mouse(10, 10, 0); // off it
        assertThat(types(seen)).contains(EventType.EXIT);
        assertThat(r.picking.hovered(crate)).isFalse();
        assertThat(r.picking.worldHitCount(0)).isZero();
    }

    @Test
    void theNearestNodeWinsAndUnconsumedEventsPassThroughToTheNodesBehind() {
        SceneRig r = rig();
        long front = r.box(0, 0, -5, 1, Mobility.DYNAMIC);
        long back = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<Long> pressed = new ArrayList<>();
        boolean[] consume = {false};
        r.picking.add(front, Interactive.of(EventType.PRESS, e -> {
            pressed.add(r.picking.node(e));
            if (consume[0]) {
                e.consume();
            }
        }));
        r.picking.add(back, Interactive.of(EventType.PRESS, e -> pressed.add(r.picking.node(e))));
        r.frame();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(pressed).containsExactly(front, back);
        assertThat(r.picking.worldHitCount(0)).isEqualTo(2);
        assertThat(r.picking.node(r.picking.worldHit(0, 0))).isEqualTo(front);
        pressed.clear();
        consume[0] = true;
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(pressed).containsExactly(front);
        ScenePicking.SCENE_HITS.set(1);
        try {
            consume[0] = false;
            pressed.clear();
            r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
            assertThat(pressed).containsExactly(front); // only one object along the ray takes part
        } finally {
            ScenePicking.SCENE_HITS.set(4);
        }
    }

    @Test
    void screenElementsComeFirstAndPassUnconsumedEventsOnToTheScene() {
        SceneRig r = rig();
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<String> order = new ArrayList<>();
        r.picking.add(node, Interactive.of(EventType.PRESS, e -> order.add("node")));
        Element hud = r.ui.panel(null);
        r.ui.root().add(hud);
        SceneRig.place(r.ui, hud, 0, 0, 200, 600);
        boolean[] consume = {false};
        r.ui.on(hud, EventType.PRESS, e -> {
            order.add("hud");
            if (consume[0]) {
                e.consume();
            }
        });
        r.frames(2);
        // the panel is at the top left, 200 wide: put the node under it
        r.view.pose(0, 0, 0, 0, 0, 0, 1).viewport(0, 0, 800, 600);
        float[] at = new float[3];
        assertThat(r.view.project(0, 0, -10, at)).isTrue();
        SceneRig.place(r.ui, hud, 0, 0, 800, 600);
        r.frames(2);
        r.mouse(at[0], at[1], Pointer.PRIMARY).mouse(at[0], at[1], 0);
        assertThat(order).containsExactly("hud", "node");
        order.clear();
        consume[0] = true;
        r.mouse(at[0], at[1], Pointer.PRIMARY).mouse(at[0], at[1], 0);
        assertThat(order).containsExactly("hud");
    }

    @Test
    void aPressCapturesThePointerForTheNodeEvenOffIt() {
        SceneRig r = rig();
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<EventType> seen = new ArrayList<>();
        r.picking.add(node, Interactive.of(e -> seen.add(e.type())));
        r.frame();
        r.mouse(400, 300, Pointer.PRIMARY);
        r.mouse(600, 400, Pointer.PRIMARY).mouse(700, 500, Pointer.PRIMARY); // dragged off: still the node's
        r.mouse(700, 500, 0);
        assertThat(seen).contains(EventType.DRAG_START, EventType.DRAG, EventType.DRAG_END, EventType.RELEASE)
            .doesNotContain(EventType.CLICK);
        double[] ray = new double[6];
        assertThat(r.picking.ray(0, ray)).isTrue();
        assertThat(ray[5]).isNegative();
    }

    @Test
    void eventsBubbleToTheNearestInteractiveAncestorInTheSceneGraph() {
        SceneRig r = rig();
        long car = r.scene.create();
        long body = r.scene.create(car);
        long wheel = r.scene.create(body);
        r.scene.setTranslation(car, 0, 0, -10);
        r.scene.setLocalBounds(wheel, -1, -1, -1, 1, 1, 1);
        List<String> seen = new ArrayList<>();
        // the wheel first, then its ancestor: the wheel's element moves under the car's
        r.picking.add(wheel, Interactive.of(EventType.CLICK, e -> seen.add("wheel " + (r.picking.node(e) == wheel))));
        r.picking.add(car, Interactive.of(EventType.CLICK, e -> seen.add("car " + (r.picking.targetNode(e) == wheel))));
        assertThat(r.picking.element(wheel).parent()).isEqualTo(r.picking.element(car));
        r.frame();
        r.click(400, 300);
        assertThat(seen).containsExactly("wheel true", "car true");
        r.picking.remove(car); // the wheel's element goes back up
        assertThat(r.picking.element(wheel).parent()).isNotNull();
        assertThat(r.picking.has(car)).isFalse();
        assertThat(r.scene.layers(car) & SceneRig.INTERACTIVE.mask()).isZero();
        seen.clear();
        r.click(400, 300);
        assertThat(seen).containsExactly("wheel true");
    }

    @Test
    void meshesAreHitByTheirTrianglesNotTheirBoxes() {
        SceneRig r = rig();
        // a triangle covering the lower left half of its unit box (in the node's space, scaled and turned)
        PickMesh half = new PickMesh(new float[] {-1, -1, 0, 1, -1, 0, -1, 1, 0}, new int[] {0, 1, 2});
        long node = r.scene.create();
        r.scene.setLocalBounds(node, -1, -1, -0.1, 1, 1, 0.1);
        r.scene.setTranslation(node, 0, 0, -10);
        r.scene.setScale(node, 2, 2, 2);
        double s = Math.sqrt(0.5);
        r.scene.setRotation(node, 0, 0, 0, 1);
        long behind = r.box(0, 0, -20, 4, Mobility.STATIC);
        List<Long> hits = new ArrayList<>();
        r.picking.add(node, Interactive.of(EventType.PRESS, e -> hits.add(r.picking.node(e))).mesh(half));
        r.picking.add(behind, Interactive.of(EventType.PRESS, e -> hits.add(r.picking.node(e))));
        assertThat(half.built()).isFalse();
        r.frame();
        float[] lowerLeft = new float[3];
        float[] upperRight = new float[3];
        r.view.project(-1, -1, -10, lowerLeft);
        r.view.project(1, 1, -10, upperRight);
        r.mouse(lowerLeft[0], lowerLeft[1], Pointer.PRIMARY).mouse(lowerLeft[0], lowerLeft[1], 0);
        assertThat(hits).containsExactly(node, behind);
        assertThat(half.built()).isTrue();
        double[] point = new double[3];
        hits.clear();
        r.picking.add(behind, Interactive.of(EventType.PRESS, e -> {
            hits.add(r.picking.node(e));
            assertThat(r.picking.hitPoint(e, point)).isTrue();
            assertThat(r.picking.hitDistance(e)).isFinite();
        }));
        r.frame();
        r.mouse(upperRight[0], upperRight[1], Pointer.PRIMARY).mouse(upperRight[0], upperRight[1], 0);
        assertThat(hits).containsExactly(behind); // the box is hit there, the triangle is not
        assertThat(point[2]).isCloseTo(-16, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s).isPositive();
    }

    @Test
    void customNarrowPhasesDecideAndNodesWithoutComponentsCostNothing() {
        SceneRig r = rig();
        long sphere = r.box(0, 0, -10, 1, Mobility.STATIC);
        long plain = r.box(0, 0, -8, 1, Mobility.STATIC); // no component: not on the layer
        long stray = r.box(0, 0, -9, 1, Mobility.STATIC); // on the layer, but no component
        r.scene.setLayers(stray, SceneRig.INTERACTIVE.mask());
        List<Long> hits = new ArrayList<>();
        r.picking.add(sphere, Interactive.of(EventType.PRESS, e -> hits.add(r.picking.node(e))).shape(
            (node, box, ox, oy, oz, dx, dy, dz, max) -> com.truesim.common.math.geometry.Intersections.raySphere(ox,
                oy, oz, dx, dy, dz, 0, 0, -10, 1)));
        r.frame();
        long before = r.picking.narrowTests();
        r.mouse(400, 300, Pointer.PRIMARY).mouse(400, 300, 0);
        assertThat(hits).containsExactly(sphere);
        assertThat(r.picking.narrowTests() - before).isBetween(2L, 16L); // the sphere and the stray, never the plain
        assertThat(plain).isNotEqualTo(stray);
        Interactive c = r.picking.component(sphere);
        assertThat(c.shapeKind()).isEqualTo(Interactive.Shape.CUSTOM);
        assertThat(c.bounds().shapeKind()).isEqualTo(Interactive.Shape.BOUNDS);
        assertThat(c.listenerCount()).isEqualTo(1);
        assertThat(c.pickMesh()).isNull();
    }

    @Test
    void xrPointersPickByTheirRecordedRays() {
        SceneRig r = rig();
        long target = r.box(5, 1.5, -3, 0.5, Mobility.DYNAMIC);
        List<EventType> seen = new ArrayList<>();
        r.picking.add(target, Interactive.of(e -> seen.add(e.type())));
        r.frame();
        // a recorded controller pose: from the right hand at (0.3, 1.2, 0), sweeping onto the target and pulling
        double[][] poses = {{0.3, 1.2, 0, 0, 0, -1}, {0.3, 1.2, 0, 0.6, 0.04, -0.4}, {0.3, 1.2, 0, 4.7, 0.3, -3}};
        for (double[] pose : poses) {
            r.ray(7, pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], 0).frame();
        }
        assertThat(seen).contains(EventType.ENTER);
        r.ray(7, 0.3, 1.2, 0, 4.7, 0.3, -3, Pointer.PRIMARY).frame();
        r.ray(7, 0.3, 1.2, 0, 4.7, 0.3, -3, 0).frame();
        assertThat(seen).contains(EventType.PRESS, EventType.RELEASE, EventType.CLICK);
        assertThat(r.picking.viewOf(7)).isNull();
        r.dropRay(7).frame();
        assertThat(seen).contains(EventType.EXIT);
        ScenePicking.RAY_LENGTH.set(2);
        try {
            seen.clear();
            r.ray(8, 0.3, 1.2, 0, 4.7, 0.3, -3, 0).frame();
            assertThat(seen).doesNotContain(EventType.ENTER); // out of reach
        } finally {
            ScenePicking.RAY_LENGTH.set(1000);
        }
    }

    static final DataFormat<String> ITEM = DataFormat.of("test.item", String.class);

    @Test
    void dragAndDropGoesFromAListToASceneNodeAndBackInOneSession() {
        SceneRig r = rig();
        long chest = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<String> log = new ArrayList<>();
        r.picking.add(chest, Interactive.create().acceptDrops(DragOperation.MOVE, ITEM)
            .draggable(ITEM, "gold", DragOperation.MOVE)
            .on(EventType.DROP, e -> log.add("chest got " + e.drag().get(ITEM))));
        Element slot = r.ui.panel(null);
        r.ui.root().add(slot);
        SceneRig.place(r.ui, slot, 0, 0, 100, 100);
        r.ui.on(slot, EventType.DRAG_START, e -> e.startDrag(DragOperation.MOVE).put(ITEM, "sword"));
        r.ui.on(slot, EventType.DROP_OVER, e -> {
            if (e.drag().has(ITEM)) {
                e.acceptDrop(DragOperation.MOVE);
            }
        });
        r.ui.on(slot, EventType.DROP, e -> log.add("slot got " + e.drag().get(ITEM) + " from node "
            + (r.picking.node(e.drag().source()) == chest)));
        r.frames(2);
        // list slot -> chest
        r.mouse(50, 50, Pointer.PRIMARY).mouse(200, 200, Pointer.PRIMARY).mouse(400, 300, Pointer.PRIMARY);
        assertThat(r.picking.element(chest).is(UiState.DROP_TARGET)).isTrue();
        r.mouse(400, 300, 0);
        // chest -> list slot
        r.mouse(400, 300, Pointer.PRIMARY).mouse(300, 200, Pointer.PRIMARY).mouse(50, 50, Pointer.PRIMARY);
        assertThat(r.ui.interaction().dragSession(0).source()).isEqualTo(r.picking.element(chest));
        r.mouse(50, 50, 0);
        assertThat(log).containsExactly("chest got sword", "slot got gold from node true");
    }

    @Test
    void worldPanelsGetThePointersWhoseRaysReachThemAtTheirLocalPoints() {
        SceneRig r = rig();
        com.truesim.ui.controls.Ui panelUi = new com.truesim.ui.controls.Ui(Tss.defaultTheme(), null);
        WorldPanel panel = new WorldPanel(panelUi, 400, 300).pixelsPerMeter(100).place(0, 0, -5, 0, 0, 0, 1, 1);
        Element button = panelUi.panel(null);
        panelUi.root().add(button);
        SceneRig.place(panelUi, button, 0, 0, 100, 100);
        float[] local = new float[2];
        List<String> seen = new ArrayList<>();
        panelUi.on(button, EventType.CLICK, e -> seen.add("button " + Math.round(e.x()) + "," + Math.round(e.y())));
        long behind = r.box(0, 0, -10, 3, Mobility.STATIC);
        r.picking.add(behind, Interactive.of(EventType.CLICK, e -> seen.add("behind")));
        r.picking.addPanel(panel);
        assertThat(r.picking.panel(panel.element())).isSameAs(panel);
        assertThat(r.picking.panelCount()).isEqualTo(1);
        r.frames(2);
        // the button is the panel's top-left 100 px: one metre square, from (-2, 1.5) to (-1, 0.5) at z = -5
        float[] at = new float[3];
        r.view.project(-1.5, 1, -5, at);
        r.click(at[0], at[1]);
        assertThat(seen).containsExactly("button 50,50");
        seen.clear();
        r.view.project(1, -1, -5, at); // the panel's empty part: passes through to the node behind
        r.click(at[0], at[1]);
        assertThat(seen).containsExactly("behind");
        seen.clear();
        panel.passThrough(false);
        r.click(at[0], at[1]);
        assertThat(seen).isEmpty();
        assertThat(panel.intersect(0, 0, 0, 0, 0, 1, true, local)).isInfinite(); // pointing away
        double[] transform = new double[8];
        assertThat(panel.transform(transform)).isTrue();
        assertThat(transform[7]).isEqualTo(0.01);
        r.picking.removePanel(panel);
        assertThat(r.picking.panelCount()).isZero();
    }

    @Test
    void aWorldPanelFollowsItsNodeAndKeepsAPointerItCapturedOffItsRectangle() {
        SceneRig r = rig();
        com.truesim.ui.controls.Ui panelUi = new com.truesim.ui.controls.Ui(Tss.defaultTheme(), null);
        long screen = r.scene.create();
        r.scene.setTranslation(screen, 0, 0, -5);
        WorldPanel panel = new WorldPanel(panelUi, 200, 200).pixelsPerMeter(100).follow(r.scene, screen);
        assertThat(panel.node()).isEqualTo(screen);
        Element knob = panelUi.panel(null);
        panelUi.root().add(knob);
        SceneRig.place(panelUi, knob, 0, 0, 200, 200);
        List<String> seen = new ArrayList<>();
        panelUi.on(knob, EventType.DRAG, e -> seen.add("drag " + Math.round(e.x())));
        r.picking.addPanel(panel);
        r.frames(2);
        float[] at = new float[3];
        r.view.project(0, 0, -5, at);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.view.project(2, 0, -5, at);
        r.mouse(at[0], at[1], Pointer.PRIMARY);
        r.view.project(3, 0, -5, at); // 3 m right: off the panel, still captured by its knob
        r.mouse(at[0], at[1], Pointer.PRIMARY).mouse(at[0], at[1], 0);
        assertThat(seen).contains("drag 400");
        r.scene.remove(screen);
        r.frame();
        assertThat(panel.transform(new double[8])).isFalse();
        assertThat(panel.intersect(0, 0, 0, 0, 0, -1, true, new float[2])).isInfinite();
    }

    @Test
    void contextMenusTooltipsAndTheWheelReachSceneNodes() {
        SceneRig r = new SceneRig(Tss.defaultTheme(), jdkText(), 800, 600);
        long lamp = r.box(0, 0, -10, 1, Mobility.STATIC);
        Controls controls = new Controls(r.ui);
        Menu menu = new Menu(controls, "lamp");
        menu.item("Switch on", () -> { });
        List<EventType> seen = new ArrayList<>();
        r.picking.add(lamp, Interactive.of(EventType.WHEEL.bit() | EventType.LONG_PRESS.bit(), e -> seen.add(e.type()))
            .tooltip("A lamp").contextMenu(menu));
        r.frame();
        r.mouse(400, 300, 0).frames(60); // rests: the tooltip shows
        assertThat(r.picking.tooltipNode()).isEqualTo(lamp);
        assertThat(r.picking.tooltip().showing()).isTrue();
        r.pointers.scroll(0, 0, -1);
        r.frame();
        assertThat(seen).contains(EventType.WHEEL);
        r.mouse(400, 300, Pointer.SECONDARY).mouse(400, 300, 0);
        assertThat(menu.isOpen()).isTrue();
        assertThat(r.picking.tooltipNode()).isEqualTo(NodeHandle.NONE);
        menu.close();
        r.mouse(400, 300, Pointer.PRIMARY).frames(60).mouse(400, 300, 0);
        assertThat(seen).contains(EventType.LONG_PRESS);
        r.picking.remove(lamp);
        assertThat(r.picking.count()).isZero();
    }

    @Test
    void cameraViewsPickThroughTheirOwnCamera() {
        SceneRig r = rig();
        FakeCameraSource source = new FakeCameraSource(r.ui.textures(), 256, 256);
        CameraView minimap = new CameraView(r.ui, source, r.picking, null);
        r.ui.root().add(minimap.element());
        SceneRig.place(r.ui, minimap.element(), 0, 0, 200, 200);
        // the map camera looks straight down from 100 m over (50, 0, 50)
        double h = Math.sqrt(0.5);
        minimap.view().orthographic(40, 1, 1000).pose(50, 100, 50, -h, 0, 0, h);
        long tower = r.box(50, 0, 50, 2, Mobility.STATIC);
        long seenInMain = r.box(0, 0, -10, 1, Mobility.STATIC);
        List<Long> clicked = new ArrayList<>();
        r.picking.add(tower, Interactive.of(EventType.CLICK, e -> clicked.add(r.picking.node(e))));
        r.picking.add(seenInMain, Interactive.of(EventType.CLICK, e -> clicked.add(r.picking.node(e))));
        r.frames(3);
        assertThat(minimap.placed()).isTrue();
        assertThat(source.version()).isPositive();
        assertThat(source.requests()).isPositive();
        r.click(100, 100); // the minimap's centre: the tower under its camera
        assertThat(clicked).containsExactly(tower);
        assertThat(r.picking.viewOf(0)).isSameAs(minimap.view());
        r.click(400, 300); // the main view
        assertThat(clicked).containsExactly(tower, seenInMain);
        SceneAnchor marker = minimap.anchor().node(r.scene, tower);
        float[] box = new float[4];
        assertThat(marker.bounds(box)).isTrue();
        assertThat(box[0] + box[2] / 2).isCloseTo(100, org.assertj.core.data.Offset.offset(1f));
        minimap.dispose();
        r.click(100, 100);
        assertThat(clicked).hasSize(2); // through the main view now: nothing there
    }

    @Test
    void misuseIsRejectedAndStaleNodesArePruned() {
        SceneRig r = rig();
        long node = r.box(0, 0, -10, 1, Mobility.STATIC);
        r.scene.remove(node);
        assertThatIllegalArgumentException().isThrownBy(() -> r.picking.add(node, Interactive.create()));
        long other = r.box(0, 0, -10, 1, Mobility.STATIC);
        r.picking.add(other, Interactive.create());
        r.picking.add(other, Interactive.create()); // replaces
        assertThat(r.picking.count()).isEqualTo(1);
        r.scene.remove(other);
        r.picking.prune();
        assertThat(r.picking.count()).isZero();
        assertThat(r.picking.node((Element) null)).isEqualTo(NodeHandle.NONE);
        assertThat(r.picking.element(other)).isNull();
        assertThat(r.picking.component(other)).isNull();
        assertThat(r.picking.mainView()).isSameAs(r.view);
        assertThat(r.picking.ui()).isSameAs(r.ui);
        assertThat(r.picking.scene()).isSameAs(r.scene);
        assertThat(r.picking.index()).isSameAs(r.index);
        assertThat(r.picking.interactiveLayer()).isEqualTo(SceneRig.INTERACTIVE);
        assertThat(r.ui.tunables()).contains(ScenePicking.SCENE_HITS, ScenePicking.RAY_LENGTH);
        UiEvent[] none = new UiEvent[1];
        assertThat(none[0]).isNull();
    }
}
