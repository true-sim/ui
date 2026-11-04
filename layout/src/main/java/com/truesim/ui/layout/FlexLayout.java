package com.truesim.ui.layout;

import static com.truesim.ui.layout.Layout.A_STRETCH;
import static com.truesim.ui.layout.Layout.D_COLUMN_REVERSE;
import static com.truesim.ui.layout.Layout.D_ROW;
import static com.truesim.ui.layout.Layout.D_ROW_REVERSE;
import static com.truesim.ui.layout.Layout.EXACTLY;
import static com.truesim.ui.layout.Layout.K_STACK;
import static com.truesim.ui.layout.Layout.U_AUTO;
import static com.truesim.ui.layout.Layout.UNDEFINED;
import static com.truesim.ui.layout.Layout.W_NO_WRAP;
import static com.truesim.ui.layout.Layout.W_WRAP_REVERSE;
import static com.truesim.ui.layout.Layout.alignOffset;
import static com.truesim.ui.layout.Layout.clamp;
import static com.truesim.ui.layout.Layout.resolve;
import static com.truesim.ui.layout.Layout.resolveMax;
import static com.truesim.ui.layout.Layout.resolveMin;
import static com.truesim.ui.layout.Layout.selfAlign;
import static com.truesim.ui.layout.Layout.sub;

import com.truesim.ui.core.UiStore;

/**
 * Flexbox (CSS Flexible Box Layout, section 9) and stacks (flex without growing, shrinking, basis or wrapping).
 *
 * <p>Follows the CSS algorithm, with Yoga's choices where CSS needs what a layout of measured leaves does not have
 * (min-content sizes): a flex base size from content is measured at most the container's inner size; a container
 * sized by its content under an at-most constraint takes its content's size even when that is larger (it overflows,
 * and its parent shrinks it if it may), except a wrapping container, which stays within the constraint and fills it
 * once its items wrap (but is never narrower than its widest item); justify-content's spacing modes spread only
 * positive free space. Checked against Yoga 3.4.3 (with its web flex basis) on random trees, see
 * {@code YogaOracleTest}.
 *
 * <p><b>Automatic minimum size</b> (CSS 4.5, {@code min-width: auto}): an item whose main-axis minimum is auto and
 * which does not clip its content ({@link Layout#clipsContent}) shrinks no further than its min-content size (measured
 * under an at-most constraint of 0: text breaks at every opportunity, so its longest word; a container its items'
 * minimums), or than its specified size if that is smaller, within its maximum; never below its padding. An explicit
 * minimum wins, and a clipping or scrolling item keeps a minimum of 0 (its padding). The size is measured only when an
 * item is to be smaller than its hypothetical size (shrinking, or growing from a basis below its content), so a line
 * that fits measures nothing more. Yoga lets items shrink to their padding instead: a squeezed label overflows its box.
 *
 * <p>Where this follows CSS and Yoga does not:
 * <ul>
 *   <li>a content-sized container is as large as its items' hypothetical sizes even when an item can grow (Yoga grows
 *       the items, and so the container, to the whole available space);</li>
 *   <li>a percentage of a size that is not definite is auto while measuring (Yoga resolves it against an at-most
 *       size);</li>
 *   <li>a minimum wins over a smaller maximum (in Yoga the maximum wins);</li>
 *   <li>flexible lengths are resolved by CSS's iterative freezing from the flex base sizes (Yoga freezes once and
 *       flexes from the clamped sizes, so they differ where a basis or fixed size breaks a minimum or maximum, or an
 *       item shrinks to its padding);</li>
 *   <li>in wrapping containers, item margins count in cross-axis alignment, wrap-reverse mirrors lines within the
 *       content box (padding and margins stay where they are), and stretched items fill their line (Yoga's multi-line
 *       pass ignores margins, mirrors across the border box and sizes stretched items wrongly with some
 *       align-content values);</li>
 *   <li>an item whose content overflows its at-most constraint is laid out at its final size (Yoga lays it out again
 *       under the constraint).</li>
 * </ul>
 * Auto margins, baselines and absolute children are not supported (baseline aligns at the start).
 */
