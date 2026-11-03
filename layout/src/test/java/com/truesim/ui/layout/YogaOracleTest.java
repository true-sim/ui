package com.truesim.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.util.yoga.Yoga.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.yoga.YGMeasureFunc;

/**
 * Random flex trees laid out by this module and by Yoga (lwjgl-yoga 3.4.3, with its web flex basis), the conformance
 * oracle of design 4.6: every element's rectangle must agree within a hundredth of a unit. Compared: direction and
 * reverse, wrap and wrap-reverse, justify, align-items, -self and -content, gaps, padding and margins as start and end,
 * grow, shrink and basis, min and max sizes, fixed and percent sizes, wrapping text, nesting, right to left.
 *
 * <p>The generator leaves out where this module follows CSS and Yoga does not ({@link FlexLayout} lists them):
 * <ul>
 *   <li>items grow only in containers with a definite main size (Yoga grows a content-sized container's items, and
 *       so the container, to the whole available space);</li>
 *   <li>percentages only of definite sizes (Yoga resolves them against an at-most size while measuring), and not in
 *       wrapping containers (Yoga breaks lines there as if a percentage item were smaller);</li>
 *   <li>minimums at most the fixed size and the maximum, and no main-axis minimum or maximum with an explicit basis
 *       (Yoga lets the maximum win over the minimum and flexes from the clamped size, CSS from the flex base size);</li>
 *   <li>in wrapping containers, no stretched items and no item margins, and symmetric padding with wrap-reverse
 *       (Yoga's multi-line pass ignores item margins, stretches items wrongly with some align-content values and
 *       mirrors wrap-reverse lines across the border box);</li>
 *   <li>no aspect ratio, baseline or absolute positioning.</li>
 *   <li>items with padding do not shrink (Yoga lets shrinking items under their padding without freezing them);</li>
 * </ul>
 * Trees in whose Yoga layout something overflows (a child's margin box out of its parent's border box) are skipped:
 * when content overflows an at-most constraint, Yoga lays an item out again under that constraint where CSS (and this
 * module) lays it out at its final size, and Yoga's own results there can be meaningless (sizes of 10^8).
 */
class YogaOracleTest {

    private static long config;
    private static YGMeasureFunc measureFunc;
    private static TestUi current;

    @BeforeAll
    static void yoga() {
        config = newConfig();
        measureFunc = YGMeasureFunc.create((node, w, wm, h, hm, out) -> {
            long size = current.measure((int) YGNodeGetContext(node), w, wm, h, hm);
            out.width(Measure.width(size)).height(Measure.height(size));
        });
    }

    private static long newConfig() {
        long c = YGConfigNew();
        YGConfigSetPointScaleFactor(c, 0);
        // Recompute flex bases for every layout pass (CSS); by default Yoga reuses the first one it computed.
        YGConfigSetExperimentalFeatureEnabled(c, YGExperimentalFeatureWebFlexBasis, true);
        return c;
    }

    @AfterAll
    static void free() {
        measureFunc.free();
        YGConfigFree(config);
    }

    @Test
    void randomFlexTreesMatchYoga() {
        int trees = 2000;
        int elements = 0;
        int compared = 0;
        int comparedTrees = 0;
        for (int seed = 0; seed < trees; seed++) {
            if (seed == YOGA_WRONG) {
                continue;
            }
            int n = compare(seed);
            compared += n;
            elements += lastElements;
            comparedTrees += n > 0 ? 1 : 0;
        }
        System.out.println("Yoga oracle: " + comparedTrees + " of " + trees + " trees, " + compared + " of " + elements
            + " elements");
        // Trees that overflow somewhere in Yoga's layout are skipped: about a third, the larger ones.
        assertThat(comparedTrees).isGreaterThan(trees * 6 / 10);
        assertThat(compared).isGreaterThan(elements * 35 / 100);
    }

