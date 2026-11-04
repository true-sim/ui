package com.truesim.ui.gizmo;

import com.truesim.common.math.Maths;

/**
 * Vector and quaternion arithmetic in doubles on plain arrays (D37): what gizmos solve drags with, allocation-free.
 * Vectors are three doubles and quaternions four (x, y, z, w) at an offset of an array.
 */
final class GizmoMath {

    static final double EPSILON = 1e-12;

    private GizmoMath() {
    }

    static double dot(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }

    static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** {@code q * (x, y, z)}: component {@code c} (0 x, 1 y, 2 z) of the vector turned by quaternion {@code q}. */
    static double rotate(double[] q, int o, double x, double y, double z, int c) {
        return rotate(q[o], q[o + 1], q[o + 2], q[o + 3], x, y, z, c);
    }

    /** Component {@code c} of {@code (x, y, z)} turned by quaternion {@code (qx, qy, qz, qw)}. */
    static double rotate(double qx, double qy, double qz, double qw, double x, double y, double z, int c) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return switch (c) {
            case 0 -> x + qw * tx + (qy * tz - qz * ty);
            case 1 -> y + qw * ty + (qz * tx - qx * tz);
            default -> z + qw * tz + (qx * ty - qy * tx);
        };
    }

    /** Writes {@code a * b} into {@code out[o..o+3]}; any may be the same array. */
    static void multiply(double ax, double ay, double az, double aw, double bx, double by, double bz, double bw,
        double[] out, int o) {
        double x = aw * bx + ax * bw + ay * bz - az * by;
        double y = aw * by - ax * bz + ay * bw + az * bx;
        double z = aw * bz + ax * by - ay * bx + az * bw;
        double w = aw * bw - ax * bx - ay * by - az * bz;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = w;
    }

    /** Writes the rotation by {@code angle} radians about unit axis {@code (x, y, z)} into {@code out[o..o+3]}. */
    static void axisAngle(double x, double y, double z, double angle, double[] out, int o) {
        double s = Maths.sin(angle / 2);
        out[o] = x * s;
        out[o + 1] = y * s;
        out[o + 2] = z * s;
        out[o + 3] = Maths.cos(angle / 2);
    }

    /** Normalises the quaternion at {@code out[o]}. */
    static void normalize4(double[] out, int o) {
        double l = Math.sqrt(out[o] * out[o] + out[o + 1] * out[o + 1] + out[o + 2] * out[o + 2]
            + out[o + 3] * out[o + 3]);
        if (l > 0) {
            out[o] /= l;
            out[o + 1] /= l;
            out[o + 2] /= l;
            out[o + 3] /= l;
        }
    }

    /**
     * The parameter {@code s} of the point {@code s * a} on the line through the origin along unit {@code a} closest to
     * the ray {@code o + t d}; NaN when they are (nearly) parallel.
     */
    static double closestOnLine(double ax, double ay, double az, double ox, double oy, double oz, double dx, double dy,
        double dz) {
        double b = dot(ax, ay, az, dx, dy, dz);
        double c = dot(dx, dy, dz, dx, dy, dz);
        double denominator = c - b * b; // |a| = 1
        if (Math.abs(denominator) < 1e-9 * c) {
            return Double.NaN;
        }
        // w0 = -o
        double dv = -dot(ax, ay, az, ox, oy, oz);
        double e = -dot(dx, dy, dz, ox, oy, oz);
        return (b * e - c * dv) / denominator;
    }

    /** The ray parameter where {@code o + t d} meets the plane through the origin with normal {@code n}; NaN if parallel. */
    static double rayPlane(double nx, double ny, double nz, double ox, double oy, double oz, double dx, double dy,
        double dz) {
        double denominator = dot(nx, ny, nz, dx, dy, dz);
        if (Math.abs(denominator) < 1e-9 * length(dx, dy, dz)) {
            return Double.NaN;
        }
        return -dot(nx, ny, nz, ox, oy, oz) / denominator;
    }

    /** The squared distance from point {@code p} to the ray {@code o + t d}, {@code t >= 0}. */
    static double pointRaySquared(double px, double py, double pz, double ox, double oy, double oz, double dx,
        double dy, double dz) {
        double wx = px - ox;
        double wy = py - oy;
        double wz = pz - oz;
        double t = Math.max(0, dot(wx, wy, wz, dx, dy, dz) / dot(dx, dy, dz, dx, dy, dz));
        double cx = wx - dx * t;
        double cy = wy - dy * t;
        double cz = wz - dz * t;
        return dot(cx, cy, cz, cx, cy, cz);
    }

    /**
     * The squared distance between the segment {@code p..q} and the ray {@code o + t d}, {@code 0 <= t <= reach}
     * (closest points of two segments, clamped).
     */
    static double segmentRaySquared(double px, double py, double pz, double qx, double qy, double qz, double ox,
        double oy, double oz, double dx, double dy, double dz, double reach) {
        double ux = qx - px;
        double uy = qy - py;
        double uz = qz - pz;
        double vx = dx * reach;
        double vy = dy * reach;
        double vz = dz * reach;
        double wx = px - ox;
        double wy = py - oy;
        double wz = pz - oz;
        double a = dot(ux, uy, uz, ux, uy, uz);
        double b = dot(ux, uy, uz, vx, vy, vz);
        double c = dot(vx, vy, vz, vx, vy, vz);
        double d = dot(ux, uy, uz, wx, wy, wz);
        double e = dot(vx, vy, vz, wx, wy, wz);
        double denominator = a * c - b * b;
        double s;
        double t;
        if (denominator < EPSILON * a * c || denominator <= 0) {
            s = 0;
            t = c > 0 ? clamp(e / c, 0, 1) : 0;
        } else {
            s = clamp((b * e - c * d) / denominator, 0, 1);
            t = c > 0 ? clamp((b * s + e) / c, 0, 1) : 0;
            s = a > 0 ? clamp((b * t - d) / a, 0, 1) : 0;
        }
        double cx = wx + ux * s - vx * t;
        double cy = wy + uy * s - vy * t;
        double cz = wz + uz * s - vz * t;
        return dot(cx, cy, cz, cx, cy, cz);
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : v > max ? max : v;
    }

    /** {@code value} rounded to the nearest multiple of {@code step} (unchanged for a step of 0 or less). */
    static double snap(double value, double step) {
        return step > 0 ? Math.round(value / step) * step : value;
    }
}
