package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Scripts of synthetic input against charts: zoom, pan, boxes, legend, axes, drag tools, reset, follow, crosshair. */
class InteractionTest {

    private final Rig rig = new Rig();

    private Chart lineChart() {
        Chart c = rig.chart("i");
        double[] x = new double[101];
        double[] y = new double[101];
        for (int i = 0; i <= 100; i++) {
            x[i] = i;
            y[i] = Math.sin(i * 0.1) * 10;
        }
        c.line("wave", Columns.of(x), Columns.of(y));
        rig.settle();
        return c;
    }

    private float midX(Chart c) {
        return (c.plotLeft() + c.plotRight()) * 0.5f;
    }

    private float midY(Chart c) {
        return (c.plotTop() + c.plotBottom()) * 0.5f;
    }

    @Test
    void theFirstDataFitsTheAxes() {
        Chart c = lineChart();
        assertThat(c.x().range().min()).isLessThan(0);
        assertThat(c.x().range().max()).isGreaterThan(100);
        assertThat(c.y().range().min()).isLessThan(-9.9);
        assertThat(c.y().range().max()).isGreaterThan(9.9);
    }

    @Test
    void wheelZoomsAboutThePointerOverThePlotAndAnAxis() {
        Chart c = lineChart();
        float px = midX(c) + 40;
        float py = midY(c) - 20;
        double underX = c.x().fromPixel(px);
        float share = (px - c.plotLeft()) / (c.plotRight() - c.plotLeft()); // the plot may move as labels change
        double spanX = c.x().range().span();
        double spanY = c.y().range().span();
        rig.wheel(c, px, py, 1, 0);
        assertThat(c.x().range().span()).isCloseTo(spanX * 0.9, within(1e-6));
        assertThat(c.y().range().span()).isCloseTo(spanY * 0.9, within(1e-6));
        assertThat(c.x().range().min() + share * c.x().range().span()).isCloseTo(underX, within(1e-4));
        // Shift: X only; Ctrl: Y only
        spanY = c.y().range().span();
        rig.wheel(c, px, py, -1, Pointer.SHIFT);
        assertThat(c.y().range().span()).isEqualTo(spanY);
        spanX = c.x().range().span();
        rig.wheel(c, px, py, 1, Pointer.CTRL);
        assertThat(c.x().range().span()).isEqualTo(spanX);
        // over the X axis: that axis alone
        spanY = c.y().range().span();
        rig.wheel(c, midX(c), c.plotBottom() + 6, 2, 0);
        assertThat(c.y().range().span()).isEqualTo(spanY);
        assertThat(c.x().range().span()).isLessThan(spanX);
        // a selected axis is the only one the plot's wheel zooms
        spanX = c.x().range().span();
        rig.click(c, c.plotLeft() - 8, midY(c));
        assertThat(c.y().selected()).isTrue();
        rig.wheel(c, px, py, 1, 0);
        assertThat(c.x().range().span()).isEqualTo(spanX);
        rig.click(c, c.plotLeft() - 8, midY(c));
        assertThat(c.y().selected()).isFalse();
        // outside the plot and the axes nothing happens
        spanX = c.x().range().span();
        rig.wheel(c, 1, 1, 1, 0);
        assertThat(c.x().range().span()).isEqualTo(spanX);
    }

    @Test
    void dragPansAndAxisDragPansOneAxis() {
        Chart c = lineChart();
        double x0 = c.x().range().min();
        double y0 = c.y().range().min();
        float k = (float) c.x().pixelsPerUnit();
        rig.drag(c, midX(c), midY(c), midX(c) + 50, midY(c) + 30, Pointer.PRIMARY, 0, 5);
        assertThat(c.x().range().min()).isCloseTo(x0 - 50 / k, within(Math.abs(50 / k) * 0.05));
        assertThat(c.y().range().min()).isGreaterThan(y0);
        y0 = c.y().range().min();
        x0 = c.x().range().min();
        rig.drag(c, c.plotLeft() - 8, midY(c), c.plotLeft() - 8, midY(c) + 40, Pointer.PRIMARY, 0, 4);
        assertThat(c.y().range().min()).isNotEqualTo(y0);
        assertThat(c.x().range().min()).isEqualTo(x0);
        // a double click fits again
        rig.click(c, midX(c), midY(c));
        rig.mouse(c, midX(c), midY(c), Pointer.PRIMARY);
        rig.mouse(c, midX(c), midY(c), 0);
        assertThat(c.x().range().min()).isLessThan(0);
        assertThat(c.x().range().max()).isGreaterThan(100).isLessThan(110);
    }

