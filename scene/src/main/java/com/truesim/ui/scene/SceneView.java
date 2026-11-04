package com.truesim.ui.scene;

/**
 * A camera as the UI sees it: where a 3D view is on the screen (its viewport, in UI pixels) and the camera that draws
 * it (position in doubles, orientation, perspective or orthographic projection). The engine sets it every frame from
 * its view (the main view, a camera view's second camera); the UI turns pointers on the viewport into world rays
 * ({@link #ray}) and world points into screen points ({@link #project}) with it, for scene picking, gizmos, scene
 * anchors and camera views.
 *
 * <p>The camera looks along its local -Z axis, with +Y up and +X to the right, as the engine's cameras do. Positions
 * are doubles and every computation subtracts the camera's position first, so a view 10,000 km from the origin is as
 * exact as one at it (D37). Nothing here allocates.
 *
 * <pre>{@code
 * SceneView main = new SceneView().perspective(Math.toRadians(60), 0.1, 10_000).viewport(0, 0, 1920, 1080);
 * main.pose(cx, cy, cz, qx, qy, qz, qw);   // each frame, from the camera node's world transform
 * }</pre>
 */
public final class SceneView {

    private double px;
    private double py;
    private double pz;
    private double qx;
    private double qy;
    private double qz;
    private double qw = 1;
    private boolean orthographic;
    private double fovY = Math.toRadians(60);
    private double tanHalf = Math.tan(fovY / 2);
    private double halfHeight = 1;
    private double near = 0.1;
    private double far = 10_000;
    private float vx;
    private float vy;
    private float vw = 1;
    private float vh = 1;
    private long version;

    /** Places the camera at {@code x, y, z} with rotation {@code qx, qy, qz, qw} (normalised here); returns this. */
    public SceneView pose(double x, double y, double z, double rx, double ry, double rz, double rw) {
        double length = Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        if (!(length > 0)) {
            throw new IllegalArgumentException("not a rotation: " + rx + ", " + ry + ", " + rz + ", " + rw);
        }
        if (x != px || y != py || z != pz || rx / length != qx || ry / length != qy || rz / length != qz
            || rw / length != qw) {
            px = x;
            py = y;
            pz = z;
            qx = rx / length;
            qy = ry / length;
            qz = rz / length;
            qw = rw / length;
            version++;
        }
        return this;
    }

    /** A perspective projection with vertical field of view {@code fovY} (radians) and clip distances; returns this. */
    public SceneView perspective(double verticalFov, double nearDistance, double farDistance) {
        if (!(verticalFov > 0 && verticalFov < Math.PI) || !(nearDistance > 0) || !(farDistance > nearDistance)) {
            throw new IllegalArgumentException("not a perspective: fov " + verticalFov + ", " + nearDistance + " to "
                + farDistance);
        }
        orthographic = false;
        fovY = verticalFov;
        tanHalf = Math.tan(verticalFov / 2);
        near = nearDistance;
        far = farDistance;
        version++;
        return this;
    }

    /** An orthographic projection showing {@code height} world units top to bottom; returns this. */
    public SceneView orthographic(double height, double nearDistance, double farDistance) {
        if (!(height > 0) || !(farDistance > nearDistance)) {
            throw new IllegalArgumentException("not an orthographic view: height " + height);
        }
        orthographic = true;
        halfHeight = height / 2;
        near = nearDistance;
        far = farDistance;
        version++;
        return this;
    }

    /** Where the view is on the UI, in UI pixels; returns this. */
    public SceneView viewport(float x, float y, float width, float height) {
        if (!(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException("an empty viewport: " + width + " x " + height);
        }
        if (x != vx || y != vy || width != vw || height != vh) {
            vx = x;
            vy = y;
            vw = width;
            vh = height;
            version++;
        }
        return this;
    }

    /** Grows whenever the pose, projection or viewport changes: what projects through it must be updated. */
    public long version() {
        return version;
    }

    // ---------------------------------------------------------------- rays and projection

    /**
     * The world ray through UI point {@code x, y}: its origin (the camera, or the point on the camera plane for an
     * orthographic view) and unit direction into {@code out[0..5]}.
     */
    public void ray(float x, float y, double[] out) {
        double nx = (x - vx) / vw * 2 - 1;
        double ny = 1 - (y - vy) / vh * 2;
        double aspect = vw / (double) vh;
        if (orthographic) {
            double cx = nx * halfHeight * aspect;
            double cy = ny * halfHeight;
            out[0] = px + rotateX(cx, cy, 0);
            out[1] = py + rotateY(cx, cy, 0);
            out[2] = pz + rotateZ(cx, cy, 0);
            out[3] = rotateX(0, 0, -1);
            out[4] = rotateY(0, 0, -1);
            out[5] = rotateZ(0, 0, -1);
            return;
        }
        double cx = nx * tanHalf * aspect;
        double cy = ny * tanHalf;
        double length = Math.sqrt(cx * cx + cy * cy + 1);
        out[0] = px;
        out[1] = py;
        out[2] = pz;
        out[3] = rotateX(cx, cy, -1) / length;
        out[4] = rotateY(cx, cy, -1) / length;
        out[5] = rotateZ(cx, cy, -1) / length;
    }

    /**
     * Projects world point {@code x, y, z} onto the UI: {@code out[0], out[1]} in UI pixels and {@code out[2]} its depth
     * (distance along the view direction). Returns false (and leaves a projection of no use) when the point is
     * behind the near plane.
     */
    public boolean project(double x, double y, double z, float[] out) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        double cx = inverseX(dx, dy, dz);
        double cy = inverseY(dx, dy, dz);
        double depth = -inverseZ(dx, dy, dz);
        out[2] = (float) depth;
        double aspect = vw / (double) vh;
        double nx;
        double ny;
        if (orthographic) {
            nx = cx / (halfHeight * aspect);
            ny = cy / halfHeight;
        } else {
            if (depth < near) {
                return false;
            }
            nx = cx / (depth * tanHalf * aspect);
            ny = cy / (depth * tanHalf);
        }
        out[0] = (float) (vx + (nx + 1) / 2 * vw);
        out[1] = (float) (vy + (1 - ny) / 2 * vh);
        return true;
    }