    /**
     * The one tree of the 2,000 where Yoga is wrong by CSS (checked by hand): a column container 212 high (210 inside)
     * with wrap, whose items are 16 and 202 high, is not wrapped; Yoga shrinks the text to 8 instead.
     */
    private static final int YOGA_WRONG = 469;

    private static int lastElements;

    @Test
    void fixtureTreesMatchYoga() {
        // A settings form: label and growing field per row, wrapping chips, a footer pushed to the end.
        TestUi ui = new TestUi();
        current = ui;
        List<long[]> pairs = new ArrayList<>();
        int r = ui.root();
        long yr = YGNodeNewWithConfig(config);
        pairs.add(new long[] {r, yr});
        ui.layout.kind(r, LayoutKind.FLEX).direction(r, Direction.COLUMN).padding(r, 8, 8, 8, 8).gap(r, 4, 0);
        YGNodeStyleSetFlexDirection(yr, YGFlexDirectionColumn);
        YGNodeStyleSetPadding(yr, YGEdgeAll, 8);
        YGNodeStyleSetGap(yr, YGGutterRow, 4);
        for (int i = 0; i < 6; i++) {
            int row = ui.node(r);
            long yrow = child(yr, pairs, row);
            ui.layout.kind(row, LayoutKind.FLEX).direction(row, Direction.ROW).gap(row, 0, 6).shrink(row, 0)
                .alignItems(row, Align.CENTER);
            YGNodeStyleSetFlexDirection(yrow, YGFlexDirectionRow);
            YGNodeStyleSetGap(yrow, YGGutterColumn, 6);
            YGNodeStyleSetAlignItems(yrow, YGAlignCenter);
            int label = ui.text(row, 5 + i * 3);
            long ylabel = leaf(yrow, pairs, label);
            ui.layout.width(label, 120, Unit.PX).shrink(label, 0);
            YGNodeStyleSetWidth(ylabel, 120);
            int field = ui.box(row, 40, 22);
            long yfield = leaf(yrow, pairs, field);
            ui.layout.grow(field, 1).shrink(field, 0);
            YGNodeStyleSetFlexGrow(yfield, 1);
        }
        int chips = ui.node(r);
        long ychips = child(yr, pairs, chips);
        ui.layout.kind(chips, LayoutKind.FLEX).direction(chips, Direction.ROW).wrap(chips, Wrap.WRAP).gap(chips, 3, 3)
            .shrink(chips, 0).alignContent(chips, AlignContent.START);
        YGNodeStyleSetFlexDirection(ychips, YGFlexDirectionRow);
        YGNodeStyleSetFlexWrap(ychips, YGWrapWrap);
        YGNodeStyleSetGap(ychips, YGGutterAll, 3);
        for (int i = 0; i < 12; i++) {
            int chip = ui.text(chips, 3 + i % 7);
            long ychip = leaf(ychips, pairs, chip);
            ui.layout.padding(chip, 2, 6, 2, 6).shrink(chip, 0);
            YGNodeStyleSetPadding(ychip, YGEdgeVertical, 2);
            YGNodeStyleSetPadding(ychip, YGEdgeHorizontal, 6);
        }
        int spacer = ui.node(r);
        long yspacer = child(yr, pairs, spacer);
        ui.layout.grow(spacer, 1);
        YGNodeStyleSetFlexGrow(yspacer, 1);
        int footer = ui.text(r, 20);
        long yfooter = leaf(yr, pairs, footer);
        ui.layout.alignSelf(footer, Align.END).shrink(footer, 0);
        YGNodeStyleSetAlignSelf(yfooter, YGAlignFlexEnd);
        assertThat(run(ui, pairs, 420, 360, false, "form")).isEqualTo(pairs.size());
        assertThat(run(ui, pairs, 300, 330, true, "narrow form")).isEqualTo(pairs.size());
        YGNodeFreeRecursive(yr);
    }

    private static long child(long parent, List<long[]> pairs, int slot) {
        long y = YGNodeNewWithConfig(config);
        YGNodeStyleSetFlexShrink(y, 0);
        YGNodeInsertChild(parent, y, YGNodeGetChildCount(parent));
        pairs.add(new long[] {slot, y});
        return y;
    }

