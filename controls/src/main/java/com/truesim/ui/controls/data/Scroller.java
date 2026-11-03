package com.truesim.ui.controls.data;

/**
 * One axis of a scroll view: a position over content longer than its viewport, scrolled directly (wheel, keys, scroll
 * bars, focus), dragged (touch: past the ends with a rubber band when overscroll is on) and flung (momentum slowed by
 * {@link DataTunables#FRICTION}, springing back from overscroll). Plain numbers in panel units; positions are doubles,
 * so a list of millions of rows scrolls exactly. Nothing allocates.
 */
public final class Scroller {

    /** Drag velocity samples older than this (seconds) count as stopped. */
    static final double STALE = 0.08;

    private double position;
    private double content;
    private double viewport;
    private double velocity;
    private boolean dragging;
    private boolean flinging;
    private double lastMove = -1;
    private long version;

    /** The scroll position: 0 at the start, {@link #range()} at the end (outside while overscrolled). */
    public double position() {
        return position;
    }

    /** How far it scrolls: the content's length beyond the viewport, or 0. */
    public double range() {
        return content > viewport ? content - viewport : 0;
    }

    /** The content's length. */
    public double content() {
        return content;
    }

    /** The viewport's length. */
    public double viewport() {
        return viewport;
    }

    /** Whether the content is longer than the viewport. */
    public boolean overflows() {
        return content > viewport;
    }

    /** Sets the lengths; the position is kept in range (unless dragging or springing back). */
    public void extents(double contentLength, double viewportLength) {
        if (contentLength != content || viewportLength != viewport) {
            content = contentLength;
            viewport = viewportLength;
            if (!dragging && !flinging) {
                set(clamp(position));
            }
            version++;
        }
    }

    /** Scrolls to {@code value}, clamped to the range; stops a fling. Returns whether the position changed. */
    public boolean scrollTo(double value) {
        flinging = false;
        velocity = 0;
        return set(clamp(value));
    }

    /** Scrolls by {@code delta}, clamped; returns whether the position changed. */
    public boolean scrollBy(double delta) {
        return scrollTo((flinging || overscroll() != 0 ? clamp(position) : position) + delta);
    }

    /** Whether it can scroll further in the direction of {@code delta}'s sign. */
    public boolean canScroll(double delta) {
        return delta < 0 ? position > 0 : delta > 0 && position < range();
    }

    /**
     * Scrolls the least so that {@code start..end} (content coordinates) is in view, the start first if it does not
     * fit; returns whether the position changed.
     */
    public boolean reveal(double start, double end) {
        double target = position;
        if (end > target + viewport) {
            target = end - viewport;
        }
        if (start < target) {
            target = start;
        }
        return target != position && scrollTo(target);
    }

    /** A touch (or pen) began dragging the content at time {@code seconds}: any fling stops. */
    public void beginDrag(double seconds) {
        dragging = true;
        flinging = false;
        velocity = 0;
        lastMove = seconds;
    }

    /**
     * The dragging pointer moved the content by {@code delta} (positive scrolls on) at time {@code seconds}: past the
     * ends it moves less the further it is out ({@code bounce}), or stops there.
     */
    public void drag(double delta, double seconds, boolean bounce) {
        double next;
        double out = overscroll();
        if (!bounce) {
            next = clamp(position + delta);
        } else if (out != 0 && Math.signum(delta) == Math.signum(out) || out == 0 && !inRange(position + delta)) {
            // resisting: the further out, the less it follows (rubber band)
            double room = viewport > 0 ? viewport : 1;
            double factor = 0.5 / (1 + Math.abs(out) / room * 4);
            next = position + delta * factor;
        } else {
            next = position + delta;
        }
        double dt = seconds - lastMove;
        double measured = dt > 1e-4 ? (next - position) / dt : velocity;
        velocity = dt > STALE ? measured : velocity * 0.4 + measured * 0.6;
        lastMove = seconds;
        set(next);
    }

    /** The drag ended at time {@code seconds}: a fling continues at the drag's speed, or it springs back. */
    public void endDrag(double seconds) {
        dragging = false;
        if (seconds - lastMove > STALE) {
            velocity = 0; // held still before lifting
        }
        if (Math.abs(velocity) < DataTunables.MIN_FLING_SPEED.get()) {
            velocity = 0;
        }
        flinging = velocity != 0 || overscroll() != 0;
    }

    /** Whether a touch is dragging it. */
    public boolean dragging() {
        return dragging;
    }

    /** Whether it moves by itself (a fling or a spring back). */
    public boolean moving() {
        return flinging;
    }

    /** The fling's velocity in panel units per second. */
    public double velocity() {
        return velocity;
    }

    /** Stops a fling where it is (clamped). */
    public void stop() {
        flinging = false;
        velocity = 0;
        set(clamp(position));
    }

    /**
     * Advances a fling or spring back by {@code dt} seconds: friction slows the fling, overscroll springs back
     * (bounce) or stops it at the end. Returns whether the position changed.
     */
    public boolean step(double dt, boolean bounce) {
        if (!flinging || dt <= 0) {
            return false;
        }
        double before = position;
        double next = position + velocity * dt;
        velocity -= velocity * Math.min(1, DataTunables.FRICTION.get() * dt);
        double out = overscrollOf(next);
        if (out != 0) {
            if (!bounce) {
                next = clamp(next);
                velocity = 0;
            } else {
                // the overscroll pulls back; the fling decays fast against it
                velocity -= velocity * Math.min(1, DataTunables.BOUNCE_STIFFNESS.get() * dt * 2);
                double back = out * Math.min(1, DataTunables.BOUNCE_STIFFNESS.get() * dt);
                next -= back;
                if (Math.abs(overscrollOf(next)) < 0.5 && Math.abs(velocity) < DataTunables.MIN_FLING_SPEED.get()) {
                    next = clamp(next);
                    velocity = 0;
                }
            }
        }
        if (Math.abs(velocity) < DataTunables.MIN_FLING_SPEED.get()) {
            velocity = 0;
            if (overscrollOf(next) == 0) {
                flinging = false;
            }
        }
        set(next);
        return position != before;
    }

    /** How far the position is past an end: negative before the start, positive past the end, else 0. */
    public double overscroll() {
        return overscrollOf(position);
    }

    private double overscrollOf(double p) {
        if (p < 0) {
            return p;
        }
        double r = range();
        return p > r ? p - r : 0;
    }

    private boolean inRange(double p) {
        return p >= 0 && p <= range();
    }

    private double clamp(double p) {
        double r = range();
        return p < 0 ? 0 : (p > r ? r : p);
    }

    private boolean set(double p) {
        if (p == position) {
            return false;
        }
        position = p;
        version++;
        return true;
    }

    /** Grows when the position or the extents change. */
    public long version() {
        return version;
    }
}
