package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import org.junit.jupiter.api.Test;

/**
 * Scrolling a virtualized list moves its body's clip offset instead of recording its rows again (design 6.1), and the
 * rows draw where layout, and so picking, has them.
 */
class ScrollRegionTest {

    @Test
    void scrollingMovesTheRegionsClipOffsetAndRowsDrawWhereTheyAreLaidOut() {
        DataFrame f = new DataFrame(DataFrame.SHEET, null);
        VersionedList<Integer> items = f.ui.store().clock().list();
        for (int i = 0; i < 200; i++) {
            items.add(i);
        }
        ListView<Integer> list = new ListView<>(f.ui, "Rows", RowSource.of(items), new ListViewTest.Bars() {
            @Override
            public int kind(Integer value) {
                return 0;
            }
        });
        f.ui.root().add(list.root());
        list.selection().select(5, 5);
        f.run(3);
        list.scrollTo(0, 100);
        f.run(3);
        Element body = f.ui.store().element(f.ui.store().parent(list.rowElement(5).slot()));
        assertThat(f.ui.scrollRegion(body)).isTrue();
        DrawList dl = f.ui.layers()[0];
        Element row = list.rowElement(5); // selected: it draws a box
        // a few pixels: no row enters or leaves the window, so nothing but the clip offset changes
        list.scrollTo(0, 103);
        f.run();
        assertThat(f.ui.recorded()).isLessThan(4); // the scroll bar's thumb, not the rows
        int start = f.ui.store().rangeStart(row.slot());
        int clip = Primitive.clip(dl.header(start));
        assertThat(clip).isNotZero();
        assertThat(dl.clipOffsetY(clip)).isEqualTo(-f.ui.layout().scrollPixelsY(body.slot()));
        assertThat(dl.drawnY(start, Primitive.Y0)).isEqualTo(f.ui.layout().y(row.slot()));
        assertThat(dl.drawnX(start, Primitive.X0)).isEqualTo(f.ui.layout().x(row.slot()));
        // picking sees the same place
        int[] hits = new int[8];
        float px = f.ui.layout().x(row.slot()) + 5;
        float py = f.ui.layout().y(row.slot()) + 5;
        int n = f.ui.interaction().picking().pick(px, py, hits);
        boolean found = false;
        for (int i = 0; i < n; i++) {
            found |= hits[i] == row.slot() || f.ui.store().parent(hits[i]) == row.slot() || hits[i] == body.slot();
        }
        assertThat(found).isTrue();
        // the region off: rows are recorded scrolled, as before
        f.ui.scrollRegion(body, false);
        f.run();
        list.scrollTo(0, 106);
        f.run();
        start = f.ui.store().rangeStart(row.slot());
        assertThat(dl.drawnY(start, Primitive.Y0)).isEqualTo(f.ui.layout().y(row.slot()));
        assertThat(f.ui.recorded()).isGreaterThan(4);
    }
}
