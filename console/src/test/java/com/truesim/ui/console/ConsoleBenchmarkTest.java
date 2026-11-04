package com.truesim.ui.console;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The log flood of stage 14 (100,000 lines) on the CPU, with the console open over the default theme and the real
 * text path: (a) 100,000 lines logged from another thread into the inbox, drained, filtered and shown in one frame;
 * (b) 1,000 lines a frame for 100 frames, each frame's drain, filter, rebinding of the visible rows and recording
 * (the budget: section 11's 0.3 ms for a changing HUD); (c) appending alone, per line. Runs with
 * {@code TRUESIM_BENCHMARK=true} (JaCoCo off); prints median and p99.
 */
class ConsoleBenchmarkTest {

    private static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "median %.4f ms, p99 %.4f ms", sorted[sorted.length / 2] / 1e6,
            sorted[sorted.length * 99 / 100] / 1e6);
    }

    @Test
    void aFloodOfAHundredThousandLines() throws InterruptedException {
        assumeThat(System.getenv("TRUESIM_BENCHMARK")).isEqualTo("true");
        Ui ui = ConsoleGalleryTest.ui("default");
        LogRing ring = new LogRing(10_000);
        Console console = new Console(ui, new ConsoleShell(ring), ring,
            new ConsoleHistory("bench", HistoryStore.memory()));
        LogInbox inbox = new LogInbox(100_000);
        console.inbox(inbox);
        console.open(true);
        String[] messages = new String[64];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = "Frame graph pass " + i + " recorded 1,204 draws into 3 render targets in 0.42 ms";
        }
        String[] loggers = {"com.truesim.render.Graph", "com.truesim.game.World", "com.truesim.net.Session"};
        UiInput input = new UiInput(4);
        long[] nanos = {1};
        Runnable frame = () -> {
            input.beginFrame();
            ui.update(1920, 1080, input, nanos[0] += 16_000_000L);
        };
        for (int i = 0; i < 60; i++) {
            frame.run();
        }
        // warm up the flood path
        int[] s = {0};
        Runnable flood1000 = () -> {
            for (int k = 0; k < 1000; k++) {
                int n = s[0]++;
                inbox.log(n % 5, loggers[n % 3], messages[n % messages.length], n % 4 == 0 ? "Graph.java:120" : null,
                    n);
            }
            frame.run();
        };
        for (int i = 0; i < 2000; i++) {
            flood1000.run();
        }
        // (a) 100,000 lines from another thread, shown in one frame
        long[] oneFrame = new long[20];
        for (int r = 0; r < oneFrame.length; r++) {
            Thread logger = Thread.ofPlatform().start(() -> {
                for (int k = 0; k < 100_000; k++) {
                    int n = s[0]++;
                    inbox.log(n % 5, loggers[n % 3], messages[n % messages.length], null, n);
                }
            });
            logger.join();
            long t0 = System.nanoTime();
            frame.run();
            oneFrame[r] = System.nanoTime() - t0;
        }
        System.out.println("console flood: 100,000 lines drained and shown in one frame: " + stats(oneFrame));
        // (b) 1,000 lines a frame
        long[] perFrame = new long[2000];
        long[][] phases = new long[4][perFrame.length];
        ui.stats().enabled(true);
        for (int f = 0; f < perFrame.length; f++) {
            for (int k = 0; k < 1000; k++) {
                int n = s[0]++;
                inbox.log(n % 5, loggers[n % 3], messages[n % messages.length], null, n);
            }
            long t0 = System.nanoTime();
            frame.run();
            perFrame[f] = System.nanoTime() - t0;
            phases[0][f] = ui.stats().listenerNanos();
            phases[1][f] = ui.stats().styleNanos();
            phases[2][f] = ui.stats().layoutNanos();
            phases[3][f] = ui.stats().recordNanos();
        }
        ui.stats().enabled(false);
        System.out.println("console flood: 1,000 lines a frame (drain, filter, rows, record): " + stats(perFrame));
        System.out.println("console flood: phases: frame code (drain, rows bound) " + stats(phases[0]) + "; styles "
            + stats(phases[1]) + "; layout " + stats(phases[2]) + "; record " + stats(phases[3]));
        // (c) appending alone
        LogRing alone = new LogRing(10_000);
        long[] append = new long[200];
        for (int r = 0; r < append.length; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < 10_000; k++) {
                int n = s[0]++;
                alone.log(n % 5, loggers[n % 3], messages[n % messages.length], null, n);
            }
            append[r] = (System.nanoTime() - t0) / 10;
        }
        System.out.println("console flood: appending 1,000 lines to the ring: " + stats(append));
    }
}
