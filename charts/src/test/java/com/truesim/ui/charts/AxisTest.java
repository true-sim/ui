package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Axis transforms (linear, log, symlog, inverted), navigation (zoom about a point, pan, box), fitting, linking. */
class AxisTest {

    private final Rig rig = new Rig(Rig.DEFAULT, null);
    private final Chart chart = rig.chart("axes");

    private Axis axis(Scale scale, double min, double max, boolean vertical) {
        Axis a = chart.addAxis("a" + chart.axes().size(), vertical ? Axis.Side.LEFT : Axis.Side.BOTTOM, scale);
        a.range().set(min, max);
        if (vertical) {
            a.map(500, 100);
        } else {
            a.map(100, 500);
        }
        return a;
    }

    @Test
    void linearMapsBothWays() {
        Axis a = axis(Scale.LINEAR, -10, 30, false);
        assertThat(a.toPixel(-10)).isEqualTo(100f);
        assertThat(a.toPixel(30)).isEqualTo(500f);
        assertThat(a.toPixel(10)).isEqualTo(300f);
        assertThat(a.fromPixel(200)).isCloseTo(0, within(1e-9));
        assertThat(a.pixelsPerUnit()).isEqualTo(10);
        assertThat(a.length()).isEqualTo(400f);
        assertThat(a.startPixel()).isEqualTo(100f);
        assertThat(a.endPixel()).isEqualTo(500f);
        Axis y = axis(Scale.LINEAR, 0, 1, true);
        assertThat(y.toPixel(1)).isEqualTo(100f); // up is smaller pixels
        assertThat(y.pixelsPerUnit()).isNegative();
        y.inverted(true);
        y.map(500, 100);
        assertThat(y.toPixel(1)).isEqualTo(500f);
        assertThat(y.inverted()).isTrue();
    }

