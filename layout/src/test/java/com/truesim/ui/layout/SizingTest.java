package com.truesim.ui.layout;

import static com.truesim.ui.layout.FlexLayoutTest.assertRect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.UiStore;
import org.junit.jupiter.api.Test;

/** Units, minimum and maximum sizes, aspect ratio, scale and snapping, right to left. */
class SizingTest {

    private static TestUi column() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).alignItems(ui.root(), Align.START);
        return ui;
    }

    @Test
    void percentAndEm() {
        TestUi ui = column();
        int a = ui.node(ui.root());
        ui.layout.width(a, 50, Unit.PERCENT).height(a, 25, Unit.PERCENT);
        int b = ui.node(ui.root());
        ui.layout.fontSize(b, 12).width(b, 10, Unit.EM).height(b, 2, Unit.EM).padding(b, 1, 1, 1, 1);
        int auto = ui.node(ui.root());
        int inner = ui.node(auto);
        ui.layout.width(inner, 50, Unit.PERCENT).height(inner, 10, Unit.PX);
        ui.text(auto, 3);
        ui.run();
        assertRect(ui, a, 0, 0, 200, 75);
        assertRect(ui, b, 0, 75, 120, 24);
        // a percentage of a size that depends on the content is auto while measuring, then of the final size
        assertRect(ui, inner, 0, 99, 12, 10);
        assertRect(ui, auto, 0, 99, 24, 26);
    }

    @Test
    void minimumAndMaximumSizes() {
        TestUi ui = column();
        int wrapped = ui.text(ui.root(), 30);
        ui.layout.maxWidth(wrapped, 100, Unit.PX);
        int tall = ui.text(ui.root(), 2);
        ui.layout.minHeight(tall, 100, Unit.PX).minWidth(tall, 50, Unit.PERCENT);
        int both = ui.node(ui.root());
        ui.layout.width(both, 10, Unit.PX).minWidth(both, 40, Unit.PX).maxWidth(both, 20, Unit.PX)
            .maxHeight(both, 5, Unit.EM).height(both, 1000, Unit.PX).fontSize(both, 2);
        ui.run();
        assertRect(ui, wrapped, 0, 0, 96, 48);
        assertRect(ui, tall, 0, 48, 200, 100);
        // the minimum wins over the maximum
        assertRect(ui, both, 0, 148, 40, 10);
    }

    @Test
    void maximumLimitsAContainerMeasuredWithoutLimit() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.NONE);
        int c = ui.node(ui.root());
        ui.layout.maxWidth(c, 50, Unit.PX).maxHeight(c, 30, Unit.PX);
        int t = ui.text(c, 20);
        ui.run();
        // the text wraps at 6 characters a line, 64 high; a stack does not shrink it, so it overflows
        assertRect(ui, c, 0, 0, 48, 30);
        assertRect(ui, t, 0, 0, 48, 64);
        ui.layout.kind(c, LayoutKind.FLEX);
        ui.run();
        // a flex item does not shrink below its content either (CSS min-height: auto)...
        assertRect(ui, t, 0, 0, 48, 64);
        ui.layout.minHeight(t, 0, Unit.PX); // ...unless its minimum is 0
        ui.run();
        assertRect(ui, t, 0, 0, 48, 30);
    }

    @Test
    void aspectRatio() {
        TestUi ui = column();
        int byWidth = ui.node(ui.root());
        ui.layout.width(byWidth, 160, Unit.PX).aspectRatio(byWidth, 2);
        int byHeight = ui.node(ui.root());
        ui.layout.height(byHeight, 50, Unit.PX).aspectRatio(byHeight, 2);
        int byContent = ui.text(ui.root(), 4);
        ui.layout.aspectRatio(byContent, 1);
        ui.run();
        assertRect(ui, byWidth, 0, 0, 160, 80);
        assertRect(ui, byHeight, 0, 80, 100, 50);
        assertRect(ui, byContent, 0, 130, 32, 32);

        TestUi row = new TestUi();
        row.layout.kind(row.root(), LayoutKind.FLEX).direction(row.root(), Direction.ROW)
            .alignItems(row.root(), Align.START);
        int grows = row.node(row.root());
        row.layout.grow(grows, 1).aspectRatio(grows, 4);
        int fixed = row.node(row.root());
        row.layout.height(fixed, 50, Unit.PX).aspectRatio(fixed, 2);
        row.run();
        assertRect(row, grows, 0, 0, 300, 75);
        assertRect(row, fixed, 300, 0, 100, 50);
    }

    @Test
    void snappingAtFractionalScales() {
        TestUi ui = new TestUi();
        ui.layout.snapping(true).kind(ui.root(), LayoutKind.NONE);
        int a = ui.box(ui.root(), 20.4f, 10);
        ui.layout.offsets(a, 10.3f, 0.2f, 0, 0);
        int b = ui.box(ui.root(), 20.4f, 10);
        ui.layout.offsets(b, 30.7f, 0.2f, 0, 0);
        ui.scale.set(1.5f);
        ui.run(600, 450);
        // 15.45 to 46.05 and 46.05 to 76.65: neighbours share the snapped edge
        assertRect(ui, a, 15, 0, 31, 15);
        assertRect(ui, b, 46, 0, 31, 15);
        assertRect(ui, ui.root(), 0, 0, 600, 450);
        assertThat(ui.layout.scale()).isEqualTo(1.5f);

        ui.layout.contentScale(2);
        ui.run(1200, 900);
        assertThat(ui.layout.changedCount()).isEqualTo(3);
        assertRect(ui, a, 31, 1, 61, 30);
        ui.layout.snapping(false);
        ui.run(1200, 900);
        assertRect(ui, a, 30.9f, 0.6f, 61.2f, 30);
        assertThatThrownBy(() -> ui.layout.contentScale(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultScaleIsTheUiScaleSetting() {
        UiStore store = new UiStore();
        Layout layout = new Layout(store);
        layout.update(null, 640, 480);
        assertThat(Layout.UI_SCALE.name()).isEqualTo("ui.scale");
        assertThat(layout.scale()).isEqualTo(Layout.UI_SCALE.get());
        assertThat(layout.width(store.rootSlot())).isEqualTo(640);
    }

    @Test
    void rightToLeftMirrorsFlex() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.FLEX).direction(r, Direction.ROW).alignItems(r, Align.START)
            .textDirection(r, TextDirection.RTL).padding(r, 0, 0, 0, 10).gap(r, 0, 5);
        int a = ui.box(r, 50, 20);
        int b = ui.box(r, 50, 20);
        ui.layout.margin(b, 0, 0, 0, 3);
        int ltr = ui.node(r);
        ui.layout.kind(ltr, LayoutKind.FLEX).direction(ltr, Direction.ROW).textDirection(ltr, TextDirection.LTR);
        int x = ui.box(ltr, 10, 10);
        int y = ui.box(ltr, 10, 10);
        ui.run();
        assertRect(ui, a, 340, 0, 50, 20);
        assertRect(ui, b, 282, 0, 50, 20);
        assertRect(ui, ltr, 257, 0, 20, 10);
        assertRect(ui, x, 257, 0, 10, 10);
        assertRect(ui, y, 267, 0, 10, 10);

        ui.layout.textDirection(r, TextDirection.LTR);
        ui.run();
        assertRect(ui, a, 10, 0, 50, 20);
        assertRect(ui, x, 123, 0, 10, 10);
        ui.layout.textDirection(r, TextDirection.INHERIT).textDirection(ltr, TextDirection.INHERIT);
        ui.run();
        assertRect(ui, a, 10, 0, 50, 20);
    }

    @Test
    void rightToLeftMirrorsGridAnchorsAndColumns() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.ANCHORS).textDirection(r, TextDirection.RTL);
        int anchored = ui.node(r);
        ui.layout.offsets(anchored, 10, 20, 110, 70);
        int grid = ui.node(r);
        ui.layout.anchors(grid, 0, 0, 1, 1).kind(grid, LayoutKind.GRID).gridColumns(grid, 2)
            .gridColumn(grid, 0, Unit.PX, 100, Unit.PX, 100).gridColumn(grid, 1, Unit.AUTO, 0, Unit.FR, 1);
        int first = ui.box(grid, 5, 5);
        int second = ui.box(grid, 5, 5);
        ui.run();
        assertRect(ui, anchored, 290, 20, 100, 50);
        assertRect(ui, first, 300, 0, 100, 300);
        assertRect(ui, second, 0, 0, 300, 300);
    }

    @Test
    void reusedSlotsStartFromTheDefaults() {
        TestUi ui = column();
        int a = ui.node(ui.root());
        ui.layout.width(a, 33, Unit.PX).height(a, 10, Unit.PX).grow(a, 1);
        ui.run();
        ui.store.destroy(a);
        int b = ui.node(ui.root());
        assertThat(b).isEqualTo(a);
        int t = ui.text(b, 2);
        ui.run();
        assertRect(ui, b, 0, 0, 16, 16);
        assertRect(ui, t, 0, 0, 16, 16);
        assertThat(ui.layout.changedIn(b)).isEqualTo(ui.layout.updates());
        assertThat(ui.layout.changedIn(100_000)).isZero();
    }

    @Test
    void settersCheckTheirArguments() {
        TestUi ui = new TestUi();
        int g = ui.root();
        assertThatThrownBy(() -> ui.layout.width(999, 1, Unit.PX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ui.layout.gridColumns(g, -1)).isInstanceOf(IllegalArgumentException.class);
        ui.layout.gridColumns(g, 1).gridRows(g, 1);
        assertThatThrownBy(() -> ui.layout.gridColumn(g, 0, Unit.FR, 1, Unit.FR, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ui.layout.gridColumn(g, 0, Unit.PX, 1, Unit.EM, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ui.layout.gridRow(g, 1, Unit.PX, 1, Unit.PX, 1))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ui.layout.gridRow(g, -1, Unit.PX, 1, Unit.PX, 1))
            .isInstanceOf(IndexOutOfBoundsException.class);
        // shrinking the track count keeps the array; growing it adds auto tracks
        ui.layout.gridColumn(g, 0, Unit.PX, 7, Unit.PX, 7).gridColumns(g, 0).gridColumns(g, 2);
    }

    @Test
    void enumerationsMatchTheStyleModulesOrder() {
        assertThat(LayoutKind.OVERLAY.ordinal()).isEqualTo(Layout.K_OVERLAY);
        assertThat(LayoutKind.ANCHORS.ordinal()).isEqualTo(Layout.K_ANCHORS);
        assertThat(Unit.FR.ordinal()).isEqualTo(Layout.U_FR);
        assertThat(Unit.AUTO.ordinal()).isEqualTo(Layout.U_AUTO);
        assertThat(Align.STRETCH.ordinal()).isEqualTo(Layout.A_STRETCH);
        assertThat(Direction.COLUMN_REVERSE.ordinal()).isEqualTo(Layout.D_COLUMN_REVERSE);
        assertThat(Wrap.WRAP_REVERSE.ordinal()).isEqualTo(Layout.W_WRAP_REVERSE);
        assertThat(BorderRegion.WEST.ordinal()).isEqualTo(Layout.R_WEST);
        assertThat(GrowDirection.BEGIN.ordinal()).isEqualTo(Layout.G_BEGIN);
        assertThat(TextDirection.RTL.ordinal()).isEqualTo(Layout.T_RTL);
        assertThat(Measure.width(Measure.size(3.5f, -2))).isEqualTo(3.5f);
        assertThat(Measure.height(Measure.size(3.5f, -2))).isEqualTo(-2);
    }
}
