package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Length;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Section 11's data-control targets on the CPU, under the default theme through the real text path (HarfBuzz,
 * msdfgen): a 10,000-row list scrolling continuously (0.1 ms a frame, memory of the visible rows), a 1,000,000-row
 * sorted and filtered view with 100 changes a frame shown in a list (0.5 ms), and a 100,000 x 20 table scrolling
 * (0.3 ms). Runs with {@code TRUESIM_BENCHMARK=true} (JaCoCo off); prints median and p99 per frame.
 */
class DataBenchmarkTest {

    static final float W = 1920;
    static final float H = 1080;

    record Row(int id, int value, double load, String name) {
    }

    static void size(Ui ui, com.truesim.ui.core.Element e, float w, float h) {
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
    }

    static String[] pool(int n) {
        String[] pool = new String[n];
        for (int i = 0; i < n; i++) {
            pool[i] = String.format(Locale.ROOT, "Entry %05d of the benchmark", i);
        }
        return pool;
    }

    static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "median %.4f ms, p99 %.4f ms", sorted[sorted.length / 2] / 1e6,
            sorted[sorted.length * 99 / 100] / 1e6);
    }

    static long median(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    @Test
    void aListOfTenThousandRowsScrollsContinuously() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        ChangeClock clock = ui.store().clock();
        VersionedList<String> items = clock.list();
        String[] pool = pool(10_000);
        items.addAll(Arrays.asList(pool));
        ListView<String> list = ListView.of(ui, "Benchmark", items);
        ui.root().add(list.root());
        size(ui, list.root(), 480, 900);
        for (int i = 0; i < 4; i++) {
            ui.update(W, H);
        }
        DrawList hud = ui.layers()[0];
        // warm: every row text shaped once (the atlas holds the glyphs)
        for (double y = 0; y < list.vertical().range(); y += 600) {
            list.scrollTo(0, y);
            ui.update(W, H);
        }
        int frames = 20_000;
        long[] times = new long[frames];
        double speed = 7.3; // panel units a frame: a steady fling
        for (int round = 0; round < 3; round++) {
            double y = 0;
            for (int f = 0; f < frames; f++) {
                y += speed;
                if (y > list.vertical().range()) {
                    y = 0;
                }
                long t0 = System.nanoTime();
                list.scrollTo(0, y);
                ui.update(W, H);
                times[f] = System.nanoTime() - t0;
                hud.clean();
            }
            System.out.println("list 10,000 rows scrolling, round " + round + ": " + stats(times) + "; "
                + list.rowElements() + " row elements, " + ui.store().count() + " elements");
        }
        assertThat(list.rowElements()).isLessThan(60);
    }

    @Test
    void aMillionRowSortedFilteredViewTakesAHundredChangesAFrame() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        ChangeClock clock = ui.store().clock();
        VersionedList<Row> rows = clock.list(4096);
        String[] pool = pool(1_000);
        Row[] values = new Row[1_000_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Row(i, (int) ((i * 2_654_435_761L) & 0xFFFFF), i * 0.001, pool[i % pool.length]);
        }
        rows.addAll(Arrays.asList(values));
        DataView<Row> view = new DataView<>(clock, rows).filter(r -> (r.value() & 3) != 0)
            .sort(Comparator.comparingInt(Row::value));
        ListView<Row> list = new ListView<>(ui, "Sorted", view, Renderers.ofText(Row::name));
        ui.root().add(list.root());
        size(ui, list.root(), 480, 900);
        long tb = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            ui.update(W, H);
        }
        System.out.println("1,000,000-row view built in " + (System.nanoTime() - tb) / 1_000_000 + " ms: "
            + view.size() + " rows shown");
        // replacements prepared beforehand: the frame measures the view and the list, not the test's records
        int frames = 3_000;
        Row[] next = new Row[frames * 100];
        int[] at = new int[frames * 100];
        java.util.Random random = new java.util.Random(5);
        for (int k = 0; k < next.length; k++) {
            at[k] = random.nextInt(values.length);
            next[k] = new Row(at[k], random.nextInt(0x100000), at[k] * 0.001, pool[at[k] % pool.length]);
        }
        long[] times = new long[frames];
        for (int round = 0; round < 3; round++) {
            for (int f = 0; f < frames; f++) {
                long t0 = System.nanoTime();
                int base = f * 100;
                for (int k = 0; k < 100; k++) {
                    rows.set(at[base + k], next[base + k]);
                }
                ui.update(W, H);
                times[f] = System.nanoTime() - t0;
                ui.layers()[0].clean();
            }
            System.out.println("1,000,000-row sorted+filtered view, 100 changes a frame, round " + round + ": "
                + stats(times));
            // the next round puts the old rows back
            for (int k = 0; k < next.length; k++) {
                Row old = rows.get(at[k]);
                rows.set(at[k], next[k]);
                next[k] = old;
            }
            ui.update(W, H);
        }
        assertThat(view.size()).isPositive();
    }

    @Test
    void aTableOfAHundredThousandRowsAndTwentyColumnsScrolls() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        ChangeClock clock = ui.store().clock();
        VersionedList<Row> rows = clock.list();
        String[] pool = pool(1_000);
        Row[] values = new Row[100_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Row(i, (i * 7919) % 100_000, i * 0.01, pool[i % pool.length]);
        }
        rows.addAll(Arrays.asList(values));
        TableView<Row> table = TableView.of(ui, "Benchmark", clock, rows);
        table.add(Column.<Row>ofText("Name", Row::name).width(220));
        for (int c = 1; c < 20; c++) {
            int k = c;
            if (c % 2 == 0) {
                table.add(Column.<Row>ofInt("I" + c, r -> r.value() + k).width(90));
            } else {
                table.add(Column.<Row>ofDouble("D" + c, r -> r.load() * k, 2).width(90));
            }
        }
        table.freeze(1);
        ui.root().add(table.root());
        size(ui, table.root(), 1880, 1000);
        for (int i = 0; i < 4; i++) {
            ui.update(W, H);
        }
        for (double y = 0; y < 30_000; y += 500) {
            table.scrollTo(0, y);
            ui.update(W, H);
        }
        int frames = 10_000;
        long[] times = new long[frames];
        for (int round = 0; round < 3; round++) {
            double y = 0;
            for (int f = 0; f < frames; f++) {
                y = (y + 7.3) % 30_000;
                long t0 = System.nanoTime();
                table.scrollTo(0, y);
                ui.update(W, H);
                times[f] = System.nanoTime() - t0;
                ui.layers()[0].clean();
            }
            System.out.println("table 100,000 x 20 scrolling, round " + round + ": " + stats(times) + "; columns "
                + table.firstShownColumn() + ".." + table.lastShownColumn() + " with cells, " + table.rowElements()
                + " rows, " + ui.store().count() + " elements");
        }
        assertThat(table.rowElements()).isLessThan(60);
    }
}