final class FlexLayout {

    private static final float EPSILON = 1e-4f;

    private FlexLayout() {
    }

    /**
     * The automatic minimum main size of item {@code c} (CSS 4.5), or its padding where it has none: an explicit
     * minimum, a clipping item. Measured once a run, when first needed.
     */
    private static float floor(Layout l, int c, boolean row, float mainOwner, float ow, float oh, boolean rtl) {
        float pad = padMain(l, c, row);
        if ((row ? l.minWidthUnit[c] : l.minHeightUnit[c]) != U_AUTO || l.clipsContent[c]) {
            return pad;
        }
        float m = l.sAutoMin[c];
        if (m == m) {
            return m;
        }
        float ca = l.sCrossAvail[c];
        int cm = l.sCrossMode[c];
        int key = cm << 1 | (row ? 1 : 0);
        int k = c * 3;
        if (l.amValid[c] && l.amModes[c] == key && l.amKey[k] == ca && Float.compare(l.amKey[k + 1], ow) == 0
            && Float.compare(l.amKey[k + 2], oh) == 0) {
            m = l.amValue[c];
        } else {
            if (row) {
                l.layoutNode(c, 0, Layout.AT_MOST, ca, cm, ow, oh, false, rtl);
            } else {
                l.layoutNode(c, ca, cm, 0, Layout.AT_MOST, ow, oh, false, rtl);
            }
            m = row ? l.resW : l.resH;
            l.amValid[c] = true;
            l.amModes[c] = key;
            l.amKey[k] = ca;
            l.amKey[k + 1] = ow;
            l.amKey[k + 2] = oh;
            l.amValue[c] = m;
        }
        float em = l.fontSize[c];
        float specified = row ? resolve(l.width[c], l.widthUnit[c], mainOwner, em)
            : resolve(l.height[c], l.heightUnit[c], mainOwner, em);
        if (specified == specified) {
            m = Math.min(m, specified);
        }
        m = Math.max(Math.min(m, maxMain(l, c, row, mainOwner)), pad);
        l.sAutoMin[c] = m;
        return m;
    }

