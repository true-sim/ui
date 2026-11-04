package com.truesim.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlexLayoutTest {

    static void assertRect(TestUi ui, int s, float x, float y, float w, float h) {
        assertThat(ui.rect(s)).as("slot %d", s).usingComparatorWithPrecision(0.01f).containsExactly(x, y, w, h);
    }

    private static TestUi row(int boxes) {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW);
        for (int i = 0; i < boxes; i++) {
            ui.box(ui.root(), 50, 20);
        }
        return ui;
    }

    static int child(TestUi ui, int i) {
        int c = ui.store.firstChild(ui.root());
        for (int k = 0; k < i; k++) {
            c = ui.store.nextSibling(c);
        }
        return c;
    }

    @Test
    void justifyContent() {
        float[][] expected = {
            {0, 50, 100}, {250, 300, 350}, {125, 175, 225}, {0, 175, 350}, {41.667f, 175, 308.333f},
            {62.5f, 175, 287.5f},
        };
        for (Justify j : Justify.values()) {
            TestUi ui = row(3);
            ui.layout.justifyContent(ui.root(), j).alignItems(ui.root(), Align.START);
            ui.run();
            for (int i = 0; i < 3; i++) {
                assertRect(ui, child(ui, i), expected[j.ordinal()][i], 0, 50, 20);
            }
        }
    }

    @Test
    void justifyContentSpacingOnOverflowStartsAtTheStart() {
        TestUi ui = row(3);
        ui.layout.justifyContent(ui.root(), Justify.SPACE_AROUND).alignItems(ui.root(), Align.START);
        for (int i = 0; i < 3; i++) {
            ui.layout.shrink(child(ui, i), 0);
        }
        ui.run(120, 100);
        assertRect(ui, child(ui, 2), 100, 0, 50, 20);
        ui.layout.justifyContent(ui.root(), Justify.SPACE_EVENLY);
        ui.run(120, 100);
        assertRect(ui, child(ui, 0), 0, 0, 50, 20);
        ui.layout.justifyContent(ui.root(), Justify.SPACE_BETWEEN);
        ui.run(120, 100);
        assertRect(ui, child(ui, 1), 50, 0, 50, 20);
        ui.layout.justifyContent(ui.root(), Justify.CENTER);
        ui.run(120, 100);
        assertRect(ui, child(ui, 0), -15, 0, 50, 20);
    }

    @Test
    void alignItemsAndSelf() {
        float[][] expected = {{0, 300}, {0, 20}, {280, 20}, {140, 20}, {0, 300}, {0, 20}};
        for (Align a : Align.values()) {
            if (a == Align.AUTO) {
                continue;
            }
            TestUi ui = row(1);
            ui.layout.alignItems(ui.root(), a);
            ui.run();
            assertRect(ui, child(ui, 0), 0, expected[a.ordinal()][0], 50, expected[a.ordinal()][1]);
        }
        TestUi ui = row(2);
        ui.layout.alignItems(ui.root(), Align.START).alignSelf(child(ui, 1), Align.END);
        ui.run();
        assertRect(ui, child(ui, 0), 0, 0, 50, 20);
        assertRect(ui, child(ui, 1), 50, 280, 50, 20);
    }

    @Test
    void growDistributesFreeSpaceByFactor() {
        TestUi ui = row(3);
        ui.layout.grow(child(ui, 0), 1).grow(child(ui, 1), 2).grow(child(ui, 2), 1);
        ui.run();
        assertRect(ui, child(ui, 0), 0, 0, 112.5f, 300);
        assertRect(ui, child(ui, 1), 112.5f, 0, 175, 300);
        assertRect(ui, child(ui, 2), 287.5f, 0, 112.5f, 300);
    }

    @Test
    void growFactorsBelowOneTakeTheirShareOnly() {
        TestUi ui = row(1);
        ui.layout.grow(child(ui, 0), 0.5f);
        ui.run();
        assertRect(ui, child(ui, 0), 0, 0, 225, 300);
    }

    @Test
    void shrinkWeighsByBasis() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW);
        int a = ui.box(ui.root(), 100, 20);
        int b = ui.box(ui.root(), 50, 20);
        ui.layout.minWidth(a, 0, Unit.PX).minWidth(b, 0, Unit.PX); // below their content (min-width: 0)
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 66.667f, 50);
        assertRect(ui, b, 66.667f, 0, 33.333f, 50);
        ui.layout.shrink(a, 0);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 100, 50);
        assertRect(ui, b, 100, 0, 0, 50);
    }

    @Test
    void itemsShrinkNoFurtherThanTheirMinContentUnlessTheyClipOrHaveAMinimum() {
        // CSS min-width: auto. Two boxes of 80 in 100: they keep their content and the row overflows (Yoga: 50 each).
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW).alignItems(ui.root(),
            Align.START);
        int a = ui.box(ui.root(), 80, 20);
        int b = ui.box(ui.root(), 80, 20);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 80, 20);
        assertRect(ui, b, 80, 0, 80, 20);
        // one clips its content (overflow: hidden, a scroll pane): its minimum is 0, it takes the whole shortfall
        ui.layout.clipsContent(b, true);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 80, 20);
        assertRect(ui, b, 80, 0, 20, 20);
        // an explicit minimum wins over the automatic one
        ui.layout.clipsContent(b, false).minWidth(a, 30, Unit.PX).minWidth(b, 30, Unit.PX);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 50, 20);
        assertRect(ui, b, 50, 0, 50, 20);
        // a specified size smaller than the content is the minimum (CSS's specified size suggestion)
        ui.layout.minWidth(a, 0, Unit.AUTO).minWidth(b, 0, Unit.AUTO).width(a, 60, Unit.PX).width(b, 60, Unit.PX);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 60, 20);
        assertRect(ui, b, 60, 0, 60, 20);
        // and a maximum caps it
        ui.layout.width(a, 0, Unit.AUTO).width(b, 0, Unit.AUTO).maxWidth(a, 70, Unit.PX);
        ui.run(100, 50);
        assertRect(ui, a, 0, 0, 70, 20);
        assertRect(ui, b, 70, 0, 80, 20);
    }

    @Test
    void textShrinksToItsLongestWordAndARowToItsItemsMinimums() {
        // TestUi text breaks between any characters: its min-content is one character, 8 wide
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW).alignItems(ui.root(),
            Align.START);
        int text = ui.text(ui.root(), 20);
        int inner = ui.node(ui.root());
        ui.layout.kind(inner, LayoutKind.FLEX).direction(inner, Direction.ROW).padding(inner, 0, 2, 0, 2);
        int x = ui.box(inner, 30, 10);
        int y = ui.box(inner, 40, 10);
        ui.run(60, 300);
        // the inner row's minimum: its boxes' (70) and padding (4); the text takes the rest down to 8
        assertRect(ui, inner, 8, 0, 74, 10);
        assertRect(ui, text, 0, 0, 8, 320);
        assertRect(ui, x, 10, 0, 30, 10);
        assertRect(ui, y, 40, 0, 40, 10);
        // growing from a basis of 0 (flex: 1) still respects the content
        ui.layout.basis(text, 0, Unit.PX).grow(text, 1).basis(inner, 0, Unit.PX).grow(inner, 1);
        ui.run(200, 300);
        assertRect(ui, text, 0, 0, 98, 32); // 196 shared (the inner row's basis is its padding, 4)
        assertRect(ui, inner, 98, 0, 102, 10);
        ui.run(70, 300); // 66 shared would be 33 each: the row stops at its minimum, 74, the text at 8
        assertRect(ui, text, 0, 0, 8, 320);
        assertRect(ui, inner, 8, 0, 74, 10);
    }

    @Test
    void aWrappingContainerIsNeverNarrowerThanItsWidestItem() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW).alignItems(ui.root(),
            Align.START);
        int wrap = ui.node(ui.root());
        ui.layout.kind(wrap, LayoutKind.FLEX).direction(wrap, Direction.ROW).wrap(wrap, Wrap.WRAP)
            .alignItems(wrap, Align.START);
        ui.box(wrap, 30, 10);
        ui.box(wrap, 50, 10);
        int side = ui.box(ui.root(), 60, 10);
        ui.run(80, 100);
        assertRect(ui, wrap, 0, 0, 50, 20); // its min-content: the widest item; the side box keeps its own
        assertRect(ui, side, 50, 0, 60, 10);
    }

    @Test
    void basisAndMaximumsFreezeItems() {
        TestUi ui = row(3);
        for (int i = 0; i < 3; i++) {
            ui.layout.grow(child(ui, i), 1);
        }
        ui.layout.maxWidth(child(ui, 0), 60, Unit.PX).basis(child(ui, 2), 100, Unit.PX);
        ui.run();
        // free 200: the first would get 116.7, more than 60, so it freezes at 60; the others share 190
        assertRect(ui, child(ui, 0), 0, 0, 60, 300);
        assertRect(ui, child(ui, 1), 60, 0, 145, 300);
        assertRect(ui, child(ui, 2), 205, 0, 195, 300);
    }

    @Test
    void minimumsFreezeShrinkingItems() {
        TestUi ui = row(3);
        ui.layout.minWidth(child(ui, 0), 45, Unit.PX).minWidth(child(ui, 1), 0, Unit.PX)
            .minWidth(child(ui, 2), 0, Unit.PX);
        ui.run(120, 50);
        // 30 too much: each would shrink to 40, the first stops at 45 and the others share the rest
        assertRect(ui, child(ui, 0), 0, 0, 45, 50);
        assertRect(ui, child(ui, 1), 45, 0, 37.5f, 50);
    }

    @Test
    void wrapAndAlignContent() {
        float[][] expected = {{0, 20}, {260, 280}, {130, 150}, {0, 150}, {0, 280}, {65, 215}, {86.667f, 193.333f}};
        for (AlignContent ac : AlignContent.values()) {
            TestUi ui = row(3);
            ui.layout.wrap(ui.root(), Wrap.WRAP).alignItems(ui.root(), Align.START).alignContent(ui.root(), ac);
            ui.run(120, 300);
            assertRect(ui, child(ui, 0), 0, expected[ac.ordinal()][0], 50, 20);
            assertRect(ui, child(ui, 1), 50, expected[ac.ordinal()][0], 50, 20);
            assertRect(ui, child(ui, 2), 0, expected[ac.ordinal()][1], 50, 20);
        }
    }

    @Test
    void wrapReverseStacksLinesFromTheEnd() {
        TestUi ui = row(3);
        ui.layout.wrap(ui.root(), Wrap.WRAP_REVERSE).alignItems(ui.root(), Align.START);
        ui.run(120, 300);
        assertRect(ui, child(ui, 0), 0, 280, 50, 20);
        assertRect(ui, child(ui, 2), 0, 260, 50, 20);
    }

    @Test
    void wrappedLinesStretchToTheirLine() {
        TestUi ui = row(3);
        ui.layout.wrap(ui.root(), Wrap.WRAP).alignContent(ui.root(), AlignContent.STRETCH);
        ui.run(120, 300);
        assertRect(ui, child(ui, 0), 0, 0, 50, 150);
        assertRect(ui, child(ui, 2), 0, 150, 50, 150);
    }

    @Test
    void reverseDirectionsAndGaps() {
        TestUi ui = row(3);
        ui.layout.direction(ui.root(), Direction.ROW_REVERSE).alignItems(ui.root(), Align.START)
            .gap(ui.root(), 7, 10);
        ui.run();
        assertRect(ui, child(ui, 0), 350, 0, 50, 20);
        assertRect(ui, child(ui, 1), 290, 0, 50, 20);
        ui.layout.direction(ui.root(), Direction.COLUMN).alignItems(ui.root(), Align.STRETCH);
        ui.run();
        assertRect(ui, child(ui, 1), 0, 27, 400, 20);
        ui.layout.direction(ui.root(), Direction.COLUMN_REVERSE);
        ui.run();
        assertRect(ui, child(ui, 0), 0, 280, 400, 20);
        assertRect(ui, child(ui, 1), 0, 253, 400, 20);
    }

    @Test
    void marginsAndPadding() {
        TestUi ui = row(2);
        ui.layout.padding(ui.root(), 5, 6, 7, 8);
        ui.layout.margin(child(ui, 0), 1, 2, 3, 4);
        ui.run();
        assertRect(ui, child(ui, 0), 12, 6, 50, 300 - 12 - 4);
        assertRect(ui, child(ui, 1), 12 + 50 + 2, 5, 50, 300 - 12);
    }

    @Test
    void nestedContainersSizeToTheirContent() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.ROW)
            .alignItems(ui.root(), Align.START);
        int col = ui.node(ui.root());
        ui.layout.kind(col, LayoutKind.FLEX).direction(col, Direction.COLUMN).padding(col, 2, 2, 2, 2)
            .alignItems(col, Align.START);
        int a = ui.text(col, 5);
        int b = ui.text(col, 10);
        ui.run();
        assertRect(ui, col, 0, 0, 84, 36);
        assertRect(ui, a, 2, 2, 40, 16);
        assertRect(ui, b, 2, 18, 80, 16);
    }

    @Test
    void textWrapsInANarrowColumn() {
        TestUi ui = new TestUi();
        ui.layout.kind(ui.root(), LayoutKind.FLEX).direction(ui.root(), Direction.COLUMN);
        int t = ui.text(ui.root(), 30);
        ui.run(100, 300);
        // 12 characters fit in 100 units: 3 lines
        assertRect(ui, t, 0, 0, 100, 48);
        ui.layout.direction(ui.root(), Direction.ROW).alignItems(ui.root(), Align.START);
        ui.run(100, 300);
        assertRect(ui, t, 0, 0, 96, 48);
    }

    @Test
    void stackIgnoresFlexing() {
        TestUi ui = new TestUi();
        int a = ui.box(ui.root(), 50, 20);
        int b = ui.box(ui.root(), 50, 20);
        ui.layout.grow(a, 1).basis(b, 100, Unit.PX).gap(ui.root(), 4, 0);
        ui.run();
        assertRect(ui, a, 0, 0, 400, 20);
        assertRect(ui, b, 0, 24, 400, 20);
        ui.layout.direction(ui.root(), Direction.ROW).alignItems(ui.root(), Align.CENTER)
            .justifyContent(ui.root(), Justify.END).wrap(ui.root(), Wrap.WRAP);
        ui.run();
        assertRect(ui, a, 300, 140, 50, 20);
        assertRect(ui, b, 350, 140, 50, 20);
    }

    @Test
    void hiddenItemsTakeNoSpace() {
        TestUi ui = row(3);
        ui.layout.alignItems(ui.root(), Align.START);
        ui.run();
        ui.layout.visible(child(ui, 1), false);
        ui.run();
        assertRect(ui, child(ui, 0), 0, 0, 50, 20);
        assertRect(ui, child(ui, 1), 0, 0, 0, 0);
        assertRect(ui, child(ui, 2), 50, 0, 50, 20);
        ui.layout.visible(child(ui, 1), true);
        ui.run();
        assertRect(ui, child(ui, 1), 50, 0, 50, 20);
        assertRect(ui, child(ui, 2), 100, 0, 50, 20);
    }
}
