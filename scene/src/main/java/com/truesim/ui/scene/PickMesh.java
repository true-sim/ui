package com.truesim.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.math.geometry.Intersections;
import com.truesim.common.math.spatial.Bvh;
import com.truesim.common.math.spatial.RayHit;

/**
 * A mesh's triangles for precise picking (design 4.7, the MESH narrow phase): positions and indices in the mesh's
 * own space, and a triangle BVH built lazily, on the first ray that reaches the mesh's bounds, so meshes nobody points
 * at cost no memory beyond their data. Keep one per mesh with the mesh's data (the engine keeps it with the mesh
 * asset), shared by every node that shows the mesh: {@link Interactive#mesh}.
 *
 * <p>Built from the CPU copy of the mesh, which the engine keeps for interactive meshes only. Skinned and displaced
 * meshes have no current CPU pose: pick them by bounds or by a custom {@link Interactive.NarrowPhase}.
 *
 * <p>Raycasts allocate nothing once the BVH is built; one thread at a time.
 */
public final class PickMesh {

    private final float[] positions;
    private final int[] indices;
    private final int triangles;
    private Bvh bvh;
    private final RayHit hit = new RayHit();
    private final Bvh.RayTest triangle = this::triangle;

    /**
     * A mesh of the triangles {@code indices} (three per triangle) over {@code positions} (x, y, z per vertex), in the
     * mesh's space. The arrays are kept, not copied: do not change them while the mesh is in use.
     */
    @AllowAllocation("meshes are made when loaded")
    public PickMesh(float[] positions, int[] indices) {
        if (positions.length % 3 != 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("positions and indices come in threes");
        }
        int vertices = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertices) {
                throw new IllegalArgumentException("index " + index + " of " + vertices + " vertices");
            }
        }
        this.positions = positions;
        this.indices = indices;
        this.triangles = indices.length / 3;
    }

    /** How many triangles. */
    public int triangleCount() {
        return triangles;
    }

    /** Whether the BVH was built (a ray reached the mesh). */
    public boolean built() {
        return bvh != null;
    }

    /**
     * Where the ray from {@code ox, oy, oz} along {@code dx, dy, dz} (mesh space) first hits a triangle from either
     * side, in units of the direction's length; {@link Double#POSITIVE_INFINITY} for a miss or beyond {@code max}.
     * Builds the BVH on first use.
     */
    public double raycast(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        if (triangles == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (bvh == null) {
            build();
        }
        return bvh.raycast(ox, oy, oz, dx, dy, dz, max, triangle, hit) ? hit.distance() : Double.POSITIVE_INFINITY;
    }

    @AllowAllocation("the BVH, once per mesh")
    private void build() {
        AabbArray boxes = AabbArray.onHeap(triangles);
        for (int t = 0; t < triangles; t++) {
            int a = indices[t * 3] * 3;
            int b = indices[t * 3 + 1] * 3;
            int c = indices[t * 3 + 2] * 3;
            boxes.set(t,
                min(positions[a], positions[b], positions[c]),
                min(positions[a + 1], positions[b + 1], positions[c + 1]),
                min(positions[a + 2], positions[b + 2], positions[c + 2]),
                max(positions[a], positions[b], positions[c]),
                max(positions[a + 1], positions[b + 1], positions[c + 1]),
                max(positions[a + 2], positions[b + 2], positions[c + 2]));
        }
        bvh = Bvh.build(boxes);
    }

    private double triangle(int t, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int a = indices[t * 3] * 3;
        int b = indices[t * 3 + 1] * 3;
        int c = indices[t * 3 + 2] * 3;
        return Intersections.rayTriangle(ox, oy, oz, dx, dy, dz,
            positions[a], positions[a + 1], positions[a + 2],
            positions[b], positions[b + 1], positions[b + 2],
            positions[c], positions[c + 1], positions[c + 2]);
    }

    private static double min(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }
}
