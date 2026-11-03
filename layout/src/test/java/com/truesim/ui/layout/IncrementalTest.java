package com.truesim.ui.layout;

import static com.truesim.ui.layout.FlexLayoutTest.assertRect;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Incremental relayout equals full relayout; only what changed is measured and reported. */
class IncrementalTest {

    @Test
    void anIdleUpdateDoesNothing() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW);
        for (int i = 0; i < 5; i++) {
            ui.text(ui.root(), i + 1);
        }
        ui.run();
        assertThat(ui.layout.changedCount()).isEqualTo(6);
        ui.measures = 0;
        ui.run();
        assertThat(ui.layout.changedCount()).isZero();
        assertThat(ui.measures).isZero();
    }

    @Test
    void aChangedTextIsMeasuredAloneAndMovesItsFollowers() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int[] t = new int[5];
        for (int i = 0; i < 5; i++) {
            t[i] = ui.text(ui.root(), 2);
        }
        ui.run();
        ui.measures = 0;
        ui.setText(t[2], 4);
        ui.run();
        assertThat(ui.measures).isBetween(1, 3);
        assertThat(changed(ui)).containsExactlyInAnyOrder(t[2], t[3], t[4]);
        assertRect(ui, t[4], 80, 0, 16, 16);
    }

    @Test
    void aFixedSizePanelStopsTheDirtyBubble() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.FLEX).direction(r, Direction.ROW);
        int panel = ui.node(r);
        ui.layout.width(panel, 100, Unit.PX).height(panel, 50, Unit.PX).padding(panel, 3, 3, 3, 3);
        int inside = ui.text(panel, 2);
        int after = ui.text(r, 2);
        int deep = ui.node(panel);
        int deeper = ui.text(deep, 1);
        ui.run();
        ui.measures = 0;
        ui.setText(inside, 20);
        ui.run();
        // only the text itself is measured again: the panel's size cannot change, so its parent is not laid out;
        // the text grows to two lines and moves its sibling
        assertThat(ui.measures).isEqualTo(1);
        assertThat(changed(ui)).containsExactlyInAnyOrder(inside, deep, deeper);
        assertRect(ui, inside, 3, 3, 94, 32);
        assertRect(ui, deeper, 3, 35, 94, 16);
        assertRect(ui, after, 100, 0, 16, 300);
        // a change of the panel's own size goes up
        ui.layout.width(panel, 120, Unit.PX);
        ui.run();
        assertRect(ui, after, 120, 0, 16, 300);
        // two changes under one boundary, and one above it, in one update
        ui.setText(inside, 1);
        ui.setText(deeper, 3);
        ui.setText(after, 3);
        ui.run();
        assertRect(ui, deeper, 3, 19, 114, 16);
        assertRect(ui, after, 120, 0, 24, 300);
    }

    @Test
    void movingAndRemovingElementsRelaysTheirParents() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.FLEX).direction(r, Direction.ROW).alignItems(r, Align.START);
        int left = ui.node(r);
        ui.layout.width(left, 100, Unit.PX).height(left, 100, Unit.PX);
        int right = ui.node(r);
        ui.layout.width(right, 100, Unit.PX).height(right, 100, Unit.PX);
        int a = ui.text(left, 2);
        int b = ui.text(left, 3);
        ui.run();
        ui.store.append(right, a);
        ui.run();
        assertRect(ui, a, 100, 0, 100, 16);
        assertRect(ui, b, 0, 0, 100, 16);
        ui.store.destroy(b);
        int detached = ui.store.create("panel", null).slot();
        ui.layout.width(detached, 5, Unit.PX);
        ui.run();
        assertRect(ui, a, 100, 0, 100, 16);
        ui.store.append(left, detached);
        ui.run();
        assertRect(ui, detached, 0, 0, 5, 0);
    }

    @Test
    void incrementalEqualsFullOnRandomTrees() {
        for (int seed = 0; seed < 40; seed++) {
            TestUi inc = new TestUi();
            TestUi full = new TestUi();
            Random ri = new Random(seed);
            Random rf = new Random(seed);
            List<Integer> si = build(inc, ri);
            List<Integer> sf = build(full, rf);
            for (int frame = 0; frame < 12; frame++) {
                float w = frame % 5 == 4 ? 500 : 400;
                inc.run(w, 300);
                full.store.markAll(UiStore.DIRTY_LAYOUT);
                full.run(w, 300);
                for (int s = 0; s < inc.store.highWater(); s++) {
                    if (inc.store.alive(s) && (s == inc.root() || attached(inc, s))) {
                        assertThat(inc.rect(s)).as("seed %d frame %d slot %d", seed, frame, s)
                            .usingComparatorWithPrecision(1e-3f).containsExactly(full.rect(s));
                    }
                }
                mutate(inc, ri, si);
                mutate(full, rf, sf);
            }
        }
    }

    /** In the tree and not under a hidden element (whose subtree is not laid out). */
    private static boolean attached(TestUi ui, int s) {
        for (int p = ui.store.parent(s); p != ui.root(); p = ui.store.parent(p)) {
            if (p == UiStore.NONE || ui.layout.hidden[p]) {
                return false;
            }
        }
        return true;
    }

    private static int[] changed(TestUi ui) {
        int[] c = new int[ui.layout.changedCount()];
        System.arraycopy(ui.layout.changedSlots(), 0, c, 0, c.length);
        return c;
    }

    /** A random tree of every layout kind: returns the slots made. */
    static List<Integer> build(TestUi ui, Random r) {
        List<Integer> slots = new ArrayList<>();
        ui.layout.kind(ui.root(), LayoutKind.ANCHORS);
        slots.add(ui.root());
        int n = 30 + r.nextInt(40);
        for (int i = 0; i < n; i++) {
            int parent = slots.get(r.nextInt(slots.size()));
            if (ui.layout.measured[parent]) {
                parent = ui.root();
            }
            int s;
            if (r.nextInt(3) == 0) {
                s = r.nextBoolean() ? ui.text(parent, 1 + r.nextInt(30)) : ui.box(parent, r.nextInt(60), r.nextInt(40));
            } else {
                s = ui.node(parent);
                ui.layout.kind(s, LayoutKind.values()[r.nextInt(LayoutKind.values().length)]);
            }
            randomize(ui, r, s);
            slots.add(s);
        }
        return slots;
    }

    private static void randomize(TestUi ui, Random r, int s) {
        Layout l = ui.layout;
        l.direction(s, Direction.values()[r.nextInt(4)])
            .wrap(s, Wrap.values()[r.nextInt(3)])
            .justifyContent(s, Justify.values()[r.nextInt(6)])
            .alignItems(s, Align.values()[1 + r.nextInt(5)])
            .alignContent(s, AlignContent.values()[r.nextInt(7)])
            .justifyItems(s, Align.values()[1 + r.nextInt(5)])
            .alignSelf(s, Align.values()[r.nextInt(6)])
            .justifySelf(s, Align.values()[r.nextInt(6)])
            .gap(s, r.nextInt(3) * 4, r.nextInt(3) * 4)
            .padding(s, r.nextInt(4), r.nextInt(4), r.nextInt(4), r.nextInt(4))
            .margin(s, r.nextInt(3), r.nextInt(3), r.nextInt(3), r.nextInt(3))
            .grow(s, r.nextInt(3))
            .shrink(s, r.nextInt(2))
            .region(s, BorderRegion.values()[r.nextInt(5)])
            .anchors(s, r.nextInt(3) * 0.5f, r.nextInt(3) * 0.5f, 0.5f + r.nextInt(2) * 0.5f, 0.5f + r.nextInt(2) * 0.5f)
            .offsets(s, r.nextInt(20), r.nextInt(20), r.nextInt(40) - 20, r.nextInt(40) - 20)
            .growDirection(s, GrowDirection.values()[r.nextInt(3)], GrowDirection.values()[r.nextInt(3)])
            .gridColumnPlacement(s, r.nextInt(3), 1 + r.nextInt(2))
            .gridRowPlacement(s, r.nextInt(3), 1 + r.nextInt(2));
        if (r.nextInt(3) == 0) {
            l.width(s, 20 + r.nextInt(150), Unit.PX);
        } else if (r.nextInt(4) == 0) {
            l.width(s, 10 + r.nextInt(90), Unit.PERCENT);
        }
        if (r.nextInt(3) == 0) {
            l.height(s, 10 + r.nextInt(100), Unit.PX);
        } else if (r.nextInt(5) == 0) {
            l.height(s, 1 + r.nextInt(5), Unit.EM);
        }
        if (r.nextInt(5) == 0) {
            l.minWidth(s, r.nextInt(80), Unit.PX);
        }
        if (r.nextInt(5) == 0) {
            l.maxHeight(s, 20 + r.nextInt(80), Unit.PX);
        }
        if (r.nextInt(6) == 0) {
            l.basis(s, r.nextInt(100), Unit.PX);
        }
        if (r.nextInt(8) == 0) {
            l.aspectRatio(s, 0.5f + r.nextInt(3));
        }
        if (r.nextInt(8) == 0) {
            l.textDirection(s, TextDirection.values()[r.nextInt(3)]);
        }
        int cols = r.nextInt(4);
        l.gridColumns(s, cols);
        for (int i = 0; i < cols; i++) {
            switch (r.nextInt(4)) {
                case 0 -> l.gridColumn(s, i, Unit.PX, 30 + r.nextInt(50), Unit.PX, 30 + r.nextInt(50));
                case 1 -> l.gridColumn(s, i, Unit.AUTO, 0, Unit.FR, 1 + r.nextInt(3));
                case 2 -> l.gridColumn(s, i, Unit.PX, 20, Unit.FR, 1);
                default -> l.gridColumn(s, i, Unit.AUTO, 0, Unit.AUTO, 0);
            }
        }
        l.gridRows(s, r.nextInt(2));
    }

    private static void mutate(TestUi ui, Random r, List<Integer> slots) {
        int changes = 1 + r.nextInt(4);
        for (int i = 0; i < changes; i++) {
            int s = slots.get(r.nextInt(slots.size()));
            if (!ui.store.alive(s)) {
                continue;
            }
            switch (r.nextInt(8)) {
                case 0, 1 -> {
                    if (ui.layout.measured[s] && ui.boxW[s] < 0) {
                        ui.setText(s, 1 + r.nextInt(30));
                    }
                }
                case 2 -> ui.layout.width(s, 10 + r.nextInt(150), r.nextBoolean() ? Unit.PX : Unit.AUTO);
                case 3 -> ui.layout.visible(s, r.nextInt(4) != 0);
                case 4 -> randomize(ui, r, s);
                case 5 -> {
                    int to = slots.get(r.nextInt(slots.size()));
                    if (s != ui.root() && ui.store.alive(to) && !ui.layout.measured[to] && !under(ui.store, to, s)) {
                        ui.store.append(to, s);
                    }
                }
                case 6 -> {
                    if (s != ui.root() && r.nextInt(3) == 0) {
                        ui.store.destroy(s);
                    } else if (!ui.layout.measured[s]) {
                        slots.add(ui.text(s, 1 + r.nextInt(10)));
                    }
                }
                default -> ui.layout.textDirection(s, TextDirection.values()[r.nextInt(3)]);
            }
        }
    }

    private static boolean under(UiStore store, int s, int ancestor) {
        for (int p = s; p != UiStore.NONE; p = store.parent(p)) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }
}
