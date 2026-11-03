package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

/** The frame's hooks for controls: painted content, style parts and frame listeners. */
class UiPainterTest {

    /** Draws one box of the part's background colour, sized by a field. */
    private static final class Swatch implements Painter {
        final Ui ui;
        final Element part;
        float size = 10;
        int measures;
        int paints;

        Swatch(Ui ui, Element part) {
            this.ui = ui;
            this.part = part;
        }

        @Override
        public long measure(int slot, float width, int widthMode, float height, int heightMode) {
            measures++;
            return Measure.size(size, size);
        }

        @Override
        public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            paints++;
            long c = ui.styles().styleOf(part.slot()).rgba(Styles.COLOR);
            list.box(x0, y0, x0 + size, y0 + size, Rgba.fade(c, opacity), 0, 0, 0, 0, 0, 0);
        }
    }

    @Test
    void paintedContentMeasuresRecordsAndClipsWithItsPartsStyles() {
        Ui ui = new Ui(Tss.compileString("painted.tss", """
            root { layout: stack; padding: 0px; align-items: start; }
            swatch { padding: 2px; clip: true; background: box(#101010); }
            dot { color: #ff0000; background: box(#00ff00); width: 50px; }
            dot:hover { color: #0000ff; }
            """), null);
        Element e = ui.element("swatch", null);
        Element part = ui.part(e, "dot", null);
        assertThat(ui.isPart(part)).isTrue();
        assertThat(ui.isPart(e)).isFalse();
        Swatch s = new Swatch(ui, part);
        ui.paint(e, s);
        ui.root().add(e);
        ui.update(200, 200);
        assertThat(ui.layout().width(e.slot())).as("measured plus padding").isEqualTo(14);
        assertThat(ui.layout().shown(part.slot())).as("a part takes no space").isFalse();
        String drawn = DrawListDump.of(ui.layers()[0]);
        assertThat(drawn).contains("box clip=1 2.00,2.00 12.00,12.00 a=(1.00 0.00 0.00 1.00)");
        assertThat(drawn).as("a part draws nothing of its own").doesNotContain("a=(0.00 1.00 0.00 1.00)");
        int paints = s.paints;
        ui.update(200, 200);
        assertThat(s.paints).as("nothing changed").isEqualTo(paints);
        ui.repaint(e);
        ui.update(200, 200);
        assertThat(s.paints).isEqualTo(paints + 1);
        s.size = 30;
        ui.remeasure(e);
        ui.update(200, 200);
        assertThat(ui.layout().width(e.slot())).isEqualTo(34);
        part.state(UiState.HOVER, true); // a part's restyle records its owner again
        ui.update(200, 200);
        assertThat(DrawListDump.of(ui.layers()[0])).contains("a=(0.00 0.00 1.00 1.00)");
        ui.paint(e, null);
        ui.update(200, 200);
        assertThat(DrawListDump.of(ui.layers()[0])).doesNotContain("a=(0.00 0.00 1.00 1.00)");
        assertThat(new Painter() {
            @Override
            public long measure(int slot, float width, int widthMode, float height, int heightMode) {
                return 0;
            }

            @Override
            public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            }
        }.spare(10)).isEqualTo(21);
        ui.destroy(e);
        ui.update(200, 200);
    }

    @Test
    void frameListenersRunEachUpdateWithTheClock() {
        Ui ui = new Ui(Tss.compileString("empty.tss", "root { layout: stack; }"), null);
        List<Long> seen = new ArrayList<>();
        LongConsumer a = seen::add;
        LongConsumer b = t -> seen.add(-t);
        for (int i = 0; i < 5; i++) {
            ui.onFrame(i == 0 ? a : b);
        }
        ui.update(10, 10, null, 7);
        assertThat(seen).containsExactly(7L, -7L, -7L, -7L, -7L);
        seen.clear();
        ui.removeOnFrame(b);
        ui.removeOnFrame(a);
        ui.removeOnFrame(t -> { });
        ui.update(10, 10, null, 8);
        assertThat(seen).containsExactly(-8L, -8L, -8L);
    }
}
