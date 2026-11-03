package com.truesim.ui.controls.anim;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Easing;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Transition;
import java.util.Arrays;
import java.util.List;

/**
 * The UI's tween and transition engine (design 4.8), driven by the UI clock once a frame, allocation-free.
 *
 * <p><b>Channels.</b> Every animation runs on a channel: an element's visual property ({@link #OPACITY},
 * {@link #SCALE}, {@link #TRANSLATE}, {@link #BACKGROUND}, {@link #COLOR}, {@link #BORDER_COLOR}, {@link #WIDTH},
 * {@link #HEIGHT}, {@link #OFFSETS}, {@link #ANCHORS}: values written into {@link Visuals}), or a channel of a
 * control's own ({@link Animated}, a key and a channel number: a carousel's page position, a spinner's phase). A
 * channel runs one animation: starting another replaces it, from the value showing now. Lemur's fast-forward: an
 * animation asked to go back to where the running one came from reverses in place, along the same curve, so an
 * interrupted open closes smoothly and takes as long as it had run.
 *
 * <p><b>Style transitions.</b> {@link #restyled} compares an element's old and new resolved styles: each key with a
 * {@code transition} for it (or {@code all}) and a changed value animates from the value showing to the new one, then
 * hands back to the style. Colours (box and gradient backgrounds, text, borders), opacity, scale, pixel widths and
 * heights and offsets transition; other values change at once.
 *
 * <p><b>Time.</b> {@link #advance} takes the UI clock's nanoseconds; {@code 0} or less (a frame without a clock)
 * finishes everything at once, as does {@link #SPEED} 0 (reduced motion).
 */
public final class Animator {

    /** How fast UI animations play: 1 normal, 0 off (every animation ends at once: reduced motion). */
    public static final Tunable SPEED = Tunable.of("ui.animation.speed", 1, 0, 4,
        "How fast UI animations play; 0 turns them off (reduced motion)");

    public static final int OPACITY = 0;
    public static final int SCALE = 1;
    /** Two values: x and y. */
    public static final int TRANSLATE = 2;
    /** Eight values: two colours (linear r, g, b, a), a box's fill and a gradient's end. */
    public static final int BACKGROUND = 3;
    /** Four values: a colour. */
    public static final int COLOR = 4;
    /** Four values: a colour. */
    public static final int BORDER_COLOR = 5;
    /** One value, in panel units. */
    public static final int WIDTH = 6;
    /** One value, in panel units. */
    public static final int HEIGHT = 7;
    /** Four values: left, top, right, bottom. */
    public static final int OFFSETS = 8;
    /** Four values: left, top, right, bottom. */
    public static final int ANCHORS = 9;
    private static final int ELEMENT_CHANNELS = 10;

    private static final int[] COMPONENTS = {1, 1, 2, 8, 4, 4, 1, 1, 4, 4};
    private static final int[] VISUAL_BITS = {Visuals.OPACITY, Visuals.SCALE, Visuals.TRANSLATE, Visuals.BACKGROUND,
        Visuals.COLOR, Visuals.BORDER_COLOR, Visuals.WIDTH, Visuals.HEIGHT, Visuals.OFFSETS, Visuals.ANCHORS};
    private static final int MAX = 8;

    /** A control's own animated value: told each frame what its channel shows now. */
    @FunctionalInterface
    public interface Animated {
        /** Channel {@code channel} of {@code key} shows {@code value} now. */
        void animate(int key, int channel, float value);
    }

    /** Told when an animation ends. */
    @FunctionalInterface
    public interface Listener {
        /**
         * The animation of {@code key} (an element's slot, or a control's key) on {@code channel} ended: it reached
         * its target ({@code completed}), or was cancelled or replaced by one going elsewhere.
         */
        void finished(int key, int channel, boolean completed);
    }

    private final UiStore store;
    private final Visuals visuals;

    private int count;
    private Animated[] target = new Animated[16]; // null: an element's channel
    private int[] key = new int[16];
    private int[] gen = new int[16];
    private int[] channel = new int[16];
    private float[] from = new float[16 * MAX];
    private float[] to = new float[16 * MAX];
    private float[] progress = new float[16];
    private byte[] direction = new byte[16];
    private float[] duration = new float[16];
    private float[] delay = new float[16];
    private Easing[] easing = new Easing[16];
    private Listener[] listener = new Listener[16];
    private boolean[] clearAtEnd = new boolean[16];

