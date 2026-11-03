package com.truesim.ui.controls.overlay;

/**
 * Where an overlay goes next to its anchor (pure arithmetic, no state): on the preferred side at a gap, aligned along
 * that side and shifted, flipped to another side when it would leave the viewport there, then shifted back inside the
 * viewport (kept a margin from its edges). The sides tried, in order: the preferred one, the opposite one, then the
 * two across (end side first); the first where the overlay fits wins; if none fits, the better of the preferred and
 * the opposite side (more room), and the overlay is pushed inside the viewport even if it then covers its anchor.
 */
public final class Placer {

    /** {@code out} index: the overlay's left edge. */
    public static final int X = 0;
    /** {@code out} index: the overlay's top edge. */
    public static final int Y = 1;
    /**
     * {@code out} index: where the anchor's centre is along the side, from the overlay's left (above or below) or top
     * (left or right) edge, clamped into the overlay: where an arrow would point.
     */
    public static final int ARROW = 2;

    private Placer() {
    }

    /**
     * Places a {@code w x h} overlay next to the anchor box {@code ax, ay, aw, ah} within the viewport
     * {@code vx, vy, vw, vh}: {@code gap} between them, {@code shift} along the side (mirrored in right-to-left),
     * {@code margin} kept from the viewport's edges, other sides tried only with {@code flip}. Writes {@link #X},
     * {@link #Y} and {@link #ARROW} into {@code out} (three floats) and returns the side used (TOP, BOTTOM, LEFT or
     * RIGHT).
     */
    public static Placement place(float ax, float ay, float aw, float ah, float w, float h, float vx, float vy,
        float vw, float vh, Placement preferred, PlacementAlign align, float gap, float shift, float margin,
        boolean flip, boolean rtl, float[] out) {
        Placement side = preferred.physical(rtl);
        if (flip && room(side, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin) < 0) {
            Placement opposite = side.opposite();
            Placement across = side.vertical() ? Placement.END.physical(rtl) : Placement.BOTTOM;
            Placement other = across.opposite();
            if (room(opposite, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin) >= 0) {
                side = opposite;
            } else if (room(across, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin) >= 0) {
                side = across;
            } else if (room(other, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin) >= 0) {
                side = other;
            } else if (room(opposite, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin)
                > room(side, ax, ay, aw, ah, w, h, vx, vy, vw, vh, gap, margin)) {
                side = opposite;
            }
        }
        float x;
        float y;
        float s = rtl ? -shift : shift;
        if (side.vertical()) {
            y = side == Placement.TOP ? ay - gap - h : ay + ah + gap;
            x = align(ax, aw, w, align, rtl) + s;
        } else {
            x = side == Placement.LEFT ? ax - gap - w : ax + aw + gap;
            y = align(ay, ah, h, align, false) + shift;
        }
        x = inside(x, w, vx, vw, margin);
        y = inside(y, h, vy, vh, margin);
        out[X] = x;
        out[Y] = y;
        out[ARROW] = side.vertical() ? clamp(ax + aw * 0.5f - x, 0, w) : clamp(ay + ah * 0.5f - y, 0, h);
        return side;
    }

    /** The room left on {@code side} once the overlay is there: negative when it does not fit. */
    static float room(Placement side, float ax, float ay, float aw, float ah, float w, float h, float vx, float vy,
        float vw, float vh, float gap, float margin) {
        return switch (side) {
            case TOP -> ay - gap - (vy + margin) - h;
            case BOTTOM -> vy + vh - margin - (ay + ah + gap) - h;
            case LEFT -> ax - gap - (vx + margin) - w;
            default -> vx + vw - margin - (ax + aw + gap) - w;
        };
    }

    private static float align(float start, float length, float size, PlacementAlign align, boolean rtl) {
        PlacementAlign a = align;
        if (rtl && a != PlacementAlign.CENTER) {
            a = a == PlacementAlign.START ? PlacementAlign.END : PlacementAlign.START;
        }
        return switch (a) {
            case START -> start;
            case CENTER -> start + (length - size) * 0.5f;
            default -> start + length - size;
        };
    }

    /** {@code pos} moved so {@code size} stays within the viewport less its margins; its start edge if too large. */
    static float inside(float pos, float size, float viewStart, float viewLength, float margin) {
        float min = viewStart + margin;
        float max = viewStart + viewLength - margin - size;
        if (pos > max) {
            pos = max;
        }
        return pos < min ? min : pos;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
