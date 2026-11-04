package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * What a profiler shows: zones per lane (a CPU thread's nested scopes, a GPU queue's passes, the runtime's markers),
 * frames, and counters, all on one clock in nanoseconds, kept in rings that keep the newest. A writer (the engine, a
 * test, a file being read) adds to it on one thread and {@link #commit()}s once a frame; views read it on the same
 * thread and redraw when {@link #version()} moved.
 *
 * <p><b>Zones</b> are kept in the order they started: {@link #open} takes a record when a zone begins (its end
 * {@link #OPEN} until {@link #close}), {@link #zone} adds a finished one. A lane's zones nest by {@code depth}: a zone
 * of depth {@code d + 1} lies inside the last zone of depth {@code d} before it. Each lane's ring starts small and
 * doubles up to {@link #zoneCapacity}, then drops the oldest; {@link #retain} drops what is older than a time span.
 *
 * <p><b>Frames</b> are numbered: {@link #frame} gives frame {@code n} its CPU start and submit and the time the CPU
 * waited for the GPU or the swapchain in it; {@link #gpuFrame} adds the GPU's span once the GPU finished it (frames
 * later). <b>Counters</b> ({@link #counter}) take samples of a value at times.
 *
 * <p>Adding and reading allocate nothing once the rings have grown, but for names and lanes seen for the first time.
 */
public final class Recording {

    /** A zone's end while it has not ended. */
    public static final long OPEN = Long.MAX_VALUE;

    private static final int FIRST_ZONES = 1024;
    private static final int FIRST_FRAMES = 256;
    private static final int FIRST_SAMPLES = 256;

    private final int zoneCapacity;
    private final int frameCapacity;
    private final int sampleCapacity;
    private long version;

    // names
    private String[] names = new String[64];
    private int nameCount;
    private final Map<String, Integer> nameIds = new HashMap<>();

    // lanes
    private Lane[] lanes = new Lane[8];
    private int laneCount;

    // frames: indexed by frame number
    private long[] frameStart;
    private long[] frameEnd;
    private long[] frameWait;
    private long[] gpuStart;
    private long[] gpuEnd;
    private boolean[] hitch;
    private long firstFrame;
    private long endFrame;

    // counters
    private Counter[] counters = new Counter[8];
    private int counterCount;

    private long latest = Long.MIN_VALUE; // the latest time added

    /** A recording keeping up to 262,144 zones a lane, 65,536 frames and 65,536 samples a counter. */
    public Recording() {
        this(1 << 18, 1 << 16, 1 << 16);
    }

    /**
     * A recording keeping up to {@code zonesPerLane} zones a lane, {@code frames} frames and {@code samples} samples a
     * counter (each rounded up to a power of two).
     */
    @AllowAllocation("made once")
    public Recording(int zonesPerLane, int frames, int samples) {
        this.zoneCapacity = pow2(Math.max(16, zonesPerLane));
        this.frameCapacity = pow2(Math.max(16, frames));
        this.sampleCapacity = pow2(Math.max(16, samples));
        int f = Math.min(FIRST_FRAMES, frameCapacity);
        frameStart = new long[f];
        frameEnd = new long[f];
        frameWait = new long[f];
        gpuStart = new long[f];
        gpuEnd = new long[f];
        hitch = new boolean[f];
    }

    private static int pow2(int n) {
        return Integer.highestOneBit(n - 1) << 1;
    }

    // ---------------------------------------------------------------- the whole

    /** Grows with every {@link #commit()}: views redraw when it moved. */
    public long version() {
        return version;
    }

    /** Ends the frame's additions: views see them ({@link #version()} moves). */
    public void commit() {
        version++;
    }

    /** The most zones a lane keeps. */
    public int zoneCapacity() {
        return zoneCapacity;
    }

    /** The earliest time anything kept starts at, or {@link Long#MAX_VALUE} when empty. */
    public long startTime() {
        long t = Long.MAX_VALUE;
        for (int l = 0; l < laneCount; l++) {
            Lane lane = lanes[l];
            if (lane.end > lane.first) {
                t = Math.min(t, lane.start[(int) lane.first & lane.mask]);
            }
        }
        if (endFrame > firstFrame) {
            t = Math.min(t, frameStart[(int) firstFrame & (frameStart.length - 1)]);
        }
        for (int c = 0; c < counterCount; c++) {
            Counter counter = counters[c];
            if (counter.end > counter.first) {
                t = Math.min(t, counter.time[(int) counter.first & counter.mask]);
            }
        }
        return t;
    }

    /** The latest time added (a zone's start or end, a frame's end, a sample), or {@link Long#MIN_VALUE}. */
    public long endTime() {
        return latest;
    }

    private void saw(long time) {
        if (time != OPEN && time > latest) {
            latest = time;
        }
    }

    /** Forgets everything: names and lanes stay, their contents go. */
    public void clear() {
        for (int l = 0; l < laneCount; l++) {
            lanes[l].first = lanes[l].end;
        }
        firstFrame = endFrame;
        for (int c = 0; c < counterCount; c++) {
            counters[c].first = counters[c].end;
        }
        latest = Long.MIN_VALUE;
        version++;
    }

    /**
     * Drops what ended more than {@code nanos} before the latest time: zones (from the oldest, up to the first one
     * still open or recent), frames and samples. Allocates nothing.
     */
    public void retain(long nanos) {
        if (latest == Long.MIN_VALUE) {
            return;
        }
        long cut = latest - nanos;
        for (int l = 0; l < laneCount; l++) {
            Lane lane = lanes[l];
            while (lane.first < lane.end) {
                int s = (int) lane.first & lane.mask;
                if (lane.end_[s] == OPEN || lane.end_[s] >= cut) {
                    break;
                }
                lane.first++;
            }
        }
        int fm = frameStart.length - 1;
        while (firstFrame < endFrame && frameEnd[(int) firstFrame & fm] < cut) {
            firstFrame++;
        }
        for (int c = 0; c < counterCount; c++) {
            Counter counter = counters[c];
            while (counter.first < counter.end && counter.time[(int) counter.first & counter.mask] < cut) {
                counter.first++;
            }
        }
    }

    // ---------------------------------------------------------------- names

    /** The id of {@code text} as a zone or marker name: the same text, the same id. Allocates for new names only. */
    @AllowAllocation("a name seen for the first time")
    public int name(String text) {
        Integer id = nameIds.get(text);
        if (id != null) {
            return id;
        }
        if (nameCount == names.length) {
            names = Arrays.copyOf(names, nameCount * 2);
        }
        names[nameCount] = text;
        nameIds.put(text, nameCount);
        return nameCount++;
    }

    /** Name {@code id}'s text. */
    public String nameOf(int id) {
        return id >= 0 && id < nameCount ? names[id] : "?";
    }

    /** How many names there are (ids 0 to this minus one). */
    public int names() {
        return nameCount;
    }

    // ---------------------------------------------------------------- lanes

    /** The lane named {@code name} of {@code kind}, made when first asked for; lanes keep the order they were made. */
    @AllowAllocation("a lane seen for the first time")
    public int lane(String name, LaneKind kind) {
        for (int l = 0; l < laneCount; l++) {
            if (lanes[l].kind == kind && lanes[l].name.equals(name)) {
                return l;
            }
        }
        if (laneCount == lanes.length) {
            lanes = Arrays.copyOf(lanes, laneCount * 2);
        }
        lanes[laneCount] = new Lane(name, kind, Math.min(FIRST_ZONES, zoneCapacity));
        version++;
        return laneCount++;
    }

    /** How many lanes there are. */
    public int lanes() {
        return laneCount;
    }

    /** Lane {@code lane}'s name: a thread's, a queue's, a kind of marker's. */
    public String laneName(int lane) {
        return lanes[lane].name;
    }

    /** What lane {@code lane} holds. */
    public LaneKind laneKind(int lane) {
        return lanes[lane].kind;
    }

    /** The deepest zone lane {@code lane} has held (0: only top-level zones). */
    public int maxDepth(int lane) {
        return lanes[lane].maxDepth;
    }

    /** Lane {@code lane}'s oldest zone kept (an absolute index). */
    public long firstZone(int lane) {
        return lanes[lane].first;
    }

    /** One past lane {@code lane}'s newest zone. */
    public long endZone(int lane) {
        return lanes[lane].end;
    }

    /** Zone {@code zone}'s start (a kept zone's absolute index). */
    public long zoneStart(int lane, long zone) {
        Lane l = lanes[lane];
        return l.start[(int) zone & l.mask];
    }

    /** Zone {@code zone}'s end, or {@link #OPEN}. */
    public long zoneEnd(int lane, long zone) {
        Lane l = lanes[lane];
        return l.end_[(int) zone & l.mask];
    }

    /** Zone {@code zone}'s name id. */
    public int zoneName(int lane, long zone) {
        Lane l = lanes[lane];
        return l.name_[(int) zone & l.mask];
    }

    /** Zone {@code zone}'s depth (0: top level). */
    public int zoneDepth(int lane, long zone) {
        Lane l = lanes[lane];
        return l.depth[(int) zone & l.mask];
    }

    /**
     * Starts a zone of {@code lane} at {@code start} named {@code name} at {@code depth}; returns its index for
     * {@link #close}. Zones of a lane are opened in the order they start.
     */
    public long open(int lane, long start, int name, int depth) {
        Lane l = lanes[lane];
        l.room(zoneCapacity);
        int s = (int) l.end & l.mask;
        l.start[s] = start;
        l.end_[s] = OPEN;
        l.name_[s] = name;
        l.depth[s] = (short) Math.min(depth, Short.MAX_VALUE);
        if (depth > l.maxDepth) {
            l.maxDepth = depth;
        }
        saw(start);
        return l.end++;
    }

    /** Ends zone {@code zone} (from {@link #open}) at {@code end}; a zone dropped since is ignored. */
    public void close(int lane, long zone, long end) {
        Lane l = lanes[lane];
        if (zone >= l.first && zone < l.end) {
            l.end_[(int) zone & l.mask] = end;
            saw(end);
        }
    }

    /** Adds a finished zone of {@code lane}; returns its index. */
    public long zone(int lane, long start, long end, int name, int depth) {
        long z = open(lane, start, name, depth);
        close(lane, z, end);
        return z;
    }

    /**
     * The first zone of {@code lane} to look at for what shows from {@code time} on: the top-level zone covering
     * {@code time} if there is one, else the first zone starting at or after it. Zones from there on, in order, are
     * everything that may overlap {@code time} and later. Allocates nothing.
     */
    public long seek(int lane, long time) {
        Lane l = lanes[lane];
        long lo = l.first;
        long hi = l.end;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (l.start[(int) mid & l.mask] < time) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // the top-level zone before it may still be running at time
        for (long i = lo - 1; i >= l.first; i--) {
            int s = (int) i & l.mask;
            if (l.depth[s] == 0) {
                return l.end_[s] >= time ? i : lo;
            }
        }
        return l.first < lo ? l.first : lo;
    }

    // ---------------------------------------------------------------- frames

    /**
     * Frame {@code number}: started at {@code start} on the CPU, submitted at {@code end}, of which the CPU waited
     * {@code waitNanos} for the GPU or the swapchain; {@code isHitch} marks a hitch. Frames come in order.
     */
    public void frame(long number, long start, long end, long waitNanos, boolean isHitch) {
        if (endFrame == firstFrame || number < firstFrame || number > endFrame + frameCapacity) {
            firstFrame = number;
            endFrame = number;
        }
        while (endFrame <= number) {
            growFrames();
            int s = (int) endFrame & (frameStart.length - 1);
            frameStart[s] = start;
            frameEnd[s] = end;
            frameWait[s] = 0;
            gpuStart[s] = 0;
            gpuEnd[s] = 0;
            hitch[s] = false;
            endFrame++;
            if (endFrame - firstFrame > frameStart.length) {
                firstFrame = endFrame - frameStart.length;
            }
        }
        int s = (int) number & (frameStart.length - 1);
        frameStart[s] = start;
        frameEnd[s] = end;
        frameWait[s] = waitNanos;
        hitch[s] = isHitch;
        saw(end);
    }

    /** Frame {@code number}'s GPU work ran from {@code start} to {@code end} (the CPU's clock); ignored if not kept. */
    public void gpuFrame(long number, long start, long end) {
        if (number >= firstFrame && number < endFrame) {
            int s = (int) number & (frameStart.length - 1);
            gpuStart[s] = start;
            gpuEnd[s] = end;
            saw(end);
        }
    }

    @AllowAllocation("the frame ring grows to its capacity, once")
    private void growFrames() {
        if (endFrame - firstFrame < frameStart.length || frameStart.length >= frameCapacity) {
            return;
        }
        int n = frameStart.length * 2;
        long[] fs = new long[n];
        long[] fe = new long[n];
        long[] fw = new long[n];
        long[] gs = new long[n];
        long[] ge = new long[n];
        boolean[] h = new boolean[n];
        for (long f = firstFrame; f < endFrame; f++) {
            int a = (int) f & (frameStart.length - 1);
            int b = (int) f & (n - 1);
            fs[b] = frameStart[a];
            fe[b] = frameEnd[a];
            fw[b] = frameWait[a];
            gs[b] = gpuStart[a];
            ge[b] = gpuEnd[a];
            h[b] = hitch[a];
        }
        frameStart = fs;
        frameEnd = fe;
        frameWait = fw;
        gpuStart = gs;
        gpuEnd = ge;
        hitch = h;
    }

    /** The oldest frame kept (its number). */
    public long firstFrame() {
        return firstFrame;
    }

    /** One past the newest frame. */
    public long endFrame() {
        return endFrame;
    }

    /** Frame {@code n}'s start on the CPU. */
    public long frameStart(long n) {
        return frameStart[(int) n & (frameStart.length - 1)];
    }

    /** When frame {@code n} was submitted. */
    public long frameEnd(long n) {
        return frameEnd[(int) n & (frameStart.length - 1)];
    }

    /** How long the CPU waited for the GPU or the swapchain in frame {@code n}, in nanoseconds. */
    public long frameWait(long n) {
        return frameWait[(int) n & (frameStart.length - 1)];
    }

    /** Frame {@code n}'s GPU start, or 0 before the GPU finished it. */
    public long frameGpuStart(long n) {
        return gpuStart[(int) n & (frameStart.length - 1)];
    }

    /** Frame {@code n}'s GPU end, or 0 before the GPU finished it. */
    public long frameGpuEnd(long n) {
        return gpuEnd[(int) n & (frameStart.length - 1)];
    }

    /** Whether frame {@code n} was a hitch. */
    public boolean frameHitch(long n) {
        return hitch[(int) n & (frameStart.length - 1)];
    }

    /** The frame whose CPU time covers {@code time}, the last one starting before it, or -1. */
    public long frameAt(long time) {
        long lo = firstFrame;
        long hi = endFrame;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            if (frameStart(mid) <= time) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1 >= firstFrame ? lo - 1 : -1;
    }

    // ---------------------------------------------------------------- counters

    /** The counter named {@code name} (dotted: its group first, {@code memory.heap}) in {@code unit}. */
    @AllowAllocation("a counter seen for the first time")
    public int counter(String name, String unit) {
        for (int c = 0; c < counterCount; c++) {
            if (counters[c].name.equals(name)) {
                return c;
            }
        }
        if (counterCount == counters.length) {
            counters = Arrays.copyOf(counters, counterCount * 2);
        }
        counters[counterCount] = new Counter(name, unit, Math.min(FIRST_SAMPLES, sampleCapacity));
        version++;
        return counterCount++;
    }

    /** Adds a sample of counter {@code counter}: {@code value} at {@code time}. */
    public void sample(int counter, long time, double value) {
        Counter c = counters[counter];
        c.room(sampleCapacity);
        int s = (int) c.end & c.mask;
        c.time[s] = time;
        c.value[s] = value;
        c.end++;
        saw(time);
    }

    /** How many counters there are. */
    public int counters() {
        return counterCount;
    }

    /** Counter {@code c}'s name. */
    public String counterName(int c) {
        return counters[c].name;
    }

    /** Counter {@code c}'s unit ({@code ms}, {@code bytes}, {@code %}, ...). */
    public String counterUnit(int c) {
        return counters[c].unit;
    }

    /** Counter {@code c}'s oldest sample kept. */
    public long firstSample(int c) {
        return counters[c].first;
    }

    /** One past counter {@code c}'s newest sample. */
    public long endSample(int c) {
        return counters[c].end;
    }

    /** Sample {@code i}'s time. */
    public long sampleTime(int c, long i) {
        Counter counter = counters[c];
        return counter.time[(int) i & counter.mask];
    }

    /** Sample {@code i}'s value. */
    public double sampleValue(int c, long i) {
        Counter counter = counters[c];
        return counter.value[(int) i & counter.mask];
    }

    /** Counter {@code c}'s newest value, or NaN. */
    public double latestValue(int c) {
        Counter counter = counters[c];
        return counter.end > counter.first ? counter.value[(int) (counter.end - 1) & counter.mask] : Double.NaN;
    }

    // ---------------------------------------------------------------- storage

    private static final class Lane {
        final String name;
        final LaneKind kind;
        long[] start;
        long[] end_;
        int[] name_;
        short[] depth;
        int mask;
        long first;
        long end;
        int maxDepth;

        Lane(String name, LaneKind kind, int size) {
            this.name = name;
            this.kind = kind;
            start = new long[size];
            end_ = new long[size];
            name_ = new int[size];
            depth = new short[size];
            mask = size - 1;
        }

        /** Room for one more zone: the ring doubles up to {@code capacity}, then the oldest goes. */
        void room(int capacity) {
            if (end - first < start.length) {
                return;
            }
            if (start.length < capacity) {
                grow();
            } else {
                first++;
            }
        }

        @AllowAllocation("a lane's ring grows to its capacity, once")
        private void grow() {
            int n = start.length * 2;
            long[] s2 = new long[n];
            long[] e2 = new long[n];
            int[] n2 = new int[n];
            short[] d2 = new short[n];
            for (long i = first; i < end; i++) {
                int a = (int) i & mask;
                int b = (int) i & (n - 1);
                s2[b] = start[a];
                e2[b] = end_[a];
                n2[b] = name_[a];
                d2[b] = depth[a];
            }
            start = s2;
            end_ = e2;
            name_ = n2;
            depth = d2;
            mask = n - 1;
        }
    }

    private static final class Counter {
        final String name;
        final String unit;
        long[] time;
        double[] value;
        int mask;
        long first;
        long end;

        Counter(String name, String unit, int size) {
            this.name = name;
            this.unit = unit;
            time = new long[size];
            value = new double[size];
            mask = size - 1;
        }

        void room(int capacity) {
            if (end - first < time.length) {
                return;
            }
            if (time.length < capacity) {
                grow();
            } else {
                first++;
            }
        }

        @AllowAllocation("a counter's ring grows to its capacity, once")
        private void grow() {
            int n = time.length * 2;
            long[] t2 = new long[n];
            double[] v2 = new double[n];
            for (long i = first; i < end; i++) {
                t2[(int) i & (n - 1)] = time[(int) i & mask];
                v2[(int) i & (n - 1)] = value[(int) i & mask];
            }
            time = t2;
            value = v2;
            mask = n - 1;
        }
    }
}