    private final float[] start = new float[MAX];
    private final float[] end = new float[MAX];
    private final float[] value = new float[MAX];
    private long last = -1;
    private int finishedCount;
    private Listener[] finishedListener = new Listener[8];
    private int[] finishedKey = new int[8];
    private int[] finishedChannel = new int[8];
    private boolean[] finishedCompleted = new boolean[8];
    private long frames;

    /** The animator of {@code store}'s elements, writing into {@code visuals}. */
    @AllowAllocation("one per UI")
    public Animator(UiStore store, Visuals visuals) {
        this.store = store;
        this.visuals = visuals;
    }

    /** The overrides element channels write into. */
    public Visuals visuals() {
        return visuals;
    }

    /** Animations running now. */
    public int running() {
        return count;
    }

    /** Frames {@link #advance} ran. */
    public long frames() {
        return frames;
    }

    // ================================================================ starting

    /** Scratch for {@link #start}: the values to start from (when {@code hasFrom}). */
    float[] startValues() {
        return start;
    }

    /**
     * Animates element {@code slot}'s scalar channel ({@link #OPACITY}, {@link #SCALE}, {@link #WIDTH},
     * {@link #HEIGHT}) from {@code fromValue} (NaN: the value showing now) to {@code toValue} over {@code seconds},
     * keeping the end value ({@code keep}) or handing back to the style.
     */
    public void animate(int slot, int ch, float fromValue, float toValue, float seconds, Easing curve, boolean keep,
        Listener done) {
        checkElementChannel(ch, 1);
        boolean hasFrom = fromValue == fromValue;
        start[0] = fromValue;
        end[0] = toValue;
        begin(null, slot, ch, 1, hasFrom, seconds, 0, curve, !keep, done);
    }

    /** Animates element {@code slot}'s drawn offset from {@code fromX, fromY} (NaN: the current) to {@code x, y}. */
    public void translate(int slot, float fromX, float fromY, float x, float y, float seconds, Easing curve,
        boolean keep, Listener done) {
        start[0] = fromX;
        start[1] = fromY;
        end[0] = x;
        end[1] = y;
        begin(null, slot, TRANSLATE, 2, fromX == fromX, seconds, 0, curve, !keep, done);
    }

    /**
     * Animates element {@code slot}'s colour channel ({@link #BACKGROUND}: {@code a} the fill or gradient start,
     * {@code b} its end; {@link #COLOR}, {@link #BORDER_COLOR}: {@code a} only) between packed colours.
     */
    public void color(int slot, int ch, long fromA, long fromB, long toA, long toB, float seconds, Easing curve,
        boolean keep, Listener done) {
        if (ch != BACKGROUND && ch != COLOR && ch != BORDER_COLOR) {
            throw new IllegalArgumentException("not a colour channel: " + ch);
        }
        int n = COMPONENTS[ch];
        unpack(fromA, start, 0);
        unpack(toA, end, 0);
        if (n == 8) {
            unpack(fromB, start, 4);
            unpack(toB, end, 4);
        }
        begin(null, slot, ch, n, true, seconds, 0, curve, !keep, done);
    }

    /** Animates element {@code slot}'s {@link #OFFSETS} or {@link #ANCHORS} from {@code a} to {@code b}. */
    public void edges(int slot, int ch, Insets a, Insets b, float seconds, Easing curve, boolean keep, Listener done) {
        checkElementChannel(ch, 4);
        edges(a, start);
        edges(b, end);
        begin(null, slot, ch, 4, true, seconds, 0, curve, !keep, done);
    }

    /**
     * Animates channel {@code ch} of {@code key} of a control's own {@code animated} value from {@code fromValue}
     * (NaN: the value showing now, or {@code toValue} if none runs) to {@code toValue}.
     */
    public void animate(Animated animated, int k, int ch, float fromValue, float toValue, float seconds, Easing curve,
        Listener done) {
        if (animated == null) {
            throw new IllegalArgumentException("no animated value");
        }
        start[0] = fromValue;
        end[0] = toValue;
        begin(animated, k, ch, 1, fromValue == fromValue, seconds, 0, curve, false, done);
    }

    private static void checkElementChannel(int ch, int components) {
        if (ch < 0 || ch >= ELEMENT_CHANNELS || COMPONENTS[ch] != components) {
            throw new IllegalArgumentException("channel " + ch + " does not take " + components + " values");
        }
    }

