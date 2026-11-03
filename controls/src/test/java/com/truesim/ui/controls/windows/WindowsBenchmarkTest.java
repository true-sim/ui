package com.truesim.ui.controls.windows;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiFrameHook;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * CPU per frame with 50 floating windows and a dock space of 30 panels (6 panes of 5 tabs in nested splits), idle and
 * while a window is dragged (targets: idle 0.05 ms, dragging 0.3 ms). Runs with {@code TRUESIM_BENCHMARK=true}
 * (JaCoCo off).
 */
class WindowsBenchmarkTest {

    private static final int FRAMES = 4_000;

    @Test
    void fiftyWindowsAndThirtyDockedPanels() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Ui ui = new Ui(Fx.theme(), Fx.nativeText());
        Overlays overlays = new Overlays(ui);
        WindowManager windows = new WindowManager(overlays);
        DockSpace dock = new DockSpace(overlays, windows);
        ui.root().add(dock.root());
        ui.addFrameHook(new UiFrameHook() {
            @Override
            public void beforeLayout(Ui frame) {
                frame.layout().kind(frame.store().rootSlot(), LayoutKind.FLEX);
                frame.layout().grow(dock.root().slot(), 1).width(dock.root().slot(), 100, Unit.PERCENT);
            }
        });
        StringBuilder layout = new StringBuilder("dock split(row; 0.3 0.4 0.3;");
        for (int column = 0; column < 3; column++) {
            layout.append(" split(column; 0.5 0.5;");
            for (int pane = 0; pane < 2; pane++) {
                layout.append(" tabs(0;");
                for (int tab = 0; tab < 5; tab++) {
                    String id = "p" + column + pane + tab;
                    DockPanel p = dock.panel(id, "Panel " + id);
                    p.content().add(ui.label(null, "content of " + id));
                    layout.append(' ').append(id);
                }
                layout.append(')');
            }
            layout.append(')');
        }
        layout.append(")\n");
        dock.restore(DockLayout.parse(layout.toString()));
        for (int i = 0; i < 50; i++) {
            FloatingWindow w = windows.create("w" + i, "Window " + i)
                .bounds(20 + (i % 10) * 120, 20 + (i / 10) * 130, 220, 160);
            w.content().add(ui.label(null, "window " + i)).add(ui.label("caption", "a caption line"));
            w.open();
        }
        UiInput input = new UiInput(32);
        SyntheticInput in = new SyntheticInput();
        long[] now = {1_000_000_000L};
        Runnable frame = () -> {
            input.beginFrame();
            in.poll(input);
            ui.update(1920, 1080, input, now[0]);
            now[0] += 16_666_667L;
        };
        in.mouse(1900, 1060, 0);
        for (int i = 0; i < 200; i++) {
            frame.run();
        }
        double[] idle = measure(frame, FRAMES);

        FloatingWindow top = windows.active();
        float x0 = ui.layout().x(top.titleBar().slot()) + 20;
        float y0 = ui.layout().y(top.titleBar().slot()) + ui.layout().height(top.titleBar().slot()) / 2;
        in.mouse(x0, y0, Pointer.PRIMARY);
        frame.run();
        in.mouse(x0 + 20, y0 + 10, Pointer.PRIMARY);
        frame.run();
        int[] step = {0};
        Runnable drag = () -> {
            int i = step[0]++ % 200;
            float t = i < 100 ? i : 200 - i;
            in.mouse(x0 + t * 7 - 300, y0 + t * 3, Pointer.PRIMARY);
            frame.run();
        };
        for (int i = 0; i < 2_000; i++) {
            drag.run();
        }
        double[] dragging = measure(drag, FRAMES);
        System.out.printf(Locale.ROOT, "windows benchmark: idle median %.4f ms p99 %.4f ms; dragging a window median "
            + "%.4f ms p99 %.4f ms (50 windows, 30 docked panels, %d elements)%n", idle[0], idle[1], dragging[0],
            dragging[1], ui.store().count());
    }

    private static double[] measure(Runnable frame, int frames) {
        for (int i = 0; i < frames; i++) {
            frame.run(); // warm-up
        }
        long[] times = new long[frames];
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            frame.run();
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return new double[] {times[frames / 2] / 1e6, times[frames * 99 / 100] / 1e6};
    }
}
