package com.truesim.ui.scene;

import com.truesim.scene.core.Mobility;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/**
 * Steady-state frames with scene picking allocate nothing (without escape analysis): pointers moving over interactive
 * nodes (bounds and meshes), a world panel and a camera view, pressing and dragging a node, an XR ray sweeping.
 */
class SceneAllocationTest {

    private static final int WARMUP = 4_000;
    private static final int MEASURED = 1_000;
    private static final int ROUNDS = 6;

    @Test
    void pickingFramesAllocateNothing() {
        SceneRig r = new SceneRig(Tss.defaultTheme(), null, 800, 600);
        PickMesh mesh = ScenePickingBenchmarkTest.sphere();
        for (int i = 0; i < 20; i++) {
            long node = r.box(-5 + (i % 5) * 2.5, -2 + (i / 5) * 1.5, -12, 0.6, i % 2 == 0 ? Mobility.DYNAMIC
                : Mobility.STATIC);
            Interactive c = Interactive.of(e -> { }).tooltip(null);
            r.picking.add(node, i % 3 == 0 ? c.mesh(mesh) : c);
        }
        Ui panelUi = new Ui(Tss.defaultTheme(), null);
        WorldPanel panel = new WorldPanel(panelUi, 200, 100).pixelsPerMeter(100).place(3, 2, -8, 0, 0, 0, 1, 1);
        Element button = panelUi.panel(null);
        panelUi.root().add(button);
        SceneRig.place(panelUi, button, 0, 0, 100, 100);
        panelUi.on(button, EventType.CLICK, e -> { });
        r.picking.addPanel(panel);
        FakeCameraSource source = new FakeCameraSource(r.ui.textures(), 128, 128);
        CameraView minimap = new CameraView(r.ui, source, r.picking, null);
        r.ui.root().add(minimap.element());
        SceneRig.place(r.ui, minimap.element(), 600, 20, 160, 120);
        double h = Math.sqrt(0.5);
        minimap.view().orthographic(30, 1, 500).pose(0, 50, -12, -h, 0, 0, h);
        r.frames(4);
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++;
            int k = i % 60;
            float x = 100 + k * 10;
            float y = 120 + (i / 60 % 6) * 60;
            int buttons = i % 37 < 8 ? Pointer.PRIMARY : 0; // presses, drags, releases now and then
            r.pointers.mouse(x, y, buttons);
            double sweep = (i % 50) / 50.0 - 0.5;
            r.ray(9, 0.2, 0, 0, sweep, 0.1, -1, 0);
            r.frame();
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
    }
}