    private void begin(Animated animated, int k, int ch, int n, boolean hasFrom, float seconds, float wait,
        Easing curve, boolean clear, Listener done) {
        int i = find(animated, k, ch);
        if (i >= 0) {
            boolean toEnd = direction[i] > 0;
            int o = i * MAX;
            if (same(end, toEnd ? to : from, o, n)) {
                listener[i] = done; // already going there
                clearAtEnd[i] = clear;
                return;
            }
            if (same(end, toEnd ? from : to, o, n) && delay[i] <= 0) {
                direction[i] = (byte) -direction[i]; // fast-forward: back along the same curve
                listener[i] = done;
                clearAtEnd[i] = clear;
                return;
            }
            current(i, start); // from what shows now
            hasFrom = true;
            Listener old = listener[i];
            listener[i] = null;
            if (old != null) {
                old.finished(k, ch, false);
            }
            i = find(animated, k, ch); // the listener may have changed things
        }
        if (!hasFrom) {
            showing(animated, k, ch, n, start);
        }
        if (i < 0) {
            i = add();
        }
        target[i] = animated;
        key[i] = k;
        gen[i] = animated == null ? store.generation(k) : 0;
        channel[i] = ch;
        int o = i * MAX;
        System.arraycopy(start, 0, from, o, n);
        System.arraycopy(end, 0, to, o, n);
        progress[i] = 0;
        direction[i] = 1;
        float speed = SPEED.get();
        duration[i] = speed <= 0 ? 0 : seconds / speed;
        delay[i] = speed <= 0 ? 0 : wait / speed;
        easing[i] = curve == null ? Easing.LINEAR : curve;
        listener[i] = done;
        clearAtEnd[i] = clear;
        if (duration[i] <= 0 && delay[i] <= 0) {
            progress[i] = 1;
            write(i, 1);
            finish(i, true);
            flushFinished();
            return;
        }
        write(i, Easings.ease(easing[i], 0));
    }

    private static boolean same(float[] values, float[] array, int o, int n) {
        for (int c = 0; c < n; c++) {
            if (values[c] != array[o + c]) {
                return false;
            }
        }
        return true;
    }

    /** What channel {@code ch} of {@code k} shows now, without an animation running on it. */
    private void showing(Animated animated, int k, int ch, int n, float[] out) {
        if (animated != null) {
            out[0] = end[0];
            return;
        }
        switch (ch) {
            case OPACITY -> out[0] = visuals.opacity(k);
            case SCALE -> out[0] = visuals.scale(k);
            case TRANSLATE -> {
                out[0] = visuals.translateX(k);
                out[1] = visuals.translateY(k);
            }
            default -> System.arraycopy(end, 0, out, 0, n); // sizes and edges: callers give their start
        }
    }

    @AllowAllocation("growing, rarely: more animations at once than ever before")
    private int add() {
        if (count == key.length) {
            int n = count * 2;
            target = Arrays.copyOf(target, n);
            key = Arrays.copyOf(key, n);
            gen = Arrays.copyOf(gen, n);
            channel = Arrays.copyOf(channel, n);
            from = Arrays.copyOf(from, n * MAX);
            to = Arrays.copyOf(to, n * MAX);
            progress = Arrays.copyOf(progress, n);
            direction = Arrays.copyOf(direction, n);
            duration = Arrays.copyOf(duration, n);
            delay = Arrays.copyOf(delay, n);
            easing = Arrays.copyOf(easing, n);
            listener = Arrays.copyOf(listener, n);
            clearAtEnd = Arrays.copyOf(clearAtEnd, n);
        }
        return count++;
    }

