package com.truesim.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.util.yoga.Yoga.*;

import com.truesim.ui.core.UiStore;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.util.yoga.YGMeasureFunc;

/**
 * This module's layout against Yoga (lwjgl-yoga 3.4.3) on a HUD (500 and 2,000 elements) and a 500-row form (2,000
 * elements): idle frames, a tenth of the content changing per frame, and full relayout (design 4.6, UI14).
 *
 * <p>Both sides do a frame's whole work: apply the changes (store marks and setters; Yoga's style setters and
 * {@code YGNodeMarkDirty}), lay out (text measured by the same Java function, through upcalls for Yoga), and bring the
 * absolute, pixel-snapped rectangles of what moved into Java arrays (Yoga: walking the nodes with new layouts, and
 * everything below one that moved). Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :ui-layout:test --tests '*LayoutBenchmark'} (coverage is off then).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class LayoutBenchmark {

    private static final int WARMUP = 4_000;
    private static final int FRAMES = 4_000;
    private static final float W = 1920;
    private static final float H = 1080;

    /** One side of a scene. */
    private interface Side {
        void idle();

        void incremental();

        void full();
    }

    // ================================================================ the scenes, built on both sides alike

    /** Builds the same scene on both sides: our elements and Yoga nodes in parallel. */
    private abstract static class Scene {
        final TestUi ui = new TestUi();
        final YogaTree yoga = new YogaTree();
        int[] changing;
        int[] bars;
        int changingCount;
        int barCount;
        int frame;

        Scene(boolean snap) {
            ui.layout.snapping(snap);
            yoga.snap = snap;
            changing = new int[16];
            bars = new int[16];
        }

        int elements() {
            return ui.store.count() - 1;
        }

        void addChanging(int slot) {
            if (changingCount == changing.length) {
                changing = Arrays.copyOf(changing, changingCount * 2);
            }
            changing[changingCount++] = slot;
        }

        void addBar(int slot) {
            if (barCount == bars.length) {
                bars = Arrays.copyOf(bars, barCount * 2);
            }
            bars[barCount++] = slot;
        }

        int text(int parent, int chars, boolean shrink) {
            int s = ui.text(parent, chars);
            ui.layout.shrink(s, shrink ? 1 : 0);
            yoga.leaf(parent, s, ui);
            YGNodeStyleSetFlexShrink(yoga.node[s], shrink ? 1 : 0);
            return s;
        }

        /** The changes of one frame: {@code texts} texts change length, {@code widths} bars change width. */
        void changes(int texts, int widths, boolean own) {
            frame++;
            for (int k = 0; k < texts; k++) {
                int s = changing[(frame * 7 + k * (changingCount / Math.max(1, texts))) % changingCount];
                int n = 2 + (frame + k) % 5;
                if (own) {
                    ui.setText(s, n);
                } else {
                    ui.chars[s] = n;
                    YGNodeMarkDirty(yoga.node[s]);
                }
            }
            for (int k = 0; k < widths; k++) {
                int s = bars[(frame * 3 + k * (barCount / Math.max(1, widths))) % barCount];
                float w = 20 + (frame + k) % 40;
                if (own) {
                    ui.layout.width(s, w, Unit.PX);
                } else {
                    YGNodeStyleSetWidth(yoga.node[s], w);
                }
            }
        }

        abstract int texts();

        abstract int widths();

        Side own() {
            return new Side() {
                @Override
                public void idle() {
                    ui.run(W, H);
                }

                @Override
                public void incremental() {
                    changes(texts(), widths(), true);
                    ui.run(W, H);
                }

                @Override
                public void full() {
                    ui.store.markAll(UiStore.DIRTY_LAYOUT);
                    ui.run(W, H);
                }
            };
        }

        Side yoga() {
            return new Side() {
                @Override
                public void idle() {
                    yoga.layout(ui, false);
                }

                @Override
                public void incremental() {
                    changes(texts(), widths(), false);
                    yoga.layout(ui, false);
                }

                @Override
                public void full() {
                    for (int s = 0; s < ui.store.highWater(); s++) {
                        if (ui.layout.measured[s]) {
                            YGNodeMarkDirty(yoga.node[s]);
                        }
                    }
                    yoga.layout(ui, true);
                }
            };
        }
    }

    /** Anchored panels: a title and three rows of label, value and bar each (14 elements a panel). */
    private static final class Hud extends Scene {

        Hud(int panels, boolean snap) {
            super(snap);
            Layout l = ui.layout;
            int root = ui.root();
            l.kind(root, LayoutKind.ANCHORS);
            yoga.root(root, ui);
            for (int p = 0; p < panels; p++) {
                int panel = ui.node(root);
                long y = yoga.node(root, panel, ui);
                YGNodeStyleSetPositionType(y, YGPositionTypeAbsolute);
                float px = 10 + (p * 37) % 1500;
                float py = 10 + (p * 53) % 900;
                if (p % 3 == 2) {
                    l.anchors(panel, 1, 1, 1, 1).offsets(panel, -px, -py, -px, -py)
                        .growDirection(panel, GrowDirection.BEGIN, GrowDirection.BEGIN);
                    YGNodeStyleSetPosition(y, YGEdgeRight, px);
                    YGNodeStyleSetPosition(y, YGEdgeBottom, py);
                } else {
                    l.offsets(panel, px, py, px, py);
                    YGNodeStyleSetPosition(y, YGEdgeLeft, px);
                    YGNodeStyleSetPosition(y, YGEdgeTop, py);
                }
                l.padding(panel, 6, 6, 6, 6).gap(panel, 4, 0);
                YGNodeStyleSetFlexDirection(y, YGFlexDirectionColumn);
                YGNodeStyleSetPadding(y, YGEdgeAll, 6);
                YGNodeStyleSetGap(y, YGGutterRow, 4);
                text(panel, 8 + p % 5, true);
                for (int r = 0; r < 3; r++) {
                    int row = ui.node(panel);
                    long yr = yoga.node(panel, row, ui);
                    l.kind(row, LayoutKind.FLEX).direction(row, Direction.ROW).gap(row, 0, 6)
                        .alignItems(row, Align.CENTER);
                    YGNodeStyleSetFlexDirection(yr, YGFlexDirectionRow);
                    YGNodeStyleSetGap(yr, YGGutterColumn, 6);
                    YGNodeStyleSetAlignItems(yr, YGAlignCenter);
                    text(row, 5 + r, false);
                    addChanging(text(row, 3, false));
                    int bar = ui.node(row);
                    long yb = yoga.node(row, bar, ui);
                    l.width(bar, 40, Unit.PX).height(bar, 8, Unit.PX).shrink(bar, 0);
                    YGNodeStyleSetWidth(yb, 40);
                    YGNodeStyleSetHeight(yb, 8);
                    YGNodeStyleSetFlexShrink(yb, 0);
                    addBar(bar);
                }
            }
        }

        /** A tenth of the elements change: half of them values, half bars. */
        @Override
        int texts() {
            return elements() / 20;
        }

        @Override
        int widths() {
            return elements() / 20;
        }
    }

    /** A settings form: rows of a fixed-width label, a growing field and a help text (4 elements a row). */
    private static final class Form extends Scene {

        Form(int rows, boolean snap) {
            super(snap);
            Layout l = ui.layout;
            int root = ui.root();
            l.kind(root, LayoutKind.FLEX).direction(root, Direction.COLUMN).padding(root, 8, 8, 8, 8).gap(root, 2, 0);
            long yroot = yoga.root(root, ui);
            YGNodeStyleSetFlexDirection(yroot, YGFlexDirectionColumn);
            YGNodeStyleSetPadding(yroot, YGEdgeAll, 8);
            YGNodeStyleSetGap(yroot, YGGutterRow, 2);
            for (int i = 0; i < rows; i++) {
                int row = ui.node(root);
                long y = yoga.node(root, row, ui);
                l.kind(row, LayoutKind.FLEX).direction(row, Direction.ROW).alignItems(row, Align.CENTER)
                    .padding(row, 4, 4, 4, 4).gap(row, 0, 8).shrink(row, 0);
                YGNodeStyleSetFlexDirection(y, YGFlexDirectionRow);
                YGNodeStyleSetAlignItems(y, YGAlignCenter);
                YGNodeStyleSetPadding(y, YGEdgeAll, 4);
                YGNodeStyleSetGap(y, YGGutterColumn, 8);
                YGNodeStyleSetFlexShrink(y, 0);
                int label = text(row, 6 + i % 9, false);
                l.width(label, 160, Unit.PX);
                YGNodeStyleSetWidth(yoga.node[label], 160);
                int field = ui.box(row, 100, 22);
                long yf = yoga.leaf(row, field, ui);
                l.grow(field, 1).shrink(field, 1);
                YGNodeStyleSetFlexGrow(yf, 1);
                YGNodeStyleSetFlexShrink(yf, 1);
                addChanging(text(row, 10 + i % 20, false));
                addBar(field);
            }
        }

        /** A tenth of the rows change their help text. */
        @Override
        int texts() {
            return changingCount / 10;
        }

        @Override
        int widths() {
            return 0;
        }
    }

    // ================================================================ Yoga's side

    /** Yoga nodes per slot, the measure upcall, and reading layouts back into absolute, snapped rectangles. */
    private static final class YogaTree {
        private static TestUi measuring;
        private static final YGMeasureFunc MEASURE = YGMeasureFunc.create((node, w, wm, h, hm, out) -> {
            long size = measuring.measure((int) YGNodeGetContext(node), w, wm, h, hm);
            out.width(Measure.width(size)).height(Measure.height(size));
        });

        final long config = YGConfigNew();
        long[] node = new long[64];
        float[] absX = new float[64];
        float[] absY = new float[64];
        float[] out = new float[256];
        int rootSlot;
        boolean snap;
        int changed;

        YogaTree() {
            YGConfigSetExperimentalFeatureEnabled(config, YGExperimentalFeatureWebFlexBasis, true);
        }

        private void grow(int s) {
            if (s >= node.length) {
                int n = Math.max(s + 1, node.length * 2);
                node = Arrays.copyOf(node, n);
                absX = Arrays.copyOf(absX, n);
                absY = Arrays.copyOf(absY, n);
                out = Arrays.copyOf(out, n * 4);
            }
        }

        long root(int s, TestUi ui) {
            YGConfigSetPointScaleFactor(config, snap ? 1 : 0);
            grow(s);
            node[s] = YGNodeNewWithConfig(config);
            YGNodeSetContext(node[s], s);
            YGNodeStyleSetWidth(node[s], W);
            YGNodeStyleSetHeight(node[s], H);
            rootSlot = s;
            return node[s];
        }

        long node(int parent, int s, TestUi ui) {
            grow(s);
            long y = YGNodeNewWithConfig(config);
            YGNodeSetContext(y, s);
            YGNodeStyleSetFlexShrink(y, 1); // as this module's default
            YGNodeInsertChild(node[parent], y, YGNodeGetChildCount(node[parent]));
            node[s] = y;
            return y;
        }

        long leaf(int parent, int s, TestUi ui) {
            long y = node(parent, s, ui);
            YGNodeSetMeasureFunc(y, MEASURE);
            return y;
        }

        void layout(TestUi ui, boolean all) {
            measuring = ui;
            changed = 0;
            YGNodeCalculateLayout(node[rootSlot], W, H, YGDirectionLTR);
            read(node[rootSlot], 0, 0, all);
        }

        /** Reads what has a new layout, and everything below what moved, as this module's absolute pass does. */
        private void read(long y, float px, float py, boolean force) {
            if (!force && !YGNodeGetHasNewLayout(y)) {
                return;
            }
            YGNodeSetHasNewLayout(y, false);
            int s = (int) YGNodeGetContext(y);
            float ax = px + YGNodeLayoutGetLeft(y);
            float ay = py + YGNodeLayoutGetTop(y);
            boolean moved = force || ax != absX[s] || ay != absY[s];
            absX[s] = ax;
            absY[s] = ay;
            float x0 = snap ? (float) Math.floor(ax + 0.5f) : ax;
            float y0 = snap ? (float) Math.floor(ay + 0.5f) : ay;
            float x1 = snap ? (float) Math.floor(ax + YGNodeLayoutGetWidth(y) + 0.5f) : ax + YGNodeLayoutGetWidth(y);
            float y1 = snap ? (float) Math.floor(ay + YGNodeLayoutGetHeight(y) + 0.5f) : ay + YGNodeLayoutGetHeight(y);
            int o = s * 4;
            if (out[o] != x0 || out[o + 1] != y0 || out[o + 2] != x1 - x0 || out[o + 3] != y1 - y0) {
                out[o] = x0;
                out[o + 1] = y0;
                out[o + 2] = x1 - x0;
                out[o + 3] = y1 - y0;
                changed++;
            }
            for (int i = 0, n = (int) YGNodeGetChildCount(y); i < n; i++) {
                read(YGNodeGetChild(y, i), ax, ay, moved);
            }
        }

        void free() {
            YGNodeFreeRecursive(node[rootSlot]);
            YGConfigFree(config);
        }
    }

    // ================================================================ running

    @Test
    void ownAgainstYoga() {
        // Both sides lay the scenes out alike (unsnapped, Yoga unrounded).
        for (Scene scene : new Scene[] {new Hud(36, false), new Form(100, false)}) {
            scene.ui.run(W, H);
            scene.yoga.layout(scene.ui, true);
            for (int s = 1; s < scene.ui.store.highWater(); s++) {
                int o = s * 4;
                assertThat(scene.ui.rect(s)).as("slot %d", s).usingComparatorWithPrecision(0.01f)
                    .containsExactly(scene.yoga.out[o], scene.yoga.out[o + 1], scene.yoga.out[o + 2],
                        scene.yoga.out[o + 3]);
            }
            scene.yoga.free();
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "%-34s %-6s %10s %10s %10s%n", "scene / frame", "side", "mean us",
            "p50 us", "p99 us"));
        bench(report, "HUD 500 elements", () -> new Hud(36, true));
        bench(report, "HUD 2,000 elements", () -> new Hud(143, true));
        bench(report, "form 500 rows (2,000 elements)", () -> new Form(500, true));
        System.out.println(report);
    }

    private interface Maker {
        Scene make();
    }

    private static void bench(StringBuilder report, String name, Maker maker) {
        Scene own = maker.make();
        Scene yoga = maker.make();
        own.ui.run(W, H);
        yoga.yoga.layout(yoga.ui, true);
        String[] frames = {"idle", "10% changing", "full relayout"};
        for (int f = 0; f < 3; f++) {
            long[] o = time(own.own(), f);
            long[] y = time(yoga.yoga(), f);
            String label = name + ", " + frames[f];
            report.append(line(label, "own", o)).append(line("", "Yoga", y));
        }
        report.append(String.format(Locale.ROOT, "  (%d elements, %d texts and %d widths change per frame)%n",
            own.elements(), own.texts(), own.widths()));
        yoga.yoga.free();
        own.yoga.free();
    }

    private static long[] time(Side side, int frame) {
        for (int i = 0; i < WARMUP; i++) {
            run(side, frame);
        }
        long[] t = new long[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            long start = System.nanoTime();
            run(side, frame);
            t[i] = System.nanoTime() - start;
        }
        return t;
    }

    private static void run(Side side, int frame) {
        switch (frame) {
            case 0 -> side.idle();
            case 1 -> side.incremental();
            default -> side.full();
        }
    }

    private static String line(String label, String side, long[] t) {
        long[] sorted = t.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(t).average().orElse(0) / 1000.0;
        return String.format(Locale.ROOT, "%-34s %-6s %10.1f %10.1f %10.1f%n", label, side, mean,
            sorted[sorted.length / 2] / 1000.0, sorted[sorted.length * 99 / 100] / 1000.0);
    }
}