    private static long leaf(long parent, List<long[]> pairs, int slot) {
        long y = child(parent, pairs, slot);
        YGNodeSetContext(y, slot);
        YGNodeSetMeasureFunc(y, measureFunc);
        return y;
    }

    /**
     * Builds a random tree in both, lays both out, compares; returns the elements compared, or 0 when Yoga's layout
     * overflows somewhere (then the tree is skipped).
     */
    private static int compare(int seed) {
        YGConfigFree(config);
        config = newConfig();
        Random r = new Random(seed);
        TestUi ui = new TestUi();
        current = ui;
        List<long[]> pairs = new ArrayList<>();
        int root = ui.root();
        long yroot = YGNodeNewWithConfig(config);
        pairs.add(new long[] {root, yroot});
        container(ui, r, root, yroot);
        boolean[] definite = {true, true};
        grow(ui, r, root, yroot, pairs, definite, 0);
        float w = 500 + r.nextInt(600);
        float h = 400 + r.nextInt(500);
        int n = run(ui, pairs, w, h, r.nextInt(4) == 0, "seed " + seed);
        lastElements = pairs.size();
        YGNodeFreeRecursive(yroot);
        return n;
    }

    /** Whether a child's margin box sticks out of {@code y}'s border box in Yoga's layout. */
    private static boolean overflows(long y) {
        for (int i = 0, n = (int) YGNodeGetChildCount(y); i < n; i++) {
            long c = YGNodeGetChild(y, i);
            float l = YGNodeLayoutGetLeft(c) - YGNodeLayoutGetMargin(c, YGEdgeLeft);
            float t = YGNodeLayoutGetTop(c) - YGNodeLayoutGetMargin(c, YGEdgeTop);
            float r = YGNodeLayoutGetLeft(c) + YGNodeLayoutGetWidth(c) + YGNodeLayoutGetMargin(c, YGEdgeRight);
            float b = YGNodeLayoutGetTop(c) + YGNodeLayoutGetHeight(c) + YGNodeLayoutGetMargin(c, YGEdgeBottom);
            if (l < -0.01f || t < -0.01f || r > YGNodeLayoutGetWidth(y) + 0.01f || b > YGNodeLayoutGetHeight(y) + 0.01f) {
                return true;
            }
        }
        return false;
    }

    private static int run(TestUi ui, List<long[]> pairs, float w, float h, boolean rtl, String what) {
        ui.layout.textDirection(ui.root(), rtl ? TextDirection.RTL : TextDirection.LTR);
        ui.run(w, h);
        long yroot = pairs.get(0)[1];
        YGNodeStyleSetWidth(yroot, w);
        YGNodeStyleSetHeight(yroot, h);
        YGNodeCalculateLayout(yroot, w, h, rtl ? YGDirectionRTL : YGDirectionLTR);
        for (long[] p : pairs) {
            if (overflows(p[1])) {
                return 0;
            }
        }
        float[] ax = new float[ui.store.highWater()];
        float[] ay = new float[ui.store.highWater()];
        for (long[] p : pairs) {
            int s = (int) p[0];
            long y = p[1];
            int parent = ui.store.parent(s);
            float px = parent < 0 ? 0 : ax[parent];
            float py = parent < 0 ? 0 : ay[parent];
            ax[s] = px + YGNodeLayoutGetLeft(y);
            ay[s] = py + YGNodeLayoutGetTop(y);
            float[] yoga = {ax[s], ay[s], YGNodeLayoutGetWidth(y), YGNodeLayoutGetHeight(y)};
            try {
                assertThat(ui.rect(s)).as("%s, slot %d (parent %d)", what, s, parent)
                    .usingComparatorWithPrecision(0.01f).containsExactly(yoga);
            } catch (AssertionError e) {
                dump(ui, pairs, ui.root(), 0, w, h, rtl);
                throw e;
            }
        }
        return pairs.size();
    }

