package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickMesh;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.tss.Tss;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Section 11's scene picking targets: at most 20 µs per pointer by bounds and 50 µs with the mesh narrow phase, in a
 * 100,000-node scene (static and dynamic) of which 1% is interactive; and 0 for nodes without an {@link Interactive}
 * (counted narrow-phase visits). The timings run with {@code TRUESIM_BENCHMARK=true} (JaCoCo off) and print; the
 * counted visits always run.
 */
class ScenePickingBenchmarkTest {

    static final int NODES = 100_000;
    static final int INTERACTIVE_EVERY = 100;

    /** A unit sphere of 320 triangles (an icosphere, subdivided twice), shared by every mesh-picked node. */
    static PickMesh sphere() {
        double t = (1 + Math.sqrt(5)) / 2;
        double[][] v = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t},
            {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2},
            {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11},
            {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        java.util.List<double[]> verts = new java.util.ArrayList<>(Arrays.asList(v));
        java.util.List<int[]> faces = new java.util.ArrayList<>(Arrays.asList(f));
        for (int level = 0; level < 2; level++) {
            java.util.List<int[]> next = new java.util.ArrayList<>();
            java.util.Map<Long, Integer> mids = new java.util.HashMap<>();
            for (int[] tri : faces) {
                int[] m = new int[3];
                for (int e = 0; e < 3; e++) {
                    int a = tri[e];
                    int b = tri[(e + 1) % 3];
                    long key = Math.min(a, b) * 100_000L + Math.max(a, b);
                    Integer mid = mids.get(key);
                    if (mid == null) {
                        double[] p = verts.get(a);
                        double[] q = verts.get(b);
                        verts.add(new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2});
                        mid = verts.size() - 1;
                        mids.put(key, mid);
                    }
                    m[e] = mid;
                }
                next.add(new int[] {tri[0], m[0], m[2]});
                next.add(new int[] {tri[1], m[1], m[0]});
                next.add(new int[] {tri[2], m[2], m[1]});
                next.add(m);
            }
            faces = next;
        }
        float[] positions = new float[verts.size() * 3];
        for (int i = 0; i < verts.size(); i++) {
            double[] p = verts.get(i);
            double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            positions[i * 3] = (float) (p[0] / l);
            positions[i * 3 + 1] = (float) (p[1] / l);
            positions[i * 3 + 2] = (float) (p[2] / l);
        }
        int[] indices = new int[faces.size() * 3];
        for (int i = 0; i < faces.size(); i++) {
            System.arraycopy(faces.get(i), 0, indices, i * 3, 3);
        }
        return new PickMesh(positions, indices);
    }

    /**
     * A 100,000-node scene: a 2 km field of boxes and spheres from 0.5 to 4 m, a fifth of them dynamic, every 100th
     * interactive (by its mesh when {@code meshes}), seen from 30 m up looking across it.
     */
    static SceneRig scene(boolean meshes) {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 1920, 1080);
        Random random = new Random(42);
        PickMesh sphere = sphere();
        for (int i = 0; i < NODES; i++) {
            double half = 0.25 + random.nextDouble() * 1.75;
            long node = r.box(random.nextDouble() * 2000 - 1000, half, -random.nextDouble() * 2000, half,
                i % 5 == 0 ? Mobility.DYNAMIC : Mobility.STATIC);
            if (i % INTERACTIVE_EVERY == 0) {
                Interactive c = Interactive.create();
                r.picking.add(node, meshes ? c.mesh(sphere) : c);
                r.scene.setScale(node, half, half, half);
                r.scene.setLocalBounds(node, -1, -1, -1, 1, 1, 1);
            }
        }
        double pitch = Math.atan2(30, 400);
        r.view.pose(0, 30, 50, -Math.sin(pitch / 2), 0, 0, Math.cos(pitch / 2)).perspective(Math.toRadians(60), 0.1,
            3000);
        r.frame();
        return r;
    }

    @Test
    void nodesWithoutAnInteractiveComponentAreNeverTested() {
        SceneRig r = scene(true);
        Random random = new Random(7);
        Pointer pointer = new UiInput(1).pointer(0, Pointer.Kind.MOUSE);
        int[] hits = new int[Interaction.MAX_HITS];
        double[] ray = new double[6];
        long tested = 0;
        long reached = 0;
        int picks = 2_000;
        float[] xs = new float[picks];
        float[] ys = new float[picks];
        aims(r, random, xs, ys);
        for (int i = 0; i < picks; i++) {
            pointer.moveTo(xs[i], ys[i]);
            long before = r.picking.narrowTests();
            r.picking.pick(pointer, hits, 0);
            tested += r.picking.narrowTests() - before;
            // what a narrow phase could be asked about at most: the interactive boxes the ray enters
            r.view.ray(pointer.x(), pointer.y(), ray);
            reached += interactiveBoxesOnRay(r, ray);
        }
        // every test was of an interactive node whose box the ray enters (each raycast asks at most twice per node)
        assertThat(tested).isLessThanOrEqualTo(reached * 2L * (long) ScenePicking.SCENE_HITS.get());
        assertThat(tested).isPositive();
        System.out.printf(Locale.ROOT, "scene picking: %.2f narrow tests a pick, %.2f interactive boxes on the ray, "
            + "of %d nodes%n", tested / (double) picks, reached / (double) picks, NODES);
    }

    private static int interactiveBoxesOnRay(SceneRig r, double[] ray) {
        int n = 0;
        for (int i = 0; i < r.scene.slotCount(); i++) {
            // nodes are made in order: slot i is node i, generation 1
            long node = NodeHandle.of(i, 1);
            if (r.picking.has(node)) {
                var b = r.scene.worldBounds();
                double t = com.truesim.common.math.geometry.Intersections.rayAabb(ray[0], ray[1], ray[2], ray[3],
                    ray[4], ray[5], b.get(i, 0), b.get(i, 1), b.get(i, 2), b.get(i, 3), b.get(i, 4), b.get(i, 5));
                if (t <= 3000) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void picking100000NodesStaysWithinItsBudgets() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")));
        for (boolean meshes : new boolean[] {false, true}) {
            SceneRig r = scene(meshes);
            Pointer pointer = new UiInput(1).pointer(0, Pointer.Kind.MOUSE);
            int[] hits = new int[Interaction.MAX_HITS];
            Random random = new Random(3);
            int n = 20_000;
            float[] xs = new float[n];
            float[] ys = new float[n];
            aims(r, random, xs, ys);
            for (int i = 0; i < 20_000; i++) { // warm up
                pointer.moveTo(xs[i % n], ys[i % n]);
                r.picking.pick(pointer, hits, 0);
            }
            long[] times = new long[n];
            int hit = 0;
            for (int i = 0; i < n; i++) {
                pointer.moveTo(xs[i], ys[i]);
                long t0 = System.nanoTime();
                hit += r.picking.pick(pointer, hits, 0) > 0 ? 1 : 0;
                times[i] = System.nanoTime() - t0;
            }
            Arrays.sort(times);
            double median = times[n / 2] / 1000.0;
            double p99 = times[n * 99 / 100] / 1000.0;
            System.out.printf(Locale.ROOT, "scene picking (%s), %d nodes, 1%% interactive: median %.2f us, p99 %.2f "
                + "us, %d%% of pointers hit%n", meshes ? "mesh" : "bounds", NODES, median, p99, hit * 100 / n);
            assertThat(p99).isLessThan(meshes ? 50 : 20);
        }
    }

    /**
     * Pointer positions: half at random below the horizon, half on interactive nodes in view (so the narrow phases
     * run on the candidates the ray reaches).
     */
    static void aims(SceneRig r, Random random, float[] xs, float[] ys) {
        float[] p = new float[3];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = random.nextFloat() * 1920;
            ys[i] = 300 + random.nextFloat() * 780;
            if (i % 2 == 1) {
                for (int tries = 0; tries < 100; tries++) {
                    int k = random.nextInt(NODES / INTERACTIVE_EVERY) * INTERACTIVE_EVERY;
                    var b = r.scene.worldBounds();
                    if (r.view.project((b.get(k, 0) + b.get(k, 3)) / 2, (b.get(k, 1) + b.get(k, 4)) / 2,
                        (b.get(k, 2) + b.get(k, 5)) / 2, p) && r.view.contains(p[0], p[1])) {
                        xs[i] = p[0];
                        ys[i] = p[1];
                        break;
                    }
                }
            }
        }
    }
}
