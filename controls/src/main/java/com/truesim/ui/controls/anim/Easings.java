package com.truesim.ui.controls.anim;

import com.truesim.ui.style.value.Easing;

/**
 * The easing curves of transitions and animations: CSS's named timing functions as their cubic Béziers
 * ({@code ease} is {@code cubic-bezier(0.25, 0.1, 0.25, 1)}, ...), evaluated without allocation and with only exactly
 * specified arithmetic, so a curve is the same on every platform.
 */
public final class Easings {

    private static final float[] X1 = {0, 0.25f, 0.42f, 0, 0.42f};
    private static final float[] Y1 = {0, 0.1f, 0, 0, 0};
    private static final float[] X2 = {1, 0.25f, 1, 0.58f, 0.58f};
    private static final float[] Y2 = {1, 1, 1, 1, 1};

    private Easings() {
    }

    /** {@code easing} at time fraction {@code t} (clamped to 0..1): 0 at 0, 1 at 1. */
    public static float ease(Easing easing, float t) {
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        int i = easing.ordinal();
        if (i == 0) {
            return t;
        }
        return cubicBezier(X1[i], Y1[i], X2[i], Y2[i], t);
    }

    /**
     * CSS's {@code cubic-bezier(x1, y1, x2, y2)} at {@code t}: the curve's x solved for {@code t} by Newton's method,
     * falling back to bisection where the slope is flat; its y returned.
     */
    public static float cubicBezier(float x1, float y1, float x2, float y2, float t) {
        float u = t;
        for (int i = 0; i < 8; i++) {
            float x = bezier(x1, x2, u) - t;
            if (Math.abs(x) < 1e-6f) {
                return bezier(y1, y2, u);
            }
            float d = slope(x1, x2, u);
            if (Math.abs(d) < 1e-6f) {
                break;
            }
            u -= x / d;
        }
        float lo = 0;
        float hi = 1;
        u = t;
        for (int i = 0; i < 32; i++) {
            float x = bezier(x1, x2, u);
            if (Math.abs(x - t) < 1e-6f) {
                break;
            }
            if (x < t) {
                lo = u;
            } else {
                hi = u;
            }
            u = (lo + hi) * 0.5f;
        }
        return bezier(y1, y2, u);
    }

    private static float bezier(float p1, float p2, float u) {
        float v = 1 - u;
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
    }

    private static float slope(float p1, float p2, float u) {
        float v = 1 - u;
        return 3 * v * v * p1 + 6 * v * u * (p2 - p1) + 3 * u * u * (1 - p2);
    }

    /** Hermite smooth step: 0 at 0, 1 at 1, flat at both ends. */
    public static float smoothStep(float t) {
        float c = t < 0 ? 0 : (t > 1 ? 1 : t);
        return c * c * (3 - 2 * c);
    }
}
