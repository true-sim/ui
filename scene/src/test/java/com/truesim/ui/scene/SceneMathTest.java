package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickMesh;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** The views, anchors, meshes and panels of the scene bridge, on their own. */
class SceneMathTest {

    @Test
    void perspectiveViewsTurnPointsIntoRaysAndBack() {
        SceneView v = new SceneView().perspective(StrictMath.toRadians(90), 0.5, 100).viewport(10, 20, 200, 100);
        long version = v.version();
        v.viewport(10, 20, 200, 100).pose(0, 0, 0, 0, 0, 0, 1);
        assertThat(v.version()).isEqualTo(version); // nothing changed
        v.pose(1e7, 0, 0, 0, 0, 0, 2); // normalised
        assertThat(v.rotationW()).isEqualTo(1);
        assertThat(v.version()).isGreaterThan(version);
        double[] ray = new double[6];
        v.ray(110, 70, ray); // the centre: straight ahead
        assertThat(ray[0]).isEqualTo(1e7);
        assertThat(ray[5]).isCloseTo(-1, offset(1e-12));
        float[] p = new float[3];
        assertThat(v.project(1e7 + 1, 0.5, -2, p)).isTrue();
        assertThat(p[2]).isEqualTo(2);
        v.ray(p[0], p[1], ray);
        assertThat(ray[3] / -ray[5]).isCloseTo(0.5, offset(1e-6));
        assertThat(v.project(1e7, 0, 1, p)).isFalse(); // behind
        assertThat(v.depth(1e7, 0, -3)).isEqualTo(3);
        assertThat(v.unitsPerPixel(4)).isCloseTo(2 * 4 / 100.0, offset(1e-12));
        assertThat(v.unitsPerPixel(0)).isCloseTo(2 * 0.5 / 100.0, offset(1e-12)); // not nearer than near
        assertThat(v.contains(10, 20)).isTrue();
        assertThat(v.contains(210, 20)).isFalse();
        assertThat(v.right(0)).isEqualTo(1);
        assertThat(v.up(1)).isEqualTo(1);
        assertThat(v.forward(2)).isEqualTo(-1);
        assertThat(v.x() + v.y() + v.z()).isEqualTo(1e7);
        assertThat(v.rotationX() + v.rotationY() + v.rotationZ()).isZero();
        assertThat(v.isOrthographic()).isFalse();
        assertThat(v.fovY()).isCloseTo(StrictMath.PI / 2, offset(1e-12));
        assertThat(v.near()).isEqualTo(0.5);
        assertThat(v.far()).isEqualTo(100);
        assertThat(v.viewportX() + v.viewportY() + v.viewportWidth() + v.viewportHeight()).isEqualTo(330);
    }

    @Test
    void orthographicViewsCastParallelRays() {
        double h = Math.sqrt(0.5);
        SceneView v = new SceneView().orthographic(20, 1, 100).viewport(0, 0, 100, 100).pose(5, 50, 5, -h, 0, 0, h);
        double[] ray = new double[6];
        v.ray(0, 0, ray); // the top left: 10 units left and 10 "up" (towards -z, as the camera looks down)
        assertThat(ray[0]).isCloseTo(-5, offset(1e-9));
        assertThat(ray[2]).isCloseTo(-5, offset(1e-9));
        assertThat(ray[4]).isCloseTo(-1, offset(1e-12));
        float[] p = new float[3];
        assertThat(v.project(5, -1000, 5, p)).isTrue(); // depth is no limit for an orthographic view
        assertThat(p[0]).isEqualTo(50);
        assertThat(v.unitsPerPixel(1000)).isCloseTo(0.2, offset(1e-12));
        assertThat(v.isOrthographic()).isTrue();
    }