    static void dump(TestUi ui, List<long[]> pairs, int s, int depth, float w, float h, boolean rtl) {
        Layout l = ui.layout;
        long y = 0;
        for (long[] p : pairs) {
            if (p[0] == s) {
                y = p[1];
            }
        }
        System.out.println("  ".repeat(depth) + "#" + s + " vp=" + w + "x" + h + " rtl=" + rtl + " meas=" + l.measured[s]
            + (l.measured[s] ? (ui.boxW[s] >= 0 ? " box " + ui.boxW[s] + "x" + ui.boxH[s] : " text " + ui.chars[s]) : "")
            + " dir=" + l.direction[s] + " wrap=" + l.wrap[s] + " just=" + l.justify[s] + " ai=" + l.alignItems[s]
            + " ac=" + l.alignContent[s] + " as=" + l.alignSelf[s] + " g=" + l.grow[s] + " sh=" + l.shrink[s]
            + " b=" + l.basis[s] + "/" + l.basisUnit[s] + " w=" + l.width[s] + "/" + l.widthUnit[s]
            + " h=" + l.height[s] + "/" + l.heightUnit[s] + " min=" + l.minWidth[s] + "/" + l.minWidthUnit[s] + ","
            + l.minHeight[s] + "/" + l.minHeightUnit[s] + " max=" + l.maxWidth[s] + "/" + l.maxWidthUnit[s] + ","
            + l.maxHeight[s] + "/" + l.maxHeightUnit[s] + " pad=" + l.padTop[s] + "," + l.padRight[s] + ","
            + l.padBottom[s] + "," + l.padLeft[s] + " mar=" + l.marTop[s] + "," + l.marRight[s] + "," + l.marBottom[s]
            + "," + l.marLeft[s] + " gap=" + l.rowGap[s] + "," + l.columnGap[s]
            + " OURS=" + java.util.Arrays.toString(ui.rect(s)) + " YOGA(rel)=[" + YGNodeLayoutGetLeft(y) + ", "
            + YGNodeLayoutGetTop(y) + ", " + YGNodeLayoutGetWidth(y) + ", " + YGNodeLayoutGetHeight(y) + "]");
        for (int c = ui.store.firstChild(s); c >= 0; c = ui.store.nextSibling(c)) {
            dump(ui, pairs, c, depth + 1, w, h, rtl);
        }
    }