    @Test
    void boxZoomAndBoxSelect() {
        Chart c = lineChart();
        float ax = c.x().toPixel(20);
        float bx = c.x().toPixel(40);
        float ay = c.y().toPixel(5);
        float by = c.y().toPixel(-5);
        rig.drag(c, ax, ay, bx, by, Pointer.SECONDARY, 0, 4);
        assertThat(c.x().range().min()).isCloseTo(20, within(0.05));
        assertThat(c.x().range().max()).isCloseTo(40, within(0.05));
        assertThat(c.y().range().min()).isCloseTo(-5, within(0.05));
        // a thin box zooms one direction
        double ySpan = c.y().range().span();
        rig.drag(c, c.x().toPixel(25), midY(c), c.x().toPixel(30), midY(c) + 1, Pointer.SECONDARY, 0, 3);
        assertThat(c.y().range().span()).isEqualTo(ySpan);
        assertThat(c.x().range().span()).isCloseTo(5, within(0.05));
        // Shift and drag: a selection the game reads
        c.fitAll();
        rig.run(2);
        DragRect sel = c.selection();
        assertThat(sel.enabled()).isFalse();
        rig.drag(c, c.x().toPixel(10), c.y().toPixel(8), c.x().toPixel(30), c.y().toPixel(-2), Pointer.PRIMARY,
            Pointer.SHIFT, 4);
        assertThat(sel.enabled()).isTrue();
        assertThat(sel.x0().get()).isCloseTo(10, within(0.1));
        assertThat(sel.x1().get()).isCloseTo(30, within(0.1));
        assertThat(sel.y0().get()).isCloseTo(-2, within(0.1));
        assertThat(sel.y1().get()).isCloseTo(8, within(0.1));
        // and it can be moved
        rig.drag(c, c.x().toPixel(20), c.y().toPixel(3), c.x().toPixel(25), c.y().toPixel(3), Pointer.PRIMARY, 0, 3);
        assertThat(sel.x0().get()).isCloseTo(15, within(0.2));
    }

    @Test
    void legendTogglesAndHighlights() {
        Chart c = rig.chart("l");
        BarSeries bars = c.bars(List.of("a", "b"), Columns.of(0, 1), Columns.of(1, 2), Columns.of(2, 1));
        rig.settle();
        assertThat(c.legendCount()).isEqualTo(2);
        assertThat(c.legendSeries(1)).isSameAs(bars);
        assertThat(c.legendItem(1)).isEqualTo(1);
        rig.mouse(c, c.legendX(1), c.legendY(1), 0);
        assertThat(c.highlighted(bars)).isTrue();
        rig.click(c, c.legendX(1), c.legendY(1));
        assertThat(bars.itemEnabled(1)).isFalse();
        rig.click(c, c.legendX(1), c.legendY(1));
        assertThat(bars.itemEnabled(1)).isTrue();
        // a press dragged off the legend does not toggle
        rig.drag(c, c.legendX(0), c.legendY(0), c.legendX(0) - 100, c.legendY(0) + 100, Pointer.PRIMARY, 0, 3);
        assertThat(bars.itemEnabled(0)).isTrue();
        rig.mouse(c, midX(c), midY(c), 0);
        assertThat(c.highlighted(bars)).isFalse();
        assertThat(c.legendEntryAt(-5, -5)).isEqualTo(-1);
    }

