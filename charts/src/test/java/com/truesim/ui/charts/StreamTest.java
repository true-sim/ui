package com.truesim.ui.charts;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import org.junit.jupiter.api.Test;

/**
 * Streaming: lines on a following X axis drawn by the chart's stream element, their vertices written once and
 * scrolled by its drawn offset. Each frame only the stream records again (one primitive per line); what draws is where
 * the axis maps every sample; a change of the Y range, a pause of the follow or a dashed line goes back to the plot.
 */
class StreamTest {

    private static final class Setup {
        final Rig rig = new Rig(Rig.DEFAULT, Rig.nativeText());
        final VersionedDoubleRing xs;
        final VersionedDoubleRing ys;
        final Chart chart;
        final LineSeries line;
        final InfiniteLines marks;
        final VersionedDoubleRing markXs;
        long n;

        Setup() {
            rig.width = 800;
            rig.height = 400;
            ChangeClock clock = rig.ui.store().clock();
            xs = clock.doubleRing(300);
            ys = clock.doubleRing(300);
            markXs = clock.doubleRing(16);
            chart = rig.chart("stream");
            line = chart.line("signal", Columns.of(xs), Columns.of(ys));
            marks = (InfiniteLines) chart.infiniteLines("marks", Columns.of(markXs), true).styled("hitch");
            chart.x().range(0, 300).fits(false).follow(true);
            chart.y().range(-1.5, 1.5).fits(false);
        }

        void frame() {
            long f = n++;
            xs.append(f);
            ys.append(f % 50 == 25 ? Double.NaN : Math.sin(f * 0.1));
            if (f % 97 == 0) {
                markXs.append(f);
            }
            rig.run();
        }
    }

    @Test
    void aFollowingLineStreamsRecordingOnlyItsStream() {
        Setup s = new Setup();
        for (int i = 0; i < 700; i++) {
            s.frame();
        }
        assertThat(s.line.streamed).isTrue();
        assertThat(s.marks.streamed).isTrue();
        for (int i = 0; i < 20; i++) {
            s.frame();
            assertThat(s.rig.ui.recorded()).as("the stream and the shown X axis's ticks").isLessThanOrEqualTo(2);
        }
        s.chart.x().enabled(false);
        s.frame();
        s.frame();
        for (int i = 0; i < 20; i++) {
            s.frame();
            assertThat(s.rig.ui.recorded()).as("only the stream records").isLessThanOrEqualTo(1);
        }
        drawnWhereMapped(s);
        // allocation free while streaming
        assertAllocationFree(s::frame, 2000, 200, 10);
    }

    @Test
    void aChangeOfTheYRangeWritesItAgainAndAPauseHandsItBackToThePlot() {
        Setup s = new Setup();
        for (int i = 0; i < 400; i++) {
            s.frame();
        }
        s.chart.y().range(-3, 3);
        s.frame();
        s.frame();
        int anchors = s.chart.anchors;
        s.frame();
        assertThat(s.chart.anchors).as("a shift keeps the anchor").isEqualTo(anchors);
        drawnWhereMapped(s);
        s.chart.x().follow(false); // not following: the plot draws the line
        s.frame();
        s.frame();
        assertThat(s.line.streamed).isFalse();
        s.chart.x().follow(true); // following again
        s.frame();
        s.frame();
        assertThat(s.line.streamed).isTrue();
        drawnWhereMapped(s);
    }

    /** Every finite visible sample is a vertex of a stream path, drawn where the axes map it. */
    private static void drawnWhereMapped(Setup s) {
        DrawList list = s.rig.ui.layers()[0];
        Chart c = s.chart;
        Axis xa = c.x();
        Axis ya = c.y();
        int paths = 0;
        int checked = 0;
        for (int p = 0; p < list.size(); p++) {
            if (Primitive.kind(list.header(p)) != Primitive.PATH) {
                continue;
            }
            paths++;
            int first = list.intAt(p, Primitive.FIRST_VERTEX);
            int count = list.intAt(p, Primitive.VERTEX_COUNT);
            int t = list.transformOf(p);
            int clip = Primitive.clip(list.header(p));
            for (int v = first; v < first + count; v++) {
                float dx = (list.vertexAt(v, 0) + list.clipOffsetX(clip)) * list.transformScale(t)
                    + list.transformX(t);
                float dy = (list.vertexAt(v, 1) + list.clipOffsetY(clip)) * list.transformScale(t)
                    + list.transformY(t);
                // the sample this vertex is: X from its pixel
                double xv = Math.rint(xa.fromPixel(dx - c.contentX()));
                double yv = s.ys.get((long) xv);
                assertThat(dx - c.contentX()).isCloseTo(xa.toPixel(xv), org.assertj.core.data.Offset.offset(0.05f));
                assertThat(dy - c.contentY()).isCloseTo(ya.toPixel(yv), org.assertj.core.data.Offset.offset(0.05f));
                checked++;
            }
        }
        assertThat(paths).as("the line's runs between its gaps").isGreaterThanOrEqualTo(2);
        assertThat(checked).isGreaterThan(200);
    }
}
