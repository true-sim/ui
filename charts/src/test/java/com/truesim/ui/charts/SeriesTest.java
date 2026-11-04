package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleArray;
import com.truesim.common.state.VersionedFloatArray;
import com.truesim.common.state.VersionedLong;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.UiTexture;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each series kind: what it draws, its extent, its nearest sample and its hit test by geometry. */
class SeriesTest {

    private final Rig rig = new Rig();

    private Chart chart() {
        return rig.chart("s");
    }

    private int count(int kind, Chart chart) {
        int n = 0;
        DrawList list = rig.ui.layers()[0];
        int[] slots = {chart.plotElement().slot(), chart.element().slot(), chart.overlayElement().slot()};
        for (int slot : slots) {
            int start = rig.ui.store().rangeStart(slot);
            int end = start + rig.ui.store().rangeCapacity(slot);
            for (int i = start; i < end; i++) {
                if (Primitive.kind(list.header(i)) == kind) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The plot's primitives of {@code kind}, its own background box left out. */
    private int plotCount(int kind, Chart chart) {
        int n = kind == Primitive.BOX ? -1 : 0;
        DrawList list = rig.ui.layers()[0];
        int start = rig.ui.store().rangeStart(chart.plotElement().slot());
        int end = start + rig.ui.store().rangeCapacity(chart.plotElement().slot());
        for (int i = start; i < end; i++) {
            if (Primitive.kind(list.header(i)) == kind) {
                n++;
            }
        }
        return n;
    }

    @Test
    void linesAreHitNearestByXAndDecimatedWhenDense() {
        Chart c = chart();
        double[] x = new double[20];
        double[] y = new double[20];
        for (int i = 0; i < x.length; i++) {
            x[i] = i;
            y[i] = i % 2;
        }
        LineSeries line = c.line("zigzag", Columns.of(x), Columns.of(y));
        rig.settle();
        assertThat(line.sorted()).isTrue();
        assertThat(plotCount(Primitive.LINE, c)).isEqualTo(19);
        Hit h = new Hit();
        float px = c.x().toPixel(7.2);
        assertThat(c.hitTest(px, c.y().toPixel(1), h)).isTrue();
        assertThat(h.series).isSameAs(line);
        assertThat(h.index).isEqualTo(7);
        assertThat(h.toString()).contains("zigzag");
        // stairs, area and dashes add primitives
        line.step(LineSeries.Step.POST);
        rig.run();
        assertThat(plotCount(Primitive.LINE, c)).isEqualTo(38);
        line.step(LineSeries.Step.MID).fill(0);
        rig.run();
        assertThat(plotCount(Primitive.BOX, c)).isGreaterThanOrEqualTo(38);
        line.step(LineSeries.Step.PRE);
        rig.run();
        line.step(LineSeries.Step.NONE);
        rig.run();
        assertThat(plotCount(Primitive.TRIANGLE, c)).isGreaterThan(19); // crossings split
        // dense: the envelope per pixel column (two segments a column at most)
        double[] dx = new double[200_000];
        double[] dy = new double[200_000];
        for (int i = 0; i < dx.length; i++) {
            dx[i] = i * 0.0001;
            dy[i] = Math.sin(i * 0.001);
        }
        line.fill(Double.NaN).data(Columns.of(dx), Columns.of(dy));
        c.fitAll();
        rig.run(2);
        float plotW = c.plotRight() - c.plotLeft();
        assertThat(plotCount(Primitive.LINE, c)).isLessThanOrEqualTo((int) (plotW * 2 + 4));
        line.decimation(Decimation.NONE);
        rig.run();
        assertThat(line.decimation()).isEqualTo(Decimation.NONE);
        line.decimation(Decimation.LTTB);
        rig.run();
        line.decimation(Decimation.MIN_MAX);
        line.fill(-1);
        rig.run();
        assertThat(plotCount(Primitive.BOX, c)).isGreaterThan(100); // the envelope's fill by columns
    }

    @Test
    void scatterIsHitByTheGridWhenUnsorted() {
        Chart c = chart();
        ScatterSeries s = c.scatter("dots", Columns.of(3, 1, 2, 5, 4), Columns.of(1, 5, 2, 4, 3));
        rig.settle();
        assertThat(s.sorted()).isFalse();
        Hit h = new Hit();
        assertThat(c.hitTest(c.x().toPixel(2) + 3, c.y().toPixel(2) - 2, h)).isTrue();
        assertThat(h.index).isEqualTo(2);
        assertThat(c.hitTest(c.x().toPixel(2) + 200, c.y().toPixel(2) + 200, h)).isFalse();
        assertThat(plotCount(Primitive.BOX, c)).isEqualTo(5); // circles
    }

    @Test
    void barsAreHitByTheirRectangles() {
        Chart c = chart();
        BarSeries bars = c.bars(List.of("a", "b"), Columns.of(0, 1, 2), Columns.of(3, 4, 5), Columns.of(1, -2, 3));
        rig.settle();
        assertThat(bars.items()).isEqualTo(2);
        assertThat(bars.item(1)).isEqualTo("b");
        assertThat(bars.space()).isEqualTo(1);
        assertThat(plotCount(Primitive.BOX, c)).isEqualTo(6);
        Hit h = new Hit();
        // grouped: item a left of the position, item b right
        assertThat(c.hitTest(c.x().toPixel(0.9), c.y().toPixel(2), h)).isTrue();
        assertThat(h.item).isZero();
        assertThat(h.index).isEqualTo(1);
        assertThat(c.hitTest(c.x().toPixel(1.1), c.y().toPixel(-1), h)).isTrue();
        assertThat(h.item).isEqualTo(1);
        assertThat(h.y).isEqualTo(-2);
        // stacked: b sits on a
        bars.stacked(true);
        c.fitAll();
        rig.run(2);
        assertThat(bars.stacked()).isTrue();
        assertThat(c.hitTest(c.x().toPixel(2), c.y().toPixel(6), h)).isTrue();
        assertThat(h.item).isEqualTo(1);
        // horizontal
        bars.horizontal(true).stacked(false);
        c.fitAll();
        rig.run(2);
        assertThat(bars.horizontal()).isTrue();
        assertThat(c.hitTest(c.x().toPixel(2), c.y().toPixel(1.1), h)).isTrue();
        assertThat(h.index).isEqualTo(1);
        // the nearest bar by position, for the crosshair
        assertThat(bars.nearest(c.x().toPixel(0), c.y().toPixel(1.9), 100, true, h)).isTrue();
        assertThat(h.index).isEqualTo(2);
        bars.itemEnabled(0, false);
        rig.run();
        assertThat(plotCount(Primitive.BOX, c)).isEqualTo(3);
        bars.spacing(2);
        assertThat(bars.space()).isEqualTo(2);
        assertThatThrownBy(() -> c.bars(List.of("a"), Columns.of(1), Columns.of(1), Columns.of(2)))
            .isInstanceOf(IllegalArgumentException.class);
        // unsorted positions are hit too
        Chart d = chart();
        d.bars(List.of("u"), Columns.of(2, 0, 1), Columns.of(1, 2, 3));
        rig.settle();
        assertThat(d.hitTest(d.x().toPixel(0), d.y().toPixel(1), h)).isTrue();
        assertThat(h.index).isEqualTo(1);
    }

    @Test
    void histogramsBinOnChange() {
        Chart c = chart();
        Columns.Values values = Columns.of(new double[] {1, 1, 2, 2, 2, 3, 9});
        HistogramSeries h = c.histogram("h", values, 4);
        rig.settle();
        assertThat(h.binCount()).isEqualTo(4);
        assertThat(h.bin(0)).isEqualTo(5); // 1..3 in the first quarter of 1..9
        assertThat(h.center(0)).isEqualTo(2);
        h.bins(0);
        rig.run();
        assertThat(h.binCount()).isEqualTo(3); // sqrt(7) rounded up
        h.range(0, 10).bins(10).cumulative(true);
        rig.run();
        assertThat(h.bin(9)).isEqualTo(7);
        h.cumulative(false).density(true);
        rig.run();
        double area = 0;
        for (int i = 0; i < h.binCount(); i++) {
            area += h.bin(i) * 1;
        }
        assertThat(area).isCloseTo(1, within(1e-9));
        values.set(6, 2);
        rig.run();
        Hit hit = new Hit();
        assertThat(c.hitTest(c.x().toPixel(2.5), c.y().toPixel(0.1), hit)).isTrue();
        Chart empty = chart();
        HistogramSeries none = empty.histogram("none", Columns.of(new double[0]), 5);
        rig.settle();
        assertThat(none.binCount()).isZero();
    }

    @Test
    void heatmapsAndHistograms2dAreHitByCell() {
        Chart c = chart();
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            v[i] = i;
        }
        HeatmapSeries map = c.heatmap("map", Columns.of(v), 2, 3, 0, 0, 3, 2);
        rig.settle();
        assertThat(plotCount(Primitive.IMAGE, c)).isEqualTo(1);
        assertThat(map.scaleMin()).isZero();
        assertThat(map.scaleMax()).isEqualTo(5);
        Hit h = new Hit();
        assertThat(c.hitTest(c.x().toPixel(2.5), c.y().toPixel(1.5), h)).isTrue();
        assertThat(h.index).isEqualTo(2); // first row, third column
        assertThat(map.cell(h.index)).isEqualTo(2);
        StringBuilder sb = new StringBuilder();
        map.describe(h, sb);
        assertThat(sb.toString()).isEqualTo("2");
        assertThat(c.hitTest(c.x().toPixel(0.5), c.y().toPixel(0.5), h)).isTrue();
        assertThat(h.index).isEqualTo(3);
        map.scale(-10, 10);
        rig.run();
        assertThat(map.scaleMin()).isEqualTo(-10);
        UiTexture t = map.heat.texture();
        assertThat(t.width()).isEqualTo(3 * (int) ChartTunables.HEATMAP_TEXELS.get());
        Histogram2DSeries h2 = c.histogram2d("h2", Columns.of(0, 1, 1, 2), Columns.of(0, 1, 1, 2), 2, 2);
        rig.run(2);
        assertThat(h2.cell(3)).isEqualTo(2); // bottom right: (1,1) twice
        assertThat(h2.cell(1)).isEqualTo(1); // top right: (2,2)
        h2.range(0, 0, 4, 4);
        rig.run();
        assertThat(h2.cell(2)).isEqualTo(3); // bottom left now holds every point but (2,2)
    }

    @Test
    void piesAreHitByAngleAndRadius() {
        Chart c = chart();
        PieSeries pie = c.pie(List.of("a", "b", "c"), Columns.of(1, 1, 2), 0, 0, 1);
        c.x().range(-2, 2).fits(false);
        c.y().range(-2, 2).fits(false);
        rig.settle();
        assertThat(plotCount(Primitive.TRIANGLE, c)).isGreaterThan(20);
        Hit h = new Hit();
        float cx = c.x().toPixel(0);
        float cy = c.y().toPixel(0);
        float r = (float) Math.abs(c.x().pixelsPerUnit()) * 0.5f;
        assertThat(c.hitTest(cx + r, cy - 5, h)).isTrue(); // just right of 12 o'clock, clockwise: the first slice
        assertThat(h.item).isZero();
        assertThat(c.hitTest(cx - r, cy, h)).isTrue(); // 9 o'clock: in the second half, the third slice
        assertThat(h.item).isEqualTo(2);
        assertThat(c.hitTest(cx + 3 * r, cy, h)).isFalse();
        pie.itemEnabled(0, false);
        rig.run();
        assertThat(c.hitTest(cx + r, cy - 5, h)).isTrue();
        assertThat(h.item).isEqualTo(1);
        pie.labels(false).place(0.1, 0, 1);
        rig.run();
        assertThat(pie.items()).isEqualTo(3);
        StringBuilder sb = new StringBuilder();
        pie.describe(h, sb);
        assertThat(sb.toString()).isEqualTo("1");
    }

    @Test
    void candlesErrorsStemsDigitalInfiniteLinesImagesAndBands() {
        Chart c = chart();
        CandlestickSeries candles = c.candlestick("ohlc", Columns.of(0, 1, 2), Columns.of(1, 2, 3), Columns.of(3, 4, 5),
            Columns.of(0, 1, 2), Columns.of(2, 1, 4));
        rig.settle();
        assertThat(plotCount(Primitive.BOX, c)).isEqualTo(3);
        Hit h = new Hit();
        assertThat(c.hitTest(c.x().toPixel(1), c.y().toPixel(1), h)).isTrue();
        StringBuilder sb = new StringBuilder();
        candles.describe(h, sb);
        assertThat(sb.toString()).startsWith("O ").contains("C ");
        candles.ohlc(true);
        rig.run();
        assertThat(plotCount(Primitive.BOX, c)).isZero();
        candles.enabled(false);

        ErrorBarSeries errors = c.errorBars("e", Columns.of(0, 1), Columns.of(1, 2), Columns.of(0.5, 0.5), null);
        StemSeries stems = c.stems("s", Columns.of(0.5, 1.5), Columns.of(1, -1), 0);
        rig.run(2);
        assertThat(stems.baseline()).isZero();
        stems.baseline(0.5);
        errors.horizontal(true);
        rig.run();
        sb.setLength(0);
        errors.nearest(c.x().toPixel(1), c.y().toPixel(2), 50, false, h);
        errors.describe(h, sb);
        assertThat(sb.toString()).contains("-").contains("+");

        DigitalSeries digital = c.digital("d", Columns.of(0, 1, 2), Columns.of(0, 1, 0));
        rig.run();
        assertThat(digital.nearest(c.x().toPixel(1), c.plotBottom() - 5, 100, true, h)).isTrue();
        InfiniteLines lines = c.infiniteLines("inf", Columns.of(0.25), true);
        rig.run();
        assertThat(lines.vertical()).isTrue();
        assertThat(lines.nearest(c.x().toPixel(0.25) + 2, 100, 10, true, h)).isTrue();
        sb.setLength(0);
        lines.describe(h, sb);
        assertThat(sb.toString()).isEqualTo("0.25");
        c.infiniteLines("", Columns.of(1.0), false);

        UiTexture texture = rig.ui.textures().create("t", 2, 2, UiTexture.Format.RGBA8_SRGB);
        ImageSeries image = c.image("img", texture, 0, 0, 1, 1);
        rig.run();
        assertThat(c.hitTest(c.x().toPixel(0.5), c.y().toPixel(0.5), h)).isTrue();
        assertThat(h.series).isSameAs(image);
        image.set(texture, 1, 1, 0, 0);
        rig.run();

        ShadedSeries band = c.shaded("band", Columns.of(0, 1, 2), Columns.of(0, 2, 0), Columns.of(1, 1, 1));
        rig.run();
        assertThat(band.y2()).isNotNull();
        sb.setLength(0);
        band.nearest(c.x().toPixel(1), c.y().toPixel(2), 50, true, h);
        band.describe(h, sb);
        assertThat(sb.toString()).contains(" to ");
        StackedAreaSeries stacked = c.stackedArea(List.of("p", "q"), Columns.of(0, 1, 2), Columns.of(1, 1, 1),
            Columns.of(2, 2, 2));
        rig.run();
        assertThat(stacked.items()).isEqualTo(2);
        assertThat(stacked.nearest(c.x().toPixel(1), c.y().toPixel(2), 1e9f, false, h)).isTrue();
        assertThat(stacked.nearest(c.x().toPixel(1), c.y().toPixel(2), 1, false, h)).isFalse(); // the top is far
        assertThat(h.item).isEqualTo(1); // y 2 lies in the second layer (1 to 3)
        assertThat(h.y).isEqualTo(2);
        stacked.itemEnabled(0, false);
        rig.run();
        assertThat(stacked.nearest(c.x().toPixel(1), c.y().toPixel(1.5), 1e9f, true, h)).isTrue();
        assertThat(h.item).isEqualTo(1);
        assertThat(h.py).isCloseTo(c.y().toPixel(2), within(0.01f)); // the hidden layer left the stack
    }

    @Test
    void columnsOverEveryKindOfData() {
        ChangeClock clock = rig.ui.store().clock();
        VersionedDoubleArray d = clock.doubleArray(3);
        d.set(1, 5);
        VersionedFloatArray f = clock.floatArray(2);
        f.set(1, 2.5f);
        VersionedLong trigger = clock.longValue(0);
        Column getter = Columns.of(4, i -> i * i, trigger);
        Column dc = Columns.of(d);
        Column fc = Columns.of(f);
        assertThat(dc.get(1)).isEqualTo(5);
        assertThat(dc.size()).isEqualTo(3);
        assertThat(fc.get(1)).isEqualTo(2.5);
        assertThat(fc.end()).isEqualTo(2);
        assertThat(getter.get(3)).isEqualTo(9);
        long v = getter.version();
        trigger.set(1);
        assertThat(getter.version()).isNotEqualTo(v);
        assertThat(Columns.of(2, i -> i, null).version()).isZero();
        Columns.FloatValues fv = Columns.of(1f, 2f);
        long fvv = fv.version();
        fv.set(0, 3f);
        fv.changed();
        assertThat(fv.version()).isGreaterThan(fvv);
        assertThat(fv.get(0)).isEqualTo(3);
        Column index = Columns.index(5, 0.5);
        assertThat(index.get(4)).isEqualTo(7);
        assertThat(index.uniformStart()).isEqualTo(5);
        assertThat(dc.uniformStep()).isNaN();
        assertThat(dc.appendedSince(0, (a, b) -> { })).isFalse();
        Chart c = chart();
        c.line("d", Columns.of(0, 1, 2), dc);
        c.line("f", Columns.of(0, 1), fc);
        c.line("g", Columns.of(0, 1, 2, 3), getter);
        c.y().autoFit(true);
        rig.settle();
        d.set(2, 9); // the chart sees the change
        rig.run();
        assertThat(c.y().range().max()).isGreaterThan(9);
    }
}
