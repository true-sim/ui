package com.truesim.ui.profiler;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import java.util.SplittableRandom;

/**
 * The profiler's views over a made-up but engine-like recording, for tests and screenshots: 240 frames of a main
 * thread (the app's phases, the UI, recording and submitting the frame), three job threads recording chunks, an audio
 * thread mixing blocks, a streaming thread, the GPU's graphics and compute queues with their passes, and the runtime's
 * garbage collections and compilations; frame 200 is a hitch. Counters: memory, CPU and GPU load. The frame graph sits
 * at the top left, the profiler under it. Build it in a UI of {@link #WIDTH} by {@link #HEIGHT} pixels and
 * {@link #settle} it; {@link #pose} selects a zone and a frame and hovers the flame graph.
 */
public final class ProfilerGallery {

    /** The page's width in pixels. */
    public static final int WIDTH = 1600;
    /** The page's height in pixels. */
    public static final int HEIGHT = 1000;

    private static final long MS = 1_000_000L;

    public final Element root;
    public final Recording recording;
    public final FrameTimes times;
    public final FrameGraphView frameGraph;
    public final ProfilerView profiler;
    public final int mainLane;
    public final int graphicsLane;

    private ProfilerGallery(Ui ui) {
        recording = new Recording(1 << 16, 1024, 1024);
        times = new FrameTimes(ui.store().clock(), 240);
        root = ui.panel("hud");
        ui.root().add(root);
        ui.styles().inline(root.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.COLUMN).set(Styles.GAP, 8f).set(Styles.WIDTH, Length.px(WIDTH))
            .set(Styles.HEIGHT, Length.px(HEIGHT)).build());
        Controls controls = new Controls(ui);
        frameGraph = new FrameGraphView(ui, times).budgets(60, 120, 144, 165);
        profiler = new ProfilerView(controls, recording);
        ui.styles().inline(profiler.element().slot(), InlineStyle.builder().set(Styles.GROW, 1f).build());
        root.add(frameGraph.element()).add(profiler.element());
        mainLane = recording.lane("main", LaneKind.THREAD);
        fill(recording, times);
        graphicsLane = recording.lane("GPU graphics", LaneKind.GPU);
        profiler.timeline().show(recording.frameStart(236), recording.frameStart(239));
    }

    /** Builds the gallery in {@code ui}. */
    public static ProfilerGallery build(Ui ui) {
        return new ProfilerGallery(ui);
    }

    /** Fills {@code r} and {@code times} with 240 made-up frames (one a hitch). */
    public static void fill(Recording r, FrameTimes times) {
        SplittableRandom random = new SplittableRandom(11);
        int main = r.lane("main", LaneKind.THREAD);
        int[] jobs = {r.lane("jobs-1", LaneKind.THREAD), r.lane("jobs-2", LaneKind.THREAD),
            r.lane("jobs-3", LaneKind.THREAD)};
        int audio = r.lane("audio", LaneKind.THREAD);
        int streaming = r.lane("streaming", LaneKind.THREAD);
        int graphics = r.lane("GPU graphics", LaneKind.GPU);
        int compute = r.lane("GPU compute", LaneKind.GPU);
        int gc = r.lane("GC", LaneKind.MARKERS);
        int jit = r.lane("JIT", LaneKind.MARKERS);
        int frame = r.name("frame");
        int input = r.name("input");
        int fixed = r.name("FIXED");
        int physics = r.name("physics step");
        int update = r.name("UPDATE");
        int animate = r.name("ANIMATE");
        int late = r.name("LATE");
        int uiUpdate = r.name("ui.update");
        int sync = r.name("scene sync");
        int render = r.name("render");
        int wait = r.name("wait for GPU");
        int record = r.name("record");
        int submit = r.name("submit");
        int chunk = r.name("record chunk");
        int mix = r.name("mix block");
        int upload = r.name("texture upload");
        int decode = r.name("decode");
        int[] passes = {r.name("frame start"), r.name("cull early"), r.name("depth"), r.name("shadows"),
            r.name("gbuffer"), r.name("lighting"), r.name("transparency"), r.name("post"), r.name("ui")};
        int ao = r.name("ambient occlusion");
        int young = r.name("G1 Young Generation");
        int compile = r.name("C2 Renderer.record");
        int heap = r.counter("memory.heap", "bytes");
        int direct = r.counter("memory.direct", "bytes");
        int textures = r.counter("gpu.memory.textures", "bytes");
        int meshes = r.counter("gpu.memory.meshes", "bytes");
        int cpuLoad = r.counter("cpu.process", "%");
        int gpuBusy = r.counter("gpu.busy", "%");
        long t = 1_000 * MS;
        long gpuFree = t;
        long previousStart = t - 16 * MS;
        for (int f = 0; f < 240; f++) {
            boolean hitch = f == 200;
            long start = t;
            long fz = r.open(main, t, frame, 0);
            t = zone(r, main, t, input, 1, 0.15 * MS);
            long fx = r.open(main, t, fixed, 1);
            for (int s = 0; s < 2; s++) {
                t = zone(r, main, t, physics, 2, (0.6 + random.nextDouble() * 0.2) * MS);
            }
            r.close(main, fx, t);
            t = zone(r, main, t, update, 1, (1.2 + random.nextDouble() * 0.5 + (hitch ? 14 : 0)) * MS);
            t = zone(r, main, t, animate, 1, 0.9 * MS);
            t = zone(r, main, t, late, 1, 0.3 * MS);
            t = zone(r, main, t, uiUpdate, 1, 0.4 * MS);
            t = zone(r, main, t, sync, 1, 0.7 * MS);
            long rz = r.open(main, t, render, 1);
            long waitNs = (long) ((0.3 + random.nextDouble() * 0.8) * MS);
            t = zone(r, main, t, wait, 2, waitNs);
            long rec = r.open(main, t, record, 2);
            for (int j = 0; j < jobs.length; j++) {
                long js = t + (long) (0.05 * MS * j);
                for (int c = 0; c < 3; c++) {
                    js = zone(r, jobs[j], js, chunk, 0, (0.35 + random.nextDouble() * 0.2) * MS) + 20_000;
                }
            }
            t += (long) (1.9 * MS);
            r.close(main, rec, t);
            t = zone(r, main, t, submit, 2, 0.25 * MS);
            r.close(main, rz, t);
            r.close(main, fz, t);
            long cpuEnd = t;
            // the GPU: starts once submitted and free
            long g = Math.max(cpuEnd + 60_000, gpuFree);
            long gf = r.open(graphics, g, frame, 0);
            long gpuStart = g;
            for (int p = 0; p < passes.length; p++) {
                double ms = p == 5 ? 2.2 : p == 3 ? 1.1 : 0.45 + random.nextDouble() * 0.3;
                g = zone(r, graphics, g, passes[p], 1, ms * MS);
                if (p == 2) {
                    zone(r, compute, g - 200_000, ao, 1, 0.9 * MS);
                }
            }
            r.close(graphics, gf, g);
            gpuFree = g;
            // audio every ~5.3 ms, streaming now and then
            for (long a = start; a < cpuEnd; a += 5_333_333) {
                zone(r, audio, a, mix, 0, 0.12 * MS);
            }
            if (f % 7 == 3) {
                long s = zone(r, streaming, start + 2 * MS, decode, 0, 3 * MS);
                zone(r, streaming, s, upload, 0, 0.8 * MS);
            }
            if (f % 60 == 30 || hitch) {
                zone(r, gc, start + 3 * MS, start + (hitch ? 13 : 4) * MS, young, 0);
            }
            if (f % 45 == 10) {
                zone(r, jit, start + 1 * MS, start + 6 * MS, compile, 0);
            }
            long next = Math.max(start + (long) (16.6 * MS), cpuEnd + MS);
            r.frame(f, start, cpuEnd, waitNs, hitch);
            r.gpuFrame(f, gpuStart, gpuFree);
            double cpuMs = (cpuEnd - start) / 1e6;
            double intervalMs = f == 0 ? Double.NaN : (start - previousStart) / 1e6;
            times.frame(f, cpuMs, waitNs / 1e6, intervalMs, hitch);
            times.gpu(f, (gpuFree - gpuStart) / 1e6);
            previousStart = start;
            if (f % 4 == 0) {
                r.sample(heap, start, 300e6 + f * 2e6 % 120e6);
                r.sample(direct, start, 64e6 + (f % 30) * 1e5);
                r.sample(textures, start, 1.2e9 + f * 1e6);
                r.sample(meshes, start, 400e6);
                r.sample(cpuLoad, start, 35 + 10 * Math.sin(f * 0.1));
                r.sample(gpuBusy, start, 100.0 * (gpuFree - gpuStart) / (next - start));
            }
            t = next;
            r.commit();
        }
    }

    private static long zone(Recording r, int lane, long start, int name, int depth, double nanos) {
        long end = start + (long) nanos;
        r.zone(lane, start, end, name, depth);
        return end;
    }

    private static void zone(Recording r, int lane, long start, long end, int name, int depth) {
        r.zone(lane, start, end, name, depth);
    }

    /** Selects a zone of frame 237's main thread and frame 237 as the span; hovers the flame graph's first node. */
    public void pose(Ui ui) {
        TimelineView tl = profiler.timeline();
        long f = 237;
        long z = recording.seek(mainLane, recording.frameStart(f));
        for (long i = z; i < recording.endZone(mainLane); i++) {
            if (recording.nameOf(recording.zoneName(mainLane, i)).equals("UPDATE")) {
                tl.select(mainLane, i);
                break;
            }
        }
        tl.selectSpan(recording.frameStart(f), recording.frameStart(f + 1));
        settle(ui, WIDTH, HEIGHT);
    }

    private static long nanos = 1_000_000_000L;

    /** Whether every glyph asked for is in the atlas. */
    static boolean quiet(Ui ui) {
        return ui.textSystem() == null || ui.textSystem().atlas().pending() == 0;
    }

    /** Frames until the atlas has every glyph and nothing records. */
    public static void settle(Ui ui, int width, int height) {
        UiInput input = new UiInput(4);
        int quiet = 0;
        for (int i = 0; i < 400; i++) {
            input.beginFrame();
            ui.update(width, height, input, nanos += 16_000_000L);
            if (i > 10 && quiet(ui) && ui.recorded() == 0 && ++quiet >= 3) {
                return;
            }
        }
    }
}
