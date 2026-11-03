package com.truesim.ui.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.tss.Compilation;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Design 11's target "style hot reload of a 2,000-rule sheet: re-resolve ≤ 5 ms once": compiling a 2,000-rule
 * {@code .tss} sheet, replacing the resolver's sheet and re-resolving a 2,000-element HUD. Beside it, the competing
 * technique for the re-resolve: the plain linear scan without interning, cache or rule index ({@link LinearResolver}).
 * It prints the timings (as the other true-sim benchmarks; the machine decides them) and asserts only what it built.
 * Run with {@code TRUESIM_BENCHMARK=true} (JaCoCo is off then); warmed up past C2 first.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class StyleReloadBenchmark {

    private static final String[] TYPES = {"panel", "label", "image", "button", "slider", "meter"};
    private static final int WARMUP = 60;
    private static final int RUNS = 40;

    /** 500 blocks of four rules each: a base rule, a hover rule, a child rule and a fact rule. */
    static String sheet() {
        StringBuilder sb = new StringBuilder();
        sb.append("$base: #20303a;\n$gap: 4px;\n:root { --accent: #3fa7ff; }\n");
        sb.append("@mixin raised($d: 1) { shadow: 0 ($d * 2px) ($d * 6px) rgba(black, 0.35); }\n");
        for (int i = 0; i < 500; i++) {
            String type = TYPES[i % TYPES.length];
            String style = "s" + (i % 50);
            String id = i % 5 == 0 ? "#item" + (i % 40) : "";
            sb.append(type).append('.').append(style).append(id).append(" {\n")
                .append("  color: rgba(").append(i % 256).append(", 120, 200, 0.9);\n")
                .append("  padding: ($gap * ").append(i % 4).append(") 8px;\n")
                .append("  font-size: ").append(10 + i % 12).append("px;\n");
            if (i % 10 == 0) {
                sb.append("  background: box(var(--accent));\n");
            } else {
                sb.append("  background: box(lighten($base, ").append(i % 20).append("%));\n");
            }
            if (i % 3 == 0) {
                sb.append("  @include raised(").append(1 + i % 3).append(");\n");
            }
            sb.append("  &:hover { background: box(darken($base, 4%)); scale: 1.02; }\n")
                .append("  > label { color: mix($base, white, ").append(i % 100).append("%); }\n")
                .append("  &[kind=k").append(i % 7).append("] { radius: ").append(i % 9).append("px; }\n")
                .append("}\n");
        }
        return sb.toString();
    }

    /** A HUD of 2,000 elements: 40 cards of 49 children of mixed types, style names, ids and states. */
    static List<Element> hud(UiStore store) {
        List<Element> all = new ArrayList<>();
        Element hud = store.create("panel", "hud").style("s0");
        store.root().add(hud);
        all.add(hud);
        for (int c = 0; c < 40; c++) {
            Element card = store.create("panel", "card" + c).style("s" + c % 50);
            hud.add(card);
            all.add(card);
            for (int k = 0; k < 49 && all.size() < 2000; k++) {
                Element e = store.create(TYPES[k % TYPES.length], k % 4 == 0 ? "item" + k % 40 : null);
                if (k % 3 != 0) {
                    e.style("s" + (c * 7 + k) % 50);
                }
                if (k % 11 == 0) {
                    e.state(UiState.HOVER, true);
                }
                card.add(e);
                all.add(e);
            }
        }
        return all;
    }

    private static long gcCount() {
        long n = 0;
        for (java.lang.management.GarbageCollectorMXBean gc
            : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            n += gc.getCollectionCount();
        }
        return n;
    }

    private static double[] stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return new double[] {sorted[0] / 1e6, sorted[sorted.length / 2] / 1e6, sorted[sorted.length * 9 / 10] / 1e6};
    }

    private static String show(String what, long[] nanos) {
        double[] s = stats(nanos);
        return String.format(Locale.ROOT, "%-48s min %.3f ms, median %.3f ms, p90 %.3f ms", what, s[0], s[1], s[2]);
    }

    @Test
    void reloadOfA2000RuleSheetAnd2000Elements() {
        SourceLoader loader = SourceLoader.memory(Map.of("bench.tss", sheet()));
        Compilation first = Tss.compile(loader, "bench.tss");
        assertThat(first.sheet().rules()).hasSize(2000);
        UiStore store = new UiStore();
        List<Element> elements = hud(store);
        assertThat(store.count()).isEqualTo(2001);
        StyleResolver resolver = new StyleResolver(store, first.sheet());
        int[] slots = new int[elements.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = elements.get(i).slot();
        }

        long[] compile = new long[RUNS];
        long[] resolve = new long[RUNS];
        long[] total = new long[RUNS];
        long[] linear = new long[RUNS];
        long sink = 0;
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long allocated = 0;
        long collections = 0;
        for (int run = -WARMUP; run < RUNS; run++) {
            if (run == 0) {
                allocated = threads.getCurrentThreadAllocatedBytes();
                collections = gcCount();
            }
            long t0 = System.nanoTime();
            StyleSheet next = Tss.compile(loader, "bench.tss").sheet();
            long t1 = System.nanoTime();
            resolver.sheet(next);
            for (int slot : slots) {
                sink += resolver.resolve(slot);
            }
            store.clearDirtyQueue();
            long t2 = System.nanoTime();
            if (run >= 0) {
                compile[run] = t1 - t0;
                resolve[run] = t2 - t1;
                total[run] = t2 - t0;
            }
        }
        allocated = (threads.getCurrentThreadAllocatedBytes() - allocated) / RUNS;
        collections = gcCount() - collections;
        // the alternative in a loop of its own, so its garbage does not land in the timings above
        StyleSheet sheet = first.sheet();
        for (int run = -WARMUP; run < RUNS; run++) {
            long t0 = System.nanoTime();
            LinearResolver plain = new LinearResolver(store, sheet);
            for (int slot : slots) {
                sink += plain.resolve(slot).length;
            }
            long t1 = System.nanoTime();
            if (run >= 0) {
                linear[run] = t1 - t0;
            }
        }
        System.out.println("style reload, 2,000 rules, 2,000 elements (" + resolver.cachedStyles()
            + " distinct styles), sink " + (sink & 1));
        System.out.println(show("compile .tss", compile));
        System.out.println(show("re-resolve: interned, cached, indexed", resolve));
        System.out.println(show("re-resolve: linear scan (the alternative)", linear));
        System.out.println(show("reload total (compile + re-resolve)", total));
        System.out.printf(Locale.ROOT, "allocated per reload %.1f MB, %d collections in %d reloads%n", allocated / 1e6,
            collections, RUNS);
    }
}