    /** The depth of world point {@code x, y, z}: its distance along the view direction (negative behind the camera). */
    public double depth(double x, double y, double z) {
        return -inverseZ(x - px, y - py, z - pz);
    }

    /**
     * World units one UI pixel covers at {@code depth} along the view direction: what keeps a gizmo the same size on
     * the screen at any distance.
     */
    public double unitsPerPixel(double depth) {
        return orthographic ? 2 * halfHeight / vh : 2 * Math.max(depth, near) * tanHalf / vh;
    }

    // ---------------------------------------------------------------- the camera's axes

    /** Component {@code axis} (0 x, 1 y, 2 z) of the camera's right axis in world space. */
    public double right(int axis) {
        return axis(1, 0, 0, axis);
    }

    /** Component {@code axis} of the camera's up axis in world space. */
    public double up(int axis) {
        return axis(0, 1, 0, axis);
    }

    /** Component {@code axis} of the view direction (the camera's -Z) in world space. */
    public double forward(int axis) {
        return axis(0, 0, -1, axis);
    }

    private double axis(double x, double y, double z, int axis) {
        return switch (axis) {
            case 0 -> rotateX(x, y, z);
            case 1 -> rotateY(x, y, z);
            default -> rotateZ(x, y, z);
        };
    }

    // ---------------------------------------------------------------- properties

    /** The camera's position x. */
    public double x() {
        return px;
    }

    /** The camera's position y. */
    public double y() {
        return py;
    }

    /** The camera's position z. */
    public double z() {
        return pz;
    }

    /** The camera's rotation x. */
    public double rotationX() {
        return qx;
    }

    /** The camera's rotation y. */
    public double rotationY() {
        return qy;
    }

    /** The camera's rotation z. */
    public double rotationZ() {
        return qz;
    }

    /** The camera's rotation w. */
    public double rotationW() {
        return qw;
    }

    /** Whether the projection is orthographic. */
    public boolean isOrthographic() {
        return orthographic;
    }

    /** The vertical field of view in radians (perspective). */
    public double fovY() {
        return fovY;
    }

    /** The near clip distance. */
    public double near() {
        return near;
    }

    /** The far clip distance: rays are tested this far. */
    public double far() {
        return far;
    }

    /** The viewport's left edge in UI pixels. */
    public float viewportX() {
        return vx;
    }

    /** The viewport's top edge in UI pixels. */
    public float viewportY() {
        return vy;
    }

    /** The viewport's width in UI pixels. */
    public float viewportWidth() {
        return vw;
    }

    /** The viewport's height in UI pixels. */
    public float viewportHeight() {
        return vh;
    }

    /** Whether UI point {@code x, y} is on the viewport. */
    public boolean contains(float x, float y) {
        return x >= vx && y >= vy && x < vx + vw && y < vy + vh;
    }

    // ---------------------------------------------------------------- rotation by the camera's quaternion

    private double rotateX(double x, double y, double z) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return x + qw * tx + (qy * tz - qz * ty);
    }

    private double rotateY(double x, double y, double z) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return y + qw * ty + (qz * tx - qx * tz);
    }

    private double rotateZ(double x, double y, double z) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return z + qw * tz + (qx * ty - qy * tx);
    }

    private double inverseX(double x, double y, double z) {
        double tx = 2 * (-qy * z + qz * y);
        double ty = 2 * (-qz * x + qx * z);
        double tz = 2 * (-qx * y + qy * x);
        return x + qw * tx + (-qy * tz + qz * ty);
    }

    private double inverseY(double x, double y, double z) {
        double tx = 2 * (-qy * z + qz * y);
        double ty = 2 * (-qz * x + qx * z);
        double tz = 2 * (-qx * y + qy * x);
        return y + qw * ty + (-qz * tx + qx * tz);
    }

    private double inverseZ(double x, double y, double z) {
        double tx = 2 * (-qy * z + qz * y);
        double ty = 2 * (-qz * x + qx * z);
        double tz = 2 * (-qx * y + qy * x);
        return z + qw * tz + (-qx * ty + qy * tx);
    }
}
