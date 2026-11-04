package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;

/**
 * The last frames' times for the frame graph ({@link FrameGraphView}), in fixed rings: per frame its CPU time (start to
 * submit), how long the CPU waited for the GPU or the swapchain in it, and the time since the previous frame's start
 * (the frame interval); its GPU time once the GPU finished it, frames later, under the frame's own number. Frames
 * marked as hitches are kept as their numbers. Adding a frame writes a few doubles; nothing allocates.
 *
 * <pre>{@code
 * FrameTimes times = new FrameTimes(ui.store().clock(), 600);
 * times.frame(n, cpuMs, waitMs, intervalMs, hitch);     // each frame, as soon as it is submitted
 * times.gpu(m, gpuMs);                                  // when the GPU finished frame m
 * }</pre>
 */
public final class FrameTimes {

    /** The kinds of time kept, for {@link #stats}. */
    public enum Kind {
        /** CPU: the frame's start to its submit. */
        CPU,
        /** GPU: the frame's first pass's start to its last pass's end. */
        GPU,
        /** What of the CPU time went waiting for the GPU or the swapchain. */
        WAIT,
        /** The previous frame's start to this frame's start: the frame's delta, what a player feels. */
        INTERVAL
    }

    /** Statistics of one kind over the frames kept. */
    public static final class Stats {
        /** How many frames have a value. */
        public int count;
        /** The least, in milliseconds (NaN without frames). */
        public double min = Double.NaN;
        /** The mean. */
        public double avg = Double.NaN;
        /** The 99th percentile (the value 1 % of frames are above). */
        public double p99 = Double.NaN;
        /** The largest. */
        public double max = Double.NaN;
    }

    private final int capacity;
    private final VersionedDoubleRing frames; // X of the CPU-side values: frame numbers
    private final VersionedDoubleRing cpu;
    private final VersionedDoubleRing wait;
    private final VersionedDoubleRing interval;
    private final VersionedDoubleRing gpuFrames; // X of the GPU values: their frames' numbers
    private final VersionedDoubleRing gpu;
    private final VersionedDoubleRing hitches; // numbers of frames that hitched
    private final double[] scratch;
    private long hitchCount;
    private long latest = -1;

    /** Keeps the last {@code capacity} frames, stamped by {@code clock} (the UI's). */
    @AllowAllocation("made once")
    public FrameTimes(ChangeClock clock, int capacity) {
        this.capacity = capacity;
        frames = clock.doubleRing(capacity);
        cpu = clock.doubleRing(capacity);
        wait = clock.doubleRing(capacity);
        interval = clock.doubleRing(capacity);
        gpuFrames = clock.doubleRing(capacity);
        gpu = clock.doubleRing(capacity);
        hitches = clock.doubleRing(Math.max(16, capacity / 8));
        scratch = new double[capacity];
    }

    /** How many frames are kept. */
    public int capacity() {
        return capacity;
    }

    /**
     * Frame {@code number}: {@code cpuMillis} from its start to its submit, of which {@code waitMillis} waiting,
     * {@code intervalMillis} since the previous frame's start (NaN for the first); {@code hitch} marks it. Frames come
     * in order.
     */
    public void frame(long number, double cpuMillis, double waitMillis, double intervalMillis, boolean hitch) {
        frames.append(number);
        cpu.append(cpuMillis);
        wait.append(waitMillis);
        interval.append(intervalMillis);
        if (hitch) {
            hitches.append(number);
            hitchCount++;
        }
        latest = number;
    }

    /** Frame {@code number}'s GPU time, now that the GPU finished it. */
    public void gpu(long number, double gpuMillis) {
        gpuFrames.append(number);
        gpu.append(gpuMillis);
    }

    /** Forgets every frame. */
    public void clear() {
        frames.clear();
        cpu.clear();
        wait.clear();
        interval.clear();
        gpuFrames.clear();
        gpu.clear();
        hitches.clear();
        hitchCount = 0;
        latest = -1;
    }

    /** The newest frame's number, or -1. */
    public long latest() {
        return latest;
    }

    /** Frames marked as hitches since made (or cleared). */
    public long hitchCount() {
        return hitchCount;
    }

    /** Hitches among the frames kept. */
    public int hitchesKept() {
        long oldest = frames.size() == 0 ? Long.MAX_VALUE : (long) frames.get(frames.first());
        int n = 0;
        for (long i = hitches.first(), end = hitches.appended(); i < end; i++) {
            if (hitches.get(i) >= oldest) {
                n++;
            }
        }
        return n;
    }

    /** The CPU values' frame numbers (X). */
    public VersionedDoubleRing frames() {
        return frames;
    }

    /** CPU milliseconds per frame. */
    public VersionedDoubleRing cpu() {
        return cpu;
    }

    /** Waiting milliseconds per frame. */
    public VersionedDoubleRing waits() {
        return wait;
    }

    /** Milliseconds since the previous frame's start, per frame. */
    public VersionedDoubleRing intervals() {
        return interval;
    }

    /** The GPU values' frame numbers (X). */
    public VersionedDoubleRing gpuFrames() {
        return gpuFrames;
    }

    /** GPU milliseconds per frame. */
    public VersionedDoubleRing gpu() {
        return gpu;
    }

    /** Numbers of the frames that hitched. */
    public VersionedDoubleRing hitches() {
        return hitches;
    }

    private VersionedDoubleRing ring(Kind kind) {
        return switch (kind) {
            case CPU -> cpu;
            case GPU -> gpu;
            case WAIT -> wait;
            case INTERVAL -> interval;
        };
    }

    /** Fills {@code out} with {@code kind}'s min, mean, 99th percentile and max over the frames kept. Allocates nothing. */
    public Stats stats(Kind kind, Stats out) {
        VersionedDoubleRing r = ring(kind);
        int n = 0;
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (long i = r.first(), end = r.appended(); i < end; i++) {
            double v = r.get(i);
            if (v == v) {
                scratch[n++] = v;
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        out.count = n;
        if (n == 0) {
            out.min = out.avg = out.p99 = out.max = Double.NaN;
            return out;
        }
        out.min = min;
        out.max = max;
        out.avg = sum / n;
        out.p99 = select(scratch, n, Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1));
        return out;
    }

    /** The {@code k}-th smallest of {@code a[0..n)} (quickselect, reordering {@code a}). */
    static double select(double[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                return a[k];
            }
        }
        return a[k];
    }
}
