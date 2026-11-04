package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.Marker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the canvas records: dashes carried across segments, segments clipped to the plot, markers, wedges, shapes. */
class CanvasTest {

    private final DrawList list = new DrawList();
    private final Canvas c = new Canvas();

    private Canvas begin() {
        list.reset();
        int start = list.allocate(4096);
        list.begin(start, 4096);
        c.begin(list, 10, 20, 0, 0, 100, 100, 1, 1);
        return c;
    }

    private int count(int kind) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == kind) {
                n++;
            }
        }
        return n;
    }

    private float maxExtent() {
        float m = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) != Primitive.EMPTY) {
                for (int w = Primitive.X0; w <= Primitive.Y1; w++) {
                    m = Math.max(m, Math.abs(list.floatAt(i, w)));
                }
            }
        }
        return m;
    }

    @Test
    void solidPolylinesClipToThePlotAndJoinRound() {
        begin().stroke(3, Rgba.WHITE, List.of(), true);
        assertThat(c.stroking()).isTrue();
        assertThat(c.strokeWidth()).isEqualTo(3);
        c.moveTo(10, 10);
        c.lineTo(50, 50);
        c.lineTo(90, 10);
        c.lineTo(Float.NaN, 0); // a break
        c.lineTo(20, 80);
        c.lineTo(1e9f, 80); // far outside: clipped to the plot
        c.lineTo(1e9f, 1e9f); // wholly outside: nothing
        list.end();
        assertThat(count(Primitive.LINE)).isEqualTo(3); // the break starts a new line at its next point
        assertThat(count(Primitive.BOX)).isEqualTo(1); // a round join at the inner vertex inside the plot
        assertThat(maxExtent()).isLessThan(200); // the clipped segment ends near the plot (plus the offset)
        assertThat(c.primitives()).isEqualTo(4);
        // the offset moves everything
        assertThat(list.floatAt(0, Primitive.X0)).isEqualTo(20f);
        assertThat(list.floatAt(0, Primitive.Y0)).isEqualTo(30f);
    }

    @Test
    void dashesCarryTheirPhaseAcrossSegments() {
        begin().stroke(1, Rgba.WHITE, List.of(4f, 6f), false);
        c.moveTo(0, 50);
        c.lineTo(15, 50); // dash 0-4, gap, dash 10-14
        c.lineTo(30, 50); // dash 20-24 (the pattern goes on)
        list.end();
        assertThat(count(Primitive.LINE)).isEqualTo(3);
        assertThat(list.floatAt(2, Primitive.X0)).isCloseTo(30f, within(1e-3f)); // 20 + offset 10
        // a segment far longer than the plot dashes only what shows, in phase
        begin().stroke(1, Rgba.WHITE, List.of(4f, 6f), false);
        c.line(-1e6f, 50, 1e6f, 50);
        list.end();
        int n = count(Primitive.LINE);
        assertThat(n).isBetween(9, 12);
        assertThat(maxExtent()).isLessThan(150);
        // a dash pattern too fine to show draws solid
        begin().stroke(1, Rgba.WHITE, List.of(0.1f, 0.1f), false);
        c.line(0, 0, 50, 0);
        list.end();
        assertThat(count(Primitive.LINE)).isEqualTo(1);
        // nothing to stroke
        begin().stroke(0, Rgba.WHITE, List.of(), false);
        assertThat(c.stroking()).isFalse();
        c.line(0, 0, 0, 0);
        list.end();
    }

    @Test
    void everyMarkerShape() {
        long fill = Rgba.pack(1, 0, 0, 1);
        long outline = Rgba.WHITE;
        for (Marker m : Marker.values()) {
            begin().marker(m, 50, 50, 10, fill, 1, outline);
            c.marker(m, 50, 50, 10, Rgba.TRANSPARENT, 0, 0); // neither: nothing but line shapes
            c.marker(m, 500, 500, 10, fill, 1, outline); // outside: nothing
            list.end();
            int prims = c.primitives();
            switch (m) {
                case NONE -> assertThat(prims).isZero();
                case CIRCLE, SQUARE -> assertThat(count(Primitive.BOX)).isEqualTo(2);
                case DIAMOND -> {
                    assertThat(count(Primitive.TRIANGLE)).isEqualTo(2);
                    assertThat(count(Primitive.LINE)).isEqualTo(4);
                }
                case UP, DOWN, LEFT, RIGHT -> {
                    assertThat(count(Primitive.TRIANGLE)).isEqualTo(1);
                    assertThat(count(Primitive.LINE)).isEqualTo(3);
                }
                case ASTERISK -> assertThat(count(Primitive.LINE)).isEqualTo(6);
                default -> assertThat(count(Primitive.LINE)).isEqualTo(4);
            }
        }
        begin().marker(Marker.CIRCLE, 50, 50, 0, Rgba.WHITE, 0, 0);
        list.end();
        assertThat(c.primitives()).isZero();
    }

    @Test
    void wedgesTrianglesQuadsAndBoxes() {
        begin().wedge(50, 50, 0, 40, 0, Math.PI, Rgba.WHITE, 4);
        list.end();
        int pie = count(Primitive.TRIANGLE);
        assertThat(pie).isBetween(30, 34); // half of 2 pi 40 over 4 pixel arcs
        // its outline edges only: the arc, and the two radii at the ends
        int outer = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.TRIANGLE) {
                outer += Integer.bitCount(Primitive.flags(list.header(i)));
            }
        }
        assertThat(outer).isEqualTo(pie + 2);
        begin().wedge(50, 50, 20, 40, 0, Math.PI / 2, Rgba.WHITE, 4);
        c.wedge(50, 50, 20, 40, 1, 1, Rgba.WHITE, 4); // empty
        c.wedge(50, 50, 40, 20, 0, 1, Rgba.WHITE, 4); // inverted radii: nothing
        list.end();
        assertThat(count(Primitive.TRIANGLE) % 2).isZero(); // quads
        begin().triangle(500, 500, 600, 500, 550, 600, Rgba.WHITE, 7); // outside
        c.box(500, 500, 600, 600, Rgba.WHITE, 0, 0, 0); // outside
        c.box(-1e9f, 10, 1e9f, 20, Rgba.WHITE, 2, 1, Rgba.WHITE); // cut to the plot
        c.disc(10, 10, 3, Rgba.WHITE);
        c.quad(0, 0, 10, 0, 10, 10, 0, 10, Rgba.WHITE, 15);
        c.image(0, 0, 10, 10, 0, 0, 1, 1, 1, Rgba.WHITE);
        list.end();
        assertThat(count(Primitive.BOX)).isEqualTo(2);
        assertThat(count(Primitive.TRIANGLE)).isEqualTo(2);
        assertThat(count(Primitive.IMAGE)).isEqualTo(1);
        assertThat(maxExtent()).isLessThan(200);
        // the quad's two triangles: every outer edge anti-aliased, the shared diagonal not
        int flags = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.TRIANGLE) {
                flags += Integer.bitCount(Primitive.flags(list.header(i)));
            }
        }
        assertThat(flags).isEqualTo(4);
        assertThat(c.left()).isZero();
        assertThat(c.right()).isEqualTo(100);
        assertThat(c.top()).isZero();
        assertThat(c.bottom()).isEqualTo(100);
        assertThat(c.scale()).isEqualTo(1);
        assertThat(c.fade(Rgba.WHITE)).isEqualTo(Rgba.WHITE);
    }

    @Test
    void ringXIsCheckedForOrderOverAppendedSamplesOnly() {
        Rig rig = new Rig();
        ChangeClock clock = rig.ui.store().clock();
        VersionedDoubleRing xs = clock.doubleRing(1000);
        VersionedDoubleRing ys = clock.doubleRing(1000);
        Chart chart = rig.chart("rings");
        LineSeries s = chart.line("r", Columns.of(xs), Columns.of(ys));
        for (int f = 0; f < 5; f++) {
            for (int i = 0; i < 50; i++) {
                xs.append(xs.appended());
                ys.append(Math.sin(xs.appended() * 0.1));
            }
            rig.run();
        }
        assertThat(s.sorted()).isTrue();
        xs.append(-5); // out of order
        ys.append(0);
        rig.run();
        assertThat(s.sorted()).isFalse();
        rig.run();
        xs.clear();
        ys.clear();
        for (int i = 0; i < 10; i++) {
            xs.append(i);
            ys.append(i);
        }
        rig.run();
        assertThat(s.sorted()).isTrue();
    }
}
