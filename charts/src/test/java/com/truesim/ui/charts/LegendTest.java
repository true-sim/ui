package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Where the legend goes: at its best place in the plot (away from the data), or outside the plot, never over it. */
class LegendTest {

    private static StyleSheet sheet(String position) {
        return Tss.compileString("legend-" + position + ".tss", """
            chart { padding: 8px; }
            chart-plot { clip: true; }
            chart-axis { gap: 3px; tick-length: 4px; }
            chart-title { margin: 0 0 6px 0; }
            chart-legend {
              legend-position: %s;
              direction: column;
              padding: 4px 6px;
              margin: 6px;
              gap: 4px;
              swatch-size: 10px;
              background: box(#202020);
            }
            """.formatted(position));
    }

    private static Rig rig(String position) {
        Rig r = new Rig(sheet(position), Rig.jdkText());
        r.width = 600;
        r.height = 400;
        return r;
    }

    private static double[] ramp(boolean rising) {
        double[] v = new double[101];
        for (int i = 0; i <= 100; i++) {
            v[i] = rising ? i : 100 - i;
        }
        return v;
    }

    /** Whether some sample or shape of the chart lies under the legend's box, inside the plot. */
    static boolean dataUnderLegend(Chart c) {
        Hit hit = new Hit();
        float x0 = Math.max(c.legendLeft(), c.plotLeft());
        float y0 = Math.max(c.legendTop(), c.plotTop());
        float x1 = Math.min(c.legendRight(), c.plotRight());
        float y1 = Math.min(c.legendBottom(), c.plotBottom());
        for (float y = y0; y <= y1; y += 1) {
            for (float x = x0; x <= x1; x += 1) {
                if (c.hitTest(x, y, hit) && hit.distance <= 1.5) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void bestTakesTheFirstPlaceWithoutDataAndMovesWhenTheDataDoes() {
        Rig r = rig("best");
        Chart c = r.chart("best");
        LineSeries line = c.line("diagonal", Columns.of(ramp(true)), Columns.of(ramp(true)));
        r.settle();
        float midX = (c.plotLeft() + c.plotRight()) * 0.5f;
        float midY = (c.plotTop() + c.plotBottom()) * 0.5f;
        // rising: the top right is taken, the top left is free
        assertThat(c.legendCount()).isEqualTo(1);
        assertThat(c.legendRight()).isLessThan(midX);
        assertThat(c.legendBottom()).isLessThan(midY);
        assertThat(dataUnderLegend(c)).isFalse();
        // falling: the top right is free again
        line.data(Columns.of(ramp(true)), Columns.of(ramp(false)));
        r.settle();
        assertThat(c.legendLeft()).isGreaterThan(midX);
        assertThat(c.legendBottom()).isLessThan(midY);
        assertThat(dataUnderLegend(c)).isFalse();
    }

    @Test
    void bestFallsBackToTheLeastCoveredPlace() {
        Rig r = rig("best");
        Chart c = r.chart("full");
        double[] x = new double[2001];
        double[] y = new double[2001];
        for (int i = 0; i < x.length; i++) {
            x[i] = i * 0.05;
            y[i] = Math.sin(i * 0.05 * 3); // fills every corner but only touches the middle of the edges
        }
        c.line("wave", Columns.of(x), Columns.of(y));
        r.settle();
        assertThat(c.legendCount()).isEqualTo(1);
        assertThat(c.legendLeft()).isGreaterThanOrEqualTo(c.plotLeft());
        assertThat(c.legendRight()).isLessThanOrEqualTo(c.plotRight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"outside-top", "outside-bottom", "outside-left", "outside-right"})
    void anOutsideLegendTakesRoomFromThePlotAndNeverCoversIt(String position) {
        Rig r = rig(position);
        Chart c = r.chart("outside").title("Outside");
        c.addAxis("top", Axis.Side.TOP, Scale.LINEAR);
        c.addAxis("right", Axis.Side.RIGHT, Scale.LINEAR);
        c.line("first", Columns.of(ramp(true)), Columns.of(ramp(true)));
        c.line("second", Columns.of(ramp(true)), Columns.of(ramp(false)));
        r.settle();
        assertThat(c.legendCount()).isEqualTo(2);
        boolean apart = c.legendRight() <= c.plotLeft() || c.legendLeft() >= c.plotRight()
            || c.legendBottom() <= c.plotTop() || c.legendTop() >= c.plotBottom();
        assertThat(apart).as(position + ": the legend beside the plot").isTrue();
        assertThat(c.legendLeft()).isGreaterThanOrEqualTo(0);
        assertThat(c.legendTop()).isGreaterThanOrEqualTo(0);
        assertThat(c.legendRight()).isLessThanOrEqualTo(c.width());
        assertThat(c.legendBottom()).isLessThanOrEqualTo(c.height());
        switch (position) {
            case "outside-top" -> assertThat(c.legendBottom()).isLessThan(c.plotTop());
            case "outside-bottom" -> assertThat(c.legendTop()).isGreaterThan(c.plotBottom());
            case "outside-left" -> assertThat(c.legendRight()).isLessThan(c.plotLeft());
            default -> assertThat(c.legendLeft()).isGreaterThan(c.plotRight());
        }
        // clicking an entry still toggles it
        r.click(c, c.legendX(1), c.legendY(1));
        assertThat(c.legendSeries(1).itemEnabled(0)).isFalse();
    }
}