    @Test
    void logAndSymlogRoundTrip() {
        Axis log = axis(Scale.LOG, 1, 1e4, false);
        assertThat(log.toPixel(100)).isCloseTo(300f, within(1e-3f));
        for (double v : new double[] {1, 3, 50, 1e4}) {
            assertThat(log.fromPixel(log.toPixel(v))).isCloseTo(v, within(v * 1e-5));
        }
        assertThat(log.forward(-1)).isNaN();
        Axis sym = axis(Scale.SYMLOG, -1000, 1000, false);
        sym.symlogConstant(1);
        assertThat(sym.toPixel(0)).isCloseTo(300f, within(1e-3f));
        for (double v : new double[] {-1000, -3, -0.5, 0, 0.5, 7, 999}) {
            assertThat(sym.inverse(sym.forward(v))).isCloseTo(v, within(Math.abs(v) * 1e-9 + 1e-12));
        }
        assertThat(sym.toPixel(-5)).isCloseTo(600 - sym.toPixel(5), within(1e-3f)); // symmetric
        assertThatThrownBy(() -> sym.symlogConstant(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sym.symlogConstant()).isEqualTo(1);
        // a log axis over non-positive values still maps (a decade)
        Axis bad = axis(Scale.LOG, 1, 10, false);
        bad.range().set(-5, -1);
        bad.remap();
        assertThat(bad.toPixel(0.1)).isFinite();
    }

    @Test
    void zoomKeepsThePointUnderThePixelAndPanMovesWithThePointer() {
        Axis a = axis(Scale.LINEAR, 0, 100, false);
        double under = a.fromPixel(180);
        a.zoom(180, 0.5);
        assertThat(a.range().span()).isCloseTo(50, within(1e-9));
        assertThat(a.fromPixel(180)).isCloseTo(under, within(1e-9));
        a.pan(40); // dragged right: lower values come into view
        assertThat(a.fromPixel(220)).isCloseTo(under, within(1e-9));
        a.zoomTo(300, 200);
        assertThat(a.range().min()).isLessThan(a.range().max());
        Axis log = axis(Scale.LOG, 1, 1e4, false);
        double ten = log.fromPixel(200);
        log.zoom(200, 0.5);
        assertThat(log.fromPixel(200)).isCloseTo(ten, within(ten * 1e-6));
        assertThat(log.range().max() / log.range().min()).isCloseTo(100, within(1e-6));
    }

    @Test
    void fittingPadsAndWidensSingleValues() {
        Axis a = axis(Scale.LINEAR, 0, 1, false);
        assertThat(a.fit(10, 20, 0.1)).isTrue();
        assertThat(a.range().min()).isCloseTo(9, within(1e-9));
        assertThat(a.range().max()).isCloseTo(21, within(1e-9));
        assertThat(a.fit(10, 20, 0.1)).isFalse(); // already
        a.fit(5, 5, 0);
        assertThat(a.range().min()).isLessThan(5);
        assertThat(a.range().max()).isGreaterThan(5);
        assertThat(a.fit(Double.NaN, 1, 0)).isFalse();
        assertThat(a.fit(3, 1, 0)).isFalse();
        Axis c = axis(Scale.CATEGORY, 0, 1, false);
        c.fit(0, 4, 0);
        assertThat(c.range().min()).isEqualTo(-0.5);
        assertThat(c.range().max()).isEqualTo(4.5);
        Axis log = axis(Scale.LOG, 1, 10, false);
        log.fit(10, 1000, 0);
        assertThat(log.range().min()).isCloseTo(10, within(1e-9));
        assertThat(log.fit(-1, 5, 0)).isFalse();
    }

    @Test
    void rangesAreVersionedAndLinkedBySharing() {
        ChangeClock clock = rig.ui.store().clock();
        AxisRange r = new AxisRange(clock, 0, 1);
        long v = r.version();
        assertThat(r.set(0, 1)).isFalse();
        assertThat(r.set(1, 0)).isFalse();
        assertThat(r.set(Double.NaN, 1)).isFalse();
        assertThat(r.set(0, 2)).isTrue();
        assertThat(r.version()).isGreaterThan(v);
        assertThat(r.toString()).contains("0.0", "2.0");
        assertThatThrownBy(() -> new AxisRange(clock, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        Axis a = axis(Scale.LINEAR, 0, 1, false);
        Axis b = axis(Scale.LINEAR, 5, 6, false);
        b.link(a.range());
        a.zoom(300, 0.5);
        assertThat(b.range()).isSameAs(a.range());
        assertThat(b.sync()).isTrue();
        assertThat(b.sync()).isFalse();
    }

    @Test
    void settersAndFormats() {
        Axis a = axis(Scale.LINEAR, 0, 1, false);
        assertThat(a.enabled(false).enabled()).isFalse();
        assertThat(a.title("speed").title()).isEqualTo("speed");
        assertThat(a.title(null).title()).isEmpty();
        assertThat(a.autoFit(true).autoFit()).isTrue();
        assertThat(a.follow(true).follow()).isTrue();
        assertThat(a.selected(true).selected()).isTrue();
        assertThat(a.fits(false).fits()).isFalse();
        assertThat(a.vertical()).isFalse();
        assertThat(a.chart()).isSameAs(chart);
        assertThat(a.toString()).contains("LINEAR");
        a.ticks.generate(a, 400, 80, 40, false);
        assertThat(a.format(new StringBuilder(), 0.123456).toString()).isEqualTo("0.12");
        Axis log = axis(Scale.LOG, 1, 100, false);
        assertThat(log.format(new StringBuilder(), 12.345).toString()).isEqualTo("12.3");
        Axis t = axis(Scale.TIME, 0, 60, false);
        t.ticks.generate(t, 400, 80, 40, false);
        assertThat(t.format(new StringBuilder(), 30).toString()).isEqualTo("00:00:30");
        Axis c = axis(Scale.CATEGORY, 0, 2, false);
        c.categories(List.of("x", "y"));
        assertThat(c.format(new StringBuilder(), 1.2).toString()).isEqualTo("y");
        assertThat(c.format(new StringBuilder(), 7).toString()).isEqualTo("7");
        assertThatThrownBy(() -> chart.addAxis("x", Axis.Side.TOP, Scale.LINEAR))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(chart.axis("nope")).isNull();
    }
}