    /**
     * Runs the flex algorithm over {@code s}'s children in an inner box of {@code iw x ih} with modes; without
     * {@code perform} it leaves the content size in {@link Layout#resW} and {@link Layout#resH}, with it (exact
     * modes) it places the children in a border box {@code borderW} wide.
     */
    static void run(Layout l, int s, float iw, int iwm, float ih, int ihm, float minIW, float maxIW, float minIH,
            float maxIH, boolean perform, boolean rtl, float borderW) {
        UiStore st = l.store;
        boolean stack = l.kind[s] == K_STACK;
        byte dir = l.direction[s];
        boolean row = dir == D_ROW || dir == D_ROW_REVERSE;
        boolean reverse = dir == D_ROW_REVERSE || dir == D_COLUMN_REVERSE;
        boolean wrap = !stack && l.wrap[s] != W_NO_WRAP;
        boolean wrapReverse = !stack && l.wrap[s] == W_WRAP_REVERSE;
        float mainAvail = row ? iw : ih;
        int mainMode = row ? iwm : ihm;
        float crossAvail = row ? ih : iw;
        int crossMode = row ? ihm : iwm;
        float minMain = row ? minIW : minIH;
        float maxMain = row ? maxIW : maxIH;
        float minCross = row ? minIH : minIW;
        float maxCross = row ? maxIH : maxIW;
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float mainOwner = row ? ow : oh;
        float crossOwner = row ? oh : ow;
        float mainGap = row ? l.columnGap[s] : l.rowGap[s];
        float crossGap = row ? l.rowGap[s] : l.columnGap[s];
        byte items = l.alignItems[s];

        // A: flex base sizes and hypothetical main sizes.
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            if (l.hidden[c]) {
                continue;
            }
            float em = l.fontSize[c];
            float padMain = padMain(l, c, row);
            l.sAutoMin[c] = Float.NaN;
            float mCross0 = marginCross(l, c, row);
            float ca0 = crossMode == UNDEFINED ? 0 : Math.max(0, crossAvail - mCross0);
            int cm0 = crossMode == EXACTLY && selfAlign(l.alignSelf[c], items) == A_STRETCH && crossAuto(l, c, row)
                ? EXACTLY : sub(crossMode);
            if (cm0 == EXACTLY) {
                ca0 = Math.max(clamp(ca0, minMain(l, c, !row, crossOwner), maxMain(l, c, !row, crossOwner)),
                    padMain(l, c, !row));
            }
            l.sCrossAvail[c] = ca0;
            l.sCrossMode[c] = cm0;
            float b = stack ? Float.NaN : resolve(l.basis[c], l.basisUnit[c], mainOwner, em);
            boolean measuredBasis = false;
            if (b != b) {
                b = row ? resolve(l.width[c], l.widthUnit[c], mainOwner, em)
                    : resolve(l.height[c], l.heightUnit[c], mainOwner, em);
            }
            if (b != b && l.aspect[c] > 0) {
                float cs = row ? resolve(l.height[c], l.heightUnit[c], crossOwner, em)
                    : resolve(l.width[c], l.widthUnit[c], crossOwner, em);
                if (cs == cs) {
                    b = row ? cs * l.aspect[c] : cs / l.aspect[c];
                }
            }
            if (b != b) {
                measuredBasis = true;
                float mMain = marginMain(l, c, row);
                float ma = mainMode == UNDEFINED ? 0 : Math.max(0, mainAvail - mMain);
                int mm = sub(mainMode);
                float ca = ca0;
                int cm = cm0;
                if (row) {
                    l.layoutNode(c, ma, mm, ca, cm, ow, oh, false, rtl);
                } else {
                    l.layoutNode(c, ca, cm, ma, mm, ow, oh, false, rtl);
                }
                b = row ? l.resW : l.resH;
            }
            b = Math.max(b, padMain);
            l.sBasis[c] = b;
            // A basis measured from the content is at least its min-content size; a given one may be smaller.
            float least = stack || measuredBasis ? padMain : floor(l, c, row, mainOwner, ow, oh, rtl);
            l.sHypo[c] = Math.max(clamp(b, minMain(l, c, row, mainOwner), maxMain(l, c, row, mainOwner)), least);
        }