    @Test
    void dragToolsEditTheirValues() {
        Chart c = lineChart();
        ChangeClock clock = rig.ui.store().clock();
        VersionedDouble px = clock.doubleValue(50);
        VersionedDouble py = clock.doubleValue(0);
        DragPoint point = c.dragPoint(px, py);
        VersionedDouble lx = clock.doubleValue(20);
        DragLine line = c.dragLine(lx, true);
        VersionedDouble ly = clock.doubleValue(5);
        DragLine hline = c.dragLine(ly, false);
        DragRect rect = c.dragRect(clock.doubleValue(60), clock.doubleValue(-5), clock.doubleValue(80),
            clock.doubleValue(5));
        rig.run(2);
        assertThat(c.tools()).hasSize(4);
        // hovering shows :hover, dragging moves it
        rig.mouse(c, c.x().toPixel(50), c.y().toPixel(0), 0);
        assertThat(point.hovered()).isTrue();
        rig.drag(c, c.x().toPixel(50), c.y().toPixel(0), c.x().toPixel(55), c.y().toPixel(3), Pointer.PRIMARY, 0, 3);
        assertThat(px.get()).isCloseTo(55, within(0.2));
        assertThat(py.get()).isCloseTo(3, within(0.2));
        assertThat(point.pressed()).isFalse();
        rig.drag(c, c.x().toPixel(20), c.y().toPixel(-8), c.x().toPixel(30), c.y().toPixel(-8), Pointer.PRIMARY, 0, 3);
        assertThat(lx.get()).isCloseTo(30, within(0.2));
        assertThat(line.vertical()).isTrue();
        rig.drag(c, c.x().toPixel(90), c.y().toPixel(5), c.x().toPixel(90), c.y().toPixel(7), Pointer.PRIMARY, 0, 3);
        assertThat(ly.get()).isCloseTo(7, within(0.2));
        assertThat(hline.value()).isSameAs(ly);
        // the rectangle: an edge resizes, the inside moves, edges swapped back on release
        rig.drag(c, c.x().toPixel(80), c.y().toPixel(0), c.x().toPixel(90), c.y().toPixel(0), Pointer.PRIMARY, 0, 3);
        assertThat(rect.x1().get()).isCloseTo(90, within(0.2));
        rig.drag(c, c.x().toPixel(70), c.y().toPixel(0), c.x().toPixel(72), c.y().toPixel(2), Pointer.PRIMARY, 0, 3);
        assertThat(rect.x0().get()).isCloseTo(62, within(0.2));
        rig.drag(c, c.x().toPixel(62), c.y().toPixel(7), c.x().toPixel(95), c.y().toPixel(-9), Pointer.PRIMARY, 0, 3);
        assertThat(rect.x0().get()).isLessThan(rect.x1().get());
        assertThat(rect.y0().get()).isLessThan(rect.y1().get());
        // values set by the game show
        px.set(10);
        rig.run();
        assertThat(point.x()).isSameAs(px);
        point.enabled(false);
        rig.run();
        assertThat(point.enabled()).isFalse();
        point.axes(c.x(), c.y());
        assertThat(point.xAxis()).isSameAs(c.x());
    }

    @Test
    void resetCommandFitsAgainAndResumesFollowing() {
        Chart c = lineChart();
        c.x().range(30, 40);
        rig.run();
        rig.ui.interaction().focus(c.element());
        rig.press(UiCommand.CHART_RESET);
        assertThat(c.x().range().min()).isLessThan(0);
    }

