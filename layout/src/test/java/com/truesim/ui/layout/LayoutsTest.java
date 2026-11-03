package com.truesim.ui.layout;

import static com.truesim.ui.layout.FlexLayoutTest.assertRect;

import org.junit.jupiter.api.Test;

/** Fixtures for grid, border, anchors, overlay and absolute layouts. */
class LayoutsTest {

    @Test
    void gridWithFixedAndFractionTracks() {
        TestUi ui = new TestUi();
        int g = ui.root();
        ui.layout.kind(g, LayoutKind.GRID).gridColumns(g, 3).gap(g, 5, 10).alignItems(g, Align.START)
            .gridColumn(g, 0, Unit.PX, 100, Unit.PX, 100)
            .gridColumn(g, 1, Unit.AUTO, 0, Unit.FR, 1)
            .gridColumn(g, 2, Unit.AUTO, 0, Unit.FR, 2);
        int[] cells = new int[6];
        for (int i = 0; i < 6; i++) {
            cells[i] = ui.box(g, 10, 20 + i);
        }
        ui.run();
        // 400 - 100 - 2 gaps = 280 shared 1:2
        assertRect(ui, cells[0], 0, 0, 100, 20);
        assertRect(ui, cells[1], 110, 0, 93.333f, 21);
        assertRect(ui, cells[2], 213.333f, 0, 186.667f, 22);
        // the two auto rows (22 and 25) stretch over the height: 146 and 149
        assertRect(ui, cells[3], 0, 151, 100, 23);
        assertRect(ui, cells[5], 213.333f, 151, 186.667f, 25);
    }

    @Test
    void gridAutoTracksSizeToContentAndSpansSpread() {
        TestUi ui = new TestUi();
        int g = ui.root();
        ui.layout.kind(g, LayoutKind.GRID).gridColumns(g, 3).justifyItems(g, Align.START).alignItems(g, Align.START);
        int a = ui.text(g, 4);
        int b = ui.text(g, 6);
        int c = ui.text(g, 2);
        int wide = ui.text(g, 15);
        ui.layout.gridColumnPlacement(wide, 1, 2);
        ui.run();
        // columns 32 and 48 get 20 more each (the spanning 120 needs 40 more over two auto tracks), then 16; then
        // all three stretch by 88 over the leftover 264, and the two rows by 134
        assertRect(ui, a, 0, 0, 32, 16);
        assertRect(ui, b, 140, 0, 48, 16);
        assertRect(ui, c, 296, 0, 16, 16);
        assertRect(ui, wide, 0, 150, 120, 16);
    }

    @Test
    void gridMinMaxTracksAndExplicitPlacement() {
        TestUi ui = new TestUi();
        int g = ui.root();
        ui.layout.kind(g, LayoutKind.GRID).gridColumns(g, 2).gridRows(g, 2)
            .gridColumn(g, 0, Unit.PX, 50, Unit.PX, 150)
            .gridColumn(g, 1, Unit.PX, 300, Unit.FR, 1)
            .gridRow(g, 0, Unit.PX, 40, Unit.PX, 40)
            .gridRow(g, 1, Unit.AUTO, 0, Unit.FR, 1);
        int a = ui.box(g, 10, 10);
        int b = ui.box(g, 10, 10);
        int c = ui.box(g, 10, 10);
        ui.layout.gridColumnPlacement(a, 2, 1).gridRowPlacement(a, 2, 1);
        ui.layout.gridRowPlacement(b, 1, 1);
        ui.layout.gridColumnPlacement(c, 1, 1);
        ui.run();
        // column 0 grows from 50 towards 150 but only 50 is left over 300 for the fraction; row 1 takes 260
        assertRect(ui, a, 100, 40, 300, 260);
        assertRect(ui, b, 0, 0, 100, 40);
        assertRect(ui, c, 0, 40, 100, 260);
    }