        // B: lines.
        int lineBase = l.isTop;
        int lines = 0;
        int first = UiStore.NONE;
        int count = 0;
        float sum = 0;
        float longest = 0;
        float widest = 0;
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            if (l.hidden[c]) {
                continue;
            }
            float outer = l.sHypo[c] + marginMain(l, c, row);
            widest = Math.max(widest, outer);
            if (wrap && count > 0 && mainMode != UNDEFINED && sum + mainGap + outer > mainAvail + EPSILON) {
                pushLine(l, first, count);
                lines++;
                longest = Math.max(longest, sum);
                count = 0;
            }
            if (count == 0) {
                first = c;
                sum = outer;
            } else {
                sum += mainGap + outer;
            }
            count++;
        }
        if (count > 0) {
            pushLine(l, first, count);
            lines++;
            longest = Math.max(longest, sum);
        }
        l.reserveFloats(lines);
        int crossBase = l.fsTop;
        l.fsTop += lines;

        // C: the container's main size, then flexible lengths per line.
        float main;
        if (mainMode == EXACTLY) {
            main = mainAvail;
        } else {
            // A wrapping container stays within an at-most constraint, and fills it once its items wrap (fit-content,
            // as CSS and Yoga size it); others take their content's size, which may overflow.
            float content = !wrap || mainMode != Layout.AT_MOST ? longest : lines > 1 ? Math.max(mainAvail, widest)
                : Math.min(longest, mainAvail);
            main = Math.max(clamp(content, minMain, maxMain), 0);
        }
        for (int li = 0; li < lines; li++) {
            resolveLine(l, l.is[lineBase + li * 2], l.is[lineBase + li * 2 + 1], row, stack, main, mainGap,
                mainOwner, ow, oh, rtl);
        }

        // D: hypothetical cross sizes and line cross sizes.
        float sumCross = 0;
        for (int li = 0; li < lines; li++) {
            float lineCross = 0;
            int c = l.is[lineBase + li * 2];
            for (int k = 0, n = l.is[lineBase + li * 2 + 1]; k < n; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                k++;
                float em = l.fontSize[c];
                float mCross = marginCross(l, c, row);
                float padCross = padMain(l, c, !row);
                float mn = minMain(l, c, !row, crossOwner);
                float mx = maxMain(l, c, !row, crossOwner);
                float target = l.sTarget[c];
                float cs = row ? resolve(l.height[c], l.heightUnit[c], crossOwner, em)
                    : resolve(l.width[c], l.widthUnit[c], crossOwner, em);
                float cross;
                if (cs == cs) {
                    cross = Math.max(clamp(cs, mn, mx), padCross);
                } else if (l.aspect[c] > 0) {
                    cross = Math.max(clamp(row ? target / l.aspect[c] : target * l.aspect[c], mn, mx), padCross);
                } else if (!wrap && crossMode == EXACTLY && selfAlign(l.alignSelf[c], items) == A_STRETCH) {
                    cross = Math.max(clamp(crossAvail - mCross, mn, mx), padCross);
                } else {
                    float ca = crossMode == UNDEFINED ? 0 : Math.max(0, crossAvail - mCross);
                    int cm = sub(crossMode);
                    if (row) {
                        l.layoutNode(c, target, EXACTLY, ca, cm, ow, oh, false, rtl);
                    } else {
                        l.layoutNode(c, ca, cm, target, EXACTLY, ow, oh, false, rtl);
                    }
                    cross = row ? l.resH : l.resW;
                }
                l.sCross[c] = cross;
                lineCross = Math.max(lineCross, cross + mCross);
            }
            l.fs[crossBase + li] = lineCross;
            sumCross += lineCross;
        }
        sumCross += crossGap * Math.max(0, lines - 1);
        float cross;
        if (crossMode == EXACTLY) {
            cross = crossAvail;
        } else {
            cross = Math.max(clamp(sumCross, minCross, maxCross), 0);
        }
        if (!wrap && lines == 1) {
            l.fs[crossBase] = cross;
        }

        if (perform) {
            place(l, s, lineBase, crossBase, lines, row, reverse, wrap, wrapReverse, main, cross, sumCross, mainGap,
                crossGap, ow, oh, crossOwner, rtl, borderW);
        }
        l.isTop = lineBase;
        l.fsTop = crossBase;
        l.resW = row ? main : cross;
        l.resH = row ? cross : main;
    }

    private static void pushLine(Layout l, int first, int count) {
        l.reserveInts(2);
        l.is[l.isTop++] = first;
        l.is[l.isTop++] = count;
    }

    /** CSS 9.7, resolving the flexible lengths of one line into {@link Layout#sTarget}. */
    private static void resolveLine(Layout l, int first, int count, boolean row, boolean stack, float main,
            float gap, float owner, float ow, float oh, boolean rtl) {
        UiStore st = l.store;
        float sumHypo = gap * (count - 1);
        int c = first;
        for (int k = 0; k < count; c = st.nextSibling(c)) {
            if (!l.hidden[c]) {
                k++;
                sumHypo += l.sHypo[c] + marginMain(l, c, row);
            }
        }
        boolean growing = sumHypo < main;
        c = first;
        for (int k = 0; k < count; c = st.nextSibling(c)) {
            if (!l.hidden[c]) {
                k++;
                l.sTarget[c] = l.sHypo[c];
                l.sFrozen[c] = stack || (growing ? l.grow[c] == 0 || l.sBasis[c] > l.sHypo[c]
                    : l.shrink[c] == 0 || l.sBasis[c] < l.sHypo[c]);
            }
        }
        float initialFree = Float.NaN;
        for (int iteration = 0; iteration <= count; iteration++) {
            float used = gap * (count - 1);
            float sumFactors = 0;
            float sumScaled = 0;
            boolean flexible = false;
            c = first;
            for (int k = 0; k < count; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                k++;
                used += marginMain(l, c, row) + (l.sFrozen[c] ? l.sTarget[c] : l.sBasis[c]);
                if (!l.sFrozen[c]) {
                    flexible = true;
                    sumFactors += growing ? l.grow[c] : l.shrink[c];
                    sumScaled += l.shrink[c] * l.sBasis[c];
                }
            }
            if (!flexible) {
                return;
            }
            float free = main - used;
            if (initialFree != initialFree) {
                initialFree = free;
            }
            if (sumFactors < 1) {
                float scaled = initialFree * sumFactors;
                if (Math.abs(scaled) < Math.abs(free)) {
                    free = scaled;
                }
            }
            float violation = 0;
            c = first;
            for (int k = 0; k < count; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                k++;
                if (l.sFrozen[c]) {
                    continue;
                }
                float b = l.sBasis[c];
                float t;
                if (growing) {
                    t = b + (sumFactors > 0 ? free * l.grow[c] / sumFactors : 0);
                } else {
                    t = b + (sumScaled > 0 ? free * l.shrink[c] * b / sumScaled : 0);
                }
                // below its hypothetical size an item meets its automatic minimum (measured only then)
                float least = t < l.sHypo[c] - EPSILON ? floor(l, c, row, owner, ow, oh, rtl) : padMain(l, c, row);
                float clamped = Math.max(clamp(t, minMain(l, c, row, owner), maxMain(l, c, row, owner)), least);
                l.sTarget[c] = clamped;
                l.sTemp[c] = t;
                violation += clamped - t;
            }
            c = first;
            for (int k = 0; k < count; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                k++;
                if (!l.sFrozen[c]) {
                    float target = l.sTarget[c];
                    float t = l.sTemp[c];
                    l.sFrozen[c] = Math.abs(violation) < EPSILON || (violation > 0 ? target > t : target < t);
                }
            }
        }
    }

    /** E and F: main-axis justification, cross-axis alignment, and the children's final layout. */
    private static void place(Layout l, int s, int lineBase, int crossBase, int lines, boolean row, boolean reverse,
            boolean wrap, boolean wrapReverse, float main, float cross, float sumCross, float mainGap, float crossGap,
            float ow, float oh, float crossOwner, boolean rtl, float borderW) {
        UiStore st = l.store;
        byte items = l.alignItems[s];
        float crossLead = 0;
        float lineBetween = 0;
        float lineExtra = 0;
        if (wrap) {
            float free = cross - sumCross;
            switch (l.alignContent[s]) {
                case 1 -> crossLead = free;
                case 2 -> crossLead = free * 0.5f;
                case 3 -> lineExtra = free > 0 ? free / lines : 0;
                case 4 -> lineBetween = free > 0 && lines > 1 ? free / (lines - 1) : 0;
                case 5 -> {
                    if (free > 0) {
                        lineBetween = free / lines;
                        crossLead = lineBetween * 0.5f;
                    }
                }
                case 6 -> {
                    if (free > 0) {
                        lineBetween = free / (lines + 1);
                        crossLead = lineBetween;
                    }
                }
                default -> {
                }
            }
        }
        float padX = l.padLeft[s];
        float padY = l.padTop[s];
        float crossPos = crossLead;
        for (int li = 0; li < lines; li++) {
            int first = l.is[lineBase + li * 2];
            int count = l.is[lineBase + li * 2 + 1];
            float lineCross = l.fs[crossBase + li] + lineExtra;
            float used = mainGap * (count - 1);
            int c = first;
            for (int k = 0; k < count; c = st.nextSibling(c)) {
                if (!l.hidden[c]) {
                    k++;
                    used += l.sTarget[c] + marginMain(l, c, row);
                }
            }
            float free = main - used;
            float lead = 0;
            float between = 0;
            switch (l.justify[s]) {
                case 1 -> lead = free;
                case 2 -> lead = free * 0.5f;
                case 3 -> between = free > 0 && count > 1 ? free / (count - 1) : 0;
                case 4 -> {
                    if (free > 0) {
                        between = free / count;
                        lead = between * 0.5f;
                    }
                }
                case 5 -> {
                    if (free > 0) {
                        between = free / (count + 1);
                        lead = between;
                    }
                }
                default -> {
                }
            }
            float pos = lead;
            c = first;
            for (int k = 0; k < count; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                k++;
                float target = l.sTarget[c];
                float outerMain = target + marginMain(l, c, row);
                float start = reverse ? main - pos - outerMain : pos;
                float childMain = start + (row ? l.marLeft[c] : l.marTop[c]);
                int align = selfAlign(l.alignSelf[c], items);
                float size = l.sCross[c];
                float mCross = marginCross(l, c, row);
                if (align == A_STRETCH && crossAuto(l, c, row) && l.aspect[c] <= 0) {
                    size = Math.max(clamp(lineCross - mCross, minMain(l, c, !row, crossOwner),
                        maxMain(l, c, !row, crossOwner)), padMain(l, c, !row));
                }
                float outerCross = size + mCross;
                float crossStart = crossPos + alignOffset(align, lineCross - outerCross);
                if (wrapReverse) {
                    crossStart = cross - crossStart - outerCross;
                }
                float childCross = crossStart + (row ? l.marTop[c] : l.marLeft[c]);
                if (row) {
                    l.place(c, padX + childMain, padY + childCross, target, size, ow, oh, rtl, borderW);
                } else {
                    l.place(c, padX + childCross, padY + childMain, size, target, ow, oh, rtl, borderW);
                }
                pos += outerMain + mainGap + between;
            }
            crossPos += lineCross + crossGap + lineBetween;
        }
    }

    private static boolean crossAuto(Layout l, int c, boolean row) {
        return (row ? l.heightUnit[c] : l.widthUnit[c]) == U_AUTO;
    }

    private static float marginMain(Layout l, int c, boolean row) {
        return row ? l.marLeft[c] + l.marRight[c] : l.marTop[c] + l.marBottom[c];
    }

    private static float marginCross(Layout l, int c, boolean row) {
        return row ? l.marTop[c] + l.marBottom[c] : l.marLeft[c] + l.marRight[c];
    }

    /** The padding along the row ({@code row}) or column axis. */
    private static float padMain(Layout l, int c, boolean row) {
        return row ? l.padLeft[c] + l.padRight[c] : l.padTop[c] + l.padBottom[c];
    }

    /** The minimum size along the row ({@code row}) or column axis. */
    private static float minMain(Layout l, int c, boolean row, float owner) {
        return row ? resolveMin(l.minWidth[c], l.minWidthUnit[c], owner, l.fontSize[c])
            : resolveMin(l.minHeight[c], l.minHeightUnit[c], owner, l.fontSize[c]);
    }

    private static float maxMain(Layout l, int c, boolean row, float owner) {
        return row ? resolveMax(l.maxWidth[c], l.maxWidthUnit[c], owner, l.fontSize[c])
            : resolveMax(l.maxHeight[c], l.maxHeightUnit[c], owner, l.fontSize[c]);
    }
}
