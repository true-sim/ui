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

/**
 * What the canvas records: polylines as paths (clipped to the plot, dashes carried along them), envelope columns, its
 * vertex block, dashes carried across single segments, markers, wedges, shapes.
 */
class CanvasTest {

    private final DrawList list = new DrawList();
    private final Canvas c = new Canvas();
    private final Canvas.Vertices vertices = new Canvas.Vertices();

    private Canvas begin() {
        list.reset();
        return again();
    }

    /** Records the same range again (the list not rebuilt). */
    private Canvas again() {
        if (list.size() == 0) {
            list.allocate(4096);
        }
        list.begin(0, 4096);
        c.begin(list, 10, 20, 0, 0, 100, 100, 1, 1, vertices);
        return c;
    }

    private void end() {
        c.end();
        list.end();
    }

    private int first(int kind) {
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == kind) {
                return i;
            }
        }
        return -1;
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
    void polylinesArePathsClippedToThePlotWithTheirJoins() {
        begin().stroke(3, Rgba.WHITE, List.of(), true);
        assertThat(c.stroking()).isTrue();
        assertThat(c.strokeWidth()).isEqualTo(3);
        c.moveTo(10, 10);
        c.lineTo(50, 50);
        c.lineTo(50, 50); // a repeat: no vertex of its own
        c.lineTo(90, 10);
        c.lineTo(Float.NaN, 0); // a break
        c.lineTo(20, 80);
        c.lineTo(1e9f, 80); // far outside: clipped to the plot
        c.lineTo(1e9f, 1e9f); // wholly outside: nothing
        end();
        assertThat(count(Primitive.PATH)).as("the break starts a new path").isEqualTo(2);
        assertThat(count(Primitive.LINE)).isZero();
        assertThat(count(Primitive.BOX)).as("joins are the path's").isZero();
        assertThat(maxExtent()).isLessThan(200); // the clipped segment ends near the plot (plus the offset)
        assertThat(c.primitives()).isEqualTo(2);
        int p = first(Primitive.PATH);
        assertThat(list.intAt(p, Primitive.VERTEX_COUNT)).isEqualTo(3);
        assertThat(list.intAt(p, Primitive.STROKE)).isEqualTo(Primitive.JOIN_ROUND);
        assertThat(list.floatAt(p, Primitive.WIDTH)).isEqualTo(3);
        int v = list.intAt(p, Primitive.FIRST_VERTEX);
        // the offset moves every vertex
        assertThat(list.vertexAt(v, 0)).isEqualTo(20f);
        assertThat(list.vertexAt(v, 1)).isEqualTo(30f);
        assertThat(list.vertexAt(v + 2, 0)).isEqualTo(100f);
        int q = p + 1;
        assertThat(list.intAt(q, Primitive.VERTEX_COUNT)).isEqualTo(2);
        assertThat(list.vertexAt(list.intAt(q, Primitive.FIRST_VERTEX) + 1, 0)).as("cut where it leaves the plot")
            .isLessThan(130f);
    }

    @Test
    void dashedPathsCarryTheirLengthAndPhaseAcrossWhatIsClippedAway() {
        begin().stroke(1, Rgba.WHITE, List.of(4f, 6f), false);
        c.moveTo(0, 50);
        c.lineTo(15, 50);
        c.lineTo(30, 50); // the pattern goes on along the path
        c.lineTo(30, 500); // out of the plot ...
        c.lineTo(60, 500);
        c.lineTo(60, 60); // ... and back in: 470 pixels on, the phase carried
        end();
        assertThat(count(Primitive.PATH)).isEqualTo(2);
        int p = first(Primitive.PATH);
        int v = list.intAt(p, Primitive.FIRST_VERTEX);
        assertThat(list.vertexAt(v + 2, 2)).as("the length so far").isEqualTo(30f);
        assertThat(Primitive.halves(4, 6)).isEqualTo(list.intAt(p, Primitive.DASHES));
        assertThat(list.intAt(p, Primitive.DASHES + 1)).as("two-entry patterns repeat")
            .isEqualTo(list.intAt(p, Primitive.DASHES));
        assertThat(list.floatAt(p, Primitive.DASH_PHASE)).isZero();
        int back = list.intAt(p + 1, Primitive.FIRST_VERTEX);
        float entry = 30 + 450 + 30 + (500 - 103); // back in at y = 100 + its margin of 3
        assertThat(list.vertexAt(back, 2)).as("a path's lengths start at 0").isZero();
        assertThat(list.floatAt(p + 1, Primitive.DASH_PHASE)).as("the pattern where it re-enters")
            .isCloseTo(entry % 10, within(1e-3f));
        // a four-entry pattern: both dashes
        begin().stroke(1, Rgba.WHITE, List.of(4f, 2f, 1f, 2f), false);
        c.moveTo(0, 50);
        c.lineTo(50, 50);
        end();
        p = first(Primitive.PATH);
        assertThat(list.intAt(p, Primitive.DASHES + 1)).isEqualTo(Primitive.halves(1, 2));
    }

    @Test
    void aPaintersVertexBlockIsReusedAndGrows() {
        begin().stroke(1, Rgba.WHITE, List.of(), false);
        c.moveTo(0, 0);
        for (int i = 1; i < 100; i++) {
            c.lineTo(i, i % 7);
        }
        end();
        int blocks = list.vertexCount();
        assertThat(blocks).as("one block").isEqualTo(256);
        list.clean();
        long version = list.version();
        again().stroke(1, Rgba.WHITE, List.of(), false); // the same again: the same vertices
        c.moveTo(0, 0);
        for (int i = 1; i < 100; i++) {
            c.lineTo(i, i % 7);
        }
        end();
        assertThat(list.vertexCount()).isEqualTo(blocks);
        assertThat(list.vertexRanges().isEmpty()).as("nothing changed").isTrue();
        assertThat(list.version()).isEqualTo(version);
        again().stroke(1, Rgba.WHITE, List.of(), false); // more than the block holds: a larger one
        for (int k = 0; k < 3; k++) {
            c.moveTo(0, 0);
            for (int i = 1; i < 100; i++) {
                c.lineTo(i, i % 7 + k);
            }
        }
        end();
        assertThat(list.vertexCount()).isEqualTo(256 + 512);
        assertThat(count(Primitive.PATH)).isEqualTo(3);
        begin(); // the list rebuilt: a block again
        c.columns(10);
        end();
        assertThat(list.vertexCount()).isEqualTo(512);
    }

    @Test
    void envelopeColumnsAreOnePrimitiveEachOverOneBlockOfVertices() {
        begin().stroke(2, Rgba.WHITE, List.of(), false);
        int at = c.columns(3);
        c.column(at, 0, 10, 20, 5, 25);
        c.column(at, 1, 20, 20, 20, 20);
        c.column(at, 2, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        c.fillColumns(at, 3, 50.5f, 1, 90, Rgba.WHITE);
        c.strokeColumns(at, 3, 50.5f, 1, true);
        c.fillColumns(at, 3, 50.5f, 1, 90, Rgba.TRANSPARENT); // nothing to fill
        end();
        assertThat(count(Primitive.COLUMNS)).isEqualTo(2);
        int fill = first(Primitive.COLUMNS);
        assertThat(list.intAt(fill, Primitive.STROKE)).isEqualTo(Primitive.COLUMNS_FILL);
        assertThat(list.floatAt(fill, Primitive.BASELINE)).as("moved by the offset").isEqualTo(110);
        assertThat(list.floatAt(fill, Primitive.COLUMN_X)).isEqualTo(60.5f);
        assertThat(list.floatAt(fill + 1, Primitive.WIDTH)).isEqualTo(2);
        assertThat(list.intAt(fill + 1, Primitive.STROKE)).isEqualTo(Primitive.COLUMNS_STEPS);
        assertThat(list.intAt(fill + 1, Primitive.FIRST_VERTEX)).isEqualTo(at);
        assertThat(list.vertexAt(at, 2)).isEqualTo(25);
        assertThat(list.vertexAt(at, 3)).isEqualTo(45);
        assertThat(list.floatAt(fill + 1, Primitive.X0)).isLessThan(60);
        assertThat(list.floatAt(fill + 1, Primitive.X1)).isGreaterThan(62.5f);
        assertThat(list.floatAt(fill + 1, Primitive.Y0)).isLessThan(25);
        assertThat(list.floatAt(fill + 1, Primitive.Y1)).isGreaterThan(45);
    }

    @Test
    void dashesCarryTheirPhaseAcrossSegments() {
        // a segment far longer than the plot dashes only what shows, in phase
        begin().stroke(1, Rgba.WHITE, List.of(4f, 6f), false);
        c.line(-1e6f, 50, 1e6f, 50);
        end();
        int n = count(Primitive.LINE);
        assertThat(n).isBetween(9, 12);
        assertThat(maxExtent()).isLessThan(150);
        // a dash pattern too fine to show draws solid
        begin().stroke(1, Rgba.WHITE, List.of(0.1f, 0.1f), false);
        c.line(0, 0, 50, 0);
        end();
        assertThat(count(Primitive.LINE)).isEqualTo(1);
        // nothing to stroke
        begin().stroke(0, Rgba.WHITE, List.of(), false);
        assertThat(c.stroking()).isFalse();
        c.line(0, 0, 0, 0);
        end();
    }

    @Test
    void everyMarkerShape() {
        long fill = Rgba.pack(1, 0, 0, 1);
        long outline = Rgba.WHITE;
        for (Marker m : Marker.values()) {
            begin().marker(m, 50, 50, 10, fill, 1, outline);
            c.marker(m, 50, 50, 10, Rgba.TRANSPARENT, 0, 0); // neither: nothing but line shapes
            c.marker(m, 500, 500, 10, fill, 1, outline); // outside: nothing
            end();
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
        end();
        assertThat(c.primitives()).isZero();
    }

    @Test
    void wedgesTrianglesQuadsAndBoxes() {
        begin().wedge(50, 50, 0, 40, 0, Math.PI, Rgba.WHITE, 4);
        end();
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
        end();
        assertThat(count(Primitive.TRIANGLE) % 2).isZero(); // quads
        begin().triangle(500, 500, 600, 500, 550, 600, Rgba.WHITE, 7); // outside
        c.box(500, 500, 600, 600, Rgba.WHITE, 0, 0, 0); // outside
        c.box(-1e9f, 10, 1e9f, 20, Rgba.WHITE, 2, 1, Rgba.WHITE); // cut to the plot
        c.disc(10, 10, 3, Rgba.WHITE);
        c.quad(0, 0, 10, 0, 10, 10, 0, 10, Rgba.WHITE, 15);
        c.image(0, 0, 10, 10, 0, 0, 1, 1, 1, Rgba.WHITE);
        end();
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