    private static void container(TestUi ui, Random r, int s, long y) {
        ui.layout.kind(s, LayoutKind.FLEX);
        Direction d = Direction.values()[r.nextInt(4)];
        ui.layout.direction(s, d);
        YGNodeStyleSetFlexDirection(y, switch (d) {
            case ROW -> YGFlexDirectionRow;
            case COLUMN -> YGFlexDirectionColumn;
            case ROW_REVERSE -> YGFlexDirectionRowReverse;
            case COLUMN_REVERSE -> YGFlexDirectionColumnReverse;
        });
        Wrap wrap = r.nextInt(3) == 0 ? Wrap.values()[1 + r.nextInt(2)] : Wrap.NO_WRAP;
        ui.layout.wrap(s, wrap);
        YGNodeStyleSetFlexWrap(y, switch (wrap) {
            case NO_WRAP -> YGWrapNoWrap;
            case WRAP -> YGWrapWrap;
            case WRAP_REVERSE -> YGWrapReverse;
        });
        Justify j = Justify.values()[r.nextInt(6)];
        ui.layout.justifyContent(s, j);
        YGNodeStyleSetJustifyContent(y, switch (j) {
            case START -> YGJustifyFlexStart;
            case END -> YGJustifyFlexEnd;
            case CENTER -> YGJustifyCenter;
            case SPACE_BETWEEN -> YGJustifySpaceBetween;
            case SPACE_AROUND -> YGJustifySpaceAround;
            case SPACE_EVENLY -> YGJustifySpaceEvenly;
        });
        Align a = Align.values()[1 + r.nextInt(wrap == Wrap.NO_WRAP ? 4 : 3)];
        ui.layout.alignItems(s, a);
        YGNodeStyleSetAlignItems(y, align(a));
        AlignContent ac = AlignContent.values()[r.nextInt(7)];
        ui.layout.alignContent(s, ac);
        YGNodeStyleSetAlignContent(y, switch (ac) {
            case START -> YGAlignFlexStart;
            case END -> YGAlignFlexEnd;
            case CENTER -> YGAlignCenter;
            case STRETCH -> YGAlignStretch;
            case SPACE_BETWEEN -> YGAlignSpaceBetween;
            case SPACE_AROUND -> YGAlignSpaceAround;
            case SPACE_EVENLY -> YGAlignSpaceEvenly;
        });
        if (r.nextBoolean()) {
            float rg = r.nextInt(3) * 3;
            float cg = r.nextInt(3) * 3;
            ui.layout.gap(s, rg, cg);
            YGNodeStyleSetGap(y, YGGutterRow, rg);
            YGNodeStyleSetGap(y, YGGutterColumn, cg);
        }
        if (r.nextBoolean()) {
            int t = r.nextInt(5);
            int e = r.nextInt(5);
            int b = wrap == Wrap.WRAP_REVERSE ? t : r.nextInt(5);
            int st = wrap == Wrap.WRAP_REVERSE ? e : r.nextInt(5);
            ui.layout.padding(s, t, e, b, st);
            YGNodeStyleSetPadding(y, YGEdgeTop, t);
            YGNodeStyleSetPadding(y, YGEdgeEnd, e);
            YGNodeStyleSetPadding(y, YGEdgeBottom, b);
            YGNodeStyleSetPadding(y, YGEdgeStart, st);
        }
    }

    private static int align(Align a) {
        return switch (a) {
            case AUTO -> YGAlignAuto;
            case START, BASELINE -> YGAlignFlexStart;
            case END -> YGAlignFlexEnd;
            case CENTER -> YGAlignCenter;
            case STRETCH -> YGAlignStretch;
        };
    }