    @Test
    void streamingFollowsTheNewestSample() {
        Chart c = rig.chart("stream");
        ChangeClock clock = rig.ui.store().clock();
        VersionedDoubleRing ring = clock.doubleRing(5000);
        LineSeries s = c.line("live", Columns.index(0, 0.01), Columns.of(ring));
        c.x().range(0, 20).fits(false).follow(true); // a 20-second window
        c.y().autoFit(true);
        double[] chunk = new double[100];
        for (int f = 0; f < 30; f++) {
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = Math.sin((ring.appended() + i) * 0.01) * (1 + f * 0.1);
            }
            ring.append(chunk, 0, chunk.length);
            rig.run();
        }
        double newest = (ring.appended() - 1) * 0.01;
        assertThat(c.x().range().max()).isGreaterThanOrEqualTo(newest);
        assertThat(c.x().range().max() - newest).isLessThan(c.x().range().span() * 0.1);
        assertThat(c.x().range().span()).isCloseTo(20, within(1e-6));
        assertThat(c.y().range().max()).isGreaterThan(3); // fitted to the growing amplitude
        assertThat(s.first()).isEqualTo(0);
        // panning stops following until a reset
        rig.drag(c, midX(c), midY(c), midX(c) + 100, midY(c), Pointer.PRIMARY, 0, 3);
        double held = c.x().range().max();
        ring.append(chunk, 0, chunk.length);
        rig.run();
        assertThat(c.x().range().max()).isEqualTo(held);
        c.fitAll();
        ring.append(chunk, 0, chunk.length);
        rig.run();
        assertThat(c.x().range().max()).isGreaterThan(held);
        // the ring wraps: the window moves on
        for (int f = 0; f < 60; f++) {
            ring.append(chunk, 0, chunk.length);
            rig.run();
        }
        assertThat(s.first()).isPositive();
        assertThat(s.end() - s.first()).isEqualTo(5000);
    }

    @Test
    void crosshairIsSharedAndTooltipsShow() {
        Chart a = lineChart();
        Chart b = rig.chart("b");
        b.line("other", Columns.of(0, 50, 100), Columns.of(1, 2, 3));
        Crosshair shared = new Crosshair(rig.ui.store().clock());
        a.crosshair(shared);
        b.crosshair(shared);
        b.x().link(a.x().range());
        rig.settle();
        float px = a.x().toPixel(50);
        rig.mouse(a, px, a.y().toPixel(Math.sin(5) * 10), 0);
        rig.run();
        assertThat(shared.active()).isTrue();
        assertThat(shared.source()).isSameAs(a);
        assertThat(shared.x()).isCloseTo(50, within(0.5));
        assertThat(a.crosshairShown()).isTrue();
        assertThat(a.highlights()).isEqualTo(1);
        assertThat(a.highlight(0).index).isEqualTo(50);
        assertThat(b.crosshairShown()).isTrue();
        assertThat(b.highlights()).isEqualTo(1);
        // leaving clears it
        rig.mouse(a, -50, -50, 0);
        rig.run();
        assertThat(shared.active()).isFalse();
        assertThat(b.crosshairShown()).isFalse();
        shared.clear(b); // not the source: nothing
        shared.set(a, 1, 2);
        shared.set(a, 1, 2);
        assertThat(shared.y()).isEqualTo(2);
    }

    @Test
    void perSeriesTooltipsInTheTestTheme() {
        Rig garish = new Rig(Rig.GARISH, Rig.jdkText());
        Chart c = garish.chart("g");
        c.line("a", Columns.of(0, 1, 2), Columns.of(0, 1, 0));
        c.line("b", Columns.of(0, 1, 2), Columns.of(0, 1.05, 0));
        garish.settle();
        garish.mouse(c, c.x().toPixel(1), c.y().toPixel(1), 0);
        garish.run(3);
        assertThat(c.highlights()).isEqualTo(2);
        // far from both: no highlight in this mode
        garish.mouse(c, c.x().toPixel(1), c.y().toPixel(-5), 0);
        garish.run();
        assertThat(c.highlights()).isZero();
    }

    @Test
    void chartsWithoutText() {
        Rig bare = new Rig(Rig.DEFAULT, null);
        Chart c = bare.chart("bare");
        c.title("title").line("l", Columns.of(0, 1), Columns.of(0, 1));
        c.x().title("x");
        c.annotate(0.5, 0.5, "note", 5, 5);
        bare.settle();
        bare.mouse(c, (c.plotLeft() + c.plotRight()) / 2, (c.plotTop() + c.plotBottom()) / 2, 0);
        bare.run();
        assertThat(c.legendCount()).isZero();
        assertThat(c.title()).isEqualTo("title");
    }
}
