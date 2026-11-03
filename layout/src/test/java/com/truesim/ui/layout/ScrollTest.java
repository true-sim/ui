package com.truesim.ui.layout;

import static com.truesim.ui.layout.FlexLayoutTest.assertRect;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiStore;
import org.junit.jupiter.api.Test;

/** Scrolled children move without a new layout; an independent element stops the dirty bubble. */
class ScrollTest {

    @Test
    void scrollingShiftsTheChildrenWithoutMeasuring() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.NONE);
        int view = ui.node(r);
        ui.layout.width(view, 100, Unit.PX).height(view, 50, Unit.PX).offsets(view, 10, 20, 0, 0);
        int content = ui.node(view);
        int a = ui.text(content, 2);
        int b = ui.text(content, 3);
        ui.run();
        assertRect(ui, b, 10, 36, 100, 16);
        ui.measures = 0;
        ui.layout.scroll(view, 4, 30);
        assertThat(ui.layout.scrollX(view)).isEqualTo(4);
        assertThat(ui.layout.scrollY(view)).isEqualTo(30);
        ui.run();
        assertThat(ui.measures).isZero();
        assertRect(ui, view, 10, 20, 100, 50); // the viewport stays
        assertRect(ui, a, 6, -10, 100, 16);
        assertRect(ui, b, 6, 6, 100, 16);
        assertThat(ui.layout.changedCount()).isEqualTo(3); // content, a, b
        ui.run();
        assertThat(ui.layout.changedCount()).isZero();
        ui.layout.scroll(view, 4, 30); // the same: nothing to do
        ui.run();
        assertThat(ui.layout.changedCount()).isZero();
        // scrolled while its parent moves too: one visit
        ui.layout.offsets(view, 12, 20, 0, 0).scroll(view, 0, 0);
        ui.run();
        assertRect(ui, a, 12, 20, 100, 16);
        // a negative scroll pushes children down (sticky headers)
        ui.layout.scroll(content, 0, -5);
        ui.run();
        assertRect(ui, a, 12, 25, 100, 16);
        // hidden or detached: remembered, applied when shown again
        ui.layout.visible(view, false);
        ui.run();
        ui.layout.scroll(view, 0, 10);
        ui.run();
        ui.layout.visible(view, true);
        ui.run();
        assertRect(ui, a, 12, 15, 100, 16);
        ui.store.detach(view);
        ui.layout.scroll(view, 0, 0);
        ui.run();
        assertThat(ui.layout.scrollY(view)).isZero();
        assertThat(ui.layout.scrollY(9999)).isZero();
        // the root scrolls its children too
        ui.layout.scroll(r, 0, 0);
        ui.run();
    }

    @Test
    void aReusedSlotForgetsItsScroll() {
        TestUi ui = new TestUi();
        int view = ui.node(ui.root());
        ui.layout.scroll(view, 3, 4);
        ui.store.destroy(view);
        int next = ui.node(ui.root());
        assertThat(next).isEqualTo(view);
        assertThat(ui.layout.scrollX(next)).isZero();
        ui.run();
    }

    @Test
    void anIndependentElementStopsTheDirtyBubble() {
        TestUi ui = new TestUi();
        int r = ui.root();
        ui.layout.kind(r, LayoutKind.FLEX).direction(r, Direction.ROW);
        int view = ui.node(r);
        ui.layout.grow(view, 1).independent(view, true).kind(view, LayoutKind.NONE);
        int sibling = ui.text(r, 2);
        int inside = ui.text(view, 2);
        ui.run();
        assertRect(ui, view, 0, 0, 384, 300);
        ui.measures = 0;
        ui.setText(inside, 5);
        ui.run();
        assertThat(ui.measures).isEqualTo(1);
        assertRect(ui, inside, 0, 0, 40, 16);
        assertRect(ui, view, 0, 0, 384, 300); // its own size did not depend on it
        assertThat(ui.layout.changedIn(sibling)).isLessThan(ui.layout.updates());
        // its own inputs still go up
        ui.layout.padding(view, 1, 1, 1, 1);
        ui.run();
        assertRect(ui, inside, 1, 1, 40, 16);
        ui.layout.independent(view, false);
        ui.setText(inside, 1);
        ui.run();
        assertThat(ui.store.alive(view)).isTrue();
        assertThat(UiStore.NONE).isNegative();
    }

    @Test
    void aHiddenElementsSubtreeCollapsesAndShowsAgain() {
        TestUi ui = new TestUi();
        int r = ui.root();
        int box = ui.node(r);
        int inner = ui.node(box);
        int leaf = ui.text(inner, 3);
        ui.run();
        assertThat(ui.layout.shown(leaf)).isTrue();
        ui.layout.visible(box, false);
        ui.run();
        assertThat(ui.layout.shown(box)).isFalse();
        assertThat(ui.layout.shown(inner)).isFalse(); // inside a hidden element: nothing to draw or pick
        assertThat(ui.layout.shown(leaf)).isFalse();
        assertThat(ui.layout.width(leaf)).isZero();
        ui.layout.scroll(r, 0, 5); // visited again while hidden: stays collapsed
        ui.run();
        assertThat(ui.layout.shown(leaf)).isFalse();
        ui.layout.visible(box, true).scroll(r, 0, 0);
        ui.run();
        assertThat(ui.layout.shown(leaf)).isTrue();
        assertRect(ui, leaf, 0, 0, 400, 16);
    }
}
