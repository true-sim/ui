package com.truesim.ui.gizmo;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * The transforms of a gizmo's nodes at one moment: each node's local transform (what undo restores) and its world
 * transform (what drags start from), ten doubles each: translation x, y, z, rotation x, y, z, w, scale x, y, z.
 * Reused: capturing again allocates nothing once it holds as many nodes.
 */
public final class GizmoTransforms {

    /** Doubles per transform. */
    public static final int STRIDE = 10;

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private long[] nodes = new long[4];
    private double[] local = new double[4 * STRIDE];
    private double[] world = new double[4 * STRIDE];
    private int count;

    /** Holds the transforms of the first {@code n} of {@code selected} in {@code scene} now. */
    public void capture(SceneGraph scene, long[] selected, int n) {
        if (nodes.length < n) {
            grow(n);
        }
        count = 0;
        for (int i = 0; i < n; i++) {
            long node = selected[i];
            if (!scene.contains(node)) {
                continue;
            }
            nodes[count] = node;
            read(scene.localTransforms(), node, local, count * STRIDE);
            read(scene.worldTransforms(), node, world, count * STRIDE);
            count++;
        }
    }

    /** Holds what {@code other} holds. */
    public void copy(GizmoTransforms other) {
        if (nodes.length < other.count) {
            grow(other.count);
        }
        count = other.count;
        System.arraycopy(other.nodes, 0, nodes, 0, count);
        System.arraycopy(other.local, 0, local, 0, count * STRIDE);
        System.arraycopy(other.world, 0, world, 0, count * STRIDE);
    }

    @AllowAllocation("growing with the selection, rarely")
    private void grow(int n) {
        nodes = Arrays.copyOf(nodes, n);
        local = Arrays.copyOf(local, n * STRIDE);
        world = Arrays.copyOf(world, n * STRIDE);
    }

    static void read(TransformArray transforms, long node, double[] out, int o) {
        MemorySegment s = transforms.segment();
        long at = NodeHandle.index(node) * transforms.stride();
        for (int c = 0; c < STRIDE; c++) {
            out[o + c] = s.get(DOUBLE, at + c * 8L);
        }
    }

    /** Takes the nodes' world transforms now as the start of further changes; the held local ones stay (undo). */
    void rebase(SceneGraph scene) {
        for (int i = 0; i < count; i++) {
            if (scene.contains(nodes[i])) {
                read(scene.worldTransforms(), nodes[i], world, i * STRIDE);
            }
        }
    }

    /** Gives every node that still exists its held local transform again. */
    public void restore(SceneGraph scene) {
        for (int i = 0; i < count; i++) {
            if (scene.contains(nodes[i])) {
                int o = i * STRIDE;
                scene.setLocal(nodes[i], local[o], local[o + 1], local[o + 2], local[o + 3], local[o + 4],
                    local[o + 5], local[o + 6], local[o + 7], local[o + 8], local[o + 9]);
            }
        }
    }

    /** How many nodes. */
    public int count() {
        return count;
    }

    /** Node {@code i}. */
    public long node(int i) {
        return nodes[i];
    }

    /** Component {@code c} (0 to 9) of node {@code i}'s local transform. */
    public double local(int i, int c) {
        return local[i * STRIDE + c];
    }

    /** Component {@code c} (0 to 9) of node {@code i}'s world transform. */
    public double world(int i, int c) {
        return world[i * STRIDE + c];
    }

    double[] worldArray() {
        return world;
    }
}
