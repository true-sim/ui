package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DirtyRanges;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Section 11's HUD targets on the CPU: 500 elements and about 5,000 glyphs, idle (nothing changed: 0.05 ms) and with
 * 10% of the elements changing each frame (50 counters: 0.3 ms). Runs with {@code TRUESIM_BENCHMARK=true} (JaCoCo
 * off), through the real text path (HarfBuzz, msdfgen). Prints its timings; asserts only what it built.
 */
class HudBenchmarkTest {

    static final int CARDS = 36;
    static final int ROWS = 4;

    /** The benchmark HUD: 36 cards of a title and four caption/value rows: 505 elements. */
    static Element[] build(Ui ui) {
        Element hud = ui.panel("hud");
        ui.root().add(hud);
        Element[] values = new Element[CARDS * ROWS];
        for (int c = 0; c < CARDS; c++) {
            Element card = ui.panel("card");
            hud.add(card);
            card.add(ui.label("title", String.format(Locale.ROOT, "Squadron %02d status board, sector", c)));
            for (int r = 0; r < ROWS; r++) {
                Element row = ui.panel("row");
                card.add(row);
                row.add(ui.label("caption", "Hull integrity reading " + r));
                Element value = ui.label("value", "100.0 %");
                row.add(value);
                values[c * ROWS + r] = value;
            }
        }
        return values;
    }

    @Test
    void hudFramesMeetTheCpuTargets() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = new Ui(Tss.compile(SourceLoader.classpath(HudBenchmarkTest.class.getClassLoader(), ""), "hud-bench.tss").sheet(), UiTest.nativeText());
        Element[] values = build(ui);
        UiTest.settle(ui, 3840, 2160);
        DrawList list = ui.layers()[0];
        int glyphs = 0;
        for (int i = 0; i < list.size(); i++) {
            glyphs += Primitive.kind(list.header(i)) == Primitive.GLYPH ? 1 : 0;
        }
        System.out.println("HUD: " + ui.store().count() + " elements, " + glyphs + " glyphs, " + list.size()
            + " primitives");
        assertThat(ui.store().count()).isGreaterThanOrEqualTo(500);

        // pre-warm the digits so every counter text is in the atlas
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            ui.text(values[i % values.length], counter(text, i));
        }
        UiTest.settle(ui, 3840, 2160);

        int frames = 20_000;
        long[] idle = new long[frames];
        long[] changing = new long[frames];
        int[] counter = {0};
        for (int round = 0; round < 3; round++) {
            for (int f = 0; f < frames; f++) {
                long t0 = System.nanoTime();
                ui.update(3840, 2160);
                idle[f] = System.nanoTime() - t0;
                list.clean();
            }
            for (int f = 0; f < frames; f++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < 50; k++) {
                    int n = counter[0]++;
                    ui.text(values[(n * 7) % values.length], counter(text, n % 1000));
                }
                ui.update(3840, 2160);
                changing[f] = System.nanoTime() - t0;
                list.clean();
            }
            System.out.println("round " + round + ": idle " + stats(idle) + " | 10% changing " + stats(changing));
        }

        // Four cards fading (an animated opacity) and two counters far apart changing: time and bytes uploaded.
        long[] fading = new long[frames];
        long fadeBytes = 0;
        long farBytes = 0;
        int[] cards = new int[4];
        for (int c = 0; c < 4; c++) {
            cards[c] = ui.store().parent(ui.store().parent(values[c * 9 * ROWS].slot()));
        }
        for (int round = 0; round < 3; round++) {
            fadeBytes = 0;
            farBytes = 0;
            for (int f = 0; f < frames; f++) {
                long t0 = System.nanoTime();
                for (int c = 0; c < 4; c++) {
                    ui.visuals().opacity(cards[c], 0.5f + 0.4f * (float) Math.sin(f * 0.05 + c));
                }
                ui.update(3840, 2160);
                fading[f] = System.nanoTime() - t0;
                fadeBytes += uploadBytes(list);
                list.clean();
            }
            for (int f = 0; f < 1000; f++) {
                ui.text(values[0], counter(text, f % 1000));
                ui.text(values[values.length - 1], counter(text, (f + 500) % 1000));
                ui.update(3840, 2160);
                farBytes += uploadBytes(list);
                list.clean();
            }
            System.out.println("round " + round + ": 4 cards fading " + stats(fading) + ", " + fadeBytes / frames
                + " bytes a frame | 2 counters far apart: " + farBytes / 1000 + " bytes a frame");
        }
    }

    /** The bytes a renderer uploads for {@code list}'s changes: its dirty ranges, transform entries and clips. */
    static long uploadBytes(DrawList list) {
        long bytes = 0;
        DirtyRanges r = list.dirtyRanges();
        for (int i = 0; i < r.count(); i++) {
            bytes += (long) (r.to(i) - r.from(i)) * Primitive.BYTES;
        }
        r = list.transformRanges();
        for (int i = 0; i < r.count(); i++) {
            bytes += (long) (r.to(i) - r.from(i)) * DrawList.TRANSFORM_FLOATS * 4;
        }
        return bytes + (list.clipsDirty() ? (long) list.clipCount() * DrawList.CLIP_FLOATS * 4 : 0);
    }

    private static StringBuilder counter(StringBuilder text, int n) {
        text.setLength(0);
        text.append(n / 10).append('.').append(n % 10).append(" %");
        return text;
    }

    private static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "median %.4f ms, p99 %.4f ms", sorted[sorted.length / 2] / 1e6,
            sorted[sorted.length * 99 / 100] / 1e6);
    }
}
