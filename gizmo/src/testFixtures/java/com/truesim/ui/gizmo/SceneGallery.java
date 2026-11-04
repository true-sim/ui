package com.truesim.ui.gizmo;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.scene.CameraView;
import com.truesim.ui.scene.FakeCameraSource;
import com.truesim.ui.scene.Interactive;
import com.truesim.ui.scene.SceneAnchor;
import com.truesim.ui.scene.ScenePicking;
import com.truesim.ui.scene.SceneRig;
import com.truesim.ui.scene.SceneView;

/**
 * The scene and gizmo gallery: a synthetic scene seen through a main view with a gizmo of every mode (translate on a
 * crate, rotate on a barrel in local space, scale, bounds on a wall, universal), one handle hovered and one hidden
 * part faded by scene occlusion, a view cube in the top right corner, and a minimap (a camera view with a fake
 * picture) with a marker. Used by the tests here, {@code GallerySanityTest} and the engine's GPU screenshots.
 */
public final class SceneGallery {

    public final Ui ui;
    public final SceneGraph scene;
    public final SpatialIndex index;
    public final ScenePicking picking;
    public final SceneView view = new SceneView();
    public final Gizmo[] gizmos = new Gizmo[5];
    public final ViewCube cube;
    public final CameraView minimap;
    public final FakeCameraSource source;
    public final UiInput input = new UiInput(8);
    public final float width;
    public final float height;
    private long nanos = 1_000_000_000L;

    private SceneGallery(Ui ui, float width, float height) {
        this.ui = ui;
        this.width = width;
        this.height = height;
        scene = new SceneGraph(new ChangeClock());
        index = new SpatialIndex(scene);
        view.perspective(Math.toRadians(55), 0.1, 1000).viewport(0, 0, width, height);
        // from the front right and above: yaw 20 degrees about y, then pitch down about x
        double yaw = Math.toRadians(20) / 2;
        double pitch = -Math.atan2(5, 14) / 2;
        view.pose(14 * Math.sin(2 * yaw), 5, 14 * Math.cos(2 * yaw), Math.cos(yaw) * Math.sin(pitch),
            Math.sin(yaw) * Math.cos(pitch), -Math.sin(yaw) * Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        picking = new ScenePicking(ui, scene, index).mainView(view);
        long crate = box(-5, 0.5, 0, 0.5);
        long barrel = box(-1.5, 0.6, 1, 0.6);
        scene.setRotation(barrel, 0, Math.sin(0.35), 0, Math.cos(0.35));
        long statue = box(2, 0.8, 0, 0.8);
        long wall = box(5.5, 1, -1, 1);
        scene.setScale(wall, 1, 1, 0.3);
        long lamp = box(3.5, 0.4, 5, 0.4);
        long blocker = box(-5.2, 0.6, 2.2, 0.7); // in front of the crate's gizmo: its hidden parts fade
        for (long node : new long[] {crate, barrel, statue, wall, lamp, blocker}) {
            picking.add(node, Interactive.create());
        }
        scene.update();
        index.update();
        GizmoMode[] modes = {GizmoMode.TRANSLATE, GizmoMode.ROTATE, GizmoMode.SCALE, GizmoMode.BOUNDS,
            GizmoMode.UNIVERSAL};
        long[] targets = {crate, barrel, statue, wall, lamp};
        for (int i = 0; i < 5; i++) {
            Gizmo g = new Gizmo(picking, view, null).mode(modes[i]);
            g.select(targets[i]);
            g.occlusion(new RayOcclusion(index, -1, g::contains));
            gizmos[i] = g;
        }
        gizmos[1].space(GizmoSpace.LOCAL);
        cube = new ViewCube(ui, view, null);
        ui.root().add(cube.element());
        SceneRig.place(ui, cube.element(), width - 136, 16, 120, 120);
        source = new FakeCameraSource(ui.textures(), 256, 256);
        minimap = new CameraView(ui, source, picking, null);
        ui.root().add(minimap.element());
        SceneRig.place(ui, minimap.element(), 16, height - 196, 240, 180);
        double h = Math.sqrt(0.5);
        minimap.view().orthographic(24, 1, 200).pose(0, 50, 0, -h, 0, 0, h);
        Element dot = ui.panel(null);
        dot.style("minimap-marker");
        SceneRig.place(ui, dot, 0, 0, 10, 10);
        SceneAnchor marker = minimap.marker(new Overlays(ui), dot);
        marker.node(scene, barrel);
    }

    private long box(double x, double y, double z, double half) {
        long node = scene.create();
        scene.setLocalBounds(node, -half, -half, -half, half, half, half);
        scene.setTranslation(node, x, y, z);
        scene.setMobility(node, Mobility.DYNAMIC);
        return node;
    }

    /** The gallery on {@code ui}, {@code width x height} UI pixels, settled, with the barrel's Y ring hovered. */
    public static SceneGallery build(Ui ui, float width, float height) {
        SceneGallery g = new SceneGallery(ui, width, height);
        g.frames(3);
        // hover the barrel's rotate gizmo: its Y ring, on its front
        Gizmo rotate = g.gizmos[1];
        double r = rotate.size() * 0.9;
        float[] at = new float[3];
        double angle = Math.toRadians(95); // 45 degrees from the turned barrel's x and z rings
        g.view.project(rotate.origin(0) + r * Math.cos(angle), rotate.origin(1), rotate.origin(2) + r * Math.sin(angle),
            at);
        g.input.pointer(0, Pointer.Kind.MOUSE).moveTo(at[0] + 1, at[1]);
        g.frames(1);
        g.input.pointer(0, Pointer.Kind.MOUSE).moveTo(at[0], at[1]);
        g.frames(30);
        return g;
    }

    /** {@code n} frames of 16 ms. */
    public SceneGallery frames(int n) {
        for (int i = 0; i < n; i++) {
            scene.update();
            index.update();
            input.beginFrame();
            nanos += 16_000_000L;
            picking.input(input, nanos);
            picking.update(width, height);
        }
        return this;
    }
}