    @Test
    void gridImplicitRowsAndColumns() {
        TestUi ui = new TestUi();
        int g = ui.root();
        ui.layout.kind(g, LayoutKind.GRID).alignItems(g, Align.START).justifyItems(g, Align.END);
        int a = ui.box(g, 30, 10);
        int b = ui.box(g, 20, 10);
        int c = ui.box(g, 20, 10);
        ui.layout.gridColumnPlacement(c, 3, 1).gridRowPlacement(b, 0, 2);
        ui.run(400, 300);
        // three implicit auto columns (c asks for the third) of 30, 20 and 20 stretched by 110 each; b spans both
        // rows (10 and 0 stretched by 145)
        assertRect(ui, a, 110, 0, 30, 10);
        assertRect(ui, b, 250, 0, 20, 10);
        assertRect(ui, c, 380, 0, 20, 10);
    }

    @Test
    void gridSizedByContentInAnAutoParent() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int g = ui.node(ui.root());
        ui.layout.kind(g, LayoutKind.GRID).gridColumns(g, 2).gap(g, 2, 4)
            .gridColumn(g, 0, Unit.AUTO, 0, Unit.FR, 1)
            .gridColumn(g, 1, Unit.AUTO, 0, Unit.FR, 3);
        int a = ui.box(g, 30, 10);
        int b = ui.box(g, 30, 12);
        int c = ui.box(g, 30, 14);
        ui.run();
        // indefinite: one fr is the largest base / fr: 30 (from the first column), so the second is 90
        assertRect(ui, g, 0, 0, 124, 28);
        assertRect(ui, a, 0, 0, 30, 12);
        assertRect(ui, b, 34, 0, 90, 12);
        assertRect(ui, c, 0, 14, 30, 14);
    }

    @Test
    void borderLayout() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.BORDER).gap(r, 2, 3).padding(r, 1, 1, 1, 1);
        int n = ui.text(r, 3);
        int s = ui.box(r, 10, 20);
        int w = ui.box(r, 30, 5);
        int e = ui.box(r, 40, 5);
        int c = ui.box(r, 5, 5);
        ui.layout.region(n, BorderRegion.NORTH).region(s, BorderRegion.SOUTH).region(w, BorderRegion.WEST)
            .region(e, BorderRegion.EAST);
        ui.run();
        assertRect(ui, n, 1, 1, 398, 16);
        assertRect(ui, s, 1, 279, 398, 20);
        assertRect(ui, w, 1, 19, 30, 258);
        assertRect(ui, e, 359, 19, 40, 258);
        assertRect(ui, c, 34, 19, 322, 258);
    }

    @Test
    void borderLayoutContentSize() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int b = ui.node(ui.root());
        ui.layout.kind(b, LayoutKind.BORDER).gap(b, 2, 3);
        int n = ui.box(b, 100, 10);
        int w = ui.box(b, 30, 50);
        int c = ui.box(b, 40, 20);
        int e = ui.box(b, 20, 5);
        ui.layout.region(n, BorderRegion.NORTH).region(w, BorderRegion.WEST).region(e, BorderRegion.EAST);
        ui.run();
        assertRect(ui, b, 0, 0, 100, 62);
        assertRect(ui, c, 33, 12, 44, 50);
        int only = ui.node(ui.root());
        ui.layout.kind(only, LayoutKind.BORDER).gap(only, 2, 3);
        ui.layout.region(ui.box(only, 10, 10), BorderRegion.NORTH);
        ui.layout.region(ui.box(only, 12, 10), BorderRegion.WEST);
        ui.run();
        assertRect(ui, only, 100, 0, 12, 22);
        int empty = ui.node(ui.root());
        ui.layout.kind(empty, LayoutKind.BORDER).gap(empty, 2, 3);
        ui.layout.region(ui.box(empty, 10, 10), BorderRegion.SOUTH);
        ui.run();
        assertRect(ui, empty, 112, 0, 10, 10);
    }

    @Test
    void anchorsAndOffsets() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.ANCHORS).padding(r, 10, 10, 10, 10);
        int topLeft = ui.node(r);
        ui.layout.offsets(topLeft, 10, 20, 110, 70);
        int full = ui.node(r);
        ui.layout.anchors(full, 0, 0, 1, 1).offsets(full, 5, 5, -5, -5);
        int bottomRight = ui.node(r);
        ui.layout.anchors(bottomRight, 1, 1, 1, 1).offsets(bottomRight, -110, -60, -10, -10);
        int centred = ui.text(r, 10);
        ui.layout.anchors(centred, 0.5f, 0.5f, 0.5f, 0.5f).growDirection(centred, GrowDirection.BOTH,
            GrowDirection.BOTH);
        int begin = ui.text(r, 5);
        ui.layout.anchors(begin, 1, 0, 1, 0).growDirection(begin, GrowDirection.BEGIN, GrowDirection.END);
        int sized = ui.node(r);
        ui.layout.anchors(sized, 0, 1, 0, 1).offsets(sized, 0, -30, 0, 0).width(sized, 50, Unit.PERCENT)
            .height(sized, 30, Unit.PX).minHeight(sized, 40, Unit.PX).maxWidth(sized, 100, Unit.PX);
        ui.run();
        assertRect(ui, topLeft, 20, 30, 100, 50);
        assertRect(ui, full, 15, 15, 370, 270);
        assertRect(ui, bottomRight, 280, 230, 100, 50);
        assertRect(ui, centred, 160, 142, 80, 16);
        assertRect(ui, begin, 350, 10, 40, 16);
        assertRect(ui, sized, 10, 260, 100, 40);
    }

    @Test
    void anchorsContentSize() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int a = ui.node(ui.root());
        ui.layout.kind(a, LayoutKind.ANCHORS);
        int x = ui.box(a, 30, 10);
        ui.layout.offsets(x, 5, 6, 0, 0);
        int y = ui.box(a, 20, 40);
        ui.layout.anchors(y, 1, 1, 1, 1).offsets(y, -50, -10, 0, 0);
        ui.run();
        assertRect(ui, a, 0, 0, 50, 40);
        assertRect(ui, x, 5, 6, 30, 10);
    }

    @Test
    void overlayAlignsEachLayer() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.OVERLAY).padding(r, 4, 4, 4, 4);
        int back = ui.box(r, 10, 10);
        int badge = ui.box(r, 20, 10);
        ui.layout.justifySelf(badge, Align.END).alignSelf(badge, Align.START).margin(badge, 1, 1, 1, 1);
        int mid = ui.box(r, 20, 10);
        ui.layout.justifySelf(mid, Align.CENTER).alignSelf(mid, Align.CENTER);
        ui.run();
        assertRect(ui, back, 4, 4, 392, 292);
        assertRect(ui, badge, 375, 5, 20, 10);
        assertRect(ui, mid, 190, 145, 20, 10);
    }

    @Test
    void overlayContentSize() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int o = ui.node(ui.root());
        ui.layout.kind(o, LayoutKind.OVERLAY);
        int a = ui.box(o, 30, 10);
        ui.box(o, 20, 40);
        ui.run();
        assertRect(ui, o, 0, 0, 30, 40);
        assertRect(ui, a, 0, 0, 30, 40);
    }

    @Test
    void absolutePositionsAtOffsets() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int abs = ui.node(ui.root());
        ui.layout.kind(abs, LayoutKind.NONE).padding(abs, 1, 1, 1, 1);
        int a = ui.box(abs, 30, 10);
        ui.layout.offsets(a, 5, 6, 0, 0).margin(a, 1, 1, 1, 1);
        int b = ui.text(abs, 2);
        ui.run();
        assertRect(ui, abs, 0, 0, 39, 20);
        assertRect(ui, a, 7, 8, 30, 10);
        assertRect(ui, b, 1, 1, 16, 16);
    }
}
