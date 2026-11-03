package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PickGeometry;
import com.truesim.ui.core.event.Picking;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Section 11's screen UI hit test (at most 5 µs per pointer) on a 2,000-element HUD, the hit grid against a plain walk
 * of the tree (front to back, stopping at the first hit). Runs with {@code TRUESIM_BENCHMARK=true} (JaCoCo off);
 * prints its timings. Without the variable it checks only that both agree, so the comparison stays honest.
 */
class PickingBenchmarkTest {

    static final StyleSheet SHEET = Tss.compileString("pick-bench.tss", """
        root { layout: flex; direction: row; wrap: wrap; gap: 4px; padding: 8px; align-items: start; }
        panel.card { layout: flex; direction: column; gap: 2px; padding: 4px; width: 150px; radius: 6px; }
        panel.row { layout: flex; direction: row; gap: 2px; height: 14px; }
        panel.cell { width: 34px; height: 14px; radius: 3px; }
        """);

    /** 80 cards of 6 rows of 3 cells: 2,001 elements; every cell and every second row listens to the pointer. */
    static Ui hud() {
        Ui ui = new Ui(SHEET, null);
        UiListener nothing = e -> { };
        for (int c = 0; c < 80; c++) {
            Element card = ui.panel("card");
            ui.root().add(card);
            for (int r = 0; r < 6; r++) {
                Element row = ui.panel("row");
                card.add(row);
                if (r % 2 == 0) {
                    ui.on(row, EventType.CONTEXT, nothing);
                }
                for (int k = 0; k < 3; k++) {
                    Element cell = ui.panel("cell");
                    row.add(cell);
                    ui.on(cell, EventType.CLICK, nothing);
                }
            }
        }
        ui.update(1920, 1080);
        ui.update(1920, 1080);
        return ui;
    }

    /** The plain alternative: the front-most pickable element under the point, walking the tree front to back. */
    static int walkTop(Interaction ia, PickGeometry geo, int slot, float x, float y) {
        UiStore store = ia.store();
        if (!geo.shown(slot)) {
            return UiStore.NONE;
        }
        for (int c = store.lastChild(slot); c != UiStore.NONE; c = store.previousSibling(c)) {
            int hit = walkTop(ia, geo, c, x, y);
            if (hit != UiStore.NONE) {
                return hit;
            }
        }
        if (ia.pickable(slot) && Picking.inside(x, y, geo.x(slot), geo.y(slot), geo.width(slot), geo.height(slot),
            geo.radius(slot, 0), geo.radius(slot, 1), geo.radius(slot, 2), geo.radius(slot, 3))) {
            return slot;
        }
        return UiStore.NONE;
    }

    @Test
    void theHitGridAnswersWithinTheTargetAndAgreesWithATreeWalk() {
        Ui ui = hud();
        Interaction ia = ui.interaction();
        PickGeometry geo = ia.geometry();
        int root = ui.store().rootSlot();
        Random random = new Random(3);
        int n = 4096;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = random.nextFloat() * 1920;
            ys[i] = random.nextFloat() * 1080;
        }
        int hits = 0;
        for (int i = 0; i < n; i++) {
            int grid = ia.picking().pickTop(xs[i], ys[i]);
            assertThat(grid).isEqualTo(walkTop(ia, geo, root, xs[i], ys[i]));
            hits += grid == UiStore.NONE ? 0 : 1;
        }
        assertThat(hits).isPositive();
        assertThat(ia.picking().elementCount(0)).isEqualTo(80 * 3 + 80 * 6 * 3);
        if (!"true".equals(System.getenv("TRUESIM_BENCHMARK"))) {
            return;
        }
        int[] out = new int[Interaction.MAX_HITS];
        double[] grid = new double[9];
        double[] all = new double[9];
        double[] walk = new double[9];
        long sink = 0;
        for (int round = 0; round < 9; round++) {
            long t0 = System.nanoTime();
            for (int rep = 0; rep < 50; rep++) {
                for (int i = 0; i < n; i++) {
                    sink += ia.picking().pickTop(xs[i], ys[i]);
                }
            }
            long t1 = System.nanoTime();
            for (int rep = 0; rep < 50; rep++) {
                for (int i = 0; i < n; i++) {
                    sink += ia.picking().pick(xs[i], ys[i], out);
                }
            }
            long t2 = System.nanoTime();
            for (int rep = 0; rep < 5; rep++) {
                for (int i = 0; i < n; i++) {
                    sink += walkTop(ia, geo, root, xs[i], ys[i]);
                }
            }
            long t3 = System.nanoTime();
            grid[round] = (t1 - t0) / 1e3 / (50.0 * n);
            all[round] = (t2 - t1) / 1e3 / (50.0 * n);
            walk[round] = (t3 - t2) / 1e3 / (5.0 * n);
        }
        Arrays.sort(grid);
        Arrays.sort(all);
        Arrays.sort(walk);
        System.out.printf(Locale.ROOT, "Pick on a %d-element HUD, median of 9 rounds: grid front-most %.3f µs,"
            + " grid every hit %.3f µs, tree walk front-most %.3f µs (sink %d)%n", ui.store().count(), grid[4],
            all[4], walk[4], sink);
        assertThat(all[4]).isLessThan(5.0);
    }

    @Test
    void aFrameOfPointerInputCostsMicroseconds() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = hud();
        UiInput input = new UiInput(8);
        long now = 0;
        double[] rounds = new double[9];
        for (int round = 0; round < 9; round++) {
            long t0 = System.nanoTime();
            for (int f = 0; f < 20_000; f++) {
                input.beginFrame();
                input.pointer(0, Pointer.Kind.MOUSE).moveTo((f * 7) % 1900, (f * 3) % 1000);
                ui.interaction().process(input, now += 16_000_000L);
            }
            rounds[round] = (System.nanoTime() - t0) / 1e3 / 20_000;
        }
        Arrays.sort(rounds);
        System.out.printf(Locale.ROOT, "Interaction per frame, one mouse moving over the HUD (pick, hover, move "
            + "events): %.3f µs median%n", rounds[4]);
        assertThat(rounds[4]).isLessThan(50);
    }
}
