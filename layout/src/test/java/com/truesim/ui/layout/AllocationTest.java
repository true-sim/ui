package com.truesim.ui.layout;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiStore;
import org.junit.jupiter.api.Test;

/** Steady-state updates allocate nothing: idle, incremental and full relayout. */
class AllocationTest {

    /** A HUD: anchored panels of title, label-value-bar rows, and a grid; every kind of layout once. */
    static final class Hud {
        final TestUi ui = new TestUi();
        final int[] values;
        final int[] bars;
        int frame;

        Hud(int panels) {
            Layout l = ui.layout;
            int root = ui.root();
            l.kind(root, LayoutKind.ANCHORS).snapping(true);
            values = new int[panels * 3];
            bars = new int[panels * 3];
            for (int p = 0; p < panels; p++) {
                int panel = ui.node(root);
                boolean right = p % 3 == 2;
                float x = 10 + (p * 37) % 1500;
                float y = 10 + (p * 53) % 900;
                if (right) {
                    l.anchors(panel, 1, 1, 1, 1).offsets(panel, -x, -y, -x, -y)
                        .growDirection(panel, GrowDirection.BEGIN, GrowDirection.BEGIN);
                } else {
                    l.offsets(panel, x, y, x, y);
                }
                l.padding(panel, 6, 6, 6, 6).gap(panel, 4, 0);
                ui.text(panel, 8 + p % 5);
                for (int r = 0; r < 3; r++) {
                    int row = ui.node(panel);
                    l.kind(row, LayoutKind.FLEX).direction(row, Direction.ROW).gap(row, 0, 6)
                        .alignItems(row, Align.CENTER);
                    int label = ui.text(row, 5 + r);
                    l.shrink(label, 0);
                    values[p * 3 + r] = ui.text(row, 3);
                    l.shrink(values[p * 3 + r], 0);
                    int bar = ui.node(row);
                    l.width(bar, 40, Unit.PX).height(bar, 8, Unit.PX);
                    bars[p * 3 + r] = bar;
                }
            }
            int grid = ui.node(root);
            l.anchors(grid, 0, 1, 0, 1).offsets(grid, 10, -60, 300, -10).kind(grid, LayoutKind.GRID).gridColumns(grid, 3)
                .gridColumn(grid, 0, Unit.PX, 40, Unit.PX, 40).gridColumn(grid, 1, Unit.AUTO, 0, Unit.FR, 1)
                .gridColumn(grid, 2, Unit.AUTO, 0, Unit.AUTO, 0);
            for (int i = 0; i < 6; i++) {
                ui.text(grid, 2 + i);
            }
            int border = ui.node(root);
            l.anchors(border, 1, 0, 1, 0).offsets(border, -200, 10, -10, 110).kind(border, LayoutKind.BORDER);
            l.region(ui.text(border, 6), BorderRegion.NORTH);
            l.region(ui.box(border, 20, 20), BorderRegion.WEST);
            ui.box(border, 10, 10);
            int overlay = ui.node(root);
            l.anchors(overlay, 0.5f, 0, 0.5f, 0).growDirection(overlay, GrowDirection.BOTH, GrowDirection.END)
                .kind(overlay, LayoutKind.OVERLAY);
            ui.box(overlay, 100, 30);
            l.justifySelf(ui.text(overlay, 4), Align.CENTER);
            ui.run(1920, 1080);
        }

        /** One frame with a tenth of the values and bars changing. */
        void incremental() {
            frame++;
            int n = values.length;
            for (int k = 0; k < n / 10; k++) {
                int i = (frame * 7 + k * 10) % n;
                ui.setText(values[i], 2 + (frame + k) % 4);
                ui.layout.width(bars[(i + 3) % n], 20 + (frame + k) % 40, Unit.PX);
            }
            ui.run(1920, 1080);
        }

        void full() {
            ui.store.markAll(UiStore.DIRTY_LAYOUT);
            ui.run(1920, 1080);
        }

        void idle() {
            ui.run(1920, 1080);
        }
    }

    @Test
    void idleUpdatesAllocateNothing() {
        Hud hud = new Hud(36);
        hud.ui.measures = 0;
        assertAllocationFree(hud::idle);
        assertThat(hud.ui.measures).isZero();
        assertThat(hud.ui.layout.changedCount()).isZero();
    }

    @Test
    void incrementalUpdatesAllocateNothing() {
        Hud hud = new Hud(36);
        assertAllocationFree(hud::incremental);
        assertThat(hud.ui.layout.changedCount()).isPositive();
    }

    @Test
    void fullRelayoutAllocatesNothing() {
        Hud hud = new Hud(36);
        assertAllocationFree(hud::full, 5_000, 2_000, 10);
    }

    @Test
    void incrementalEqualsFullOnTheHud() {
        Hud a = new Hud(20);
        Hud b = new Hud(20);
        for (int f = 0; f < 30; f++) {
            a.incremental();
            b.frame++;
            int n = b.values.length;
            for (int k = 0; k < n / 10; k++) {
                int i = (b.frame * 7 + k * 10) % n;
                b.ui.chars[b.values[i]] = 2 + (b.frame + k) % 4;
                b.ui.layout.width(b.bars[(i + 3) % n], 20 + (b.frame + k) % 40, Unit.PX);
            }
            b.full();
            for (int s = 0; s < a.ui.store.highWater(); s++) {
                assertThat(a.ui.rect(s)).as("frame %d slot %d", f, s).containsExactly(b.ui.rect(s));
            }
        }
    }
}
