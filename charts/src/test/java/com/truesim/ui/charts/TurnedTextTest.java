package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.testkit.LayoutSanity;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Turned text ({@code text-angle}): Y titles reading upwards beside their labels, centred on the plot; slanted tick
 * labels under their ticks, inside the chart, thinned by the room they take along the axis; drawn by rotating entries
 * of the transform table under the chart's own (an animated chart moves them); recorded without allocating.
 */
class TurnedTextTest {

    private static final float DEGREE = (float) (Math.PI / 180);

    /** A chart of twelve long month names, styled "slanted", with a Y title, settled. */
    private static Chart months(Rig r) {
        Chart c = r.chart("turned");
        c.element().style("slanted");
        Axis months = c.addAxis("month", Axis.Side.BOTTOM, Scale.CATEGORY).title("month")
            .categories(List.of("January", "February", "March", "April", "May", "June", "July", "August",
                "September", "October", "November", "December"));
        c.x().enabled(false);
        c.y().title("rainfall (mm)");
        double[] x = new double[12];
        double[] y = new double[12];
        for (int i = 0; i < 12; i++) {
            x[i] = i;
            y[i] = 40 + i * 3;
        }
        c.bars(List.of("rain"), Columns.of(x), Columns.of(y)).axes(months, c.y());
        r.settle();
        return c;
    }

    /** The bounds of {@code c}'s glyphs turned by {@code degrees} (x0, y0, x1, y1; empty: x0 > x1) and their count. */
    private static float[] turnedGlyphs(Rig r, Chart c, float degrees) {
        float[] u = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, 0};
        float[] b = new float[4];
        DrawList[] layers = r.ui.layers();
        for (int l = 0; l < r.ui.layerCount(); l++) {
            DrawList list = layers[l];
            for (int i = 0; i < list.size(); i++) {
                if (Primitive.kind(list.header(i)) != Primitive.GLYPH
                    || Math.abs(list.drawnSin(i) - (float) Math.sin(degrees * DEGREE)) > 1e-3f
                    || Math.abs(list.drawnCos(i) - (float) Math.cos(degrees * DEGREE)) > 1e-3f) {
                    continue;
                }
                list.drawnBounds(i, list.floatAt(i, Primitive.X0), list.floatAt(i, Primitive.Y0),
                    list.floatAt(i, Primitive.X1), list.floatAt(i, Primitive.Y1), b);
                u[0] = Math.min(u[0], b[0]);
                u[1] = Math.min(u[1], b[1]);
                u[2] = Math.max(u[2], b[2]);
                u[3] = Math.max(u[3], b[3]);
                u[4]++;
            }
        }
        return u;
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void titlesAndLabelsTurn(String theme) {
        Rig r = new Rig(theme.equals("default") ? Rig.DEFAULT : Rig.GARISH, Rig.nativeText());
        r.width = 420;
        r.height = 320;
        Chart c = months(r);
        LayoutSanity.assertSane(r.ui, "turned text (" + theme + ")");
        float titleAngle = theme.equals("default") ? -90 : 90;
        float labelAngle = theme.equals("default") ? -40 : 65;
        float x0 = c.contentX();
        float y0 = c.contentY();
        // the Y title: beside the labels, left of the plot, centred on it, upright
        float[] title = turnedGlyphs(r, c, titleAngle);
        assertThat(title[4]).as("glyphs of the title").isGreaterThanOrEqualTo(10);
        assertThat(title[2]).isLessThan(x0 + c.plotLeft());
        assertThat(title[0]).isGreaterThanOrEqualTo(x0 - 0.5f);
        assertThat(title[3] - title[1]).as("taller than wide").isGreaterThan(2 * (title[2] - title[0]));
        assertThat((title[1] + title[3]) * 0.5f).isCloseTo(y0 + (c.plotTop() + c.plotBottom()) * 0.5f, within(6f));
        // the labels: below the plot, within the chart, slanted
        float[] labels = turnedGlyphs(r, c, labelAngle);
        assertThat(labels[4]).as("glyphs of the labels").isGreaterThan(10);
        assertThat(labels[1]).isGreaterThan(y0 + c.plotBottom());
        assertThat(labels[3]).isLessThanOrEqualTo(y0 + r.height);
        assertThat(labels[0]).isGreaterThanOrEqualTo(x0 - 0.5f);
        assertThat(labels[2]).isLessThanOrEqualTo(x0 + 420.5f);
        // the X title below the labels
        float[] flat = turnedGlyphs(r, c, 0);
        assertThat(flat[4]).isPositive();
        Axis months = c.axis("month");
        assertThat(months.ticks().majors()).as("twelve long names thinned to fit their slanted room")
            .isBetween(2, 12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default"})
    void turnedTextFollowsAnAnimatedChart(String theme) {
        Rig r = new Rig(Rig.DEFAULT, Rig.nativeText());
        r.width = 420;
        r.height = 320;
        Chart c = months(r);
        float[] before = turnedGlyphs(r, c, -90);
        r.ui.visuals().translate(c.element().slot(), 30, 12); // the chart becomes an owner of an entry and moves
        r.settle();
        float[] after = turnedGlyphs(r, c, -90);
        assertThat(after[4]).isEqualTo(before[4]);
        assertThat(after[0] - before[0]).isCloseTo(30, within(0.5f));
        assertThat(after[1] - before[1]).isCloseTo(12, within(0.5f));
        r.ui.visuals().translate(c.element().slot(), 50, 12); // moved again: the entry alone changes
        r.run();
        r.settle();
        float[] again = turnedGlyphs(r, c, -90);
        assertThat(again[0] - before[0]).isCloseTo(50, within(0.5f));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void recordingTurnedTextAllocatesNothing(String theme) {
        Rig r = new Rig(theme.equals("default") ? Rig.DEFAULT : Rig.GARISH, Rig.nativeText());
        r.width = 420;
        r.height = 320;
        Chart c = months(r);
        Axis y = c.y();
        for (int i = 0; i < 20; i++) { // warm: label blocks for every value shown
            y.range().set(i, 100 + i);
            r.run();
        }
        AllocationAssertions.assertAllocationFree(() -> {
            for (int i = 0; i < 20; i++) {
                y.range().set(i, 100 + i); // ticks, labels and the chart record again each frame
                r.run();
            }
        });
    }
}