    /**
     * Adds random children to {@code parent}; {@code definite} says whether its inner width and height are definite
     * (for percentages and growing).
     */
    private static void grow(TestUi ui, Random r, int parent, long yparent, List<long[]> pairs, boolean[] definite,
            int depth) {
        Layout l = ui.layout;
        boolean row = l.direction[parent] == Layout.D_ROW || l.direction[parent] == Layout.D_ROW_REVERSE;
        boolean mainDefinite = row ? definite[0] : definite[1];
        int n = 1 + r.nextInt(depth == 0 ? 6 : 4);
        for (int i = 0; i < n; i++) {
            int kind = depth >= 3 ? r.nextInt(2) : r.nextInt(3);
            int s;
            long y = YGNodeNewWithConfig(config);
            YGNodeInsertChild(yparent, y, YGNodeGetChildCount(yparent));
            if (kind == 0) {
                s = ui.text(parent, 1 + r.nextInt(12));
            } else if (kind == 1) {
                s = ui.box(parent, r.nextInt(50), r.nextInt(40));
            } else {
                s = ui.node(parent);
            }
            pairs.add(new long[] {s, y});
            if (kind < 2) {
                YGNodeSetContext(y, s);
                YGNodeSetMeasureFunc(y, measureFunc);
            }
            float grow = mainDefinite && r.nextInt(3) == 0 ? 1 + r.nextInt(2) : 0;
            float shrink = r.nextInt(6) == 0 ? 0 : 1;
            l.grow(s, grow).shrink(s, shrink);
            YGNodeStyleSetFlexGrow(y, grow);
            YGNodeStyleSetFlexShrink(y, shrink);
            YGNodeStyleSetFlexBasisAuto(y);
            boolean basis = r.nextInt(5) == 0;
            if (basis) {
                float b = kind == 2 ? 80 + r.nextInt(120) : r.nextInt(60);
                l.basis(s, b, Unit.PX);
                YGNodeStyleSetFlexBasis(y, b);
            }
            boolean minMaxW = !(basis && row);
            boolean minMaxH = !(basis && !row);
            boolean[] own = {false, false};
            if (r.nextInt(4) == 0) {
                float v = kind == 2 ? 150 + r.nextInt(200) : 10 + r.nextInt(70);
                l.width(s, v, Unit.PX);
                YGNodeStyleSetWidth(y, v);
                own[0] = true;
            } else if (definite[0] && l.wrap[parent] == Layout.W_NO_WRAP && r.nextInt(5) == 0) {
                float v = 10 + r.nextInt(90);
                l.width(s, v, Unit.PERCENT);
                YGNodeStyleSetWidthPercent(y, v);
            }
            if (r.nextInt(4) == 0) {
                float v = kind == 2 ? 120 + r.nextInt(150) : 10 + r.nextInt(50);
                l.height(s, v, Unit.PX);
                YGNodeStyleSetHeight(y, v);
                own[1] = true;
            } else if (definite[1] && l.wrap[parent] == Layout.W_NO_WRAP && r.nextInt(5) == 0) {
                float v = 10 + r.nextInt(90);
                l.height(s, v, Unit.PERCENT);
                YGNodeStyleSetHeightPercent(y, v);
            }
            if (r.nextInt(6) == 0 && minMaxW) {
                float v = Math.min(r.nextInt(80), own[0] ? l.width[s] : 80);
                l.minWidth(s, v, Unit.PX);
                YGNodeStyleSetMinWidth(y, v);
            }
            if (r.nextInt(6) == 0 && minMaxW) {
                float v = Math.max(l.minWidth[s], own[0] ? l.width[s] : 0) + 20 + r.nextInt(150);
                l.maxWidth(s, v, Unit.PX);
                YGNodeStyleSetMaxWidth(y, v);
            }
            if (r.nextInt(6) == 0 && minMaxH) {
                float v = Math.min(r.nextInt(60), own[1] ? l.height[s] : 60);
                l.minHeight(s, v, Unit.PX);
                YGNodeStyleSetMinHeight(y, v);
            }
            if (r.nextInt(6) == 0 && minMaxH) {
                float v = Math.max(l.minHeight[s], own[1] ? l.height[s] : 0) + 20 + r.nextInt(120);
                l.maxHeight(s, v, Unit.PX);
                YGNodeStyleSetMaxHeight(y, v);
            }
            if (r.nextInt(3) == 0) {
                Align a = Align.values()[(l.wrap[parent] == 0 ? 0 : 1) + r.nextInt(l.wrap[parent] == 0 ? 5 : 3)];
                l.alignSelf(s, a);
                YGNodeStyleSetAlignSelf(y, align(a));
            }
            if (r.nextInt(3) == 0 && l.wrap[parent] == Layout.W_NO_WRAP) {
                int t = r.nextInt(4);
                int e = r.nextInt(4);
                int b = r.nextInt(4);
                int st = r.nextInt(4);
                l.margin(s, t, e, b, st);
                YGNodeStyleSetMargin(y, YGEdgeTop, t);
                YGNodeStyleSetMargin(y, YGEdgeEnd, e);
                YGNodeStyleSetMargin(y, YGEdgeBottom, b);
                YGNodeStyleSetMargin(y, YGEdgeStart, st);
            }
            if (kind < 2 && r.nextInt(4) == 0) {
                int p = r.nextInt(4);
                l.padding(s, p, p, p, p);
                YGNodeStyleSetPadding(y, YGEdgeAll, p);
            }
            if (kind == 2) {
                container(ui, r, s, y);
            }
            if (l.padTop[s] + l.padRight[s] + l.padBottom[s] + l.padLeft[s] > 0) {
                l.shrink(s, 0);
                YGNodeStyleSetFlexShrink(y, 0);
            }
            if (kind == 2) {
                grow(ui, r, s, y, pairs, own, depth + 1);
            }
        }
    }
}