    private int find(Animated animated, int k, int ch) {
        for (int i = 0; i < count; i++) {
            if (key[i] == k && channel[i] == ch && target[i] == animated
                && (animated != null || gen[i] == store.generation(k))) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================ queries and control

    /** Whether element {@code slot}'s channel {@code ch} animates now. */
    public boolean running(int slot, int ch) {
        return find(null, slot, ch) >= 0;
    }

    /** Whether channel {@code ch} of {@code animated}'s {@code k} animates now. */
    public boolean running(Animated animated, int k, int ch) {
        return find(animated, k, ch) >= 0;
    }

    /** Whether the running animation of element {@code slot}'s channel {@code ch} goes back (it was reversed). */
    public boolean reversed(int slot, int ch) {
        int i = find(null, slot, ch);
        return i >= 0 && direction[i] < 0;
    }

    /** The time fraction (0 to 1, before easing) of the running animation of element {@code slot}'s {@code ch}. */
    public float progress(int slot, int ch) {
        int i = find(null, slot, ch);
        return i < 0 ? 1 : progress[i];
    }

    /** Stops element {@code slot}'s animation on {@code ch} where it is (an override keeps its value). */
    public void cancel(int slot, int ch) {
        stop(find(null, slot, ch), false);
    }

    /** Stops the animation of {@code animated}'s {@code k} on {@code ch} where it is. */
    public void cancel(Animated animated, int k, int ch) {
        stop(find(animated, k, ch), false);
    }

    /** Ends element {@code slot}'s animation on {@code ch} at its target now. */
    public void finish(int slot, int ch) {
        stop(find(null, slot, ch), true);
    }

    /** Ends the animation of {@code animated}'s {@code k} on {@code ch} at its target now. */
    public void finish(Animated animated, int k, int ch) {
        stop(find(animated, k, ch), true);
    }

    private void stop(int i, boolean complete) {
        if (i < 0) {
            return;
        }
        if (complete) {
            progress[i] = direction[i] > 0 ? 1 : 0;
            write(i, Easings.ease(easing[i], progress[i]));
        }
        finish(i, complete);
        flushFinished();
    }

    // ================================================================ the frame

    /**
     * Advances every animation to {@code nanos} on the UI clock and writes the values showing now; ended animations
     * tell their listeners (after all were written, so a listener may start new ones).
     */
    public void advance(long nanos) {
        frames++;
        float dt;
        if (nanos <= 0 || SPEED.get() <= 0) {
            dt = Float.POSITIVE_INFINITY;
        } else {
            dt = last < 0 ? 0 : (nanos - last) * 1e-9f;
            last = nanos;
        }
        for (int i = 0; i < count; i++) {
            if (target[i] == null && (!store.alive(key[i]) || store.generation(key[i]) != gen[i])) {
                finish(i--, false); // the element is gone
                continue;
            }
            float t = dt;
            if (delay[i] > 0) {
                float used = Math.min(delay[i], t);
                delay[i] -= used;
                t -= used;
                if (delay[i] > 0) {
                    continue;
                }
            }
            float p = progress[i];
            float step = duration[i] <= 0 ? 1 : t / duration[i];
            p = direction[i] > 0 ? Math.min(1, p + step) : Math.max(0, p - step);
            progress[i] = p;
            write(i, Easings.ease(easing[i], p));
            if (direction[i] > 0 ? p >= 1 : p <= 0) {
                finish(i--, true);
            }
        }
        flushFinished();
    }

    /** The values of animation {@code i} at eased fraction {@code e} into {@link #value}. */
    private void current(int i, float[] out) {
        float e = Easings.ease(easing[i], progress[i]);
        int o = i * MAX;
        int n = COMPONENTS.length > channel[i] && target[i] == null ? COMPONENTS[channel[i]] : 1;
        for (int c = 0; c < n; c++) {
            out[c] = from[o + c] + (to[o + c] - from[o + c]) * e;
        }
    }

    private void write(int i, float e) {
        int o = i * MAX;
        int ch = channel[i];
        int k = key[i];
        if (target[i] != null) {
            target[i].animate(k, ch, from[o] + (to[o] - from[o]) * e);
            return;
        }
        int n = COMPONENTS[ch];
        for (int c = 0; c < n; c++) {
            value[c] = from[o + c] + (to[o + c] - from[o + c]) * e;
        }
        switch (ch) {
            case OPACITY -> visuals.opacity(k, value[0]);
            case SCALE -> visuals.scale(k, value[0]);
            case TRANSLATE -> visuals.translate(k, value[0], value[1]);
            case BACKGROUND -> visuals.background(k, pack(value, 0), pack(value, 4));
            case COLOR -> visuals.color(k, pack(value, 0));
            case BORDER_COLOR -> visuals.borderColor(k, pack(value, 0));
            case WIDTH -> visuals.width(k, value[0], Unit.PX);
            case HEIGHT -> visuals.height(k, value[0], Unit.PX);
            case OFFSETS -> visuals.offsets(k, value[0], value[1], value[2], value[3]);
            default -> visuals.anchors(k, value[0], value[1], value[2], value[3]);
        }
    }

    /** Removes animation {@code i}, queueing its listener; an element channel handing back clears its override. */
    private void finish(int i, boolean completed) {
        Listener l = listener[i];
        int k = key[i];
        int ch = channel[i];
        if (target[i] == null && clearAtEnd[i] && store.alive(k) && store.generation(k) == gen[i]) {
            visuals.clear(k, VISUAL_BITS[ch]);
        }
        int last = count - 1;
        if (i != last) {
            target[i] = target[last];
            key[i] = key[last];
            gen[i] = gen[last];
            channel[i] = channel[last];
            System.arraycopy(from, last * MAX, from, i * MAX, MAX);
            System.arraycopy(to, last * MAX, to, i * MAX, MAX);
            progress[i] = progress[last];
            direction[i] = direction[last];
            duration[i] = duration[last];
            delay[i] = delay[last];
            easing[i] = easing[last];
            listener[i] = listener[last];
            clearAtEnd[i] = clearAtEnd[last];
        }
        target[last] = null;
        listener[last] = null;
        easing[last] = null;
        count = last;
        if (l != null) {
            queueFinished(l, k, ch, completed);
        }
    }

    @AllowAllocation("growing, rarely")
    private void queueFinished(Listener l, int k, int ch, boolean completed) {
        if (finishedCount == finishedListener.length) {
            int n = finishedCount * 2;
            finishedListener = Arrays.copyOf(finishedListener, n);
            finishedKey = Arrays.copyOf(finishedKey, n);
            finishedChannel = Arrays.copyOf(finishedChannel, n);
            finishedCompleted = Arrays.copyOf(finishedCompleted, n);
        }
        finishedListener[finishedCount] = l;
        finishedKey[finishedCount] = k;
        finishedChannel[finishedCount] = ch;
        finishedCompleted[finishedCount] = completed;
        finishedCount++;
    }

    private void flushFinished() {
        for (int f = 0; f < finishedCount; f++) {
            Listener l = finishedListener[f];
            finishedListener[f] = null;
            l.finished(finishedKey[f], finishedChannel[f], finishedCompleted[f]);
        }
        finishedCount = 0;
    }

    // ================================================================ style transitions

    /**
     * {@code slot}'s resolved style changed from {@code before} (null: its first style) to {@code after}: starts a
     * transition for every changed key {@code after} has one for, and stops those it no longer has (the new value
     * shows at once). The UI frame calls this when it applies a new style.
     */
    public void restyled(int slot, ResolvedStyle before, ResolvedStyle after) {
        if (before == null || before == after) {
            return;
        }
        List<Transition> transitions = after.get(Styles.TRANSITION);
        float o0 = before.getFloat(Styles.OPACITY);
        float o1 = after.getFloat(Styles.OPACITY);
        if (o0 != o1) {
            scalar(slot, OPACITY, find(transitions, "opacity"), o0, o1);
        }
        float s0 = before.getFloat(Styles.SCALE);
        float s1 = after.getFloat(Styles.SCALE);
        if (s0 != s1) {
            scalar(slot, SCALE, find(transitions, "scale"), s0, s1);
        }
        Paint p0 = before.get(Styles.BACKGROUND);
        Paint p1 = after.get(Styles.BACKGROUND);
        if (!p0.equals(p1)) {
            background(slot, find(transitions, "background"), p0, p1);
        }
        long c0 = before.rgba(Styles.COLOR);
        long c1 = after.rgba(Styles.COLOR);
        if (c0 != c1) {
            colour(slot, COLOR, find(transitions, "color"), c0, c1);
        }
        Border b0 = before.get(Styles.BORDER);
        Border b1 = after.get(Styles.BORDER);
        if (!b0.color().equals(b1.color())) {
            colour(slot, BORDER_COLOR, find(transitions, "border"), b0.color().rgba(), b1.color().rgba());
        }
        length(slot, WIDTH, find(transitions, "width", "size"), before.get(Styles.WIDTH), after.get(Styles.WIDTH));
        length(slot, HEIGHT, find(transitions, "height", "size"), before.get(Styles.HEIGHT), after.get(Styles.HEIGHT));
        Insets e0 = before.get(Styles.OFFSETS);
        Insets e1 = after.get(Styles.OFFSETS);
        if (!e0.equals(e1)) {
            Transition t = find(transitions, "offsets");
            if (t == null || t.duration() <= 0 && t.delay() <= 0) {
                stopTransition(slot, OFFSETS);
            } else {
                edges(e0, start);
                edges(e1, end);
                begin(null, slot, OFFSETS, 4, true, t.duration(), t.delay(), t.easing(), true, null);
            }
        }
    }

    private void scalar(int slot, int ch, Transition t, float a, float b) {
        if (t == null || t.duration() <= 0 && t.delay() <= 0) {
            stopTransition(slot, ch);
            return;
        }
        start[0] = a;
        end[0] = b;
        begin(null, slot, ch, 1, true, t.duration(), t.delay(), t.easing(), true, null);
    }

    private void background(int slot, Transition t, Paint p0, Paint p1) {
        boolean boxes = p0 instanceof Paint.Box && p1 instanceof Paint.Box;
        boolean gradients = p0 instanceof Paint.LinearGradient && p1 instanceof Paint.LinearGradient;
        if (t == null || t.duration() <= 0 && t.delay() <= 0 || !boxes && !gradients) {
            stopTransition(slot, BACKGROUND);
            return;
        }
        if (boxes) {
            long a = ((Paint.Box) p0).color().rgba();
            long b = ((Paint.Box) p1).color().rgba();
            unpack(a, start, 0);
            unpack(a, start, 4);
            unpack(b, end, 0);
            unpack(b, end, 4);
        } else {
            Paint.LinearGradient g0 = (Paint.LinearGradient) p0;
            Paint.LinearGradient g1 = (Paint.LinearGradient) p1;
            unpack(g0.from().rgba(), start, 0);
            unpack(g0.to().rgba(), start, 4);
            unpack(g1.from().rgba(), end, 0);
            unpack(g1.to().rgba(), end, 4);
        }
        begin(null, slot, BACKGROUND, 8, true, t.duration(), t.delay(), t.easing(), true, null);
    }

    private void colour(int slot, int ch, Transition t, long a, long b) {
        if (t == null || t.duration() <= 0 && t.delay() <= 0) {
            stopTransition(slot, ch);
            return;
        }
        unpack(a, start, 0);
        unpack(b, end, 0);
        begin(null, slot, ch, 4, true, t.duration(), t.delay(), t.easing(), true, null);
    }

    private void length(int slot, int ch, Transition t, Length a, Length b) {
        if (a.equals(b)) {
            return;
        }
        if (t == null || t.duration() <= 0 && t.delay() <= 0 || a.unit() != Length.Unit.PX
            || b.unit() != Length.Unit.PX) {
            stopTransition(slot, ch);
            return;
        }
        start[0] = a.value();
        end[0] = b.value();
        begin(null, slot, ch, 1, true, t.duration(), t.delay(), t.easing(), true, null);
    }

    private void stopTransition(int slot, int ch) {
        int i = find(null, slot, ch);
        if (i >= 0 && clearAtEnd[i]) {
            finish(i, false);
            flushFinished();
        }
    }

    /** The transition of {@code property} (or {@code all}) in {@code transitions}, or null. */
    public static Transition find(List<Transition> transitions, String property) {
        return find(transitions, property, property);
    }

    private static Transition find(List<Transition> transitions, String property, String alias) {
        Transition all = null;
        for (int i = 0; i < transitions.size(); i++) {
            Transition t = transitions.get(i);
            String p = t.property();
            if (p.equals(property) || p.equals(alias)) {
                return t;
            }
            if (p.equals("all")) {
                all = t;
            }
        }
        return all;
    }

    // ================================================================ values

    private static void unpack(long rgba, float[] out, int o) {
        out[o] = Rgba.r(rgba);
        out[o + 1] = Rgba.g(rgba);
        out[o + 2] = Rgba.b(rgba);
        out[o + 3] = Rgba.a(rgba);
    }

    private static long pack(float[] v, int o) {
        return Rgba.pack(v[o], v[o + 1], v[o + 2], v[o + 3]);
    }

    private static void edges(Insets insets, float[] out) {
        out[0] = insets.left();
        out[1] = insets.top();
        out[2] = insets.right();
        out[3] = insets.bottom();
    }

    /** A packed colour of {@code color}: for callers that hold {@link Color}s. */
    public static long rgba(Color color) {
        return color.rgba();
    }
}