    @Test
    void badViewsAreRejected() {
        SceneView v = new SceneView();
        assertThatIllegalArgumentException().isThrownBy(() -> v.pose(0, 0, 0, 0, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> v.perspective(0, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> v.perspective(4, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> v.perspective(1, 0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> v.perspective(1, 2, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> v.orthographic(0, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> v.orthographic(1, 2, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> v.viewport(0, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> v.viewport(0, 0, 1, 0));
    }

    @Test
    void meshesAreHitByTheirNearestTriangle() {
        PickMesh quad = new PickMesh(new float[] {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0, -1, -1, -2, 1, -1, -2, 1, 1,
            -2}, new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6});
        assertThat(quad.triangleCount()).isEqualTo(3);
        assertThat(quad.raycast(0.5, -0.5, 5, 0, 0, -1, 100)).isEqualTo(5); // the front quad before the back triangle
        assertThat(quad.raycast(5, 5, 5, 0, 0, -1, 100)).isInfinite();
        assertThat(quad.raycast(0, 0, 5, 0, 0, -1, 4)).isInfinite(); // beyond the reach
        assertThat(new PickMesh(new float[0], new int[0]).raycast(0, 0, 0, 0, 0, 1, 1)).isInfinite();
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[2], new int[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[2]));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[] {0, 0, 1}));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[] {0, 0, -1}));
    }

    @Test
    void anchorsProjectPointsAndNodesAndHideOffTheView() {
        SceneView v = new SceneView().perspective(StrictMath.toRadians(90), 0.1, 100).viewport(0, 0, 100, 100);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long boxed = scene.create();
        scene.setLocalBounds(boxed, -1, -1, -1, 1, 1, 1);
        scene.setTranslation(boxed, 0, 0, -10);
        long bare = scene.create();
        scene.setTranslation(bare, 2, 0, -10);
        long near = scene.create();
        scene.setLocalBounds(near, -1, -1, -1, 1, 1, 1);
        scene.setTranslation(near, 0, 0, -0.5); // a corner behind the camera
        scene.update();
        float[] out = new float[4];
        SceneAnchor a = new SceneAnchor(v);
        assertThat(a.view()).isSameAs(v);
        assertThat(a.point(0, 0, -5).bounds(out)).isTrue();
        assertThat(out[0]).isEqualTo(50);
        assertThat(a.point(0, 0, 5).bounds(out)).isFalse(); // behind
        assertThat(a.point(100, 0, -5).bounds(out)).isFalse(); // off the view
        assertThat(a.node(scene, boxed).bounds(out)).isTrue();
        assertThat(a.node()).isEqualTo(boxed);
        assertThat(out[2]).isPositive();
        assertThat(a.node(scene, bare).bounds(out)).isTrue();
        assertThat(out[2]).isZero();
        assertThat(a.node(scene, near).bounds(out)).isTrue();
        scene.remove(bare);
        assertThat(a.node(scene, bare).bounds(out)).isFalse();
        assertThat(a.view(v).node(scene, NodeHandle.NONE).bounds(out)).isFalse();
    }

    @Test
    void worldPanelsCheckTheirSizesAndPlacementsAndMeetRaysOnTheirPlane() {
        Ui ui = new Ui(Tss.defaultTheme(), null);
        assertThatIllegalArgumentException().isThrownBy(() -> new WorldPanel(ui, 0, 1));
        WorldPanel panel = new WorldPanel(ui, 100, 50);
        assertThat(ui.worldSpace()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> panel.size(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> panel.pixelsPerMeter(0));
        assertThatIllegalArgumentException().isThrownBy(() -> panel.place(0, 0, 0, 0, 0, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> panel.place(0, 0, 0, 0, 0, 0, 1, 0));
        // turned half round about y and doubled: its front faces -z, a metre is 200 of its pixels... at scale 2
        panel.pixelsPerMeter(100).place(0, 0, -5, 0, 1, 0, 0, 2);
        float[] local = new float[2];
        assertThat(panel.intersect(0.5, 0, 0, 0, 0, -1, true, local)).isEqualTo(5);
        assertThat(local[0]).isCloseTo(25, offset(1e-3f)); // its x runs the other way, halved by the scale
        assertThat(panel.intersect(0, 0, 0, 1, 0, 0, true, local)).isInfinite(); // parallel
        assertThat(panel.intersect(10, 0, 0, 0, 0, -1, true, local)).isInfinite(); // beside it
        assertThat(panel.intersect(10, 0, 0, 0, 0, -1, false, local)).isEqualTo(5); // its plane, unbounded
        assertThat(panel.input()).isNotNull();
        assertThat(panel.ui()).isSameAs(ui);
        assertThat(panel.width() + panel.height() + panel.pixelsPerMeter()).isEqualTo(250);
        assertThat(panel.passThrough()).isTrue();
        assertThat(panel.element()).isNull();
        assertThat(panel.node()).isEqualTo(NodeHandle.NONE);
    }
}
